package io.legado.app.help.ai.scheduling

/**
 * 池满 provider 计算器（移植 ArcReel `generation_worker.py:489-505` 的 `_pool_full_providers`）。
 *
 * 每轮 claim 前重算：返回当前池满的 provider 集合，传给 claim SQL 做黑名单过滤，
 * 避免池满 task 反复 claim→requeue 刷屏。
 *
 * 关键语义：
 * - 仅统计 [SlotTable.occupiedProviders] 中的 provider（有 inflight 任务的）
 * - capacity > 0 且 `!hasRoom` 才算池满
 * - capacity == 0 的 provider 不算池满（那是 unsupported，由 worker 标 failed 处理）
 */
object PoolFullCalculator {

    /**
     * @param mediaType 当前 claim 的 media lane
     * @param slots 当前槽位台账
     * @param capacity 容量配置表
     * @return 池满的 providerId 集合（空集表示无池满）
     */
    fun calculate(
        mediaType: String,
        slots: SlotTable,
        capacity: CapacityTable
    ): Set<String> {
        val occupied = slots.occupiedProviders(mediaType)
        return occupied.filterTo(mutableSetOf()) { provider ->
            val cap = capacity.get(provider, mediaType)
            cap > 0 && !slots.hasRoom(provider, mediaType, cap)
        }
    }
}
