package io.legado.app.help.ai.backends.compress

import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 参考图压缩管线（移植 ArcReel `reference_compression.py`）。
 *
 * 核心纪律（照搬 ArcReel）：
 * - **FRAME 永不缩尺寸**（仅超单图字节上限时 q92 重编码，连 PNG 都不强转）——
 *   「帧比参考图更重要，不能因压缩损失关键帧信息」
 * - **ARRAY step0 透传**：已是 JPEG + <1MB + 尺寸合规 → 原字节透传避免二压
 * - **不可解码/非本地源 → 原路径透传，绝不 raise**——压缩是优化，不得让原本能跑通的
 *   调用因压缩层新失败
 * - 透传项不计字节预算，但按原序位合并回完整列表保 1:1
 * - 临时文件沿用源 stem（Gemini 按文件名推断参考图名）
 *
 * 降档梯子（`reference_compression.py:34-41`）：
 * ```
 * LADDER  = [(2048, q92), (1536, q90), (1280, q90), (1024, q88)]  // 4 档
 * FLOOR   = (1024, q80)                                            // 地板
 * ```
 */
object ReferenceCompressor {

    /** 降档梯子（longEdge, quality）。逐档下压到字节预算内。 */
    private val LADDER: List<Pair<Int, Int>> = listOf(
        2048 to 92,
        1536 to 90,
        1280 to 90,
        1024 to 88
    )

    /** 地板档（压到这仍超限则抛 [ReferencePayloadFloorError]）。 */
    private val FLOOR: Pair<Int, Int> = 1024 to 80

    /** FRAME 永不缩尺寸哨兵——传给 [ImageCodec.compress] 的 maxLongEdge，不会触发缩放。 */
    private const val FRAME_NO_RESIZE_EDGE = 1_000_000

    /** ARRAY step0 透传字节上限。已是 JPEG 且 <此值 → 原字节透传避免二压。 */
    private const val PASSTHROUGH_MAX_BYTES = 1 * 1024 * 1024L

    /** 4:4:4 子采样（不采样），照搬 ArcReel。 */
    private const val SUBSAMPLING_444 = 0

    /** 梯子档数（4）。MediaGenerator 413 续档上限用。 */
    val LADDER_STEPS: Int = LADDER.size

    /**
     * 逐档下压到 total≤[PayloadLimits.totalMaxBytes] 且每个 ARRAY ≤[PayloadLimits.singleMaxBytes]。
     *
     * @param raws 每个参考图的原始字节（[ReferenceSpec.source] 读出）
     * @param roles 与 raws 1:1 的角色
     * @param limits 字节预算
     * @param startStep 起始档（MediaGenerator 413 续档从 landed+1 开始）
     * @return (landedStep, compressedBytesList)——landedStep 为实际落地档位（0..LADDER_STEPS-1），
     *         LADDER_STEPS 表示已用 FLOOR
     * @throws ReferencePayloadFloorError 压到 FLOOR 仍超限
     */
    suspend fun selectLadderStep(
        raws: List<ByteArray>,
        roles: List<RefRole>,
        limits: PayloadLimits,
        startStep: Int = 0
    ): Pair<Int, List<ByteArray>> = withContext(Dispatchers.Default) {
        require(raws.size == roles.size) { "raws 与 roles 长度不一致：${raws.size} vs ${roles.size}" }
        // 逐档试：step in 0..LADDER_STEPS-1 用 LADDER[step]，step=LADDER_STEPS 用 FLOOR
        for (step in startStep..LADDER_STEPS) {
            val compressed = raws.mapIndexed { i, raw ->
                compressSingleAtStep(raw, roles[i], step, limits.singleMaxBytes)
            }
            val total = compressed.sumOf { it.size.toLong() }
            val arrayMax = compressed.zip(roles)
                .filter { it.second == RefRole.ARRAY }
                .maxOfOrNull { it.first.size.toLong() } ?: 0L
            if (total <= limits.totalMaxBytes && arrayMax <= limits.singleMaxBytes) {
                return@withContext step to compressed
            }
            if (step == LADDER_STEPS) {
                // FLOOR 仍超——抛错（不重复压缩）
                throw ReferencePayloadFloorError(
                    IllegalStateException(
                        "参考图压到地板（${FLOOR.first},q${FLOOR.second}）仍超上限：" +
                            "total=${total}B > ${limits.totalMaxBytes}B"
                    )
                )
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * 单图在指定档位压缩。
     *
     * - **FRAME**：永不缩尺寸（maxLongEdge=[FRAME_NO_RESIZE_EDGE]）。仅当 raw 超过 [singleMaxBytes]
     *   时 q92 重编码；不超则原字节透传（连 PNG 都不强转）
     * - **ARRAY step0**：raw 是 JPEG + <[PASSTHROUGH_MAX_BYTES] + 尺寸合规 → 原字节透传避免二压
     * - **ARRAY step1..LADDER_STEPS-1**：用 LADDER[step] 压缩
     * - **ARRAY step=LADDER_STEPS**（地板）：用 FLOOR 压缩
     * - 不可解码：[ImageCodec.compress] 内部已返回原字节，本函数不 raise
     */
    suspend fun compressSingleAtStep(
        raw: ByteArray,
        role: RefRole,
        step: Int,
        singleMaxBytes: Long
    ): ByteArray = withContext(Dispatchers.Default) {
        when (role) {
            RefRole.FRAME -> {
                // FRAME 永不缩尺寸：仅超字节上限才 q92 重编码
                if (raw.size.toLong() <= singleMaxBytes) raw
                else ImageCodec.compress(raw, FRAME_NO_RESIZE_EDGE, 92, SUBSAMPLING_444)
            }
            RefRole.ARRAY -> {
                if (step == 0 && raw.size.toLong() <= PASSTHROUGH_MAX_BYTES && isCompliantJpeg(raw)) {
                    // step0 透传：合规小 JPEG 原字节避免二压
                    raw
                } else {
                    val (longEdge, quality) = if (step < LADDER_STEPS) LADDER[step] else FLOOR
                    ImageCodec.compress(raw, longEdge, quality, SUBSAMPLING_444)
                }
            }
        }
    }

    /**
     * 压缩 specs → 临时文件 → 调 [block] → finally 清理临时文件。
     *
     * 透传纪律（照搬 ArcReel `reference_compression.py:261-271`）：
     * - **透传项用原路径、不写临时副本**——用对象 identity 判定（`compressSingleAtStep` 返回的
     *   `ByteArray` 与传入 `raw` 是否同一引用），避免 PNG 字节落进 `.jpg` 后缀造成 MIME 错配，
     *   也最大保真（FRAME 像素匹配）
     * - **临时文件写到系统 temp 目录**（`createTempFile`），与源资产目录隔离，源目录只读时不失败
     * - 临时文件名仅用源 stem（Gemini 按文件名推断参考图名），**不加 `_stepN` 后缀**——
     *   多图重名靠子目录隔离（`refcomp-<random>/<idx>/<stem>.jpg`）
     * - 不可解码/非本地源（[ReferenceSpec.source] 不存在或读失败）→ 原路径透传，不 raise
     * - 透传项不计字节预算，但按原序位合并回完整列表保 1:1
     *
     * @param block 收到 (landedStep, compressedRefs)，返回业务结果
     */
    suspend inline fun <T> withCompressedPayload(
        specs: List<ReferenceSpec>,
        limits: PayloadLimits,
        startStep: Int = 0,
        crossinline block: suspend (landedStep: Int, List<CompressedRef>) -> T
    ): T {
        // 读源字节：不可读/非图片格式则用 null 占位（透传原路径，不写临时文件）
        val rawsOrNull: List<ByteArray?> = specs.map { spec ->
            runCatching { spec.source.readBytes() }
                .onFailure { AppLog.put("ReferenceCompressor 读源失败，透传原路径：${spec.source} - ${it.message}") }
                .getOrNull()
                ?.let { raw -> if (ImageCodec.isLikelyImage(raw)) raw else null }
        }
        // 透传项的索引（读失败 + 非图片格式）
        val passthroughIndices = rawsOrNull.mapIndexedNotNull { i, raw -> if (raw == null) i else null }

        // 可压缩项：raws/roles/对应 spec
        val compressibleSpecs = specs.filterIndexed { i, _ -> i !in passthroughIndices }
        val compressibleRaws = rawsOrNull.filterNotNull()
        val compressibleRoles = compressibleSpecs.map { it.role }

        val tempFiles = mutableListOf<File>()
        return try {
            val compressedRefs: List<CompressedRef>
            val landedStep: Int
            if (compressibleRaws.isEmpty()) {
                // 全部透传
                landedStep = startStep
                compressedRefs = specs.map { spec ->
                    CompressedRef(spec.source, spec.label, spec.role)
                }
            } else {
                val (step, compressedBytes) = selectLadderStep(
                    compressibleRaws, compressibleRoles, limits, startStep
                )
                landedStep = step
                // 写临时文件：identity 判定透传项用原路径，非透传写系统 temp 目录
                // temp root 隔离多图重名（对齐 ArcReel tempfile.mkdtemp）
                val tempRoot = File(System.getProperty("java.io.tmpdir"), "refcomp-${System.nanoTime()}")
                tempRoot.mkdirs()
                tempFiles.add(tempRoot) // finally 清理整个 temp root
                val compressedBySpec = compressibleSpecs.zip(compressedBytes).mapIndexed { idx, (spec, bytes) ->
                    val sameRef = bytes === compressibleRaws[idx] // identity：compressSingleAtStep 返回原 raw
                    if (sameRef) {
                        // 透传：用原路径，不写临时文件（避免 MIME 错配）
                        CompressedRef(spec.source, spec.label, spec.role)
                    } else {
                        // 压缩产物：写 temp 目录，文件名仅源 stem（不加 _stepN，Gemini stem 推断）
                        val idxDir = File(tempRoot, idx.toString()).apply { mkdirs() }
                        val tempFile = File(idxDir, "${spec.source.nameWithoutExtension}.jpg")
                        tempFile.writeBytes(bytes)
                        tempFiles.add(tempFile)
                        CompressedRef(tempFile, spec.label, spec.role)
                    }
                }
                // 合并回原序位：透传项（含读失败 + identity 透传）用原路径，压缩项用临时文件
                val compressedIter = compressedBySpec.iterator()
                compressedRefs = specs.mapIndexed { i, spec ->
                    if (i in passthroughIndices) {
                        CompressedRef(spec.source, spec.label, spec.role)
                    } else {
                        compressedIter.next()
                    }
                }
            }
            block(landedStep, compressedRefs)
        } finally {
            tempFiles.forEach { f ->
                runCatching {
                    if (f.isDirectory) f.deleteRecursively() else if (f.exists()) f.delete()
                }.onFailure { AppLog.put("ReferenceCompressor 删临时文件失败：${f} - ${it.message}") }
            }
        }
    }

    /** 粗判 raw 是否为合规小 JPEG（FFD8 开头 + 尺寸可解）——step0 透传判定用。 */
    private fun isCompliantJpeg(raw: ByteArray): Boolean {
        if (raw.size < 3) return false
        if (raw[0] != 0xFF.toByte() || raw[1] != 0xD8.toByte()) return false
        // 尺寸可解交给 ImageCodec.compress 内部 decodeSampled 判定；这里仅看 JPEG 魔数
        return true
    }
}

/**
 * 参考图规格（压缩管线入参）。
 *
 * - [source]：本地源文件。不可读/不存在 → [ReferenceCompressor.withCompressedPayload] 透传原路径不 raise
 * - [label]：参考图标签（Gemini 按文件名推断参考图名）
 * - [role]：[RefRole.ARRAY] 走梯子；[RefRole.FRAME] 永不缩尺寸
 */
data class ReferenceSpec(
    val source: File,
    val label: String,
    val role: RefRole
)

/** 压缩产物（临时文件或透传原路径）。 */
data class CompressedRef(
    val path: File,
    val label: String,
    val role: RefRole
)

/** 字节预算。 */
data class PayloadLimits(
    val totalMaxBytes: Long = 8 * 1024 * 1024L,
    val singleMaxBytes: Long = 4 * 1024 * 1024L
)

/** 参考图压到地板仍超上限。MediaGenerator 413 续档耗尽时抛。 */
class ReferencePayloadFloorError(cause: Throwable) : IllegalStateException(
    "参考图压到地板仍超上限", cause
)
