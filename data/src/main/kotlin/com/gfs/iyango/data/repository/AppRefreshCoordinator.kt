package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshHandler
import com.gfs.iyango.core.domain.RefreshOutcome
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.RefreshState
import com.gfs.iyango.core.domain.RefreshTaskState
import com.gfs.iyango.core.domain.SessionExpiredException
import com.gfs.iyango.core.model.DiagnosticEvent
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 未登记的资源只刷新本地数据；需要查询条件的资源由执行器检查条件。 */
@Singleton
class RefreshHandlerRegistry @Inject constructor() {
    private val handlers = ConcurrentHashMap<RefreshResource, RefreshHandler>()
    fun register(handler: RefreshHandler) { handlers[handler.resource] = handler }
    fun unregister(resource: RefreshResource) { handlers.remove(resource) }
    fun find(resource: RefreshResource): RefreshHandler? = handlers[resource]
}

/** 同条件共享请求；只有当前会话、前台代次及该资源最新请求可以发布状态。 */
@Singleton
class AppRefreshCoordinator @Inject constructor(
    private val registry: RefreshHandlerRegistry,
    private val diagnostics: DiagnosticLogPort,
    private val clock: Clock,
) : RefreshCoordinatorPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RefreshState())
    override val state: StateFlow<RefreshState> = _state.asStateFlow()
    private val lock = Any()
    private val inFlight = mutableMapOf<RefreshKey, RefreshRun>()
    private val latestRequest = mutableMapOf<RefreshResource, Long>()
    private val lastSuccessAt = mutableMapOf<RefreshResource, Long>()
    private var nextRequestId = 0L
    private var foregroundGeneration = 0
    private var sessionRevision = 0
    private var lastForegroundAt: Long? = null

    override fun onForeground() {
        val dueResources: List<RefreshResource>
        val cancelled = synchronized(lock) {
            val now = clock.millis()
            if (lastForegroundAt?.let { now - it in 0 until FOREGROUND_GUARD_MILLIS } == true) return
            lastForegroundAt = now
            foregroundGeneration++
            dueResources = AUTO_REFRESH_RESOURCES.filter { resource ->
                val syncedAt = lastSuccessAt[resource]
                syncedAt == null || (now - syncedAt) !in 0L until AUTO_REFRESH_COOLDOWN_MILLIS
            }
            invalidateRunning(clearAll = false)
        }
        cancelled.forEach { it.cancel() }
        dueResources.forEach { resource -> scope.launch { refresh(resource) } }
    }

    override fun onSessionChanged() {
        val cancelled = synchronized(lock) {
            sessionRevision++
            lastSuccessAt.clear()
            invalidateRunning(clearAll = true)
        }
        cancelled.forEach { it.cancel() }
    }

    /** Called under lock, so an old completion cannot undo the reset. */
    private fun invalidateRunning(clearAll: Boolean): List<Deferred<RefreshTaskState>> {
        val cancelled = inFlight.values.map { it.task }
        inFlight.clear()
        latestRequest.clear()
        _state.value = if (clearAll) RefreshState() else _state.value.copy(
            tasks = _state.value.tasks.mapValues { (_, task) ->
                if (task.isRefreshing) cancelledState(task) else task
            },
        )
        return cancelled
    }

    override suspend fun refresh(resource: RefreshResource, queryFingerprint: String): RefreshTaskState {
        val run = synchronized(lock) {
            val key = RefreshKey(resource, queryFingerprint, foregroundGeneration, sessionRevision)
            inFlight[key]?.takeIf { it.task.isActive } ?: run {
                val id = ++nextRequestId
                val initial = RefreshTaskState(
                    resource = resource, outcome = RefreshOutcome.RUNNING, startedAt = clock.millis(),
                    foregroundGeneration = foregroundGeneration, sessionRevision = sessionRevision,
                    queryFingerprint = queryFingerprint,
                )
                latestRequest[resource] = id
                _state.update { it.copy(tasks = it.tasks + (resource to initial)) }
                val task = scope.async(start = CoroutineStart.LAZY) { execute(initial, id) }
                RefreshRun(id, initial, task).also { created ->
                    inFlight[key] = created
                    task.invokeOnCompletion {
                        synchronized(lock) { if (inFlight[key] === created) inFlight.remove(key) }
                    }
                    task.start()
                }
            }
        }
        return try {
            // Bound the wait separately from the worker: blocking HTTP code may not cooperate
            // with cancellation immediately. It must not keep the UI spinning.
            withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { run.task.await() } ?: finish(
                run.initial.copy(outcome = RefreshOutcome.FAILED, message = "刷新超时，已保留本地数据"), run.id,
            ).also {
                run.task.cancel()
                scope.launch { record(it, "Timeout") }
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            cancelledState(run.initial)
        }
    }

    override fun onLocalDataChanged(resource: RefreshResource) {
        synchronized(lock) {
            // A database write during synchronization does not complete the network request.
            if (_state.value.task(resource).isRefreshing) return
            _state.update { it.copy(tasks = it.tasks + (resource to RefreshTaskState(
                resource = resource, message = "本地数据已更新",
            ))) }
        }
    }

    private suspend fun execute(initial: RefreshTaskState, id: Long): RefreshTaskState {
        var errorType = ""
        val result = try {
            val handler = registry.find(initial.resource)
            val message = when {
                handler == null -> "本地数据已是最新"
                !handler.accepts(initial.queryFingerprint) -> "无查询条件，已跳过"
                else -> {
                    withTimeout(REFRESH_TIMEOUT_MILLIS) { handler.refresh(initial.queryFingerprint) }
                    "刷新成功"
                }
            }
            initial.copy(outcome = RefreshOutcome.SUCCESS, message = message)
        } catch (_: TimeoutCancellationException) {
            errorType = "Timeout"
            initial.copy(outcome = RefreshOutcome.FAILED, message = "刷新超时，已保留本地数据")
        } catch (cancelled: CancellationException) {
            finish(cancelledState(initial), id)
            throw cancelled
        } catch (expired: SessionExpiredException) {
            errorType = "SessionExpired"
            initial.copy(outcome = RefreshOutcome.FAILED, sessionExpired = true,
                message = expired.message?.takeIf { it.isNotBlank() } ?: "登录状态已失效，请重新登录")
        } catch (error: Exception) {
            errorType = error::class.simpleName ?: "Exception"
            initial.copy(outcome = RefreshOutcome.FAILED,
                message = error.message?.takeIf { it.isNotBlank() && it.length <= 120 }
                    ?: "刷新失败，已保留本地数据")
        }
        return finish(result, id).also { finished ->
            // Log I/O is independent of user-visible completion.
            if (finished.outcome != RefreshOutcome.CANCELLED) scope.launch { record(finished, errorType) }
        }
    }

    private fun finish(task: RefreshTaskState, id: Long): RefreshTaskState = synchronized(lock) {
        if (task.foregroundGeneration != foregroundGeneration || task.sessionRevision != sessionRevision ||
            latestRequest[task.resource] != id
        ) return@synchronized cancelledState(task)
        val finished = task.copy(finishedAt = clock.millis())
        if (finished.outcome == RefreshOutcome.SUCCESS) {
            lastSuccessAt[finished.resource] = finished.finishedAt
        }
        latestRequest.remove(task.resource)
        _state.update { it.copy(tasks = it.tasks + (task.resource to finished)) }
        finished
    }

    private fun cancelledState(task: RefreshTaskState) = task.copy(
        outcome = RefreshOutcome.CANCELLED, message = "刷新已取消，请重试", finishedAt = clock.millis(),
    )

    private suspend fun record(task: RefreshTaskState, errorType: String = "") {
        runCatching { diagnostics.record(DiagnosticEvent(
            timestamp = task.finishedAt, category = "refresh.${task.resource.name.lowercase()}",
            taskRevision = task.sessionRevision, durationMs = (task.finishedAt - task.startedAt).coerceAtLeast(0),
            outcome = task.outcome.name, errorType = errorType,
            message = if (task.outcome == RefreshOutcome.FAILED) task.message else "",
        )) }
    }

    private data class RefreshRun(val id: Long, val initial: RefreshTaskState, val task: Deferred<RefreshTaskState>)
    private data class RefreshKey(
        val resource: RefreshResource, val queryFingerprint: String,
        val foregroundGeneration: Int, val sessionRevision: Int,
    )
    private companion object {
        const val REFRESH_TIMEOUT_MILLIS = 30_000L
        const val FOREGROUND_GUARD_MILLIS = 2_000L
        const val AUTO_REFRESH_COOLDOWN_MILLIS = 10L * 60L * 1000L
        val AUTO_REFRESH_RESOURCES = listOf(RefreshResource.TIMETABLE, RefreshResource.PROFILE, RefreshResource.EXAMS)
    }
}
