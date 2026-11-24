package com.gfs.iyango.feature.main.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.SettingsBackupPreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsBackupMotionPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewShowsTheAnimationPreferenceFromEachBackup() {
        val preview = mutableStateOf(SettingsBackupPreview(
            token = "sample", formatVersion = 1, exportedAt = 0L, includesWallpaper = false,
            themeMode = "SYSTEM", accentHex = null, defaultStartTab = 0, navigationLayout = "AUTO",
            animationsEnabled = false, displayOverrideCount = 0, reminderEnabled = false,
            reminderLeadMinutes = 0, issues = emptyList(),
        ))
        compose.setContent {
            IYangoThemePreview { RestorePreview(preview.value, false, {}, false, {}, {}, {}, false) }
        }
        compose.onNodeWithText("界面动画关", substring = true).assertIsDisplayed()
        compose.runOnIdle { preview.value = preview.value.copy(animationsEnabled = true) }
        compose.onNodeWithText("界面动画开", substring = true).assertIsDisplayed()
    }
}
