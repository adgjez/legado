package io.legado.app.help.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ArcReel 服务控制器 — 管理 ArcReel FastAPI 服务生命周期
 */
object ArcReelServiceController {

    private const val ARCREEL_REPO = "https://github.com/ArcReel/ArcReel.git"
    private const val SERVICE_PORT = 1241
    private const val HEALTH_CHECK_URL = "http://127.0.0.1:$SERVICE_PORT"
    private const val MAX_STARTUP_WAIT_MS = 120_000L
    private const val HEALTH_CHECK_INTERVAL_MS = 2000L

    private val healthCheckClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    enum class ServiceStatus {
        NOT_INSTALLED,
        INSTALLING,
        INSTALLED,
        STARTING,
        RUNNING,
        STOPPED,
        ERROR
    }

    data class ServiceState(
        val status: ServiceStatus = ServiceStatus.NOT_INSTALLED,
        val progress: Float = 0f,
        val message: String = "",
        val error: String? = null,
        val serverUrl: String = HEALTH_CHECK_URL
    )

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state

    private val serverProcess = AtomicReference<Process?>(null)

    fun isInstalled(context: Context): Boolean {
        val arcReelDir = ArcReelEnvironment.arcReelDir(context)
        return arcReelDir.exists() && File(arcReelDir, "pyproject.toml").exists()
    }

    fun isRunning(): Boolean = serverProcess.get()?.isAlive == true

    /**
     * 完整安装 ArcReel
     */
    suspend fun install(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _state.value = ServiceState(ServiceStatus.INSTALLING, 0f, "检查 Ubuntu 环境...")

            if (!ArcReelEnvironment.isInstalled(context)) {
                return@withContext Result.failure(IllegalStateException("Ubuntu 环境未安装"))
            }

            val env = ArcReelEnvironment

            // Step 1: 更新 apt
            _state.value = ServiceState(ServiceStatus.INSTALLING, 0.1f, "更新 apt 源...")
            env.execute(context, "DEBIAN_FRONTEND=noninteractive apt-get update -qq").onFailure {
                _state.value = ServiceState(ServiceStatus.ERROR, 0f, "apt 更新失败", it.message)
                return@withContext Result.failure(it)
            }

            // Step 2: 安装依赖
            _state.value = ServiceState(ServiceStatus.INSTALLING, 0.2f, "安装 Python3 和依赖...")
            val installResult = env.execute(
                context,
                "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3 python3-pip python3-venv git curl ffmpeg 2>&1"
            )
            if (installResult.isFailure) {
                val errMsg = installResult.exceptionOrNull()?.message ?: "未知错误"
                _state.value = ServiceState(ServiceStatus.ERROR, 0f, "依赖安装失败", errMsg)
                return@withContext Result.failure(installResult.exceptionOrNull() ?: RuntimeException(errMsg))
            }

            // Step 3: 克隆/更新 ArcReel
            val arcReelDir = env.arcReelDir(context)
            val arcreelDirName = ArcReelEnvironment.ARCREEL_DIR_NAME
            if (arcReelDir.exists() && File(arcReelDir, ".git").exists()) {
                _state.value = ServiceState(ServiceStatus.INSTALLING, 0.5f, "更新 ArcReel 仓库...")
                env.execute(context, "cd /root/$arcreelDirName && git pull origin main").onFailure {
                    _state.value = ServiceState(ServiceStatus.ERROR, 0f, "Git 更新失败", it.message)
                    return@withContext Result.failure(it)
                }
            } else {
                _state.value = ServiceState(ServiceStatus.INSTALLING, 0.3f, "克隆 ArcReel 仓库...")
                arcReelDir.parentFile?.mkdirs()
                env.execute(context, "git clone $ARCREEL_REPO /root/$arcreelDirName").onFailure {
                    _state.value = ServiceState(ServiceStatus.ERROR, 0f, "Git 克隆失败", it.message)
                    return@withContext Result.failure(it)
                }
            }

            // Step 4: 安装 Python 依赖
            _state.value = ServiceState(ServiceStatus.INSTALLING, 0.6f, "安装 Python 依赖...")
            env.execute(
                context,
                "cd /root/$arcreelDirName && pip3 install --break-system-packages -e . 2>&1"
            ).onFailure {
                _state.value = ServiceState(ServiceStatus.ERROR, 0f, "Python 依赖安装失败", it.message)
                return@withContext Result.failure(it)
            }

            // Step 5: 配置 .env
            _state.value = ServiceState(ServiceStatus.INSTALLING, 0.9f, "配置服务...")
            val envConfigResult = env.execute(
                context,
                "cd /root/$arcreelDirName && test -f .env || cp .env.example .env 2>&1"
            )
            if (envConfigResult.isFailure) {
                _state.value = _state.value.copy(message = ".env 配置失败，可能需要手动配置")
            }

            _state.value = ServiceState(ServiceStatus.INSTALLED, 1f, "ArcReel 安装完成")
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState(ServiceStatus.ERROR, 0f, "安装失败", e.message)
            Result.failure(e)
        }
    }

    /**
     * 启动 ArcReel FastAPI 服务
     */
    suspend fun start(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isRunning()) {
                _state.value = ServiceState(ServiceStatus.RUNNING, 1f, "服务已在运行", serverUrl = HEALTH_CHECK_URL)
                return@withContext Result.success(Unit)
            }

            _state.value = ServiceState(ServiceStatus.STARTING, 0f, "启动 ArcReel 服务...")

            val env = ArcReelEnvironment
            val proot = env.prootBinary(context).absolutePath
            val rootfs = env.rootfsDir(context).absolutePath
            val arcreelDirName = ArcReelEnvironment.ARCREEL_DIR_NAME

            val startCommand = arrayOf(
                proot,
                "-r", rootfs,
                "-b", "/dev:/dev",
                "-b", "/proc:/proc",
                "-b", "/sys:/sys",
                "-w", "/root/$arcreelDirName",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu:/usr/lib",
                "/bin/bash", "-c",
                "cd /root/$arcreelDirName && " +
                "python3 -m uvicorn server.app:app --host 127.0.0.1 --port $SERVICE_PORT --log-level info 2>&1"
            )

            val process = Runtime.getRuntime().exec(startCommand)
            serverProcess.set(process)

            // 在后台消费 stdout，防止管道阻塞
            val reader = coroutineScope {
                async(Dispatchers.IO) {
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            while (reader.readLine() != null) { /* 丢弃日志 */ }
                        }
                    } catch (_: Exception) { }
                }
            }

            // 等待服务启动
            val startTime = System.currentTimeMillis()
            var started = false
            var attempt = 0
            while (System.currentTimeMillis() - startTime < MAX_STARTUP_WAIT_MS) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                if (healthCheck()) {
                    started = true
                    break
                }
                attempt++
                _state.value = _state.value.copy(
                    progress = (System.currentTimeMillis() - startTime).toFloat() / MAX_STARTUP_WAIT_MS,
                    message = "等待服务启动 (${attempt * 2}s)..."
                )
            }

            if (started) {
                _state.value = ServiceState(ServiceStatus.RUNNING, 1f, "服务运行中", serverUrl = HEALTH_CHECK_URL)
                Result.success(Unit)
            } else {
                reader.cancel()
                _state.value = ServiceState(ServiceStatus.ERROR, 0f, "服务启动超时", serverUrl = HEALTH_CHECK_URL)
                process.destroy()
                serverProcess.set(null)
                Result.failure(RuntimeException("服务启动超时"))
            }
        } catch (e: Exception) {
            _state.value = ServiceState(ServiceStatus.ERROR, 0f, "启动失败", e.message)
            Result.failure(e)
        }
    }

    /**
     * 停止 ArcReel 服务
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        val process = serverProcess.getAndSet(null) ?: return@withContext
        process.destroy()
        // 等待进程退出
        try {
            process.waitFor(5, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (process.isAlive) {
            process.destroyForcibly()
        }
        _state.value = ServiceState(ServiceStatus.STOPPED, 0f, "服务已停止")
    }

    /**
     * 更新 ArcReel 到最新版本
     */
    suspend fun update(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _state.value = ServiceState(ServiceStatus.INSTALLING, 0f, "更新 ArcReel...")

            val wasRunning = isRunning()
            if (wasRunning) stop()

            val env = ArcReelEnvironment
            val arcreelDirName = ArcReelEnvironment.ARCREEL_DIR_NAME
            env.execute(context, "cd /root/$arcreelDirName && git pull origin main").getOrThrow()
            env.execute(
                context,
                "cd /root/$arcreelDirName && pip3 install --break-system-packages -e . 2>&1"
            ).getOrThrow()

            if (wasRunning) {
                _state.value = ServiceState(ServiceStatus.INSTALLED, 1f, "更新完成，请重新启动服务")
            } else {
                _state.value = ServiceState(ServiceStatus.INSTALLED, 1f, "更新完成")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState(ServiceStatus.ERROR, 0f, "更新失败", e.message)
            Result.failure(e)
        }
    }

    /**
     * 健康检查
     */
    private suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(HEALTH_CHECK_URL).build()
            val response = healthCheckClient.newCall(request).execute()
            val code = response.code
            response.close()
            code in 200..399
        } catch (_: Exception) {
            false
        }
    }
}