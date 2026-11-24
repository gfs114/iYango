package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.*
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AppRefreshCoordinatorTest {
    private val registry = RefreshHandlerRegistry()
    private val diagnostics = Proxy.newProxyInstance(
        DiagnosticLogPort::class.java.classLoader, arrayOf(DiagnosticLogPort::class.java),
    ) { _, method, _ -> if (method.name == "record") Unit else error(method.name) } as DiagnosticLogPort
    private val coordinator = AppRefreshCoordinator(registry, diagnostics, Clock.systemUTC())

    private fun handler(block: suspend (String) -> Unit) = registry.register(object : RefreshHandler {
        override val resource = RefreshResource.GRADES
        override suspend fun refresh(queryFingerprint: String) = block(queryFingerprint)
    })

    @Test fun `cancelled handler ends the published spinner`() = runBlocking {
        handler { throw CancellationException("request cancelled") }
        val result = coordinator.refresh(RefreshResource.GRADES, "term")
        assertEquals(RefreshOutcome.CANCELLED, result.outcome)
        assertFalse(coordinator.state.value.task(RefreshResource.GRADES).isRefreshing)
    }

    @Test fun `foreground replacement clears non automatic resource running state`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        handler { entered.complete(Unit); release.await() }
        val request = async { coordinator.refresh(RefreshResource.GRADES, "term") }
        try {
            withTimeout(2_000) { entered.await() }
            coordinator.onForeground()
            assertFalse(coordinator.state.value.task(RefreshResource.GRADES).isRefreshing)
            assertEquals(RefreshOutcome.CANCELLED, withTimeout(2_000) { request.await() }.outcome)
        } finally { release.complete(Unit); request.join() }
    }

    @Test fun `older query completion cannot replace the newer query spinner`() = runBlocking {
        val oldEntered = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        handler { fingerprint ->
            if (fingerprint == "old") { oldEntered.complete(Unit); oldRelease.await() }
            else { newEntered.complete(Unit); newRelease.await() }
        }
        val old = async { coordinator.refresh(RefreshResource.GRADES, "old") }
        withTimeout(2_000) { oldEntered.await() }
        val newer = async { coordinator.refresh(RefreshResource.GRADES, "new") }
        try {
            withTimeout(2_000) { newEntered.await() }
            oldRelease.complete(Unit)
            old.await()
            val visible = coordinator.state.value.task(RefreshResource.GRADES)
            assertEquals("new", visible.queryFingerprint)
            assertTrue(visible.isRefreshing)
        } finally { oldRelease.complete(Unit); newRelease.complete(Unit); newer.join() }
    }

    @Test fun `same query shares its in flight request`() = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        handler { calls++; entered.complete(Unit); release.await() }
        val first = async { coordinator.refresh(RefreshResource.GRADES, "same") }
        withTimeout(2_000) { entered.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.refresh(RefreshResource.GRADES, "same") }
        release.complete(Unit)
        assertEquals(RefreshOutcome.SUCCESS, first.await().outcome)
        assertEquals(RefreshOutcome.SUCCESS, second.await().outcome)
        assertEquals(1, calls)
    }

    @Test(timeout = 36_000) fun `refresh deadline also bounds a blocking school handler`() = runBlocking {
        val release = CountDownLatch(1)
        handler { release.await(35, TimeUnit.SECONDS) }
        try {
            val result = withTimeout(32_000) { coordinator.refresh(RefreshResource.GRADES, "slow") }
            assertEquals(RefreshOutcome.FAILED, result.outcome)
            assertTrue(result.message.contains("超时"))
            assertFalse(coordinator.state.value.task(RefreshResource.GRADES).isRefreshing)
        } finally { release.countDown() }
    }

    @Test fun `foreground auto refresh skips recently succeeded resources within 10m cooldown while manual refresh runs`() = runBlocking {
        var nowMillis = 1_700_000_000_000L
        val mutableClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)
        }
        val localRegistry = RefreshHandlerRegistry()
        var timetableCalls = 0
        localRegistry.register(object : RefreshHandler {
            override val resource = RefreshResource.TIMETABLE
            override suspend fun refresh(queryFingerprint: String) { timetableCalls++ }
        })
        val localCoordinator = AppRefreshCoordinator(localRegistry, diagnostics, mutableClock)

        // First manual/foreground refresh records success timestamp.
        assertEquals(RefreshOutcome.SUCCESS, localCoordinator.refresh(RefreshResource.TIMETABLE).outcome)
        assertEquals(1, timetableCalls)

        // 3 minutes later: foreground resume passes 2s guard but stays within 10m cooldown -> skip TIMETABLE.
        nowMillis += 3L * 60L * 1000L
        localCoordinator.onForeground()
        delay(80)
        assertEquals(1, timetableCalls)

        // Explicit manual refresh must still bypass cooldown immediately.
        assertEquals(RefreshOutcome.SUCCESS, localCoordinator.refresh(RefreshResource.TIMETABLE).outcome)
        assertEquals(2, timetableCalls)

        // Advance another 11 minutes: cooldown elapsed -> next onForeground triggers auto-refresh.
        nowMillis += 11L * 60L * 1000L
        localCoordinator.onForeground()
        withTimeout(2_000) {
            while (timetableCalls < 3) delay(15)
        }
        assertEquals(3, timetableCalls)
    }
}
