package io.legado.app.help.ai.asr

import java.io.File

/**
 * 本地 ASR 引擎占位（P3 v1）。
 *
 * 留接口但不实现，避免阻塞其它 ASR 实现的接入。调用 [transcribe] 会抛 [NotImplementedError]。
 * 后续可替换为 Vosk / Sherpa-onnx 等本地模型。
 */
class LocalAsrEngine : AsrEngine {
    override suspend fun transcribe(audio: File, language: String): List<AsrSegment> {
        throw NotImplementedError("LocalAsrEngine not implemented; use Whisper or JS instead")
    }
}
