package com.gfs.iyango.feature.main.liquidglass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28]) // Same gesture path, without requiring a device RuntimeShader implementation.
class LiquidBottomTabsTest {
    @get:Rule val compose = createComposeRule()
    private var selected by mutableIntStateOf(0)
    private var motionEnabled by mutableStateOf(true)
    private val events = mutableListOf<Int>()

    private fun showDock(direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction, LocalMotionEnabled provides motionEnabled) {
                val backdrop = rememberLayerBackdrop()
                Box {
                    LiquidBottomTabs(
                        selectedTabIndex = { selected },
                        onTabSelected = { events += it; selected = it },
                        backdrop = backdrop,
                        tabsCount = 4,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        repeat(4) { index ->
                            LiquidBottomTab(index = index, selected = index == selected) { Text("tab $index") }
                        }
                    }
                }
                }
            }
        }
    }

    @Test fun accessibilityClickAndExternalSync_doNotEmitDuplicateNavigation() {
        showDock()
        compose.onNodeWithText("tab 1").performClick()
        compose.onNodeWithText("tab 1").performClick()
        compose.runOnIdle { assertEquals(listOf(1), events); selected = 3 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(1), events) }
        compose.onNodeWithText("tab 2").performClick()
        compose.runOnIdle { assertEquals(listOf(1, 2), events) }
    }

    @Test fun longPressOnUnselectedTab_movesIndicatorBeforeRelease_thenCommitsOnce() {
        showDock()
        val dock = compose.onNodeWithTag("liquid-bottom-tabs")
        val before = compose.onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left.value
        dock.performTouchInput { down(Offset(width * 0.625f, height / 2f)) }
        compose.mainClock.advanceTimeBy(450)
        compose.waitForIdle()
        val during = compose.onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left.value
        compose.runOnIdle {
            assertEquals(emptyList<Int>(), events)
            assertTrue("Indicator must move to the unselected press before UP", during > before + 40f)
        }
        dock.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(2), events) }
    }

    @Test fun cancelledLongPress_returnsToSelectedTab_withoutNavigation() {
        showDock()
        val dock = compose.onNodeWithTag("liquid-bottom-tabs")
        val before = compose.onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left.value
        dock.performTouchInput { down(Offset(width * 0.875f, height / 2f)) }
        compose.mainClock.advanceTimeBy(450)
        dock.performTouchInput { cancel() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(emptyList<Int>(), events) }
        val after = compose.onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left.value
        assertEquals(before, after, 1f)
    }

    @Test fun ordinaryTouchClicks_commitImmediatelyOncePerSelection() {
        showDock()
        val dock = compose.onNodeWithTag("liquid-bottom-tabs")
        dock.performTouchInput { click(Offset(width * 0.375f, height / 2f)) }
        compose.runOnIdle { assertEquals(listOf(1), events) }
        dock.performTouchInput { click(Offset(width * 0.875f, height / 2f)) }
        compose.runOnIdle { assertEquals(listOf(1, 3), events) }
    }

    @Test fun longPressDrag_followsFingerAndIgnoresExternalSyncUntilRelease() {
        showDock()
        val dock = compose.onNodeWithTag("liquid-bottom-tabs")
        dock.performTouchInput { down(Offset(width * 0.375f, height / 2f)) }
        compose.mainClock.advanceTimeBy(450)
        dock.performTouchInput { moveTo(Offset(width * 0.875f, height / 2f)) }
        compose.mainClock.advanceTimeBy(450)
        val heldPosition = compose.onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left.value
        compose.runOnIdle { selected = 2 }
        compose.waitForIdle()
        val afterSync = compose.onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left.value
        assertEquals(heldPosition, afterSync, 1f)
        compose.runOnIdle { assertEquals(emptyList<Int>(), events) }
        dock.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(3), events) }
    }

    @Test fun disablingDuringSelectionSnapsTheIndicatorWithoutAnotherNavigation() {
        compose.mainClock.autoAdvance = false
        showDock()
        val indicator = compose.onNodeWithTag("dock-indicator")
        compose.onNodeWithText("tab 3").performClick()
        compose.mainClock.advanceTimeBy(48)
        val midway = indicator.getUnclippedBoundsInRoot().left.value
        compose.runOnIdle { motionEnabled = false }
        compose.mainClock.advanceTimeByFrame()
        val snapped = indicator.getUnclippedBoundsInRoot().left.value
        assertTrue(snapped > midway + 5f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(snapped, indicator.getUnclippedBoundsInRoot().left.value, 1f)
        compose.runOnIdle { assertEquals(listOf(3), events) }
    }

    @Test fun disablingWhileHeldKeepsTheDragAndCommitsItsFinalPosition() {
        compose.mainClock.autoAdvance = false
        showDock()
        val dock = compose.onNodeWithTag("liquid-bottom-tabs")
        val indicator = compose.onNodeWithTag("dock-indicator")
        dock.performTouchInput { down(Offset(width * .875f, height / 2f)) }
        compose.mainClock.advanceTimeBy(450)
        compose.runOnIdle { motionEnabled = false }
        compose.mainClock.advanceTimeByFrame()
        val right = indicator.getUnclippedBoundsInRoot().left.value
        dock.performTouchInput { moveTo(Offset(width * .375f, height / 2f)) }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(right - indicator.getUnclippedBoundsInRoot().left.value > 40f)
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        dock.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(1), events) }
    }

    @Test fun rtlLongPress_usesPhysicalFingerPosition() {
        showDock(LayoutDirection.Rtl)
        val dock = compose.onNodeWithTag("liquid-bottom-tabs")
        dock.performTouchInput { down(Offset(width * 0.125f, height / 2f)) }
        compose.mainClock.advanceTimeBy(450)
        compose.runOnIdle { assertEquals(emptyList<Int>(), events) }
        dock.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(3), events) }
    }

    @Test fun quickReturnTapDuringIndicatorAnimation_usesTheNewTapPosition() {
        showDock()
        val dock = compose.onNodeWithTag("liquid-bottom-tabs")
        // Both taps are dispatched before waiting for any animation frames.
        dock.performTouchInput {
            click(Offset(width * 0.375f, height / 2f))
            advanceEventTime(16)
            click(Offset(width * 0.125f, height / 2f))
        }
        compose.runOnIdle { assertEquals(listOf(1, 0), events) }
    }

    @Test fun longPressFromGradesToMine_thenSeveralMovesToSchedule_commitsFinalFingerPosition() {
        selected = 2
        showDock()
        val dock = compose.onNodeWithTag("liquid-bottom-tabs")
        dock.performTouchInput { down(Offset(width * 0.875f, height / 2f)) }
        compose.mainClock.advanceTimeBy(450)
        val heldMinePosition = compose.onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left.value
        for (fraction in listOf(0.86f, 0.78f, 0.69f, 0.61f, 0.53f, 0.375f)) {
            dock.performTouchInput { moveTo(Offset(width * fraction, height / 2f)) }
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle {
                assertEquals("MOVE must not commit selection", emptyList<Int>(), events)
                assertEquals(2, selected)
            }
        }
        compose.mainClock.advanceTimeBy(450)
        val heldSchedulePosition = compose.onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left.value
        assertTrue("The indicator must follow all MOVE events to Schedule before UP", heldSchedulePosition < heldMinePosition - 60f)
        dock.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(1), events)
            assertEquals(1, selected)
        }
    }

    @Test fun severalLongPressMovesBeforeAnotherAnimationFrame_keepTheLatestTarget() {
        selected = 2
        showDock()
        val dock = compose.onNodeWithTag("liquid-bottom-tabs")
        dock.performTouchInput { down(Offset(width * 0.875f, height / 2f)) }
        compose.mainClock.advanceTimeBy(450)
        dock.performTouchInput {
            for (fraction in listOf(0.86f, 0.78f, 0.69f, 0.61f, 0.53f, 0.375f)) {
                advanceEventTime(16)
                moveTo(Offset(width * fraction, height / 2f))
            }
        }
        compose.runOnIdle {
            assertEquals(emptyList<Int>(), events)
            assertEquals(2, selected)
        }
        dock.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(1), events) }
    }

    @Test fun touchDownOnOtherTab_movesIndicatorImmediatelyWithoutWaiting300ms() {
        compose.mainClock.autoAdvance = false
        showDock()
        val dock = compose.onNodeWithTag("liquid-bottom-tabs")
        val indicator = compose.onNodeWithTag("dock-indicator")
        val before = indicator.getUnclippedBoundsInRoot().left.value
        dock.performTouchInput { down(Offset(width * 0.875f, height / 2f)) }
        compose.mainClock.advanceTimeBy(64)
        val afterDown = indicator.getUnclippedBoundsInRoot().left.value
        assertTrue(
            "Indicator should jump toward tab 3 immediately without a 300ms delay (before=$before, afterDown=$afterDown)",
            afterDown > before + 20f,
        )
        assertEquals(emptyList<Int>(), events)
        dock.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf(3), events)
    }
}
