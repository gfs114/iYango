package com.gfs.iyango.data.repository

import app.cash.turbine.test
import androidx.room.Room
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import com.gfs.iyango.data.db.IYangoDatabase
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomEffectiveScheduleTest {
    private lateinit var db: IYangoDatabase
    private lateinit var port: RoomEffectiveSchedule
    private val start = LocalDate.of(2026, 10, 5)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), IYangoDatabase::class.java)
            .allowMainThreadQueries().build()
        port = RoomEffectiveSchedule(db)
    }
    @After fun close() { db.close() }
    private suspend fun table(id: Long, days: List<Int>, current: Boolean = false, group: String = "A", phase: Int = 0) {
        db.timetableDao().upsert(Timetable(id = id, name = "T$id", isCurrent = current,
            autoEnabled = true, enabledDays = days, groupName = group, cycleLength = 2, cycleWeek = phase).toEntity())
        db.semesterDao().upsert(Semester(id = id, timetableId = id, startDate = start, totalWeeks = 4, isCurrent = true).toEntity())
        db.timeSlotDao().upsertAll(listOf(TimeSlot(timetableId = id, section = 1, startTime = "08:00", endTime = "08:45").toEntity()))
    }
    private suspend fun course(id: Long, table: Long, day: Int) {
        db.courseDao().upsert(Course(id = id, timetableId = table, name = "C$id", dayOfWeek = day,
            startSection = 1, endSection = 1, startWeek = 1, endWeek = 4).toEntity(table))
    }
    @Test fun `selected range follows group weekdays rotation and leaves unmatched day empty`() = runTest {
        table(1, listOf(1), true); table(2, listOf(2), phase = 1); table(3, listOf(3), group = "B")
        course(1, 1, 1); course(2, 2, 2); course(3, 1, 3); course(4, 3, 3)
        val days = port.resolveSelectedRange(start..start.plusDays(9))
        assertEquals(listOf(1L, 2L, 1L), days.take(3).map { it.timetable?.id })
        assertEquals(listOf(1, 1, 0), days.take(3).map { it.occurrences.size })
        assertTrue(days[8].occurrences.isEmpty())
        assertEquals(days[1], port.resolveDay(start.plusDays(1)))
        assertEquals(days[1], port.observeDay(start.plusDays(1)).first())
        assertEquals(1, port.resolveRange(1, start.plusDays(2)..start.plusDays(2)).size)
        assertEquals(1, days[1].timeSlots.size)
    }
    @Test fun `cancellation cross week moves and temporary day are effective query inputs`() = runTest {
        table(1, (1..7).toList(), true); table(2, (1..7).toList(), group = "B")
        course(1, 1, 1); course(2, 2, 2)
        val adjustments = RoomAdjustmentRepository(db)
        adjustments.insert(CancelOccurrence.create(1, 1, start.toString()))
        adjustments.insert(MoveOccurrence.create(1, 1, start.plusWeeks(1).toString(), start.plusDays(1), 1, 1))
        adjustments.insert(MoveOccurrence.create(1, 1, start.plusWeeks(2).toString(), start.plusDays(1), 1, 1))
        adjustments.insertTemporaryDay(ApplyTemporaryDay.create(1, start.plusDays(8), 2))
        val days = port.resolveSelectedRange(start..start.plusDays(15))
        assertTrue(days[0].occurrences.isEmpty())
        assertTrue(days[7].occurrences.isEmpty())
        assertEquals(2, days[1].occurrences.size)
        assertEquals(2, days[1].occurrences.map { it.occurrenceId }.toSet().size)
        assertEquals(setOf(start.plusWeeks(1), start.plusWeeks(2)), days[1].occurrences.map { it.anchorDate }.toSet())
        assertTrue(days[8].occurrences.single().isTemporary)
        assertEquals(2L, days[8].occurrences.single().course.timetableId)
        assertTrue(days[14].occurrences.isEmpty())
    }
    @Test fun `temporary day takes priority over unmatched automatic fallback`() = runTest {
        table(1, listOf(1), true); table(2, listOf(2), group = "B")
        course(2, 2, 2)
        RoomAdjustmentRepository(db).insertTemporaryDay(ApplyTemporaryDay.create(1, start.plusDays(1), 2))
        val day = port.resolveDay(start.plusDays(1))
        assertTrue(day.isTemporaryDay)
        assertEquals(1, day.occurrences.size)
    }
    @Test fun `selected observation reacts to another selected table and temporary source edits`() = runTest {
        table(1, listOf(1), true); table(2, listOf(2)); table(3, listOf(3), group = "B")
        course(1, 1, 1); course(2, 2, 2); course(3, 3, 3)
        RoomAdjustmentRepository(db).insertTemporaryDay(ApplyTemporaryDay.create(1, start.plusDays(2), 3))
        port.observeSelectedRange(start..start.plusDays(6)).test {
            assertEquals(1, awaitItem()[1].occurrences.size)
            course(4, 2, 2)
            var updated = awaitItem()
            while (updated[1].occurrences.size != 2) updated = awaitItem()
            assertEquals(2, updated[1].occurrences.size)
            course(5, 3, 3)
            updated = awaitItem()
            while (updated[2].occurrences.size != 2) updated = awaitItem()
            assertTrue(updated[2].occurrences.all { it.isTemporary })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `temporary day retains owner slots but displays source bells`() = runTest {
        table(1,listOf(1),true);table(2,listOf(2),group="B");course(2,2,1)
        db.timeSlotDao().deleteByTimetable(2)
        db.timeSlotDao().upsertAll(listOf(TimeSlot(timetableId=2,section=1,startTime="10:00",endTime="10:45").toEntity()))
        RoomAdjustmentRepository(db).insertTemporaryDay(ApplyTemporaryDay.create(1,start,2))
        val day=port.resolveDay(start)
        assertEquals("LOCAL",day.displaySource)
        assertEquals(1L,day.timetable!!.id)
        assertEquals("08:00",day.timeSlots.first().startTime)
        assertEquals("10:00",day.displayTimeSlots.first().startTime)
        assertEquals(600,day.occurrences.single().startMinute)
    }

    @Test fun `temporary display source comes from actual source not owner`() = runTest {
        table(1,listOf(1),true);table(2,listOf(2),group="B")
        db.timetableDao().upsert(db.timetableDao().getById(1)!!.toModel().copy(source="YGU").toEntity())
        RoomAdjustmentRepository(db).insertTemporaryDay(ApplyTemporaryDay.create(1,start,2))
        var day=port.resolveDay(start)
        assertEquals("YGU",day.timetable!!.source);assertEquals("LOCAL",day.displaySource)
        db.timetableDao().upsert(db.timetableDao().getById(2)!!.toModel().copy(source="YGU").toEntity())
        day=port.resolveDay(start)
        assertEquals("YGU",day.displaySource)
        assertEquals("YGU",port.resolveDay(start.plusWeeks(1)).displaySource)
    }

    @Test fun `fixed day metadata keeps explicit owner and actual temporary display source`() = runTest {
        table(1,listOf(1),true);table(2,listOf(2));table(3,listOf(3),group="B")
        db.timetableDao().upsert(db.timetableDao().getById(1)!!.toModel().copy(source="YGU").toEntity())
        course(3,3,2)
        db.timeSlotDao().deleteByTimetable(3)
        db.timeSlotDao().upsertAll(listOf(TimeSlot(timetableId=3,section=14,startTime="18:00",endTime="18:45").toEntity()))
        RoomAdjustmentRepository(db).insertTemporaryDay(ApplyTemporaryDay.create(1,start.plusDays(1),3))
        val range=start..start.plusDays(6)
        val fixed=port.resolveTimetableDays(1,range)
        val temporary=fixed[1]
        assertEquals(1L,temporary.timetable!!.id)
        assertEquals("LOCAL",temporary.displaySource)
        assertEquals(14,temporary.displayTimeSlots.single().section)
        assertEquals(port.resolveTimetableRange(1,range),fixed.flatMap { it.occurrences })
        assertTrue(fixed.all { it.timetable!!.id==1L })
    }

}
