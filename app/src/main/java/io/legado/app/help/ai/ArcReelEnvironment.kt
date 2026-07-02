package io.legado.app.help.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * ArcReel 环境管理器 — 管理 proot + Ubuntu rootfs
 *
 * 负责：
 * 1. 下载/解压 proot arm64 二进制
 * 2. 下载/解压 Ubuntu arm64 rootfs
 * 3. 在 proot 环境中执行 shell 命令
 * 4. 环境状态管理
 */
object ArcReelEnvironment {

    // Ubuntu 24.04 (Noble) arm64 base tarball
    private const val UBUNTU_ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz"

    // proot arm64 预编译二进制
    // 从 Termux 官方包仓库下载 .deb 包，然后提取 proot 二进制
    // 备用: 直接使用静态编译的 proot 二进制
    private const val PROOT_DEB_URL =
        "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.0_aarch64.deb"
    // 备用静态二进制 URL (如果可用)
    private const val PROOT_STATIC_URL =
        "https://github.com/proot-meefik/proot-static-build/releases/download/v5.1.0/proot-aarch64"

    private const val PROOT_BINARY_NAME = "proot"
    private const val ROOTFS_DIR_NAME = "ubuntu-rootfs"
    const val ARCREEL_DIR_NAME = "arcreel"

    enum class EnvStatus {
        NOT_INSTALLED,
        INSTALLING,
        INSTALLED,
        ERROR
    }

    data class EnvState(
        val status: EnvStatus = EnvStatus.NOT_INSTALLED,
        val progress: Float = 0f,
        val message: String = "",
        val error: String? = null
    )

    private val _state = MutableStateFlow(EnvState())
    val state: StateFlow<EnvState> = _state

    private fun envDir(context: Context): File =
        File(context.filesDir, "arcreel_env")

    fun prootBinary(context: Context): File =
        File(envDir(context), PROOT_BINARY_NAME)

    fun rootfsDir(context: Context): File =
        File(envDir(context), ROOTFS_DIR_NAME)

    fun arcReelDir(context: Context): File =
        File(rootfsDir(context), "root/$ARCREEL_DIR_NAME")

    fun isInstalled(context: Context): Boolean =
        prootBinary(context).exists() && File(rootfsDir(context), ".extraction_complete").exists()

    /**
     * 完整安装环境：proot 二进制 + Ubuntu rootfs
     */
    suspend fun install(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _state.value = EnvState(EnvStatus.INSTALLING, 0f, "准备安装环境...")

            envDir(context).mkdirs()

            // Step 1: 安装 proot 二进制
            if (!prootBinary(context).exists()) {
                _state.value = EnvState(EnvStatus.INSTALLING, 0.05f, "下载 proot 二进制...")
                downloadProotBinary(context).getOrThrow()
                if (!prootBinary(context).setExecutable(true)) {
                    throw RuntimeException("无法设置 proot 可执行权限")
                }
            }

            // Step 2: 下载并解压 Ubuntu rootfs
            if (!File(rootfsDir(context), ".extraction_complete").exists()) {
                _state.value = EnvState(EnvStatus.INSTALLING, 0.1f, "下载 Ubuntu rootfs (~200MB)...")
                downloadAndExtractRootfs(context).getOrThrow()
            }

            _state.value = EnvState(EnvStatus.INSTALLED, 1f, "环境安装完成")
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = EnvState(EnvStatus.ERROR, 0f, "安装失败", e.message)
            Result.failure(e)
        }
    }

    /**
     * 在 proot Ubuntu 环境中执行命令
     */
    suspend fun execute(context: Context, command: String, workDir: String = "/root"): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val proot = prootBinary(context).absolutePath
                val rootfs = rootfsDir(context).absolutePath
                val fullCommand = arrayOf(
                    proot,
                    "-r", rootfs,
                    "-b", "/dev:/dev",
                    "-b", "/proc:/proc",
                    "-b", "/sys:/sys",
                    "-w", workDir,
                    "/usr/bin/env", "-i",
                    "HOME=/root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu:/usr/lib",
                    "/bin/bash", "-c", command
                )

                val process = Runtime.getRuntime().exec(fullCommand)
                // 并发读取 stdout 和 stderr 防止管道死锁
                val (stdout, stderr) = coroutineScope {
                    val stdoutDeferred = async(Dispatchers.IO) {
                        process.inputStream.bufferedReader().use { it.readText() }
                    }
                    val stderrDeferred = async(Dispatchers.IO) {
                        process.errorStream.bufferedReader().use { it.readText() }
                    }
                    Pair(stdoutDeferred.await(), stderrDeferred.await())
                }
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    Result.success(stdout)
                } else {
                    Result.failure(RuntimeException("Exit code $exitCode: $stderr"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 检查环境是否正常运行
     */
    suspend fun healthCheck(context: Context): Boolean {
        val result = execute(context, "echo ok")
        return result.getOrNull()?.trim() == "ok"
    }

    // ── 私有方法 ──

    private suspend fun downloadProotBinary(context: Context): Result<Unit> {
        return try {
            // 尝试从 assets 复制
            val assetProot = try {
                context.assets.open("proot/$PROOT_BINARY_NAME")
            } catch (_: Exception) { null }

            if (assetProot != null) {
                assetProot.use { input ->
                    FileOutputStream(prootBinary(context)).use { output ->
                        input.copyTo(output)
                    }
                }
                return Result.success(Unit)
            }

            // 下载到临时文件，成功后再重命名
            val tmpFile = File(envDir(context), "proot.tmp")
            tmpFile.delete()

            // 尝试直接下载静态二进制
            _state.value = _state.value.copy(message = "下载 proot 二进制...")
            val directResult = runCatching {
                downloadFile(PROOT_STATIC_URL, tmpFile.absolutePath) { progress ->
                    _state.value = _state.value.copy(
                        progress = 0.05f + progress * 0.05f,
                        message = "下载 proot: ${(progress * 100).toInt()}%"
                    )
                }
            }

            if (directResult.isSuccess && tmpFile.length() > 1000) {
                tmpFile.renameTo(prootBinary(context))
                return Result.success(Unit)
            }
            tmpFile.delete()

            // 回退: 从 .deb 包提取
            _state.value = _state.value.copy(message = "从 .deb 包提取 proot...")
            val debFile = File(envDir(context), "proot.deb")
            downloadFile(PROOT_DEB_URL, debFile.absolutePath) { progress ->
                _state.value = _state.value.copy(
                    progress = 0.05f + progress * 0.05f,
                    message = "下载 proot .deb: ${(progress * 100).toInt()}%"
                )
            }
            extractProotFromDeb(debFile, prootBinary(context))
            debFile.delete()

            Result.success(Unit)
        } catch (e: Exception) {
            // 清理临时文件
            File(envDir(context), "proot.tmp").delete()
            File(envDir(context), "proot.deb").delete()
            Result.failure(e)
        }
    }

    /**
     * 从 .deb 包中提取 proot 二进制
     * .deb 格式: ar archive → data.tar.xz → ./usr/bin/proot
     */
    private fun extractProotFromDeb(debFile: File, destFile: File) {
        // AR 格式: "!<arch>\n" + 文件头(60字节) + 数据
        debFile.inputStream().buffered().use { input ->
            val magic = ByteArray(8)
            input.read(magic)
            if (String(magic) != "!<arch>\n") {
                throw RuntimeException("Invalid .deb file (not an ar archive)")
            }

            // 遍历 ar 条目，找到 data.tar.xz
            var foundTar = false
            while (!foundTar) {
                val header = ByteArray(60)
                val read = input.read(header)
                if (read < 60) break

                val name = String(header, 0, 16).trim()
                val sizeStr = String(header, 48, 10).trim()
                val size = sizeStr.toLongOrNull() ?: break

                if (name.startsWith("data.tar")) {
                    // 这是数据包，提取其中的 proot 二进制
                    val tarData = ByteArray(size.toInt())
                    input.read(tarData)
                    extractProotFromTarData(tarData, destFile)
                    foundTar = true
                } else {
                    // 跳过此条目（对齐到2字节边界）
                    input.skip(size)
                    if (size % 2 != 0L) input.skip(1)
                }
            }
        }
    }

    /**
     * 从 tar 数据中提取 proot 二进制
     */
    private fun extractProotFromTarData(tarData: ByteArray, destFile: File) {
        var offset = 0
        val targetPath = "./usr/bin/proot"

        while (offset < tarData.size - 512) {
            val name = String(tarData, offset, 100).trimEnd('\u0000'.toChar())
            val sizeStr = String(tarData, offset + 124, 12).trimEnd('\u0000'.toChar(), ' ')
            val size = sizeStr.toLongOrNull(16) ?: 0

            if (name == targetPath && size > 0) {
                val dataOffset = (offset + 512 + 511) / 512 * 512  // 对齐到512
                destFile.outputStream().use { output ->
                    output.write(tarData, dataOffset, size.toInt())
                }
                return
            }

            // 跳到下一个条目（512字节头 + 512对齐的数据）
            val dataBlocks = (size + 511) / 512
            offset += 512 + (dataBlocks * 512).toInt()
        }
        throw RuntimeException("proot binary not found in .deb package")
    }

    private suspend fun downloadAndExtractRootfs(context: Context): Result<Unit> {
        return try {
            val tarball = File(envDir(context), "ubuntu-rootfs.tar.gz")

            // 下载 rootfs tarball
            downloadFile(UBUNTU_ROOTFS_URL, tarball.absolutePath) { progress ->
                _state.value = _state.value.copy(
                    progress = 0.1f + progress * 0.7f,
                    message = "下载 Ubuntu rootfs: ${(progress * 100).toInt()}%"
                )
            }

            // 解压到 rootfs 目录
            _state.value = _state.value.copy(
                progress = 0.8f,
                message = "解压 Ubuntu rootfs..."
            )
            extractTarGz(tarball, rootfsDir(context))

            // 写入完成标记
            File(rootfsDir(context), ".extraction_complete").writeText("ok")

            // 清理 tarball
            tarball.delete()

            // 配置 DNS
            _state.value = _state.value.copy(
                progress = 0.9f,
                message = "配置环境..."
            )
            val resolvConf = File(rootfsDir(context), "etc/resolv.conf")
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")

            Result.success(Unit)
        } catch (e: Exception) {
            // 清理失败的部分下载和提取
            val tarball = File(envDir(context), "ubuntu-rootfs.tar.gz")
            tarball.delete()
            Result.failure(e)
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private suspend fun downloadFile(
        url: String,
        destPath: String,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw RuntimeException("HTTP ${response.code}: ${response.message}")
        }
        val body = response.body ?: throw RuntimeException("Empty response body")
        val contentLength = body.contentLength()

        FileOutputStream(destPath).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(65536)  // 64KB buffer
                var bytesRead: Int
                var totalBytesRead: Long = 0
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (contentLength > 0) {
                        onProgress(totalBytesRead.toFloat() / contentLength)
                    }
                }
            }
        }
        response.close()
    }

    private fun extractTarGz(tarball: File, destDir: File) {
        destDir.mkdirs()
        GZIPInputStream(tarball.inputStream().buffered()).use { gzStream ->
            var entry = readTarEntry(gzStream)
            while (entry != null) {
                val entryFile = File(destDir, entry.name.trimStart('/'))
                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    entryFile.outputStream().use { output ->
                        val buf = entry.data
                        output.write(buf, 0, entry.size.toInt())
                    }
                }
                entry = readTarEntry(gzStream)
            }
        }
    }

    /**
     * 简易 TAR 解析器 — 从流中读取单个 TAR 条目
     * TAR 格式: 512 字节头 + 数据块 (512 对齐)
     */
    private data class TarEntry(
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val data: ByteArray
    )

    private fun readTarEntry(input: InputStream): TarEntry? {
        val header = ByteArray(512)
        var read = 0
        while (read < 512) {
            val n = input.read(header, read, 512 - read)
            if (n == -1) return null
            read += n
        }
        // 检查是否全是0（TAR结束标记）
        if (header.all { it == 0.toByte() }) return null

        val name = String(header, 0, 100).trimEnd('\u0000'.toChar())
        val sizeStr = String(header, 124, 12).trimEnd('\u0000'.toChar(), ' ')
        val size = sizeStr.toLongOrNull(16) ?: 0
        val typeFlag = header[156].toInt() and 0xFF

        val isDirectory = typeFlag == '5'.code || name.endsWith("/")

        if (size == 0L) return TarEntry(name, 0, isDirectory, ByteArray(0))

        val data = ByteArray(size.toInt())
        var dataRead = 0
        while (dataRead < size) {
            val n = input.read(data, dataRead, (size - dataRead).toInt().coerceAtMost(8192))
            if (n == -1) break
            dataRead += n
        }

        // 跳过 512 字节对齐填充
        val padding = if (size % 512 == 0L) 0 else (512 - size % 512).toInt()
        if (padding > 0) input.skip(padding.toLong())

        return TarEntry(name, size, isDirectory, data)
    }
}