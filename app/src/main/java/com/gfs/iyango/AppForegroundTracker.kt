package com.gfs.iyango

/**
 * 进程级前台判定纯逻辑（多 Activity 架构 P1）。
 *
 * 多 Activity 后，从二级页退回 `MainActivity` 会再次走 `onStart`；刷新/提醒复核/
 * 检查更新必须只在"应用真正进入前台"时触发。本类精确维护当前处于 started 状态的
 * Activity 计数 [startedCount]：仅当计数从 `0 → 1` 且此前并非因配置变更全部停止
 * （[wasChangingConfigurations] 为 false）时，才回调 [onEnterForeground]。
 *
 * 无泄漏保证：`onActivityStopped` 始终执行 `startedCount--`；即使在配置变更期间
 * 重建被提前中止（或处于分屏/多窗模式），也不会留下永久 +1 计数导致后续无法回到 0。
 */
internal class AppForegroundTracker(private val onEnterForeground: () -> Unit) {

    private var startedCount = 0
    private var wasChangingConfigurations = false

    /** @return 本次 start 是否把应用从后台带入前台（0 → 1 且非配置变更重建，同时触发 [onEnterForeground]）。 */
    fun onActivityStarted(): Boolean {
        startedCount++
        val enteredForeground = startedCount == 1 && !wasChangingConfigurations
        wasChangingConfigurations = false
        if (enteredForeground) onEnterForeground()
        return enteredForeground
    }

    fun onActivityStopped(isChangingConfigurations: Boolean) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (startedCount == 0) {
            wasChangingConfigurations = isChangingConfigurations
        }
    }
}
