package com.gfs.iyango.data.repository

import androidx.room.Room
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.AdjustmentType
import com.gfs.iyango.data.db.OccurrenceAdjustmentEntity
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.TimetableEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [RoomScheduleDataPort.saveCourses] 事务语义：任一课程不合法时整批不落库；
 * 全部合法时按入参顺序返回持久化 id。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomScheduleDataPortTest {

    private lateinit var db: IYangoDatabase
    private lateinit var port: RoomScheduleDataPort
    private var timetableId = 0L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, IYangoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        port = RoomScheduleDataPort(db)
        timetableId = kotlinx.coroutines.runBlocking {
            db.timetableDao().upsert(
                TimetableEntity(
                    name = "测试课表",
                    source = "LOCAL",
                    schoolTerm = "",
                    connectionId = "",
                    isCurrent = true,
                    lastSyncAt = "",
                    groupName = "",
                    autoEnabled = false,
                    enabledDays = listOf(1, 2, 3, 4, 5, 6, 7),
                    cycleLength = 1,
                    cycleWeek = 0,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `批量保存按入参顺序返回id`() = runTest {
        val ids = port.saveCourses(
            listOf(
                course("高等数学", dayOfWeek = 1),
                course("大学英语", dayOfWeek = 2),
            ),
        )

        assertEquals(2, ids.size)
        assertTrue(ids.all { it > 0 })
        assertEquals(
            listOf("高等数学", "大学英语"),
            db.courseDao().getAllByTimetable(timetableId).sortedBy { it.id }.map { it.name },
        )
    }

    @Test
    fun `任一课程不合法时整批回滚`() = runTest {
        val failure = runCatching {
            port.saveCourses(
                listOf(
                    course("高等数学", dayOfWeek = 1),
                    course("", dayOfWeek = 2),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(db.courseDao().getAllByTimetable(timetableId).isEmpty())
    }

    @Test
    fun `同批新增的单次调整重复时整批回滚`() = runTest {
        val failure = runCatching {
            port.saveCourses(
                listOf(
                    course("调整一", dayOfWeek = 1, overrideOfId = 9, overrideWeek = 5),
                    course("调整二", dayOfWeek = 2, overrideOfId = 9, overrideWeek = 5),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(db.courseDao().getAllByTimetable(timetableId).isEmpty())
    }

    @Test fun `batch delete removes both adjustment formats and leaves unrelated courses`() = runTest {
        val first = port.saveCourse(course("数学", 1))
        val second = port.saveCourse(course("英语", 2))
        port.saveCourse(course("数学调整", 3, first, 5))
        port.saveCourse(course("英语调整", 4, second, 5))
        addAdjustment(first)
        addAdjustment(second)
        port.deleteCourses(timetableId + 1, setOf(first))
        assertEquals(4, db.courseDao().getAllByTimetable(timetableId).size)
        port.deleteCourses(timetableId, setOf(first))
        assertEquals(setOf("英语", "英语调整"), db.courseDao().getAllByTimetable(timetableId).map { it.name }.toSet())
        assertEquals(listOf(second), db.occurrenceAdjustmentDao().getByTimetable(timetableId).map { it.sourceCourseId })
    }

    @Test fun `failed batch deletion rolls back base courses and adjustments`() = runTest {
        val first = port.saveCourse(course("数学", 1))
        val second = port.saveCourse(course("英语", 2))
        port.saveCourse(course("数学调整", 3, first, 5))
        addAdjustment(first)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_delete BEFORE DELETE ON course WHEN OLD.id = $second BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { port.deleteCourses(timetableId, setOf(first, second)) }.isFailure)
        assertEquals(3, db.courseDao().getAllByTimetable(timetableId).size)
        assertEquals(1, db.occurrenceAdjustmentDao().getByTimetable(timetableId).size)
    }

    private suspend fun addAdjustment(courseId: Long) {
        db.occurrenceAdjustmentDao().insert(OccurrenceAdjustmentEntity(
            timetableId = timetableId, operationGroupId = "test", type = AdjustmentType.CANCEL,
            sourceCourseId = courseId, sourceDate = "2026-09-07", targetDate = "", targetDayOfWeek = 0,
            targetStartSection = 0, targetEndSection = 0, targetStartTime = "", targetEndTime = "",
            targetClassroom = "", note = "", createdAt = 0,
        ))
    }

    private fun course(
        name: String,
        dayOfWeek: Int,
        overrideOfId: Long = 0,
        overrideWeek: Int = 0,
    ): Course = Course(
        timetableId = timetableId,
        name = name,
        dayOfWeek = dayOfWeek,
        startSection = 1,
        endSection = 2,
        startWeek = 1,
        endWeek = 16,
        overrideOfId = overrideOfId,
        overrideWeek = overrideWeek,
    )
}
