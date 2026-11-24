package com.gfs.iyango.feature.main.schedule
import com.gfs.iyango.core.model.*
import java.time.*
import org.junit.Test
import org.junit.Assert.*
class ExamWeekProjectionTest {
    private val monday = LocalDate.of(2026,10,5)
    private val slots = listOf(TimeSlot(section=1,startTime="08:00",endTime="09:00"))
    private fun exam()=Exam(id=1,subject="数学",date="2026-10-05",startTime="08:00",endTime="09:00")
    private fun project(e:Exam)=ExamWeekProjection.project(listOf(e),monday,ZoneId.of("Asia/Shanghai")) { slots }
    @Test fun `complete same day draws block and keeps daily entry`() {
        val result=project(exam()); assertEquals(1,result.size); assertNotNull(result.first().topRow)
        assertEquals(monday,result.first().date)
    }
    @Test fun `cancelled hidden but incomplete cross day outside and invalid zone remain entries`() {
        assertTrue(project(exam().copy(status=ExamStatus.CANCELLED)).isEmpty())
        listOf(exam().copy(endTime=""),exam().copy(endDate="2026-10-06"),exam().copy(startTime="07:00"),exam().copy(timeZone="bad")).forEach {
            assertNull(project(it).first().topRow)
        }
    }
    @Test fun `timezone can bring previous raw day into this week`() {
        val result=project(exam().copy(date="2026-10-04",startTime="20:00",endTime="21:00",timeZone="America/New_York"))
        assertEquals(monday,result.first().date); assertNotNull(result.first().topRow)
    }
    @Test fun `date only does not change its date across zones`() {
        assertEquals(monday,project(exam().copy(startTime="",endTime="",timeZone="Pacific/Honolulu")).first().date)
    }
    @Test fun `ambiguous daylight saving hour has no time block`() {
        val result=ExamWeekProjection.project(listOf(exam().copy(date="2026-11-01",startTime="01:10",endTime="01:40",timeZone="America/New_York")),LocalDate.of(2026,10,26),ZoneId.of("UTC")){slots}
        assertNull(result.first().topRow)
    }
}
