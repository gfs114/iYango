package com.gfs.iyango.data.repository

import androidx.room.Room
import com.gfs.iyango.core.model.SyncBatchResult
import com.gfs.iyango.core.model.SyncChangeType
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SchoolSnapshotEntity
import com.gfs.iyango.data.db.SyncBatchEntity
import com.gfs.iyango.data.db.SyncChangeEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [RoomSyncHistoryRepository] 行为验证（E02）：
 * 批次按账号隔离、已读状态、清除级联与历史条数上限。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSyncHistoryRepositoryTest {

    private lateinit var db: IYangoDatabase
    private lateinit var repository: RoomSyncHistoryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            IYangoDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomSyncHistoryRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `批次与变化明细按账号隔离`() = runTest {
        val batchA = insertBatch("account-a", version = 1)
        insertChange(batchA, "高等数学", "教室", "A201", "B301")
        insertBatch("account-b", version = 1)

        val batchesA = repository.observeBatches("account-a").first()
        assertEquals(1, batchesA.size)
        assertEquals("account-a", batchesA.single().connectionId)
        assertEquals(1, repository.observeChanges(batchA).first().size)
        assertEquals(1, repository.observeBatches("account-b").first().size)
    }

    @Test
    fun `打开批次只标记目标已读`() = runTest {
        val first = insertBatch("account-a", version = 1, finishedAt = 100)
        val second = insertBatch("account-a", version = 2, finishedAt = 200)

        repository.markRead(first)

        val batches = repository.observeBatches("account-a").first()
        assertTrue(batches.first { it.id == first }.read)
        assertFalse(batches.first { it.id == second }.read)
    }

    @Test
    fun `全部已读只影响当前账号`() = runTest {
        insertBatch("account-a", version = 1, finishedAt = 100)
        insertBatch("account-a", version = 2, finishedAt = 200)
        insertBatch("account-b", version = 1, finishedAt = 300)

        repository.markAllRead("account-a")

        assertTrue(repository.observeBatches("account-a").first().all { it.read })
        assertFalse(repository.observeBatches("account-b").first().single().read)
    }

    @Test
    fun `清除级联删除变化明细但保留学校快照`() = runTest {
        val batch = insertBatch("account-a", version = 1)
        insertChange(batch, "高等数学", "教室", "A201", "B301")
        insertBatch("account-b", version = 1)
        db.schoolSnapshotDao().insert(
            SchoolSnapshotEntity(
                connectionId = "account-a",
                timetableId = 7,
                semester = "2025-1",
                payloadJson = "{}",
                digest = "digest",
                savedAt = 1,
            ),
        )

        repository.clear("account-a")

        assertTrue(repository.observeBatches("account-a").first().isEmpty())
        assertTrue(db.syncChangeDao().getByBatch(batch).isEmpty())
        assertEquals(1, repository.observeBatches("account-b").first().size)
        assertEquals(
            "digest",
            db.schoolSnapshotDao().get("account-a", 7, "2025-1")?.digest,
        )
    }

    @Test
    fun `历史条数有上限且保留最新批次`() = runTest {
        repeat(55) { index ->
            insertBatch("account-a", version = index + 1, finishedAt = (index + 1).toLong())
        }

        db.syncBatchDao().trim("account-a", keep = 50)

        val batches = repository.observeBatches("account-a").first()
        assertEquals(50, batches.size)
        assertEquals(55, batches.first().version)
        assertEquals(6, batches.last().version)
    }

    @Test
    fun `变化明细保留前后值`() = runTest {
        val batch = insertBatch("account-a", version = 2)
        insertChange(batch, "高等数学", "上课时间", "08:00-09:40", "10:00-11:40")

        val change = repository.observeChanges(batch).first().single()
        assertEquals(SyncChangeType.MODIFIED, change.changeType)
        assertEquals("上课时间", change.field)
        assertEquals("08:00-09:40", change.beforeValue)
        assertEquals("10:00-11:40", change.afterValue)
    }

    private suspend fun insertBatch(
        connectionId: String,
        version: Int,
        finishedAt: Long = version.toLong(),
    ): Long = db.syncBatchDao().insert(
        SyncBatchEntity(
            connectionId = connectionId,
            timetableId = 7,
            semester = "2025-1",
            version = version,
            startedAt = finishedAt - 1,
            finishedAt = finishedAt,
            result = SyncBatchResult.SUCCESS,
            changeCount = 1,
            firstBaseline = false,
            message = "新增 1，修改 0，移除 0",
            read = false,
        ),
    )

    private suspend fun insertChange(
        batchId: Long,
        courseName: String,
        field: String,
        before: String,
        after: String,
    ) {
        db.syncChangeDao().insertAll(
            listOf(
                SyncChangeEntity(
                    batchId = batchId,
                    stableKey = "ygu:$courseName",
                    courseName = courseName,
                    changeType = SyncChangeType.MODIFIED,
                    field = field,
                    beforeValue = before,
                    afterValue = after,
                ),
            ),
        )
    }
}
