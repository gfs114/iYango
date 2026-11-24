package com.gfs.iyango.platform.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class WidgetUpdateCoordinatorTest {
    @Test fun slowOlderBuildCannotOverwriteNewerStateAndOtherWidgetsRemainIndependent() = runBlocking {
        withTimeout(5000) {
            val coordinator = WidgetUpdateCoordinator()
            val oldRead = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val displayed = mutableMapOf<Int, String>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.publish(1, {
                    val snapshot = "D+1"
                    oldRead.complete(Unit)
                    releaseOld.await()
                    snapshot
                }) { displayed[1] = it }
            }
            oldRead.await()
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.publish(1, { "D+2" }) { displayed[1] = it }
            }
            val independent = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.publish(2, { "other widget" }) { displayed[2] = it }
            }
            independent.join()
            assertEquals("other widget", displayed[2])
            releaseOld.complete(Unit)
            first.join(); second.join()
            assertEquals("D+2", displayed[1])
        }
    }
    @Test fun failedOrCancelledBuildDoesNotPublishAndLaterRequestsStillComplete() = runBlocking {
        val coordinator = WidgetUpdateCoordinator()
        val displayed = mutableListOf<String>()
        try {
            coordinator.publish(1, { error("read failed") }) { _: String -> fail("Failed build published") }
            fail("Failure must propagate")
        } catch (_: IllegalStateException) { }
        val started = CompletableDeferred<Unit>()
        val suspended = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.publish(1, { started.complete(Unit); CompletableDeferred<String>().await() }) { displayed += it }
        }
        started.await()
        suspended.cancelAndJoin()
        coordinator.publish(1, { "recovered" }) { displayed += it }
        assertEquals(listOf("recovered"), displayed)
        try {
            coordinator.publish(1, { "publisher failure" }) { error("host failed") }
            fail("Publication failure must propagate")
        } catch (_: IllegalStateException) { }
        coordinator.publish(1, { "after host failure" }) { displayed += it }
        assertEquals("after host failure", displayed.last())
    }
    @Test fun cancelledWaitersAndQueriesThatSwallowCancellationCannotPublishOrPoisonTheGate() = runBlocking {
        withTimeout(5000) {
            val coordinator = WidgetUpdateCoordinator()
            val blocked = CompletableDeferred<Unit>()
            val displayed = mutableListOf<String>()
            val running = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.publish(1, {
                    try { blocked.await() } catch (_: kotlinx.coroutines.CancellationException) { }
                    "cancelled result"
                }) { displayed += it }
            }
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.publish(1, { "cancelled waiter" }) { displayed += it }
            }
            waiter.cancelAndJoin()
            running.cancelAndJoin()
            coordinator.publish(1, { "latest" }) { displayed += it }
            assertEquals(listOf("latest"), displayed)
        }
    }

    @Test fun deletingAnInFlightWidgetPreventsOldPublicationAndCleanupRunsAfterItsWrite() = runBlocking {
        withTimeout(5000) {
            val coordinator = WidgetUpdateCoordinator()
            val releaseOld = CompletableDeferred<Unit>()
            var saved: String? = null
            val displayed = mutableListOf<String>()
            val old = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.publish(1, { releaseOld.await(); saved = "old"; "old" }) { displayed += it }
            }
            coordinator.invalidate(1)
            val deletion = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.remove(1) { saved = null } }
            releaseOld.complete(Unit)
            old.join(); deletion.join()
            assertTrue("Deleted widget must never publish an old result", displayed.isEmpty())
            assertNull("Cleanup must follow any in-flight write", saved)
            coordinator.publish(1, { "new binding" }) { displayed += it }
            assertEquals(listOf("new binding"), displayed)
        }
    }
}
