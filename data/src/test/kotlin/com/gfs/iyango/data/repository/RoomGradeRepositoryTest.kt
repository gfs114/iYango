package com.gfs.iyango.data.repository

import androidx.room.Room
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.data.db.IYangoDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [RoomGradeRepository] 离线缓存与变化标记测试（E03）：
 * 首次缓存不算新发布、分数变化保留旧值、行序变化不误报、已读与账号/学期隔离。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomGradeRepositoryTest {

    private lateinit var db: IYangoDatabase
    private lateinit var repository: RoomGradeRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, IYangoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomGradeRepository(db, fixedClock())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `首次缓存不标记新发布`() = runTest {
        repository.saveCompleteResult("account-a", "2025-1", listOf(record("1", "高数", "80")), "2025-1")

        val records = repository.observeRecords("account-a", "2025-1").first()
        assertEquals(1, records.size)
        assertFalse(records.single().isNew)
        assertFalse(records.single().isModified)
        assertEquals("", records.single().previousScoreText)
        assertNotNull(repository.observeSnapshot("account-a", "2025-1").first())
    }

    @Test
    fun `新增与分数变化标记并保留旧值`() = runTest {
        repository.saveCompleteResult(
            "account-a",
            "2025-1",
            listOf(record("1", "高数", "80"), record("2", "英语", "90")),
            "2025-1",
        )
        repository.saveCompleteResult(
            "account-a",
            "2025-1",
            listOf(record("2", "英语", "90"), record("1", "高数", "85"), record("3", "物理", "70")),
            "2025-1",
        )

        val records = repository.observeRecords("account-a", "2025-1").first()
        val math = records.first { it.schoolRecordId == "1" }
        val english = records.first { it.schoolRecordId == "2" }
        val physics = records.first { it.schoolRecordId == "3" }
        assertTrue(math.isModified)
        assertEquals("80", math.previousScoreText)
        assertEquals("85", math.scoreText)
        assertFalse(english.isNew)
        assertFalse(english.isModified)
        assertTrue(physics.isNew)
        assertEquals(2, repository.observeUnreadCount("account-a").first())
    }

    @Test
    fun `返回顺序变化不产生变化标记`() = runTest {
        repository.saveCompleteResult(
            "account-a",
            "2025-1",
            listOf(record("1", "高数", "80"), record("2", "英语", "90")),
            "2025-1",
        )
        repository.saveCompleteResult(
            "account-a",
            "2025-1",
            listOf(record("2", "英语", "90"), record("1", "高数", "80")),
            "2025-1",
        )

        val records = repository.observeRecords("account-a", "2025-1").first()
        assertEquals(listOf("2", "1"), records.map { it.schoolRecordId })
        assertTrue(records.none { it.isNew || it.isModified })
        assertEquals(0, repository.observeUnreadCount("account-a").first())
    }

    @Test
    fun `已读按学期清除且不影响其他学期`() = runTest {
        repository.saveCompleteResult("account-a", "2025-1", listOf(record("1", "高数", "80")), "2025-1")
        repository.saveCompleteResult("account-a", "2025-2", listOf(record("2", "英语", "90")), "2025-2")
        repository.saveCompleteResult("account-a", "2025-1", listOf(record("1", "高数", "85")), "2025-1")
        repository.saveCompleteResult("account-a", "2025-2", listOf(record("2", "英语", "95")), "2025-2")
        assertEquals(2, repository.observeUnreadCount("account-a").first())

        repository.markChangesRead("account-a", "2025-1")

        assertEquals(1, repository.observeUnreadCount("account-a").first())
        val termOne = repository.observeRecords("account-a", "2025-1").first().single()
        val termTwo = repository.observeRecords("account-a", "2025-2").first().single()
        assertFalse(termOne.isModified)
        assertTrue(termTwo.isModified)
    }

    @Test
    fun `账号与学期分区隔离`() = runTest {
        repository.saveCompleteResult("account-a", "2025-1", listOf(record("1", "高数", "80")), "2025-1")
        repository.saveCompleteResult("account-b", "2025-1", listOf(record("9", "物理", "60")), "2025-1")

        assertEquals(listOf("高数"), repository.observeRecords("account-a", "2025-1").first().map { it.courseName })
        assertEquals(listOf("物理"), repository.observeRecords("account-b", "2025-1").first().map { it.courseName })
        assertEquals(emptyList<String>(), repository.observeRecords("account-a", "2025-2").first().map { it.courseName })
        assertNull(repository.observeSnapshot("account-a", "2025-2").first())
    }

    @Test
    fun `重建仓储后缓存仍可读`() = runTest {
        repository.saveCompleteResult("account-a", "2025-1", listOf(record("1", "高数", "80")), "2025-1")

        val reopened = RoomGradeRepository(db, fixedClock())
        val records = reopened.observeRecords("account-a", "2025-1").first()
        assertEquals(1, records.size)
        assertEquals("80", records.single().scoreText)
        assertEquals("2025-1", reopened.observeSnapshot("account-a", "2025-1").first()?.semester)
    }

    @Test
    fun `退出账号清理对应分区`() = runTest {
        repository.saveCompleteResult("account-a", "2025-1", listOf(record("1", "高数", "80")), "2025-1")
        repository.saveCompleteResult("account-b", "2025-1", listOf(record("2", "英语", "90")), "2025-1")

        repository.deleteAccount("account-a")

        assertNull(repository.observeSnapshot("account-a", "2025-1").first())
        assertTrue(repository.observeRecords("account-a", "2025-1").first().isEmpty())
        assertEquals(1, repository.observeRecords("account-b", "2025-1").first().size)
    }

    @Test
    fun `全部学期分区独立于具体学期`() = runTest {
        repository.saveCompleteResult(
            "account-a",
            GradeRepositoryPort.ALL_SEMESTERS,
            listOf(record("1", "高数", "80")),
            GradeRepositoryPort.ALL_SEMESTERS,
        )
        repository.saveCompleteResult("account-a", "2025-1", listOf(record("1", "高数", "85")), "2025-1")

        assertEquals(
            "80",
            repository.observeRecords("account-a", GradeRepositoryPort.ALL_SEMESTERS).first().single().scoreText,
        )
        assertEquals("85", repository.observeRecords("account-a", "2025-1").first().single().scoreText)
    }

    @Test
    fun `全部学期缓存可还原具体学期并按有成绩学期筛选`() = runTest {
        repository.saveCompleteResult(
            "account-a",
            GradeRepositoryPort.ALL_SEMESTERS,
            listOf(
                record("ygu:2024-2025-1|C1|高数|正常考试|", "高数", "88").copy(semester = "2024-2025-1"),
                record("ygu:2025-2026-1|C2|英语|正常考试|", "英语", "92").copy(semester = "2025-2026-1"),
            ),
            GradeRepositoryPort.ALL_SEMESTERS,
        )

        val allRecords = repository.observeRecords("account-a", GradeRepositoryPort.ALL_SEMESTERS).first()
        assertEquals(listOf("2024-2025-1", "2025-2026-1"), allRecords.map { it.semester })
        assertEquals(listOf("2025-2026-1", "2024-2025-1"), repository.observeSemesters("account-a").first())
        val termRecords = repository.observeRecords("account-a", "2025-2026-1").first()
        assertEquals(listOf("英语"), termRecords.map { it.courseName })
        assertTrue(repository.observeRecords("account-a", "2026-2027-1").first().isEmpty())
    }

    private fun record(id: String, name: String, score: String): GradeRecord = GradeRecord(
        semester = "2025-1",
        courseName = name,
        courseCode = "C$id",
        credit = 3.0,
        scoreText = score,
        schoolRecordId = id,
    )

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)
}
