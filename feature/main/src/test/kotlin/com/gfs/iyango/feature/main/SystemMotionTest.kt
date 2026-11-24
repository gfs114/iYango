package com.gfs.iyango.feature.main

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class SystemMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(sdk = [35])
    fun windowManagerScaleChangesApplyWhileTheGlobalSettingStaysEnabled() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val original = Settings.Global.getString(resolver, setting)
        val previousScale = ValueAnimator.getDurationScale()
        // Robolectric's shadow only writes the cached field. Android also notifies listeners.
        fun deliverScale(scale: Float) {
            Shadow.directlyOn<Void?, ValueAnimator>(ValueAnimator::class.java, "setDurationScale",
                ClassParameter.from(Float::class.javaPrimitiveType!!, scale))
        }
        try {
            compose.runOnIdle {
                Settings.Global.putFloat(resolver, setting, 1f)
                deliverScale(0f)
            }
            compose.setContent { Text(if (rememberMotionEnabled(true)) "on" else "off") }
            compose.onNodeWithText("off").assertIsDisplayed()
            compose.runOnIdle { deliverScale(1f) }
            compose.onNodeWithText("on").assertIsDisplayed()
        } finally {
            compose.runOnIdle {
                deliverScale(previousScale)
                Settings.Global.putString(resolver, setting, original)
            }
        }
    }

    @Test fun observesForegroundScaleChangesAndRereadsTheScaleAfterStop() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val original = Settings.Global.getString(resolver, setting)
        val preference = mutableStateOf(true)
        try {
            compose.runOnIdle {
                Settings.Global.putFloat(resolver, setting, 1f)
                owner.lifecycle.currentState = Lifecycle.State.STARTED
            }
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    Text(if (rememberMotionEnabled(preference.value)) "on" else "off")
                }
            }
            compose.onNodeWithText("on").assertIsDisplayed()
            compose.runOnIdle { Settings.Global.putFloat(resolver, setting, 0f) }
            compose.onNodeWithText("off").assertIsDisplayed()
            compose.runOnIdle {
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                Settings.Global.putFloat(resolver, setting, 2f)
            }
            compose.onNodeWithText("off").assertIsDisplayed()
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
            compose.onNodeWithText("on").assertIsDisplayed()
            compose.runOnIdle { preference.value = false }
            compose.onNodeWithText("off").assertIsDisplayed()
        } finally {
            compose.runOnIdle {
                owner.lifecycle.currentState = Lifecycle.State.DESTROYED
                Settings.Global.putString(resolver, setting, original)
            }
        }
    }
}
