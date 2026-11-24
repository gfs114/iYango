package com.gfs.iyango.data.seed

import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.domain.ResolveScheduleOccurrences
import com.gfs.iyango.data.repository.toModel
import com.gfs.iyango.data.db.IYangoDatabase
import java.time.LocalDate
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
import org.robolectric.annotation.Config

/**
 * [DemoSeeder] 播种行为验证（P2-13，Robolectric）：
 * - 空库播种 1 课表 / 1 学期 / 11 节 / 7 课程；二次调用不重复；
 * - 2026-10-24 解析为第 7 周（周规则锚点）；
 * - 用 [ResolveScheduleOccurrences] 抽查单次调课：周一高数在第 1 周存在、第 8 周被
 *   调课记录压制；周三第 8 节的调课实例只出现在第 8 周。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoSeederTest {

    private lateinit var db: IYangoDatabase
    private lateinit var seeder: DemoSeeder

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, IYangoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seeder = DemoSeeder(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun counts(): List<Int> = listOf(
        db.timetableDao().countAll(),
        db.semesterDao().getAllByTimetable(1).size,
        db.timeSlotDao().getByTimetable(1).size,
        db.courseDao().getAllByTimetable(1).size,
    )

    @Test
    fun `空库播种且幂等`() = runTest {
        seeder.seedDemoIfEmpty()
        assertEquals(listOf(1, 1, 11, 7), counts())

        seeder.seedDemoIfEmpty()
        assertEquals(listOf(1, 1, 11, 7), counts())
    }

    @Test
    fun `2026-10-24 解析为第 7 周`() = runTest {
        seeder.seedDemoIfEmpty()
        val semester = db.semesterDao().getCurrent(1)!!.toModel()
        assertEquals(7, CourseRules.weekOfDate(semester, LocalDate.of(2026, 10, 24)))
    }

    @Test
    fun `单次调课只在第 8 周生效`() = runTest {
        seeder.seedDemoIfEmpty()
        val timetable = db.timetableDao().getById(1)!!.toModel()
        val semester = db.semesterDao().getCurrent(1)!!.toModel()
        val slots = db.timeSlotDao().getByTimetable(1).map { it.toModel() }
        val courses = db.courseDao().getAllByTimetable(1).map { it.toModel() }

        fun occurrencesAt(date: LocalDate) = ResolveScheduleOccurrences.invoke(
            timetable, semester, slots, courses, date..date,
        )

        // 第 1 周周一（2026-09-07）：高数正常发生。
        val week1Monday = occurrencesAt(LocalDate.of(2026, 9, 7))
        val mathWeek1 = week1Monday.filter { it.course.name == "高等数学" }
        assertEquals(1, mathWeek1.size)
        assertFalse(mathWeek1[0].isOverride)
        assertEquals(1, mathWeek1[0].course.startSection)

        // 第 8 周周一（2026-10-26）：基础课被调课记录压制。
        assertTrue(occurrencesAt(LocalDate.of(2026, 10, 26)).none { it.course.name == "高等数学" })

        // 第 8 周周三（2026-10-28）：调课实例出现在第 8 节（D404）。
        val week8Wednesday = occurrencesAt(LocalDate.of(2026, 10, 28))
            .filter { it.course.name == "高等数学" }
        assertEquals(1, week8Wednesday.size)
        assertTrue(week8Wednesday[0].isOverride)
        assertEquals(8, week8Wednesday[0].course.startSection)
        assertEquals("D404", week8Wednesday[0].course.classroom)

        // 第 7 周周三（2026-10-21）：调课记录不发生。
        assertTrue(occurrencesAt(LocalDate.of(2026, 10, 21)).none { it.course.name == "高等数学" })
    }

    @Test
    fun `展示课程覆盖周一至周日且幂等`() = runTest {
        seeder.ensureTestShowcaseCourses()
        val firstCounts = counts()
        seeder.ensureTestShowcaseCourses()
        assertEquals(firstCounts, counts())

        val timetable = db.timetableDao().getById(1)!!.toModel()
        val semester = db.semesterDao().getCurrent(1)!!.toModel()
        val slots = db.timeSlotDao().getByTimetable(1).map { it.toModel() }
        val courses = db.courseDao().getAllByTimetable(1).map { it.toModel() }

        // 第 10 周周一（2026-11-09）至周日（2026-11-15）每日均有课程。
        val monday = LocalDate.of(2026, 11, 9)
        for (offset in 0L..6L) {
            val day = monday.plusDays(offset)
            val occurrences = ResolveScheduleOccurrences.invoke(timetable, semester, slots, courses, day..day)
            assertTrue("日期 $day 应有测试课程", occurrences.isNotEmpty())
        }

        val saturday = ResolveScheduleOccurrences.invoke(
            timetable, semester, slots, courses,
            LocalDate.of(2026, 11, 14)..LocalDate.of(2026, 11, 14),
        )
        assertEquals(4, saturday.size)

        val sunday = ResolveScheduleOccurrences.invoke(
            timetable, semester, slots, courses,
            LocalDate.of(2026, 11, 15)..LocalDate.of(2026, 11, 15),
        )
        assertEquals(3, sunday.size)
    }
}
