package io.legado.app.ui.novelvideo

import kotlinx.coroutines.sync.Mutex

/**
 * 小说视频任务操作的共享互斥锁（P6 修复缺口 1.4.4）。
 *
 * **背景**：原先 [NovelVideoTaskCenterViewModel] 和 [NovelVideoJobDetailViewModel]
 * 各自在 companion 持有独立的 `jobOpMutex`，导致两个 ViewModel 对同一 job 的
 * retry / cancel / delete 操作**不互斥**——任务中心发起 retry 的同时详情页发起 cancel，
 * 两者的条件更新可能交错覆写状态（如 retry 把 CANCELLED 改回 GENERATING）。
 *
 * 修复：抽出全局单例 [mutex]，两个 ViewModel 共用，确保跨 ViewModel 的同 job 操作串行化。
 *
 * 注：仍为「同 job 操作串行化」语义，不区分 jobId——实际并发中不同 job 的 retry/cancel
 * 互不冲突，串行化开销可忽略（操作均为快速 DB 写）。若未来 job 数量大且锁竞争明显，
 * 可改为 per-jobId 的 Mutex map。
 */
object NovelVideoJobOps {
    val mutex: Mutex = Mutex()
}
