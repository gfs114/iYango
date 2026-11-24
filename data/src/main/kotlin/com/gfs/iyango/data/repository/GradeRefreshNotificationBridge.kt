package com.gfs.iyango.data.repository

import com.gfs.iyango.core.model.GradeRecord
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** 单次成绩刷新写库前后的精确快照差集，专供出分通知协调器消费。 */
data class GradeSyncDeltaSnapshot(
    val seq: Long,
    val accountKey: String,
    val partition: String,
    val hadBaseline: Boolean,
    val previousRecords: List<GradeRecord>,
    val newRecords: List<GradeRecord>,
)

/**
 * [GradeRefreshHandler] 与 [DefaultGradeMonitorCoordinator] 间的无依赖内存桥接。
 *
 * 将“写库前快照 + 本轮拉取结果”按单调递增序号保存在进程内存中，避免协调器与刷新执行器
 * 互相引用形成 Hilt 循环依赖。
 */
@Singleton
class GradeRefreshNotificationBridge @Inject constructor() {

    private val seqGenerator = AtomicLong(0L)
    private val lock = Any()
    private var latest: GradeSyncDeltaSnapshot? = null

    fun currentSeq(): Long = seqGenerator.get()

    fun recordCompletedSync(
        accountKey: String,
        partition: String,
        hadBaseline: Boolean,
        previousRecords: List<GradeRecord>,
        newRecords: List<GradeRecord>,
    ): Long {
        val nextSeq = seqGenerator.incrementAndGet()
        synchronized(lock) {
            latest = GradeSyncDeltaSnapshot(
                seq = nextSeq,
                accountKey = accountKey,
                partition = partition,
                hadBaseline = hadBaseline,
                previousRecords = previousRecords,
                newRecords = newRecords,
            )
        }
        return nextSeq
    }

    /** 仅当存在比 [afterSeq] 新的记录且账号匹配时取出（一次性消费）。 */
    fun consumeIfNewer(afterSeq: Long, accountKey: String): GradeSyncDeltaSnapshot? =
        synchronized(lock) {
            val current = latest ?: return null
            if (current.seq <= afterSeq || current.accountKey != accountKey) return null
            latest = null
            current
        }
}
