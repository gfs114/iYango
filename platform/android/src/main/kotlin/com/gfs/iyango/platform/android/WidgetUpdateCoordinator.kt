package com.gfs.iyango.platform.android

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-widget ordering; entries live only while a renderer, waiter or deletion owns them. */
internal class WidgetUpdateCoordinator {
    private class Gate {
        val mutex = Mutex()
        var users = 0 // Protected by gates.
        var invalidated = false // Protected by this Gate.
    }
    private val gates = mutableMapOf<Int, Gate>()

    private fun acquire(widgetId: Int): Gate = synchronized(gates) {
        gates.getOrPut(widgetId) { Gate() }.also { it.users++ }
    }

    private fun release(widgetId: Int, gate: Gate) = synchronized(gates) {
        gate.users--
        if (gate.users == 0 && gates[widgetId] === gate) gates.remove(widgetId)
    }

    suspend fun <T> publish(widgetId: Int, build: suspend () -> T, publish: (T) -> Unit): Boolean {
        val gate = acquire(widgetId)
        try {
            return gate.mutex.withLock {
                if (synchronized(gate) { gate.invalidated }) return@withLock false
                val result = build()
                // A query may have caught cancellation; cancelled work must still never publish.
                currentCoroutineContext().ensureActive()
                synchronized(gate) {
                    if (gate.invalidated) false else {
                        publish(result)
                        true
                    }
                }
            }
        } finally {
            release(widgetId, gate)
        }
    }

    fun invalidate(widgetId: Int) {
        val gate = synchronized(gates) { gates[widgetId] } ?: return
        synchronized(gate) { gate.invalidated = true }
    }

    suspend fun remove(widgetId: Int, cleanup: suspend () -> Unit) {
        val gate = acquire(widgetId)
        synchronized(gate) { gate.invalidated = true }
        try {
            // Complete cleanup after an in-flight configuration write, even if it cannot publish.
            gate.mutex.withLock { cleanup() }
        } finally {
            release(widgetId, gate)
        }
    }
}
