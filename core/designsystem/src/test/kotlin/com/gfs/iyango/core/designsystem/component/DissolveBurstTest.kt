package com.gfs.iyango.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DissolveBurstTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun `defaultDissolveParticles produces deterministic bounded specs`() {
        val first = defaultDissolveParticles(24)
        val second = defaultDissolveParticles(24)
        assertEquals(24, first.size)
        assertEquals(first, second)
        first.forEach { spec ->
            assertTrue(spec.distanceDp in 36f..76f)
            assertTrue(spec.radiusDp in 2.2f..4.6f)
            assertTrue(spec.delayMs >= 0)
        }
    }

    @Test fun `DissolveBurst completes immediately without canvas when motion is disabled`() {
        var trigger by mutableIntStateOf(0)
        var finished = 0
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides false) {
                Box(modifier = Modifier.size(64.dp)) {
                    DissolveBurst(trigger = trigger, onFinished = { finished++ })
                }
            }
        }
        compose.runOnIdle { assertEquals(0, finished) }

        trigger = 1
        compose.waitForIdle()
        assertEquals(1, finished)
        compose.onNodeWithTag("dissolve_burst_canvas").assertDoesNotExist()
    }

    @Test fun `DissolveBurst renders canvas during animation and invokes onFinished when done`() {
        var trigger by mutableIntStateOf(0)
        var finished = 0
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides true) {
                Box(modifier = Modifier.size(64.dp)) {
                    DissolveBurst(
                        trigger = trigger,
                        color = Color(0xFF1E88E5),
                        durationMs = 300,
                        onFinished = { finished++ },
                    )
                }
            }
        }
        compose.runOnIdle { assertEquals(0, finished) }

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { Snapshot.withMutableSnapshot { trigger = 1 } }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(50L)
        compose.onNodeWithTag("dissolve_burst_canvas").assertIsDisplayed()
        assertEquals(0, finished)

        compose.mainClock.advanceTimeBy(400L)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1, finished) }
        compose.onNodeWithTag("dissolve_burst_canvas").assertDoesNotExist()
    }
}
