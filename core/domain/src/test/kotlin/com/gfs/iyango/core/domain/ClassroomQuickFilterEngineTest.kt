package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.ClassroomBuilding
import com.gfs.iyango.core.model.ClassroomCell
import com.gfs.iyango.core.model.ClassroomCellState
import com.gfs.iyango.core.model.ClassroomPeriodGroup
import com.gfs.iyango.core.model.ClassroomQuery
import com.gfs.iyango.core.model.ClassroomResult
import com.gfs.iyango.core.model.ClassroomRoom
import com.gfs.iyango.core.model.PinnedClassroomBuilding
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomQuickFilterEngineTest {

    private val groups = listOf(
        ClassroomPeriodGroup("0102", "第1-2节", "08:20", "09:55"),
        ClassroomPeriodGroup("0304", "第3-4节", "10:15", "11:50"),
        ClassroomPeriodGroup("0506", "第5-6节", "14:00", "15:35"),
        ClassroomPeriodGroup("0708", "第7-8节", "15:55", "17:30"),
        ClassroomPeriodGroup("1112", "第十一节", "20:10", "20:55"),
    )

    private fun room(name: String, vararg states: ClassroomCellState): ClassroomRoom =
        ClassroomRoom(
            name = name,
            identifier = name,
            cells = states.mapIndexed { idx, state ->
                ClassroomCell(dayOfWeek = 1, groupIndex = idx, state = state)
            },
        )

    @Test
    fun `resolveActiveSlot handles in progress break and after last period`() {
        val inClass = ClassroomQuickFilterEngine.resolveActiveSlot(groups, LocalTime.of(8, 20))
        assertTrue(inClass is ActiveSlotResolution.InProgress)
        assertEquals(0, (inClass as ActiveSlotResolution.InProgress).groupIndex)

        val duringBreak = ClassroomQuickFilterEngine.resolveActiveSlot(groups, LocalTime.of(10, 0))
        assertTrue(duringBreak is ActiveSlotResolution.UpcomingBreak)
        assertEquals(1, (duringBreak as ActiveSlotResolution.UpcomingBreak).groupIndex)

        val afterLast = ClassroomQuickFilterEngine.resolveActiveSlot(groups, LocalTime.of(21, 0))
        assertEquals(ActiveSlotResolution.AfterLastPeriod, afterLast)

        val noClocks = ClassroomQuickFilterEngine.resolveActiveSlot(
            listOf(ClassroomPeriodGroup("0102", "第1-2节")),
            LocalTime.of(9, 0),
        )
        assertEquals(ActiveSlotResolution.Unavailable, noClocks)
    }

    @Test
    fun `project filters consecutive runs breaks on unknown sorts naturally and emits no dots`() {
        val result = ClassroomResult(
            query = ClassroomQuery(dayOfWeek = 1),
            periodGroups = groups,
            rooms = listOf(
                room("A102", ClassroomCellState.FREE, ClassroomCellState.FREE, ClassroomCellState.COURSE, ClassroomCellState.FREE, ClassroomCellState.FREE),
                room("A2", ClassroomCellState.FREE, ClassroomCellState.FREE, ClassroomCellState.FREE, ClassroomCellState.FREE, ClassroomCellState.FREE),
                room("A101", ClassroomCellState.FREE, ClassroomCellState.UNKNOWN, ClassroomCellState.COURSE, ClassroomCellState.FREE, ClassroomCellState.FREE),
                room("B301", ClassroomCellState.COURSE, ClassroomCellState.FREE, ClassroomCellState.FREE, ClassroomCellState.COURSE, ClassroomCellState.FREE),
            ),
        )

        // Both availableNowOnly (at 08:30 -> index 0) + consecutive >= 2
        val both = ClassroomQuickFilterEngine.project(
            result = result,
            filter = ClassroomQuickFilter(availableNowOnly = true, minConsecutiveFreeGroups = 2),
            nowTime = LocalTime.of(8, 30),
        )
        assertEquals(listOf("A2", "A102"), both.rooms.map { it.room.name })
        assertEquals("连空 5 节 空至 20:55", both.rooms[0].badgeText)
        assertEquals("连空 2 节 空至 11:50", both.rooms[1].badgeText)
        both.rooms.forEach { proj ->
            assertFalse(proj.badgeText.contains("·"))
            assertFalse(proj.badgeText.contains("•"))
        }

        // Consecutive >= 2 at 16:30 (group 3 + eleventh group 4 free -> A101 qualifies with end 20:55)
        val evening = ClassroomQuickFilterEngine.project(
            result = result,
            filter = ClassroomQuickFilter(availableNowOnly = true, minConsecutiveFreeGroups = 2),
            nowTime = LocalTime.of(16, 30),
        )
        assertEquals(listOf("A2", "A101", "A102"), evening.rooms.map { it.room.name })
        assertEquals("连空 2 节 空至 20:55", evening.rooms[1].badgeText)

        // Inactive filter keeps original server ordering
        val off = ClassroomQuickFilterEngine.project(result, ClassroomQuickFilter(), LocalTime.of(8, 30))
        assertEquals(listOf("A102", "A2", "A101", "B301"), off.rooms.map { it.room.name })
    }

    @Test
    fun `orderBuildingsWithPins scopes by campus and keeps relative order`() {
        val buildings = listOf(
            ClassroomBuilding("B1", "一教"),
            ClassroomBuilding("B2", "二教"),
            ClassroomBuilding("B3", "三教"),
        )
        val pins = listOf(
            PinnedClassroomBuilding("C2", "南区", "B1", "南区一教"),
            PinnedClassroomBuilding("C1", "本部", "B3", "三教"),
        )
        val ordered = ClassroomQuickFilterEngine.orderBuildingsWithPins("C1", buildings, pins)
        assertEquals(listOf("B3", "B1", "B2"), ordered.map { it.building.code })
        assertEquals(listOf(true, false, false), ordered.map { it.isPinned })
    }
}
