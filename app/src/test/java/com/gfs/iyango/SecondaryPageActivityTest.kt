package com.gfs.iyango

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.gfs.iyango.feature.main.SecondaryPageRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [SecondaryPageActivity] 启动器用例（多 Activity 架构 P2）：
 * 带参路由写入与 SavedStateHandle 同键的 Extra、未知路由拒收、
 * 400ms 同路由防抖生效但不同路由放行、绝不携带 FLAG_ACTIVITY_SINGLE_TOP。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SecondaryPageActivityTest {

    private val application = RuntimeEnvironment.getApplication() as Application

    @Before
    fun resetThrottle() {
        SecondaryPageLauncher.throttle = RouteOpenThrottle(SecondaryPageLauncher.DEBOUNCE_MS)
    }

    @Test
    fun `createIntent writes SavedStateHandle extras for parameterized routes`() {
        val editor = SecondaryPageLauncher.createIntent(application, "editor?courseId=12&copyFromId=3")!!
        assertEquals(SecondaryPageActivity::class.java.name, editor.component?.className)
        assertEquals("editor?courseId=12&copyFromId=3", editor.getStringExtra(SecondaryPageActivity.EXTRA_ROUTE))
        assertEquals(12L, editor.getLongExtra("courseId", -1L))
        assertEquals(3L, editor.getLongExtra("copyFromId", -1L))

        val raw = SecondaryPageLauncher.createIntent(application, "courseraw/9")!!
        assertEquals("courseraw/9", raw.getStringExtra(SecondaryPageActivity.EXTRA_ROUTE))
        assertEquals(9L, raw.getLongExtra("timetableId", -1L))

        val display = SecondaryPageLauncher.createIntent(application, "display?perTimetable=true")!!
        assertEquals("display?perTimetable=true", display.getStringExtra(SecondaryPageActivity.EXTRA_ROUTE))
        assertTrue(display.getBooleanExtra("perTimetable", false))
    }

    @Test
    fun `createIntent defaults missing parameters like the old NavHost defaults`() {
        val editor = SecondaryPageLauncher.createIntent(application, "editor?courseId=0")!!
        assertEquals(0L, editor.getLongExtra("courseId", -1L))
        assertEquals(0L, editor.getLongExtra("copyFromId", -1L))

        val plain = SecondaryPageLauncher.createIntent(application, "wallpaper")!!
        assertEquals("wallpaper", plain.getStringExtra(SecondaryPageActivity.EXTRA_ROUTE))
        assertNull(plain.extras?.get("courseId"))
        assertNull(plain.extras?.get("timetableId"))
    }

    @Test
    fun `unknown route is rejected`() {
        assertNull(SecondaryPageLauncher.createIntent(application, "nonsense"))
        assertNull(SecondaryPageLauncher.createIntent(application, "today"))
        assertNull(SecondaryPageLauncher.createIntent(application, "editor?courseId=abc"))
        assertNull(SecondaryPageLauncher.createIntent(application, "courseraw/abc"))
        assertNull(SecondaryPageLauncher.createIntent(application, "display?perTimetable=yes"))
        // open() 对未知路由静默忽略，不启动任何 Activity。
        SecondaryPageLauncher.open(application, "nonsense")
        assertNull(shadowOf(application).peekNextStartedActivity())
    }

    @Test
    fun `open never carries FLAG_ACTIVITY_SINGLE_TOP and adds NEW_TASK only for non-Activity contexts`() {
        val intent = SecondaryPageLauncher.createIntent(application, "wallpaper")!!
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)

        SecondaryPageLauncher.open(application, "wallpaper")
        val started = shadowOf(application).nextStartedActivity
        assertEquals(SecondaryPageActivity::class.java.name, started.component?.className)
        assertTrue(
            "Non-Activity context must add NEW_TASK",
            started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertEquals(
            "Predictive cross-activity back forbids SINGLE_TOP",
            0,
            started.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }

    @Test
    fun `open debounces the same route but lets different routes through`() {
        SecondaryPageLauncher.open(application, "wallpaper")
        assertNotNull(shadowOf(application).nextStartedActivity)

        // 窗口内重复同一路由：不启动新 Activity。
        SecondaryPageLauncher.open(application, "wallpaper")
        SecondaryPageLauncher.open(application, "wallpaper")
        assertNull(shadowOf(application).peekNextStartedActivity())

        // 不同路由不受防抖影响（二级页跳三级页）。
        SecondaryPageLauncher.open(application, "font")
        val second = shadowOf(application).nextStartedActivity
        assertEquals("font", second.getStringExtra(SecondaryPageActivity.EXTRA_ROUTE))

        // 同一编辑页以不同参数构造时视为不同路由（id 不同则放行）。
        SecondaryPageLauncher.open(application, "editor?courseId=1")
        assertNotNull(shadowOf(application).nextStartedActivity)
        SecondaryPageLauncher.open(application, "editor?courseId=1")
        assertNull(shadowOf(application).peekNextStartedActivity())
        SecondaryPageLauncher.open(application, "editor?courseId=2")
        assertNotNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `route throttle reopens after the debounce window`() {
        val throttle = RouteOpenThrottle(SecondaryPageLauncher.DEBOUNCE_MS)
        assertTrue(throttle.shouldOpen("wallpaper", 1_000))
        assertFalse(throttle.shouldOpen("wallpaper", 1_000 + SecondaryPageLauncher.DEBOUNCE_MS - 1))
        assertTrue(throttle.shouldOpen("wallpaper", 1_000 + SecondaryPageLauncher.DEBOUNCE_MS))
        // 不同路由在窗口内不受影响。
        assertTrue(throttle.shouldOpen("font", 1_050))
    }

    @Test
    fun `canonical route extra re-parses to the same route`() {
        for (raw in listOf("editor?courseId=12&copyFromId=3", "courseraw/9", "display?perTimetable=true", "wallpaper")) {
            val parsed = SecondaryPageRoute.parse(raw)!!
            val intent = SecondaryPageLauncher.createIntent(application, raw)!!
            assertEquals(parsed, SecondaryPageRoute.parse(intent.getStringExtra(SecondaryPageActivity.EXTRA_ROUTE)))
        }
    }

    @Test
    fun `external deep link whitelist rejects internal routes and clears consumed URI`() {
        for (internalRoute in listOf("runtimelogs", "settingsbackup", "school", "dockglass", "dockglass/dev")) {
            val blocked = Intent(Intent.ACTION_VIEW, Uri.parse("iyango://open/$internalRoute"))
            assertNull("Internal route $internalRoute must be blocked from external URIs", MainActivity.consumeExternalIntent(blocked))
        }

        val summaryIntent = Intent(Intent.ACTION_VIEW, Uri.parse("iyango://open/semestersummary"))
        val parsedSummary = MainActivity.consumeExternalIntent(summaryIntent)
        assertEquals("semestersummary", parsedSummary?.route)
        assertNull("Consumed URI data must be cleared to prevent rotation replay", summaryIntent.data)

        val tabIntent = Intent(Intent.ACTION_VIEW, Uri.parse("iyango://open/schedule"))
        val parsedTab = MainActivity.consumeExternalIntent(tabIntent)
        assertEquals(1, parsedTab?.tab)
        assertNull(tabIntent.data)
    }
}
