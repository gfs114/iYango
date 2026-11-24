package com.gfs.iyango.feature.main.today

import androidx.lifecycle.ViewModelStore
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.model.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeSettingsViewModelTest {
    @Test fun `editing stays in draft defaults do not persist and shortcut limit permits zero`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val preferences = MutableStateFlow(AppPreferences(wallpaperBlur = 0.7f))
        val vm = HomeSettingsViewModel(settings(preferences))
        val store = ViewModelStore().apply { put("home", vm) }
        try {
            runCurrent()
            vm.moveModule("calendar", -1)
            vm.toggleModule("today")
            assertTrue(vm.state.value.dirty)
            assertEquals(HomePreferences(), preferences.value.home)
            vm.restoreDefaults()
            assertFalse(vm.state.value.dirty)
            HomeShortcut.entries.forEach { if (it.id !in vm.state.value.draft.quickActions) vm.toggleShortcut(it.id) }
            assertEquals(8, vm.state.value.draft.quickActions.size)
            vm.state.value.draft.quickActions.toList().forEach(vm::toggleShortcut)
            assertTrue(vm.state.value.draft.quickActions.isEmpty())
            vm.save(); runCurrent()
            assertTrue(vm.state.value.saved)
            assertTrue(preferences.value.home.quickActions.isEmpty())
            assertEquals(0.7f, preferences.value.wallpaperBlur)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun `save failure retains dirty draft and supports retry with latest unrelated fields`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val preferences = MutableStateFlow(AppPreferences())
        var fail = true
        val vm = HomeSettingsViewModel(settings(preferences) { if (fail) error("disk full") })
        val store = ViewModelStore().apply { put("home", vm) }
        try {
            runCurrent(); vm.toggleModule("today"); vm.save(); runCurrent()
            assertFalse(vm.state.value.saved)
            assertTrue(vm.state.value.dirty)
            assertNotNull(vm.state.value.error)
            assertTrue(preferences.value.home.hiddenModules.isEmpty())
            preferences.value = preferences.value.copy(wallpaperPath = "new-image")
            fail = false; vm.save(); runCurrent()
            assertTrue(vm.state.value.saved)
            assertEquals(setOf("today"), preferences.value.home.hiddenModules)
            assertEquals("new-image", preferences.value.wallpaperPath)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    private fun settings(preferences: MutableStateFlow<AppPreferences>, beforeWrite: () -> Unit = {}): AppSettingsPort {
        val unused = Proxy.newProxyInstance(AppSettingsPort::class.java.classLoader, arrayOf(AppSettingsPort::class.java)) { _, method, _ -> error(method.name) } as AppSettingsPort
        return object : AppSettingsPort by unused {
            override val appPreferences = preferences
            override suspend fun transformAppPreferences(transform: (AppPreferences) -> AppPreferences) {
                beforeWrite(); preferences.value = transform(preferences.value)
            }
        }
    }
}
