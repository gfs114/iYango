package com.gfs.iyango.feature.main.schedule
import com.gfs.iyango.core.model.TimeSlot
import org.junit.Test
import org.junit.Assert.*
class ScheduleAxisGeometryTest {
    private val slots = listOf(TimeSlot(section=1,startTime="11:00",endTime="11:45"), TimeSlot(section=2,startTime="13:00",endTime="13:45"))
    @Test fun `lunch is an unselectable band and spanning class covers it`() {
        val axis = ScheduleAxisGeometry(2, 50f, 1)
        assertEquals(50f, axis.sectionBottom(1), 0f)
        assertEquals(68f, axis.sectionTop(2), 0f)
        assertEquals(listOf(0f,50f,68f,118f),axis.gridLines)
        assertNull(axis.sectionAtY(59f))
        assertEquals(2, axis.sectionAtY(68f))
        assertNull(axis.sectionAtY(-1f)); assertNull(axis.sectionAtY(axis.totalHeight))
        assertEquals(118f, axis.rowToY(2f, true), 0f)
        assertEquals(1, ScheduleAxisGeometry.commonLunchBoundary(listOf(slots,slots)))
        assertNull(ScheduleAxisGeometry.commonLunchBoundary(listOf(slots,emptyList())))
    }
    @Test fun `minute mapping refuses unknown gaps and contradictory slots`() {
        assertEquals(1.6666667f, ScheduleAxisGeometry.rowAtMinute(810, slots)!!, 0.001f)
        assertNull(ScheduleAxisGeometry.rowAtMinute(750, slots))
        assertNull(ScheduleAxisGeometry.rowAtMinute(670, slots + slots.first()))
    }
    @Test fun `unknown sections do not discard other known bells`() {
        val partial = listOf(slots.first(), TimeSlot(section=2), TimeSlot(section=3,startTime="14:00",endTime="14:45"))
        assertNotNull(ScheduleAxisGeometry.rowAtMinute(670,partial))
        assertNull(ScheduleAxisGeometry.rowAtMinute(780,partial))
        assertNotNull(ScheduleAxisGeometry.rowAtMinute(850,partial))
    }
    @Test fun `integer break edges distinguish morning end and afternoon start`() {
        val axis=ScheduleAxisGeometry(6,50f,4)
        assertEquals(200f,axis.rowToY(4f,true),0f)
        assertEquals(218f,axis.rowToY(4f),0f)
        assertEquals(118f,axis.sectionBottom(5)-axis.sectionTop(4),0f)
    }
    @Test fun `section policy distinguishes fixed official exports and mixed pages`() {
        assertEquals(11, ScheduleSectionPolicy.count("YGU",15,15,40))
        assertEquals(11, ScheduleSectionPolicy.count("LOCAL",10,11,20))
        assertEquals(11, ScheduleSectionPolicy.count("LOCAL",0,0,20))
        assertEquals(12, ScheduleSectionPolicy.count("LOCAL",12,11,20))
        assertEquals(12, ScheduleSectionPolicy.count("LOCAL",8,12,20))
        assertEquals(35, ScheduleSectionPolicy.count("LOCAL",35,12,40))
        assertEquals(20, ScheduleSectionPolicy.count("LOCAL",35,12,20))
    }
}
