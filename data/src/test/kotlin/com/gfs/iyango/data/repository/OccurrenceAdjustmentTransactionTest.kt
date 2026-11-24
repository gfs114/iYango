package com.gfs.iyango.data.repository
import androidx.room.Room
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import com.gfs.iyango.data.db.IYangoDatabase
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class OccurrenceAdjustmentTransactionTest {
    private lateinit var db:IYangoDatabase
    private lateinit var repo:RoomAdjustmentRepository
    private val date=LocalDate.of(2026,10,5)
    private val course=Course(id=1,timetableId=1,name="C",dayOfWeek=1,startSection=1,endSection=1,startWeek=1,endWeek=4)
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),IYangoDatabase::class.java).allowMainThreadQueries().build();repo=RoomAdjustmentRepository(db) }
    @After fun close(){db.close()}
    private suspend fun seed(){
        db.timetableDao().upsert(Timetable(id=1,name="T",isCurrent=true).toEntity())
        db.semesterDao().upsert(Semester(id=1,timetableId=1,startDate=date,totalWeeks=4,isCurrent=true).toEntity())
        db.courseDao().upsert(course.toEntity(1))
    }
    private suspend fun expected(at:LocalDate=date):OccurrenceAdjustmentExpected {
        val days=RoomEffectiveSchedule(db).resolveSelectedRange(at..at)
        return days.adjustmentExpected(days.first().occurrences.first { it.anchorDate==date }.course,date.toString())!!.copy(targetDay=RoomEffectiveSchedule(db).resolveDay(date.plusWeeks(1)))
    }
    private fun move()=MoveOccurrence.create(1,1,date.toString(),date.plusWeeks(1),2,2)
    @Test fun `atomic replacement and same expected concurrent only once`()=runTest {
        seed();val token=expected()
        val results=coroutineScope { listOf(async(Dispatchers.IO){repo.replaceOccurrence(token,move())},async(Dispatchers.IO){repo.replaceOccurrence(token,move())}).awaitAll() }
        assertEquals(1,results.count {it==AdjustmentReplaceResult.Saved})
        assertEquals(1,db.occurrenceAdjustmentDao().getByTimetable(1).size)
    }
    @Test fun `insert failure rolls back old move`()=runTest {
        seed();assertEquals(AdjustmentReplaceResult.Saved,repo.replaceOccurrence(expected(),move()))
        val token=expected(date.plusWeeks(1));val old=db.occurrenceAdjustmentDao().getByTimetable(1)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_adjustment BEFORE INSERT ON occurrence_adjustment BEGIN SELECT RAISE(ABORT, 'injected'); END")
        try {repo.replaceOccurrence(token,move());fail("expected insert failure")}catch(_:android.database.sqlite.SQLiteException){}
        assertEquals(old,db.occurrenceAdjustmentDao().getByTimetable(1))
    }
    @Test fun `base source edit and target temporary day reject without deleting`()=runTest {
        seed();val token=expected()
        db.courseDao().upsert(course.copy(name="changed").toEntity(1))
        assertEquals(AdjustmentReplaceResult.Stale,repo.replaceOccurrence(token,move()))
        db.courseDao().upsert(course.toEntity(1))
        repo.insertTemporaryDay(ApplyTemporaryDay.create(1,date.plusWeeks(1),2))
        assertEquals(AdjustmentReplaceResult.Stale,repo.replaceOccurrence(token,move()))
        assertTrue(db.occurrenceAdjustmentDao().getByTimetable(1).isEmpty())
    }

    @Test fun `target bell or conflict set changed requires confirmation`()=runTest {
        seed();val token=expected()
        db.timeSlotDao().upsertAll(listOf(TimeSlot(timetableId=1,section=1,startTime="09:00",endTime="09:45").toEntity()))
        assertEquals(AdjustmentReplaceResult.Stale,repo.replaceOccurrence(token,move()))
        db.timeSlotDao().deleteByTimetable(1)
        val fresh=expected()
        db.courseDao().upsert(course.copy(id=2,name="new conflict").toEntity(1))
        assertEquals(AdjustmentReplaceResult.Stale,repo.replaceOccurrence(fresh,move()))
        assertTrue(db.occurrenceAdjustmentDao().getByTimetable(1).isEmpty())
    }
    @Test fun `move without explicit confirmed target safely refuses`()=runTest {
        seed();assertEquals(AdjustmentReplaceResult.Stale,repo.replaceOccurrence(expected().copy(targetDay=null),move()))
    }
    @Test fun `different anchor remains intact`()=runTest {
        seed();repo.insert(MoveOccurrence.create(1,1,date.toString(),date.plusDays(1),1,1,operationGroupId="shared"))
        val token=expected(date.plusDays(1))
        repo.insert(CancelOccurrence.create(1,1,date.plusWeeks(2).toString(),operationGroupId="shared"))
        repo.insertTemporaryDay(ApplyTemporaryDay.create(1,date.plusWeeks(3),2,operationGroupId="shared"))
        assertEquals(AdjustmentReplaceResult.Saved,repo.replaceOccurrence(token,move()))
        assertEquals(2,db.occurrenceAdjustmentDao().getByTimetable(1).size)
        assertEquals(1,db.temporaryDayDao().getByTimetable(1).size)
    }
    @Test fun `precise restore preserves other owners anchors and temporary dates sharing group`()=runTest {
        seed()
        db.timetableDao().upsert(Timetable(id=2,name="Other").toEntity())
        val group = "shared-menu"
        val a = CancelOccurrence.create(1, 1, date.toString(), operationGroupId=group)
        val otherAnchor = CancelOccurrence.create(1, 1, date.plusWeeks(1).toString(), operationGroupId=group)
        val otherOwner = CancelOccurrence.create(2, 1, date.toString(), operationGroupId=group)
        listOf(a, otherAnchor, otherOwner).forEach { repo.insert(it) }
        repo.insertTemporaryDay(ApplyTemporaryDay.create(1,date,2,operationGroupId=group))
        repo.insertTemporaryDay(ApplyTemporaryDay.create(1,date.plusWeeks(1),2,operationGroupId=group))
        repo.deleteAnchor(1,1,date.toString())
        repo.deleteTemporaryDay(1,date.toString())
        assertEquals(listOf(otherAnchor.copy(id=2)),db.occurrenceAdjustmentDao().getByTimetable(1).map { it.toModel() })
        assertEquals(1,db.occurrenceAdjustmentDao().getByTimetable(2).size)
        assertEquals(listOf(date.plusWeeks(1).toString()),db.temporaryDayDao().getByTimetable(1).map { it.date })
    }
}
