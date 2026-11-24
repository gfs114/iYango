package com.gfs.iyango.feature.main.mine

import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.feature.main.EXTRA_SETTINGS_FRAGMENT_ARG_KEY
import com.gfs.iyango.feature.main.EXTRA_SETTINGS_SHOW_FRAGMENT_ARGS
import com.gfs.iyango.feature.main.PREF_KEY_BACK_NAVIGATION_ANIMATION
import com.gfs.iyango.feature.main.PredictiveBackSettingsShortcut
import com.gfs.iyango.feature.main.SystemPredictiveBackStatus
import com.gfs.iyango.feature.main.buildPredictiveBackShortcutIntent
import com.gfs.iyango.feature.main.resolveSystemPredictiveBackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
class PredictiveBackSettingTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `below Android 13 always resolves to UNSUPPORTED_ANDROID_VERSION`() {
        for (sdk in listOf(26, 28, 31, 32)) {
            assertEquals(
                SystemPredictiveBackStatus.UNSUPPORTED_ANDROID_VERSION,
                resolveSystemPredictiveBackStatus(
                    sdkInt = sdk,
                    enableBackAnimationSetting = 1,
                    animatorDurationScale = 1f,
                    animatorsEnabled = true,
                ),
            )
        }
    }

    @Test
    fun `Android 13 and 14 require explicit system enable_back_animation`() {
        for (sdk in listOf(33, 34)) {
            assertEquals(
                SystemPredictiveBackStatus.SYSTEM_SETTING_DISABLED,
                resolveSystemPredictiveBackStatus(sdk, null, 1f, true),
            )
            assertEquals(
                SystemPredictiveBackStatus.SYSTEM_SETTING_DISABLED,
                resolveSystemPredictiveBackStatus(sdk, 0, 1f, true),
            )
            assertEquals(
                SystemPredictiveBackStatus.ENABLED,
                resolveSystemPredictiveBackStatus(sdk, 1, 1f, true),
            )
            assertEquals(
                SystemPredictiveBackStatus.SYSTEM_ANIMATION_DISABLED,
                resolveSystemPredictiveBackStatus(sdk, 1, 0f, true),
            )
        }
    }

    @Test
    fun `Android 15 plus allows null or 1 and rejects explicit 0 or disabled animators`() {
        for (sdk in listOf(35, 36, 37)) {
            assertEquals(
                SystemPredictiveBackStatus.SYSTEM_SETTING_DISABLED,
                resolveSystemPredictiveBackStatus(sdk, 0, 1f, true),
            )
            assertEquals(
                SystemPredictiveBackStatus.ENABLED,
                resolveSystemPredictiveBackStatus(sdk, null, 1f, true),
            )
            assertEquals(
                SystemPredictiveBackStatus.ENABLED,
                resolveSystemPredictiveBackStatus(sdk, 1, 1f, true),
            )
            assertEquals(
                SystemPredictiveBackStatus.SYSTEM_ANIMATION_DISABLED,
                resolveSystemPredictiveBackStatus(sdk, 1, 0f, true),
            )
            assertEquals(
                SystemPredictiveBackStatus.SYSTEM_ANIMATION_DISABLED,
                resolveSystemPredictiveBackStatus(sdk, 1, 1f, false),
            )
        }
    }

    @Test
    @Config(sdk = [28])
    fun `Android 12 and below hides predictive back toggle below Animations`() {
        compose.setContent {
            IYangoThemePreview {
                MineAppearanceScreen(onBack = {})
            }
        }
        compose.onNodeWithContentDescription("界面动画").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("预测性返回动画").assertDoesNotExist()
        compose.onNodeWithText("找不到系统开关？快捷跳转").assertDoesNotExist()
    }

    @Test
    fun `when system enable_back_animation is 0 clicking toggle blocks and shows Developer Options prompt`() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val originalBack = Settings.Global.getString(resolver, "enable_back_animation")
        val originalScale = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        val prefs = mutableStateOf(AppPreferences(predictiveBackEnabled = false))
        val recordedChanges = mutableListOf<Boolean>()
        try {
            Settings.Global.putInt(resolver, "enable_back_animation", 0)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

            compose.setContent {
                IYangoThemePreview {
                    MineAppearanceScreen(
                        onBack = {},
                        appPreferences = prefs.value,
                        onPredictiveBackEnabledChange = {
                            recordedChanges += it
                            prefs.value = prefs.value.copy(predictiveBackEnabled = it)
                        },
                    )
                }
            }

            val toggle = compose.onNodeWithContentDescription("预测性返回动画").performScrollTo()
            toggle.assertIsDisplayed()
            toggle.assertIsOff()
            compose.onNodeWithText("系统级预测性返回未开启，需先在开发者选项中打开").assertDoesNotExist()
            compose.onNodeWithText("已开启系统级与页内预测性返回动画").assertDoesNotExist()

            toggle.performClick()

            assertTrue("Must not enable app toggle while system setting is off", recordedChanges.isEmpty())
            assertFalse(prefs.value.predictiveBackEnabled)
            compose.onNodeWithText("需先开启系统级预测性返回").assertIsDisplayed()
            compose.onNodeWithText("去系统设置").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("需先开启系统级预测性返回").assertDoesNotExist()
        } finally {
            Settings.Global.putString(resolver, "enable_back_animation", originalBack)
            Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalScale)
        }
    }

    @Test
    fun `when Android 13-14 system setting is disabled clicking toggle shows Developer Options prompt`() {
        val prefs = mutableStateOf(AppPreferences(predictiveBackEnabled = false))
        val recorded = mutableListOf<Boolean>()
        compose.setContent {
            IYangoThemePreview {
                MineAppearanceScreen(
                    onBack = {},
                    appPreferences = prefs.value,
                    onPredictiveBackEnabledChange = {
                        recorded += it
                        prefs.value = prefs.value.copy(predictiveBackEnabled = it)
                    },
                    systemPredictiveBackChecker = { SystemPredictiveBackStatus.SYSTEM_SETTING_DISABLED },
                )
            }
        }
        compose.onNodeWithContentDescription("预测性返回动画").performScrollTo().assertIsOff().performClick()
        assertTrue(recorded.isEmpty())
        compose.onNodeWithText("需先开启系统级预测性返回").assertIsDisplayed()
    }

    @Test
    fun `when system animator scale is 0 clicking toggle shows system animation dialog`() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val originalBack = Settings.Global.getString(resolver, "enable_back_animation")
        val originalScale = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        val prefs = mutableStateOf(AppPreferences(predictiveBackEnabled = false))
        val recorded = mutableListOf<Boolean>()
        try {
            Settings.Global.putInt(resolver, "enable_back_animation", 1)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

            compose.setContent {
                IYangoThemePreview {
                    MineAppearanceScreen(
                        onBack = {},
                        appPreferences = prefs.value,
                        onPredictiveBackEnabledChange = {
                            recorded += it
                            prefs.value = prefs.value.copy(predictiveBackEnabled = it)
                        },
                    )
                }
            }

            compose.onNodeWithContentDescription("预测性返回动画").performScrollTo().assertIsOff().performClick()
            assertTrue(recorded.isEmpty())
            compose.onNodeWithText("系统动画已关闭").assertIsDisplayed()
            compose.onNodeWithText("去系统设置").assertIsDisplayed()
        } finally {
            Settings.Global.putString(resolver, "enable_back_animation", originalBack)
            Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalScale)
        }
    }

    @Test
    fun `when system predictive back is enabled toggle switches on and off cleanly`() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val originalBack = Settings.Global.getString(resolver, "enable_back_animation")
        val originalScale = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        val prefs = mutableStateOf(AppPreferences(predictiveBackEnabled = true))
        try {
            Settings.Global.putInt(resolver, "enable_back_animation", 1)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

            compose.setContent {
                IYangoThemePreview {
                    MineAppearanceScreen(
                        onBack = {},
                        appPreferences = prefs.value,
                        onPredictiveBackEnabledChange = {
                            prefs.value = prefs.value.copy(predictiveBackEnabled = it)
                        },
                    )
                }
            }

            val toggle = compose.onNodeWithContentDescription("预测性返回动画").performScrollTo()
            toggle.assertIsOn()
            compose.onNodeWithText("已开启系统级与页内预测性返回动画").assertDoesNotExist()
            compose.onNodeWithText("关闭后将停用应用内预测性返回预览动画").assertDoesNotExist()

            toggle.performClick()
            compose.runOnIdle { assertFalse(prefs.value.predictiveBackEnabled) }
            toggle.assertIsOff()

            toggle.performClick()
            compose.runOnIdle { assertTrue(prefs.value.predictiveBackEnabled) }
            toggle.assertIsOn()
            compose.onNodeWithText("需先开启系统级预测性返回").assertDoesNotExist()
        } finally {
            Settings.Global.putString(resolver, "enable_back_animation", originalBack)
            Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalScale)
        }
    }

    @Test
    fun `shortcut intents carry expected system settings actions and highlight key`() {
        val context = RuntimeEnvironment.getApplication()
        val devIntent = buildPredictiveBackShortcutIntent(
            context,
            PredictiveBackSettingsShortcut.DEVELOPER_PREDICTIVE_BACK,
        )
        assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, devIntent.action)
        assertEquals(PREF_KEY_BACK_NAVIGATION_ANIMATION, devIntent.getStringExtra(EXTRA_SETTINGS_FRAGMENT_ARG_KEY))
        val argsBundle = devIntent.getBundleExtra(EXTRA_SETTINGS_SHOW_FRAGMENT_ARGS)
        assertNotNull(argsBundle)
        assertEquals(PREF_KEY_BACK_NAVIGATION_ANIMATION, argsBundle?.getString(EXTRA_SETTINGS_FRAGMENT_ARG_KEY))

        assertEquals(
            Settings.ACTION_DEVICE_INFO_SETTINGS,
            buildPredictiveBackShortcutIntent(context, PredictiveBackSettingsShortcut.ABOUT_PHONE_UNLOCK_DEV).action,
        )
        assertEquals(
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            buildPredictiveBackShortcutIntent(context, PredictiveBackSettingsShortcut.ACCESSIBILITY_ANIMATIONS).action,
        )
        assertEquals(
            Settings.ACTION_SETTINGS,
            buildPredictiveBackShortcutIntent(context, PredictiveBackSettingsShortcut.SYSTEM_SETTINGS_HOME).action,
        )
    }

    @Test
    fun `dropdown only shows two items without subtitles in order unlock dev first then predictive back`() {
        val app = RuntimeEnvironment.getApplication()
        val resolver = app.contentResolver
        val originalBack = Settings.Global.getString(resolver, "enable_back_animation")
        val originalScale = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.clearNextStartedActivities()
        try {
            Settings.Global.putInt(resolver, "enable_back_animation", 0)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

            compose.setContent {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    IYangoThemePreview {
                        MineAppearanceScreen(onBack = {})
                    }
                }
            }

            // 初始收起；点击折叠栏展开后仅包含 2 项且无小字、无已删除的最后两项。
            compose.onNodeWithText("开启「开发者选项」").assertDoesNotExist()
            compose.onNodeWithText("直达「预测性返回动画」").assertDoesNotExist()
            compose.onNodeWithText("找不到系统开关？快捷跳转").performScrollTo().performClick()

            val unlockNode = compose.onNodeWithText("开启「开发者选项」").performScrollTo()
            val predictiveNode = compose.onNodeWithText("直达「预测性返回动画」").performScrollTo()
            unlockNode.assertIsDisplayed()
            predictiveNode.assertIsDisplayed()
            assertTrue(
                "Unlock Developer Options must appear above Direct Predictive Back",
                unlockNode.getBoundsInRoot().top < predictiveNode.getBoundsInRoot().top,
            )

            // 已删的 2 项与所有副标题小字均不存在。
            compose.onNodeWithText("检查系统动画设置").assertDoesNotExist()
            compose.onNodeWithText("打开系统设置首页").assertDoesNotExist()
            compose.onNodeWithText("打开开发者选项并尝试定位到预测性返回开关").assertDoesNotExist()
            compose.onNodeWithText("进入「关于手机」连续点击 7 次版本号解锁开发者选项").assertDoesNotExist()

            unlockNode.performClick()
            val aboutStarted = shadowApp.nextStartedActivity
            assertNotNull(aboutStarted)
            assertEquals(Settings.ACTION_DEVICE_INFO_SETTINGS, aboutStarted.action)

            predictiveNode.performClick()
            val devStarted = shadowApp.nextStartedActivity
            assertNotNull("Expected developer settings shortcut intent to launch", devStarted)
            assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, devStarted.action)
            assertEquals(
                PREF_KEY_BACK_NAVIGATION_ANIMATION,
                devStarted.getStringExtra(EXTRA_SETTINGS_FRAGMENT_ARG_KEY),
            )
        } finally {
            Settings.Global.putString(resolver, "enable_back_animation", originalBack)
            Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalScale)
        }
    }

    @Test
    fun `when system predictive back is enabled dropdown starts collapsed and toggles open and closed`() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val originalBack = Settings.Global.getString(resolver, "enable_back_animation")
        val originalScale = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        try {
            Settings.Global.putInt(resolver, "enable_back_animation", 1)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

            compose.setContent {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    IYangoThemePreview {
                        MineAppearanceScreen(onBack = {})
                    }
                }
            }

            compose.onNodeWithText("开启「开发者选项」").assertDoesNotExist()
            compose.onNodeWithText("直达「预测性返回动画」").assertDoesNotExist()
            val header = compose.onNodeWithText("找不到系统开关？快捷跳转").performScrollTo()
            header.performClick()

            compose.onNodeWithText("开启「开发者选项」").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("直达「预测性返回动画」").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("检查系统动画设置").assertDoesNotExist()
            compose.onNodeWithText("打开系统设置首页").assertDoesNotExist()

            header.performScrollTo().performClick()
            compose.onNodeWithText("开启「开发者选项」").assertDoesNotExist()
            compose.onNodeWithText("直达「预测性返回动画」").assertDoesNotExist()
        } finally {
            Settings.Global.putString(resolver, "enable_back_animation", originalBack)
            Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalScale)
        }
    }
}
