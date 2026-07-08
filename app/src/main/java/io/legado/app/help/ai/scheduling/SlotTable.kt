package io.legado.app.help.ai.scheduling

import kotlinx.coroutines.Job

/**
 * 运行时槽位台账（移植 ArcReel `generation_worker.py:221-315` 的 SlotTable）。
 *
 * 纯内存、容量无关：记录每个 provider × media 当前 inflight 的 jobId 集合 + 对应协程 Job。
 *
 * 关键不变量（与 ArcReel 一致）：
 * - **空 bucket 不残留**：[release] 时若 bucket 变空则整体删除，保证 [occupiedProviders]
 *   永不返回空 provider（池满黑名单决策的支点）
 * - [hasRoom] 判断当前占用数 < capacity
 *
 * @see <a href="file:///tmp/arcreel/lib/generation_worker.py">ArcReel generation_worker.py L221-315</a>
 */
class SlotTable {

    private val _slots: MutableMap<Pair<String, String>, MutableMap<String, Job>> = mutableMapOf()

    /**
     * 登记一个 inflight 任务。
     */
    fun register(providerId: String, mediaType: String, jobId: String, job: Job) {
        val key = providerId to mediaType
        _slots.getOrPut(key) { mutableMapOf() }[jobId] = job
    }

    /**
     * 释放一个 inflight 任务。若 bucket 变空则整体删除（空 bucket 不残留）。
     */
    fun release(providerId: String, mediaType: String, jobId: String) {
        val key = providerId to mediaType
        val bucket = _slots[key] ?: return
        bucket.remove(jobId)
        if (bucket.isEmpty()) {
            _slots.remove(key)
        }
    }

    /**
     * 判断指定 provider × media 是否还有空闲槽位。
     */
    fun hasRoom(providerId: String, mediaType: String, capacity: Int): Boolean {
        if (capacity <= 0) return false
        val bucket = _slots[providerId to mediaType] ?: return true
        return bucket.size < capacity
    }

    /**
     * 返回当前有 inflight 任务的 provider 集合（指定 media_type）。
     * 空 bucket 不残留，所以这里的 provider 一定有至少 1 个 inflight 任务。
     */
    fun occupiedProviders(mediaType: String): Set<String> {
        return _slots.keys
            .filter { it.second == mediaType }
            .map { it.first }
            .toSet()
    }

    /**
     * 按 jobId 查找其所在的 (providerId, mediaType)。
     * 用于 cancel 路径定位 Job。
     */
    fun findByJobId(jobId: String): Pair<String, String>? {
        for ((key, bucket) in _slots) {
            if (jobId in bucket) return key
        }
        return null
    }

    /**
     * 按 jobId 获取对应的协程 Job（cancel 路径用）。
     */
    fun getJob(jobId: String): Job? {
        for (bucket in _slots.values) {
            bucket[jobId]?.let { return it }
        }
        return null
    }

    /**
     * 返回所有 inflight 的 jobId 集合（cancel 全部用）。
     */
    fun activeJobIds(): Set<String> {
        return _slots.values.flatMap { it.keys }.toSet()
    }

    /** 当前 inflight 总数（测试/调试用）。 */
    fun totalInflight(): Int = _slots.values.sumOf { it.size }

    /** 清空所有槽位（测试用）。 */
    fun clear() = _slots.clear()
}
