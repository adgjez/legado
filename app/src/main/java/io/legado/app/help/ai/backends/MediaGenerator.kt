package io.legado.app.help.ai.backends

import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.PayloadLimits
import io.legado.app.help.ai.backends.compress.ReferenceCompressor
import io.legado.app.help.ai.backends.compress.ReferencePayloadFloorError
import io.legado.app.help.ai.backends.compress.ReferenceSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 咽喉层（移植 ArcReel `media_generator._run_with_reference_compression`）。
 *
 * 压缩 + 413 被动续档 + 调 backend。视频和图像 backend 都走它。
 *
 * 续档纪律（照搬 ArcReel）：
 * - **无参考图**（T2I/T2V）→ 直接单次调 [buildAndCall]，413 不降档（无图可压）
 * - **413 续档从 landed+1 开始**（非请求 step）——避免已成功的档位重复试
 * - 续档上限 [ReferenceCompressor.LADDER_STEPS]（4 档）；耗尽抛 [ReferencePayloadFloorError] 保 cause
 *
 * Dispatcher 约定（对应 ArcReel `asyncio.to_thread`）：
 * - 压缩 CPU 密集 → [ReferenceCompressor.selectLadderStep] 内部包 [Dispatchers.Default]
 * - backend 调用（HTTP）→ [buildAndCall] 在调用方上下文跑（backend 内部自管 [Dispatchers.IO]）
 */
object MediaGenerator {

    /**
     * 压缩 specs → 调 [buildAndCall]；遇 413 从 landed+1 续档，耗尽抛 [ReferencePayloadFloorError]。
     *
     * @param specs 参考图规格。空列表 → 直接单次调 [buildAndCall]（T2I/T2V 无参考图）
     * @param limits 字节预算
     * @param buildAndCall 业务调用（收到压缩后的 [CompressedRef] 列表，返回 backend 结果）
     */
    suspend fun <T> runWithReferenceCompression(
        specs: List<ReferenceSpec>,
        limits: PayloadLimits,
        buildAndCall: suspend (List<CompressedRef>) -> T
    ): T {
        // T2I/T2V 无参考图直接单次，413 不降档（无图可压）
        if (specs.isEmpty()) return buildAndCall(emptyList())

        var step = 0
        while (true) {
            try {
                return ReferenceCompressor.withCompressedPayload(specs, limits, startStep = step) { landed, compressed ->
                    try {
                        buildAndCall(compressed)
                    } catch (e: Throwable) {
                        if (!is413(e)) throw e
                        // 413：续档从 landed+1（非请求 step）
                        if (landed < ReferenceCompressor.LADDER_STEPS) {
                            step = landed + 1
                            throw Retry413Exception(landed, e)
                        } else {
                            // 已压到地板仍 413——抛错保 cause
                            throw ReferencePayloadFloorError(e)
                        }
                    }
                }
            } catch (e: Retry413Exception) {
                // 续档：step 已设为 landed+1，循环重试
                continue
            }
        }
    }

    /**
     * 判定是否 413 Payload Too Large（移植 ArcReel `media_generator.is413`）。
     *
     * - 异常 message 含 "HTTP 413"（服务层错误消息约定，精确匹配 "HTTP 413" 而非裸 "413"——
     *   避免 "41300 bytes" / "request id 4134134" 误命中，对齐 ArcReel 刻意不用裸 "413" 子串的纪律）
     * - 或 message 含 "payload too large" / "request entity too large"（不区分大小写）
     */
    private fun is413(e: Throwable): Boolean {
        val msg = e.message.orEmpty().lowercase()
        if (msg.contains("http 413")) return true
        if (msg.contains("payload too large")) return true
        if (msg.contains("request entity too large")) return true
        return false
    }

    /** 内部续档信号（携带 landedStep 供日志，cause 是原始 413 异常）。 */
    private class Retry413Exception(val landedStep: Int, cause: Throwable) :
        RuntimeException("413 续档从 step ${landedStep + 1} 重试", cause)
}
