package com.gfs.iyango.feature.main.about

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelStore
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.DiagnosticEvent
import com.gfs.iyango.core.model.AppPreferences
import java.lang.reflect.Proxy
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AboutDeveloperModeTest {
    @get:Rule val compose = createComposeRule()
    private val preferences = MutableStateFlow(AppPreferences(wallpaperBlur = 0.7f))
    private var failWrites = false

    @Test fun `diagnostics stay hidden until the seventh version tap`() {
        ShadowBuild.setManufacturer("HONOR")
        ShadowBuild.setModel("ELP-AN00")
        val store = ViewModelStore()
        lateinit var vm: AboutViewModel
        compose.runOnIdle { vm = viewModel().also { store.put("about", it) } }
        var openedDockGlassDev = 0
        try {
            compose.setContent {
                IYangoThemePreview {
                    AboutScreen(onBack = {}, onOpenDockGlassDev = { openedDockGlassDev++ }, viewModel = vm)
                }
            }
            assertDiagnosticsHidden()
            val version = compose.onNodeWithText("${vm.appVersion} (${vm.versionCode})")
            repeat(6) { version.performClick() }
            assertDiagnosticsHidden()
            version.performClick()
            compose.onNodeWithText("手机型号").assertIsDisplayed()
            compose.onNodeWithText("HONOR ELP-AN00").assertIsDisplayed()
            compose.onNodeWithText("界面调试").assertExists()
            compose.onNodeWithText("底栏液态玻璃完整调参").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(1, openedDockGlassDev) }
            compose.onNodeWithText("近期状态").assertExists()
            compose.onNodeWithText("诊断日志").assertExists()
            compose.onNodeWithText("导出诊断").assertExists()
            compose.onNodeWithText("关闭开发者模式").performScrollTo().performClick()
            assertDiagnosticsHidden()
            compose.runOnIdle {
                assertFalse(preferences.value.developerModeEnabled)
                assertEquals(0.7f, preferences.value.wallpaperBlur)
            }
            version.performScrollTo()
            repeat(6) { version.performClick() }
            assertDiagnosticsHidden()
            version.performClick()
            compose.onNodeWithText("诊断日志").assertExists()
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun `a long gap or leaving the page resets unfinished taps`() {
        val store = ViewModelStore()
        val mounted = mutableStateOf(true)
        lateinit var vm: AboutViewModel
        compose.runOnIdle { vm = viewModel().also { store.put("about", it) } }
        try {
            compose.setContent { IYangoThemePreview { if (mounted.value) AboutScreen(onBack = {}, viewModel = vm) } }
            val version = compose.onNodeWithText("${vm.appVersion} (${vm.versionCode})")
            repeat(6) { version.performClick() }
            compose.runOnIdle { ShadowSystemClock.advanceBy(Duration.ofMillis(2_001)) }
            version.performClick()
            assertDiagnosticsHidden()
            repeat(5) { version.performClick() }
            assertDiagnosticsHidden()
            compose.runOnIdle { mounted.value = false }
            compose.runOnIdle { mounted.value = true }
            version.performClick()
            assertDiagnosticsHidden()
            repeat(5) { version.performClick() }
            assertDiagnosticsHidden()
            version.performClick()
            compose.onNodeWithText("诊断日志").assertExists()
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun `failed persistence stays locked and another seven taps can retry`() {
        failWrites = true
        val store = ViewModelStore()
        lateinit var vm: AboutViewModel
        compose.runOnIdle { vm = viewModel().also { store.put("about", it) } }
        try {
            compose.setContent { IYangoThemePreview { AboutScreen(onBack = {}, viewModel = vm) } }
            // Hidden actions must not produce an export preview or a clear confirmation.
            compose.runOnIdle { vm.prepareExport(); vm.requestClear(); vm.clearLogs() }
            compose.runOnIdle { assertNull(vm.exportPreview.value); assertFalse(vm.confirmClear.value) }
            val version = compose.onNodeWithText("${vm.appVersion} (${vm.versionCode})")
            repeat(7) { version.performClick() }
            assertDiagnosticsHidden()
            compose.onNodeWithText("开发者模式设置未保存，请重试").assertExists()
            failWrites = false
            repeat(7) { version.performClick() }
            compose.onNodeWithText("诊断日志").assertExists()
            compose.runOnIdle { assertTrue(preferences.value.developerModeEnabled) }
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun `an already enabled setting shows diagnostics when the page opens`() {
        preferences.value = preferences.value.copy(developerModeEnabled = true)
        val store = ViewModelStore()
        lateinit var vm: AboutViewModel
        compose.runOnIdle { vm = viewModel().also { store.put("about", it) } }
        try {
            compose.setContent { IYangoThemePreview { AboutScreen(onBack = {}, viewModel = vm) } }
            compose.onNodeWithText("近期状态").assertExists()
            compose.onNodeWithText("诊断日志").assertExists()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { preferences.value = preferences.value.copy(developerModeEnabled = false) }
            compose.mainClock.advanceTimeByFrame()
            assertDiagnosticsHidden()
        } finally { compose.runOnIdle { store.clear() } }
    }

    private fun assertDiagnosticsHidden() {
        listOf("手机型号", "HONOR ELP-AN00", "界面调试", "底栏液态玻璃完整调参", "更新源调试", "近期状态", "诊断日志", "导出诊断", "清空日志").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("反馈问题时请附上", substring = true).assertDoesNotExist()
    }

    private fun viewModel() = AboutViewModel(
        context = RuntimeEnvironment.getApplication(),
        diagnostics = port { name -> when (name) {
            "observeRecent" -> flowOf(emptyList<DiagnosticEvent>())
            "getSummary" -> flowOf(DiagnosticLogSummary())
            else -> error(name)
        } },
        refreshCoordinator = port { name -> when (name) {
            "getState" -> MutableStateFlow(RefreshState())
            else -> error(name)
        } },
        reminderCoordinator = port { name -> when (name) {
            "getStatus" -> MutableStateFlow(ReminderStatus())
            else -> error(name)
        } },
        calendarCoordinator = port { name -> when (name) {
            "getStatus" -> MutableStateFlow(CalendarSyncStatus())
            else -> error(name)
        } },
        appSettings = object : AppSettingsPort by port<AppSettingsPort>({ error(it) }) {
            override val appPreferences = preferences
            override suspend fun transformAppPreferences(transform: (AppPreferences) -> AppPreferences) {
                if (failWrites) error("disk full")
                preferences.value = transform(preferences.value)
            }
        },
        appUpdate = object : AppUpdatePort by port<AppUpdatePort>({ error(it) }) {
            override val state = MutableStateFlow<AppUpdateCheckState>(AppUpdateCheckState.Idle)
            override val promptRelease = MutableStateFlow<AppUpdateRelease?>(null)
            override suspend fun configuredUrl(): String = ""
            override suspend fun checkManually(): AppUpdateCheckState = AppUpdateCheckState.NotConfigured
        },
    )

    private inline fun <reified T> port(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            call(method.name)
        } as T
}
