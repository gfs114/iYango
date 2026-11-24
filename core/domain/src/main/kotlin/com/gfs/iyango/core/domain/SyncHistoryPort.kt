package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.SyncBatch
import com.gfs.iyango.core.model.SyncChange
import kotlinx.coroutines.flow.Flow

/**
 * 教务同步变更记录端口（E02）。
 *
 * 记录只在同步成功提交的事务内写入；读取按连接隔离，历史条数由实现方限制，
 * 清除记录不影响学校快照基线（下次同步仍能正确比较）。
 */
interface SyncHistoryPort {

    /** 观察指定账号的同步批次，最新的在前。 */
    fun observeBatches(connectionId: String): Flow<List<SyncBatch>>

    /** 观察某批次的字段级变化。 */
    fun observeChanges(batchId: Long): Flow<List<SyncChange>>

    /** 用户查看批次后标记已读。 */
    suspend fun markRead(batchId: Long)

    /** 把指定账号的全部批次标记已读。 */
    suspend fun markAllRead(connectionId: String)

    /** 清除指定账号的批次与变化明细。 */
    suspend fun clear(connectionId: String)
}
