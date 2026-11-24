package com.gfs.iyango.feature.main.schedule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class SchedulePullRequest(val id: Long, val navigationVersion: Long)
data class SchedulePagePosition(val context: ScheduleContext, val week: Int, val navigationVersion: Long)

fun isCurrentPullTarget(state: ScheduleUiState, request: SchedulePullRequest): Boolean {
    val data = (state as? ScheduleUiState.Ready)?.data ?: return false
    return !data.readOnly && data.datesKnown && data.currentWeek > 0 &&
        data.displayedWeek == data.currentWeek && data.navigationVersion >= request.navigationVersion
}

private fun terminalPullState(state: ScheduleUiState, request: SchedulePullRequest): Boolean = when (state) {
    ScheduleUiState.NoTimetable, ScheduleUiState.NoSemester, is ScheduleUiState.Error -> true
    is ScheduleUiState.Ready -> !state.data.readOnly && state.data.navigationVersion >= request.navigationVersion && state.data.currentWeek == 0
    else -> false
}

private fun supersededPull(state: ScheduleUiState, request: SchedulePullRequest): Boolean {
    val data = (state as? ScheduleUiState.Ready)?.data ?: return false
    return !data.readOnly && data.navigationVersion > request.navigationVersion &&
        data.currentWeek > 0 && data.displayedWeek != data.currentWeek
}

/** One rendered grid, animated in the draw phase; school synchronization is not an input. */
@Composable
fun SchedulePullTransition(
    request: SchedulePullRequest?,
    state: ScheduleUiState,
    settled: SchedulePagePosition?,
    active: Boolean,
    onComplete: (Long, Boolean) -> Unit,
    onCancel: () -> Unit,
    localState: ScheduleUiState = state,
    content: @Composable (Modifier) -> Unit,
) {
    val motionEnabled = LocalMotionEnabled.current
    val progress = remember(request?.id, active) {
        Animatable(if (motionEnabled && active && request != null) 0f else 1f)
    }
    val distance = with(LocalDensity.current) { 8.dp.toPx() }
    val latestState by rememberUpdatedState(state)
    val latestLocal by rememberUpdatedState(localState)
    val latestSettled by rememberUpdatedState(settled)
    val latestComplete by rememberUpdatedState(onComplete)
    val latestCancel by rememberUpdatedState(onCancel)
    LaunchedEffect(progress, motionEnabled) {
        if (!motionEnabled) progress.snapTo(1f)
    }
    DisposableEffect(active) {
        if (!active) latestCancel()
        onDispose { latestCancel() }
    }
    LaunchedEffect(request?.id, active) {
        if (request == null || !active) return@LaunchedEffect
        try {
            withTimeout(2_000) {
                val local = snapshotFlow { latestState to latestLocal }.first { (displayed, native) ->
                    isCurrentPullTarget(displayed, request) || terminalPullState(native, request) || supersededPull(displayed, request)
                }
                if (supersededPull(local.first, request)) {
                    progress.snapTo(1f)
                    latestCancel()
                    return@withTimeout
                }
                if (terminalPullState(local.second, request)) {
                    progress.snapTo(1f)
                    latestComplete(request.id, false)
                    return@withTimeout
                }
                if (progress.value < 1f) coroutineScope {
                    // Snapping cancels the animation's job. Keep the pager/result wait alive.
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        progress.animateTo(1f, tween(AppMotion.SchedulePull))
                    }
                }
                val arrived = snapshotFlow { Triple(latestState, latestSettled, latestLocal) }.first { (current, page, native) ->
                    val data = (current as? ScheduleUiState.Ready)?.data
                    supersededPull(current, request) || terminalPullState(native, request) || (isCurrentPullTarget(current, request) &&
                        page == data?.let { SchedulePagePosition(it.context, it.displayedWeek, it.navigationVersion) })
                }
                if (supersededPull(arrived.first, request)) latestCancel()
                else latestComplete(request.id, isCurrentPullTarget(arrived.first, request))
            }
        } catch (_: TimeoutCancellationException) {
            progress.snapTo(1f)
            latestComplete(request.id, false)
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val fraction = if (motionEnabled) progress.value else 1f
        alpha = .6f + .4f * fraction
        translationY = (1f - fraction) * distance
    }) { content(Modifier.fillMaxSize()) }
}
