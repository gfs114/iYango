package com.gfs.iyango.feature.main.today

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.ViewModelStore
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import com.gfs.iyango.feature.main.RetainedMainTabs
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayTabLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `prewarmed home registers only when shown and releases receiver on every hide`() {
        exerciseTransitions(initialTab = 1)
    }

    @Test fun `visible home releases its original receiver when switching to schedule`() {
        exerciseTransitions(initialTab = 0)
    }

    private fun exerciseTransitions(initialTab: Int) {
        var selected by mutableIntStateOf(initialTab)
        var mainVisible by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        val store = ViewModelStore()
        lateinit var receivers: TrackingContext
        lateinit var vm: TodayViewModel
        compose.runOnIdle { vm = viewModel().also { store.put("today", it) } }
        try {
            compose.setContent {
                val base = LocalContext.current
                val context = remember(base) { TrackingContext(base).also { receivers = it } }
                CompositionLocalProvider(LocalContext provides context) {
                    IYangoThemePreview {
                        if (mounted) RetainedMainTabs(selected, visible = mainVisible, prewarmVisitedMask = 1) { index ->
                            if (index == 0) TodayScreen(viewModel = vm) else Text("课表")
                        }
                    }
                }
            }
            compose.runOnIdle { assertEquals(if (initialTab == 0) 1 else 0, receivers.active.size) }
            repeat(3) {
                compose.runOnIdle { selected = 0 }
                compose.onNodeWithContentDescription("首页设置").assertIsDisplayed()
                compose.runOnIdle { assertEquals(1, receivers.active.size); selected = 1 }
                compose.runOnIdle { assertEquals(0, receivers.active.size) }
            }
            compose.runOnIdle { selected = 0 }
            compose.runOnIdle { assertEquals(1, receivers.active.size); mainVisible = false }
            compose.runOnIdle { assertEquals(0, receivers.active.size); mainVisible = true }
            compose.runOnIdle { assertEquals(1, receivers.active.size); mounted = false }
            compose.runOnIdle {
                assertEquals(0, receivers.active.size)
                assertEquals(receivers.registered, receivers.unregistered)
            }
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    private class TrackingContext(base: Context) : ContextWrapper(base) {
        val active = mutableSetOf<BroadcastReceiver>()
        var registered = 0
        var unregistered = 0
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            val result = super.registerReceiver(receiver, filter)
            if (receiver != null && filter?.hasAction(Intent.ACTION_TIME_CHANGED) == true) {
                active += receiver
                registered++
            }
            return result
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            if (receiver.javaClass.name.contains("TodayScreen")) {
                require(active.remove(receiver)) { "Receiver not registered: $receiver" }
                unregistered++
            }
            super.unregisterReceiver(receiver)
        }
    }

    private inline fun <reified T> port(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private fun viewModel() = TodayViewModel(
        timetableRepository = port { name, _ -> when (name) {
            "observeCurrent" -> flowOf<Timetable?>(null)
            else -> error(name)
        } },
        scheduleData = port { name, _ -> error(name) },
        examRepository = port { name, _ -> when (name) {
            "observeAll" -> flowOf(emptyList<Exam>())
            else -> error(name)
        } },
        adjustmentRepository = port { name, _ -> error(name) },
        effectiveSchedule = port { name, args -> when (name) {
            "observeSelectedRange" -> {
                @Suppress("UNCHECKED_CAST") val range = args[0] as ClosedRange<LocalDate>
                flowOf(listOf(EffectiveScheduleDay(range.start)))
            }
            else -> error(name)
        } },
        refreshCoordinator = port { name, _ -> error(name) },
        clock = Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), ZoneOffset.UTC),
        appSettings = port { name, _ -> when (name) {
            "getAppPreferences" -> flowOf(AppPreferences())
            "getDisplaySettings" -> flowOf(DisplaySettingsBundle())
            else -> error(name)
        } },
        auth = port { name, _ -> when (name) {
            "observeConnections" -> flowOf(emptyList<SchoolConnection>())
            else -> error(name)
        } },
    )
}
