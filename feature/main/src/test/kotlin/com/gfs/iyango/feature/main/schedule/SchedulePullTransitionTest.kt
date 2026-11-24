package com.gfs.iyango.feature.main.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.model.SchoolTimetableData
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SchedulePullTransitionTest {
    @get:Rule val compose = createComposeRule()
    private fun ready() = projectBrowsedTerm(
        SchoolTimetableData(termValue = "current", startDate = "2026-10-05"),
        1, LocalDateTime.of(2026, 10, 5, 8, 0), 4,
    ) { _, _, _, _ -> emptyList() }.let {
        it.copy(data = it.data.copy(readOnly = false, navigationVersion = 10))
    }

    @Test fun `success requires the local target animation and settled pager in that order`() {
        compose.mainClock.autoAdvance = false
        val target = ready()
        val state = mutableStateOf<ScheduleUiState>(target.copy(data = target.data.copy(displayedWeek = 3, navigationVersion = 9)))
        val settled = mutableStateOf<SchedulePagePosition?>(null)
        var successes = 0
        compose.setContent {
            SchedulePullTransition(SchedulePullRequest(1, 10), state.value, settled.value, true,
                { _, arrived -> if (arrived) successes++ }, {}) { Box(it.testTag("single-grid")) }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(0, successes); state.value = target }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(0, successes) }
        compose.mainClock.advanceTimeBy(350)
        compose.runOnIdle {
            assertEquals(0, successes)
            settled.value = SchedulePagePosition(target.data.context, 1, 10)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeUntil(1_000) { successes == 1 }
        compose.runOnIdle { assertEquals(1, successes) }
        compose.mainClock.advanceTimeBy(350)
        compose.runOnIdle { assertEquals(1, successes) }
    }

    @Test fun `already current animates once and becoming inactive cancels without success`() {
        compose.mainClock.autoAdvance = false
        val target = ready()
        val active = mutableStateOf(true)
        var completes = 0
        var cancellations = 0
        compose.setContent {
            SchedulePullTransition(SchedulePullRequest(2, 10), target,
                SchedulePagePosition(target.data.context, 1, 10), active.value,
                { _, arrived -> if (arrived) completes++ }, { cancellations++ }) { Box(it) }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(0, completes); active.value = false }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(0, completes); assertTrue(cancellations > 0) }
    }

    @Test fun `terminal local state finishes truthfully without waiting for any school result`() {
        var arrived: Boolean? = null
        compose.setContent {
            SchedulePullTransition(SchedulePullRequest(3, 10), ScheduleUiState.NoTimetable, null, true,
                { _, success -> arrived = success }, {}) { Box(it) }
        }
        compose.runOnIdle { assertEquals(false, arrived) }
    }
    @Test fun `old historical error cannot end a return to a valid local timetable`() {
        compose.mainClock.autoAdvance = false
        val target = ready()
        val displayed = mutableStateOf<ScheduleUiState>(ScheduleUiState.Error("old term failed", true))
        val position = mutableStateOf<SchedulePagePosition?>(null)
        var result: Boolean? = null
        compose.setContent {
            SchedulePullTransition(SchedulePullRequest(4, 10), displayed.value, position.value, true,
                { _, success -> result = success }, {}, localState = target) { Box(it) }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertNull(result); displayed.value = target }
        compose.mainClock.advanceTimeBy(400)
        val retarget = target.copy(data = target.data.copy(contextVersion = 8, navigationVersion = 11))
        compose.runOnIdle {
            displayed.value = retarget
            position.value = SchedulePagePosition(target.data.context, 1, 10)
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            assertNull(result)
            position.value = SchedulePagePosition(retarget.data.context, 1, 11)
        }
        compose.mainClock.advanceTimeUntil(1_000) { result == true }
        compose.runOnIdle { assertEquals(true, result) }
    }

    @Test fun `already current still waits for its animation and emits exactly one success`() {
        compose.mainClock.autoAdvance = false
        val target = ready()
        var successes = 0
        compose.setContent {
            SchedulePullTransition(SchedulePullRequest(5, 10), target,
                SchedulePagePosition(target.data.context, 1, 10), true,
                { _, arrived -> if (arrived) successes++ }, {}) { Box(it) }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(0, successes) }
        compose.mainClock.advanceTimeUntil(1_000) { successes == 1 }
        compose.mainClock.advanceTimeBy(350)
        compose.runOnIdle { assertEquals(1, successes) }
    }

    @Test fun `disabling mid pull ends the visual wait and keeps the pager completion exactly once`() {
        compose.mainClock.autoAdvance = false
        val target = ready()
        val motion = mutableStateOf(true)
        val settled = mutableStateOf<SchedulePagePosition?>(null)
        val results = mutableListOf<Boolean>()
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides motion.value) {
                SchedulePullTransition(SchedulePullRequest(6, 10), target, settled.value, true,
                    { _, success -> results += success }, {}) { Box(it) }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { motion.value = false } }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertTrue(results.isEmpty())
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                settled.value = SchedulePagePosition(target.data.context, 1, 10)
            }
        }
        compose.mainClock.advanceTimeUntil(144) { results.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf(true), results); motion.value = true }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(listOf(true), results) }
    }
}
