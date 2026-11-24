package com.gfs.iyango

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AppForegroundTracker] 纯逻辑用例：Main → Secondary → Main 不重复触发；
 * 退后台再回前台触发；旋转（isChangingConfigurations）不误触发且不泄漏计数。
 */
class AppForegroundTrackerTest {

    private var foregrounds = 0
    private val tracker = AppForegroundTracker { foregrounds++ }

    @Test
    fun `cold start enters foreground exactly once`() {
        assertEquals(true, tracker.onActivityStarted())
        assertEquals(1, foregrounds)
        assertEquals(false, tracker.onActivityStarted())
        assertEquals(1, foregrounds)
    }

    @Test
    fun `main to secondary and back does not retrigger onForeground`() {
        tracker.onActivityStarted() // Main 冷启动
        assertEquals(1, foregrounds)
        tracker.onActivityStarted() // Secondary 压栈
        tracker.onActivityStopped(isChangingConfigurations = false) // Secondary 退出
        assertEquals("Returning to Main must not re-trigger", 1, foregrounds)
        tracker.onActivityStopped(isChangingConfigurations = false) // Main 退后台
        tracker.onActivityStarted() // 再次回前台
        assertEquals(2, foregrounds)
    }

    @Test
    fun `secondary chains keep the count consistent`() {
        tracker.onActivityStarted()
        tracker.onActivityStarted()
        tracker.onActivityStarted() // 三级页
        assertEquals(1, foregrounds)
        repeat(3) { tracker.onActivityStopped(false) }
        tracker.onActivityStarted()
        assertEquals("Full unwind then relaunch must re-trigger", 2, foregrounds)
    }

    @Test
    fun `rotation does not misfire foreground`() {
        tracker.onActivityStarted()
        assertEquals(1, foregrounds)
        // 旋转：旧 Activity 以 isChangingConfigurations=true 停止，重建后重新启动。
        tracker.onActivityStopped(isChangingConfigurations = true)
        assertEquals(false, tracker.onActivityStarted())
        assertEquals("Recreation must not look like a fresh foreground entry", 1, foregrounds)
        // 之后的正常退后台/回前台仍正确触发。
        tracker.onActivityStopped(false)
        assertEquals(true, tracker.onActivityStarted())
        assertEquals(2, foregrounds)
    }

    @Test
    fun `rotation on top of another activity keeps the foreground state`() {
        tracker.onActivityStarted() // Main
        tracker.onActivityStarted() // Secondary（前台）
        assertEquals(1, foregrounds)
        // 仅旋转处于前台的 Secondary。
        tracker.onActivityStopped(isChangingConfigurations = true)
        assertEquals(false, tracker.onActivityStarted())
        assertEquals(1, foregrounds)
        tracker.onActivityStopped(false) // Secondary 退出
        assertEquals("Main is still started, app never left foreground", 1, foregrounds)
        tracker.onActivityStopped(false) // Main 退后台
        assertEquals(true, tracker.onActivityStarted())
        assertEquals(2, foregrounds)
    }

    @Test
    fun `multi-window both activities rotating never leaks startedCount`() {
        tracker.onActivityStarted() // Activity A
        tracker.onActivityStarted() // Activity B
        assertEquals(1, foregrounds)
        // 分屏下 A 与 B 同时因配置变更停止并重建。
        tracker.onActivityStopped(isChangingConfigurations = true)
        tracker.onActivityStopped(isChangingConfigurations = true)
        assertEquals(false, tracker.onActivityStarted())
        assertEquals(false, tracker.onActivityStarted())
        assertEquals(1, foregrounds)
        // 此后正常退后台，计数必须准确归零，下次打开能再次触发前台事件。
        tracker.onActivityStopped(false)
        tracker.onActivityStopped(false)
        assertEquals(true, tracker.onActivityStarted())
        assertEquals(2, foregrounds)
    }

    @Test
    fun `aborted recreation after config change never leaks count when remaining activity stops`() {
        tracker.onActivityStarted() // Main
        tracker.onActivityStarted() // Secondary
        assertEquals(1, foregrounds)
        // Secondary 因旋转停止后尚未走到 onStart 即 finish，随后用户退后台导致 Main 停止。
        tracker.onActivityStopped(isChangingConfigurations = true)
        tracker.onActivityStopped(isChangingConfigurations = false)
        // 由于最后离开前台的是非配置变更的 Main stop（且计数已归零），下次回到前台必须正常触发。
        assertEquals(true, tracker.onActivityStarted())
        assertEquals(2, foregrounds)
    }

    @Test
    fun `stop never drives the count negative`() {
        tracker.onActivityStopped(false)
        tracker.onActivityStopped(false)
        assertEquals(true, tracker.onActivityStarted())
        assertEquals(1, foregrounds)
    }
}
