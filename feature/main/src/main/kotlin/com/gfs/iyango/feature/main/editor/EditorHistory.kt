package com.gfs.iyango.feature.main.editor

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 编辑器撤销/重做栈（契约 §5.4）。
 *
 * 语义：
 * - 每次"用户编辑发生前"调用 [push] 存入变更前的表单快照，标签 [tag] 标识输入来源
 *   （如 `"name"`、`"a0.teacher"`）；
 * - **连续文本编辑合并**：同一 [tag] 且距栈顶时间戳不足 [mergeWindowMs] 的新快照
 *   不入栈（仅刷新时间戳），因此一次连续输入只保留"输入前"的那份快照，一次撤销
 *   即回到输入前状态；
 * - 任何新的 [push]（未合并）都会清空重做栈；
 * - [undo] 返回上一份快照并把"当前状态"压入重做栈；[redo] 为其逆操作。
 *
 * 线程约束：仅供主线程（VM）使用；时间戳由构造时注入的时钟函数提供以便测试。
 */
class EditorHistory<T>(
    private val mergeWindowMs: Long = 700L,
    private val capacity: Int = 60,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Entry<T>(val state: T, val tag: String, var at: Long)

    private val undoStack = ArrayDeque<Entry<T>>()
    private val redoStack = ArrayDeque<T>()

    /** 栈内快照数（调试/测试用）。 */
    val size: Int get() = undoStack.size

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * 记录"即将被修改"的表单快照。
     *
     * @param state 修改前的表单。
     * @param tag 输入来源标签；同一标签在合并窗口内的连续快照会被合并。
     */
    fun push(state: T, tag: String) {
        val now = clock()
        val top = undoStack.lastOrNull()
        if (top != null && top.tag == tag && now - top.at <= mergeWindowMs) {
            // 连续编辑合并：保留突发开始前的旧快照，仅顺延窗口时间戳。
            top.at = now
            return
        }
        undoStack.addLast(Entry(state, tag, now))
        while (undoStack.size > capacity) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    /**
     * 撤销：弹出上一份快照返回；把 [current] 压入重做栈。无可撤销时返回 null。
     */
    fun undo(current: T): T? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return entry.state
    }

    /**
     * 重做：弹出重做栈顶返回；把 [current] 直接入撤销栈（绕过合并窗口）。
     * 无可重做时返回 null。
     */
    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(Entry(current, "\u0000redo", clock()))
        return next
    }
}

/** 把撤销/重做栈接到表单 [MutableStateFlow] 的便捷封装。 */
fun <T> EditorHistory<T>.bind(state: MutableStateFlow<T>) {
    // 预留扩展点：当前 undo/redo 由 VM 直接调用，无需绑定逻辑。
}
