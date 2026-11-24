package com.gfs.iyango.feature.main.today

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** All positions are relative to the lazy list's viewport, including while it scrolls. */
internal class HomeOrderDrag(private val list: LazyListState) {
    var row by mutableStateOf<DraggedHomeRow?>(null)
        private set

    fun start(key: String, indexInGroup: Int, count: Int, handleY: Float, handleHeight: Int, onMove: (Int) -> Unit) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val first = item.index - indexInGroup
        val grabOffset = (item.size - handleHeight) / 2f + handleY
        row = DraggedHomeRow(list, key, first until first + count, item, grabOffset, onMove)
    }

    fun finish(key: String) {
        row?.takeIf { it.key == key }?.dragging = false
    }

    fun clear() { row = null }
}

internal class DraggedHomeRow(
    private val list: LazyListState,
    val key: String,
    private val group: IntRange,
    item: LazyListItemInfo,
    private val grabOffset: Float,
    private val onMove: (Int) -> Unit,
) {
    var dragging by mutableStateOf(true)
    val release = Animatable(1f)
    val lift = Animatable(0f)
    val scale get() = 1f + 0.02f * lift.value * release.value
    private var fingerY by mutableFloatStateOf(item.offset + grabOffset)
    private var expectedIndex = item.index
    private val height = item.size
    private val visible get() = list.layoutInfo.visibleItemsInfo
    private class Positions(val active: LazyListItemInfo?, val firstTop: Float, val lastBottom: Float)
    // Measurements change on scrolling or reordering. Ordinary finger movement and
    // release frames can reuse the same positions without walking the visible rows.
    private val positions by derivedStateOf {
        var active: LazyListItemInfo? = null
        var firstTop = Float.NEGATIVE_INFINITY
        var lastBottom = Float.POSITIVE_INFINITY
        for (it in visible) {
            if (it.key == key) active = it
            if (it.index == group.first) firstTop = it.offset.toFloat()
            if (it.index == group.last) lastBottom = (it.offset + it.size).toFloat()
        }
        Positions(active, firstTop, lastBottom)
    }
    private val top get() = with(positions) {
        (fingerY - grabOffset).coerceIn(firstTop, maxOf(firstTop, lastBottom - height))
    }

    fun translation(): Float {
        val item = positions.active ?: return 0f
        return (top - item.offset) * release.value
    }

    fun dragBy(dy: Float) {
        fingerY += dy
        moveIfCrossed()
    }

    fun moveIfCrossed() {
        val item = positions.active ?: return
        // Wait for the new keyed layout before asking for another move.
        if (item.index != expectedIndex) return
        val center = top + height / 2f
        val target = if (center > item.offset + height / 2f) {
            visible.lastOrNull { it.index in group && it.index > item.index && center >= it.offset + it.size / 2f }
        } else {
            visible.firstOrNull { it.index in group && it.index < item.index && center <= it.offset + it.size / 2f }
        } ?: return
        expectedIndex = target.index
        // Key anchoring would otherwise scroll the first visible item along with its move.
        val anchor = list.firstVisibleItemIndex
        if (anchor in minOf(item.index, target.index)..maxOf(item.index, target.index)) {
            list.requestScrollToItem(anchor, list.firstVisibleItemScrollOffset)
        }
        onMove(target.index - item.index)
    }

    fun edgeScroll(edge: Float): Float {
        val layout = list.layoutInfo
        val start = layout.viewportStartOffset + layout.beforeContentPadding
        val end = layout.viewportEndOffset - layout.afterContentPadding
        return when {
            fingerY < start + edge && positions.firstTop < start ->
                ((fingerY - start - edge) / edge).coerceIn(-1f, 0f)
            fingerY > end - edge && positions.lastBottom > end ->
                ((fingerY - end + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
    }
}

@Composable
internal fun rememberHomeOrderDrag(list: LazyListState, interactive: Boolean): HomeOrderDrag {
    val drag = remember(list) { HomeOrderDrag(list) }
    val row = drag.row
    val enabled = LocalMotionEnabled.current
    val density = LocalDensity.current
    LaunchedEffect(row, row?.dragging, interactive, enabled, density) {
        when {
            row == null -> Unit
            !interactive -> drag.clear()
            row.dragging -> coroutineScope {
                launch {
                    if (enabled) row.lift.animateTo(1f, tween(AppMotion.Press)) else row.lift.snapTo(0f)
                }
                launch { snapshotFlow { list.layoutInfo }.collect { row.moveIfCrossed() } }
                val edge = with(density) { 48.dp.toPx() }
                val speed = with(density) { 640.dp.toPx() }
                snapshotFlow { row.edgeScroll(edge).compareTo(0f) }.collectLatest { direction ->
                    if (direction == 0) return@collectLatest
                    var previous = withFrameMillis { it }
                    while (true) {
                        val now = withFrameMillis { it }
                        val distance = speed * row.edgeScroll(edge) * (now - previous).coerceIn(0L, 50L) / 1000f
                        previous = now
                        if (distance != 0f && list.scrollBy(distance) == 0f) break
                    }
                }
            }
            else -> {
                if (enabled) row.release.animateTo(0f, tween(AppMotion.Reorder))
                drag.clear()
            }
        }
    }
    return drag
}
