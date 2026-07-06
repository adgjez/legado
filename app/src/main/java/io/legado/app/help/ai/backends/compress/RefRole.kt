package io.legado.app.help.ai.backends.compress

/**
 * 参考图角色（移植 reference_compression.py）。
 *
 * - [ARRAY]：多参考图，走降档梯子（2048→1024）逐档下压到字节预算内。
 * - [FRAME]：首/尾帧，永不缩尺寸（仅超单图字节上限时 q92 重编码）。
 *
 * 该区分是「帧比参考图更重要，不能因压缩损失关键帧信息」的纪律。
 */
enum class RefRole { ARRAY, FRAME }
