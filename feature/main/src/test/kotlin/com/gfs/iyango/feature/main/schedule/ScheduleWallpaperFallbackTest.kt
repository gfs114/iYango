package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import java.lang.reflect.Proxy
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScheduleWallpaperFallbackTest {
    @Test fun effectiveWallpaperFollowsGlobalUntilDedicatedImagesAreAvailable() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val settings = MutableStateFlow(AppPreferences())
            val files = mutableSetOf("global-light", "global-dark", "schedule-light", "schedule-dark")
            val vm = ScheduleViewModel(
                timetableRepository = port { name, _ -> when (name) {
                    "observeAll" -> flowOf(emptyList<Timetable>())
                    "observeCurrent" -> flowOf<Timetable?>(null)
                    else -> error(name)
                } },
                scheduleData = port { name, _ -> error(name) },
                adjustmentRepository = port { name, _ -> error(name) },
                effectiveSchedule = port { name, _ -> error(name) },
                appSettings = port { name, _ -> when (name) {
                    "getAppPreferences" -> settings
                    "getDisplaySettings" -> flowOf(DisplaySettingsBundle())
                    else -> error(name)
                } },
                wallpaperStore = port { name, args -> when (name) {
                    "resolve" -> (args[0] as String).takeIf { it in files }?.let { "/private/$it" }
                    else -> error(name)
                } },
                refreshCoordinator = port { name, _ -> when (name) {
                    "getState" -> MutableStateFlow(RefreshState())
                    else -> error(name)
                } },
                clock = Clock.systemUTC(),
                examRepository = port { name, _ -> error(name) },
            )
            var revision = 0
            suspend fun check(prefs: AppPreferences, light: String?, dark: String?) {
                val marker = (++revision) / 100f
                settings.value = prefs.copy(wallpaperScrim = marker, wallpaperBlur = 0.25f)
                val result = withTimeout(5000) { vm.wallpaper.first { it.scrim == marker } }
                assertEquals(light?.let { "/private/$it" }, result.path)
                assertEquals(dark?.let { "/private/$it" }, result.darkPath)
                assertEquals(0.25f, result.blur)
            }
            val global = AppPreferences(wallpaperPath = "global-light", wallpaperPathDark = "global-dark")
            check(global, "global-light", "global-dark")
            check(global.copy(wallpaperPathDark = "missing"), "global-light", "global-light")
            check(global.copy(wallpaperPath = ""), "global-dark", "global-dark")
            check(global.copy(timetableWallpaperPath = "schedule-light"), "schedule-light", "schedule-light")
            check(global.copy(timetableWallpaperPathDark = "schedule-dark"), "schedule-dark", "schedule-dark")
            check(global.copy(timetableWallpaperPath = "schedule-light", timetableWallpaperPathDark = "schedule-dark"),
                "schedule-light", "schedule-dark")
            files.remove("schedule-dark")
            check(global.copy(timetableWallpaperPath = "schedule-light", timetableWallpaperPathDark = "schedule-dark"),
                "schedule-light", "schedule-light")
            files.remove("schedule-light")
            check(global.copy(timetableWallpaperPath = "schedule-light", timetableWallpaperPathDark = "schedule-dark"),
                "global-light", "global-dark")
            check(global.copy(timetableWallpaperPath = "missing", timetableWallpaperPathDark = "missing"),
                "global-light", "global-dark")
            check(global, "global-light", "global-dark") // dedicated images removed
            check(AppPreferences(), null, null)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private inline fun <reified T> port(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T
}
