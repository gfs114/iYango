package com.gfs.iyango.feature.main.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EditorHistory] 纯 JVM 测试：合并窗口、撤销/重做、容量与重做栈清除。
 */
class EditorHistoryTest {

    /** 可控时钟。 */
    private class FakeClock(var now: Long = 0L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    @Test
    fun `连续同标签输入在合并窗口内只保留突发前快照`() {
        val clock = FakeClock()
        val history = EditorHistory<String>(mergeWindowMs = 500, clock = { clock.now })
        history.push("v1", "name")
        clock.advance(100)
        history.push("v2", "name")
        clock.advance(100)
        history.push("v3", "name")
        assertEquals(1, history.size)
        // 撤销一次即回到突发前。
        assertEquals("v1", history.undo(current = "v4"))
    }

    @Test
    fun `窗口外的同标签输入分别入栈`() {
        val clock = FakeClock()
        val history = EditorHistory<String>(mergeWindowMs = 500, clock = { clock.now })
        history.push("v1", "name")
        clock.advance(800)
        history.push("v2", "name")
        assertEquals(2, history.size)
        assertEquals("v2", history.undo(current = "v3"))
        assertEquals("v1", history.undo(current = "v2"))
        assertNull(history.undo(current = "v1"))
    }

    @Test
    fun `不同标签不合并`() {
        val clock = FakeClock()
        val history = EditorHistory<String>(mergeWindowMs = 10_000, clock = { clock.now })
        history.push("name-v1", "name")
        history.push("room-v1", "room")
        assertEquals(2, history.size)
    }

    @Test
    fun `undo 压入重做栈 redo 可往返`() {
        val history = EditorHistory<Int>(mergeWindowMs = 0, clock = { 0 })
        history.push(1, "a")
        history.push(2, "b")
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        val afterFirstUndo = requireNotNull(history.undo(current = 3))
        assertEquals(2, afterFirstUndo)
        assertTrue(history.canRedo)
        val afterSecondUndo = requireNotNull(history.undo(current = afterFirstUndo))
        assertEquals(1, afterSecondUndo)
        // 全部撤销后重做两次回到最新。
        assertEquals(2, history.redo(current = 1))
        assertEquals(3, history.redo(current = 2))
        assertFalse(history.canRedo)
    }

    @Test
    fun `新的编辑会清空重做栈`() {
        val history = EditorHistory<Int>(mergeWindowMs = 0, clock = { 0 })
        history.push(1, "a")
        history.undo(current = 2)
        assertTrue(history.canRedo)
        history.push(99, "b")
        assertFalse(history.canRedo)
    }

    @Test
    fun `超出容量丢弃最旧快照`() {
        val history = EditorHistory<Int>(mergeWindowMs = 0, capacity = 3, clock = { 0 })
        // 不同标签避免触发合并窗口，专注容量淘汰。
        history.push(1, "a")
        history.push(2, "b")
        history.push(3, "c")
        history.push(4, "d")
        assertEquals(3, history.size)
        // 最旧的 1 已被丢弃；栈为 [2,3,4]，undo 依次返回 4、3、2。
        assertEquals(4, history.undo(current = 5))
        assertEquals(3, history.undo(current = 4))
        assertEquals(2, history.undo(current = 3))
    }

    @Test
    fun `redo 绕过合并窗口直接入撤销栈`() {
        val clock = FakeClock()
        val history = EditorHistory<Int>(mergeWindowMs = 10_000, clock = { clock.now })
        history.push(1, "a")
        val restored = requireNotNull(history.undo(current = 9))
        assertEquals(1, restored)
        clock.advance(0)
        // redo 压回撤销栈；随后同标签 push 不应把"redo 压回项"合并掉。
        history.redo(current = restored)
        clock.advance(1)
        history.push(7, "a")
        clock.advance(1)
        history.push(8, "a")
        // 栈内：1（a），9（redo 标签），7（a 合并窗口起点）→ undo 应返回 7。
        assertEquals(7, history.undo(current = 9))
    }
}
