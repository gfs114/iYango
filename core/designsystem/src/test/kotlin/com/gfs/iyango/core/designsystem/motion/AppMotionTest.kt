package com.gfs.iyango.core.designsystem.motion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun interruptingARevealShowsTheCurrentContentImmediately() {
        val enabled = mutableStateOf(true)
        val result = mutableStateOf(1)
        lateinit var opacity: State<Float>
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides enabled.value) {
                opacity = rememberContentOpacity(result.value)
            }
        }
        compose.runOnIdle { assertEquals(1f, opacity.value, 0f) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { Snapshot.withMutableSnapshot { result.value = 2 } }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertTrue(opacity.value in 0.01f..0.99f)
            Snapshot.withMutableSnapshot {
                enabled.value = false
                result.value = 3
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1f, opacity.value, 0f)
            Snapshot.withMutableSnapshot { enabled.value = true }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1f, opacity.value, 0f) }
    }
}
