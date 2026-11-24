package com.gfs.iyango.feature.main.about

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.AppUpdateCheckState
import com.gfs.iyango.core.domain.AppUpdatePort
import com.gfs.iyango.core.domain.AppUpdateRelease
import com.gfs.iyango.core.domain.CalendarCoordinatorPort
import com.gfs.iyango.core.domain.CalendarSyncStatus
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.DiagnosticLogSummary
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshState
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.ReminderStatus
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.DiagnosticEvent
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateUiTest {

    @get:Rule
    val compose = createComposeRule()

    private inline fun <reified T> port(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            call(method.name)
        } as T

    @Test
    fun `AboutScreen check update shows unconfigured hint without throwing`() {
        val updateState = MutableStateFlow<AppUpdateCheckState>(AppUpdateCheckState.Idle)
        val appUpdate = object : AppUpdatePort by port<AppUpdatePort>({ error(it) }) {
            override val state = updateState
            override val promptRelease = MutableStateFlow<AppUpdateRelease?>(null)
            override suspend fun configuredUrl(): String = ""
            override suspend fun checkManually(): AppUpdateCheckState {
                updateState.value = AppUpdateCheckState.NotConfigured
                return AppUpdateCheckState.NotConfigured
            }
        }
        val store = ViewModelStore()
        lateinit var vm: AboutViewModel
        compose.runOnIdle {
            vm = AboutViewModel(
                context = RuntimeEnvironment.getApplication(),
                diagnostics = port { name ->
                    when (name) {
                        "observeRecent" -> flowOf(emptyList<DiagnosticEvent>())
                        "getSummary" -> flowOf(DiagnosticLogSummary())
                        else -> error(name)
                    }
                },
                refreshCoordinator = port { name ->
                    when (name) {
                        "getState" -> MutableStateFlow(RefreshState())
                        else -> error(name)
                    }
                },
                reminderCoordinator = port { name ->
                    when (name) {
                        "getStatus" -> MutableStateFlow(ReminderStatus())
                        else -> error(name)
                    }
                },
                calendarCoordinator = port { name ->
                    when (name) {
                        "getStatus" -> MutableStateFlow(CalendarSyncStatus())
                        else -> error(name)
                    }
                },
                appSettings = object : AppSettingsPort by port<AppSettingsPort>({ error(it) }) {
                    override val appPreferences = MutableStateFlow(AppPreferences())
                },
                appUpdate = appUpdate,
            ).also { store.put("about", it) }
        }
        try {
            compose.setContent {
                IYangoThemePreview {
                    AboutScreen(onBack = {}, viewModel = vm)
                }
            }
            compose.onNodeWithText("检查更新").performScrollTo().performClick()
            compose.onNodeWithText("暂未配置更新地址，待后续提供").assertIsDisplayed()
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    @Test
    fun `AppUpdateDialog shows optional dismiss and ignore buttons only when not forced`() {
        var dismissed = 0
        var ignoredKey: String? = null
        val optionalRelease = AppUpdateRelease(
            versionCode = 4,
            versionName = "0.1.3",
            releaseNotes = "新增跨楼预警与 AI 导入",
            downloadUrl = "https://example.com/app.apk",
            forceUpdate = false,
        )
        compose.setContent {
            IYangoThemePreview {
                AppUpdateDialog(
                    release = optionalRelease,
                    onDismiss = { dismissed++ },
                    onIgnore = { ignoredKey = it },
                )
            }
        }
        compose.onNodeWithText("发现新版本 v0.1.3").assertIsDisplayed()
        compose.onNodeWithText("新增跨楼预警与 AI 导入").assertIsDisplayed()
        compose.onNodeWithText("立即更新").assertIsDisplayed()
        compose.onNodeWithText("稍后再说").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
        compose.onNodeWithText("忽略此版本").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("code:4", ignoredKey) }
    }

    @Test
    fun `AppUpdateDialog hides dismiss and ignore buttons on force update`() {
        val forcedRelease = AppUpdateRelease(
            versionCode = 5,
            versionName = "0.2.0",
            releaseNotes = "重要安全更新",
            downloadUrl = "https://example.com/app.apk",
            forceUpdate = true,
        )
        compose.setContent {
            IYangoThemePreview {
                AppUpdateDialog(
                    release = forcedRelease,
                    onDismiss = {},
                    onIgnore = {},
                )
            }
        }
        compose.onNodeWithText("需要更新 v0.2.0").assertIsDisplayed()
        compose.onNodeWithText("稍后再说").assertDoesNotExist()
        compose.onNodeWithText("忽略此版本").assertDoesNotExist()
        compose.onNodeWithText("立即更新").assertIsDisplayed()
    }
}
