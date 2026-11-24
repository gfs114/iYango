package com.gfs.iyango.feature.main.coursedetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.gfs.iyango.core.model.Course

/** The click which opens the detail consumes its source. Programmatic requests have no source. */
internal class CourseDetailSources {
    var clicked: CourseDetailSource? = null
    var viewport by mutableStateOf<Rect?>(null)

    fun takeClicked(): CourseDetailSource? = clicked.also { clicked = null }
}

internal val LocalCourseDetailSources = compositionLocalOf<CourseDetailSources?> { null }

/** These pages scroll underneath fixed glass chrome which is outside their normal scroll clip. */
@Composable
internal fun Modifier.courseDetailViewport(topInset: Dp, bottomInset: Dp): Modifier {
    val sources = LocalCourseDetailSources.current ?: return this
    val density = LocalDensity.current
    var windowBounds by remember(sources) { mutableStateOf<Rect?>(null) }
    val viewport = remember(windowBounds, topInset, bottomInset, density) {
        val rect = windowBounds
        rect?.copy(
            top = rect.top + with(density) { topInset.toPx() },
            bottom = rect.bottom - with(density) { bottomInset.toPx() },
        )
    }
    SideEffect { sources.viewport = viewport }
    return onGloballyPositioned { windowBounds = it.visibleScreenBounds() }
}

internal data class CourseDetailBounds(val card: Rect, val title: Rect)

class CourseDetailSource internal constructor(
    private val sources: CourseDetailSources?,
    internal val course: Course?,
    internal val occurrenceId: String?,
    internal val color: Color,
    internal val titleColor: Color,
    internal val titleStyle: TextStyle,
    internal val radius: Dp,
    internal val titleLines: Int,
) {
    private var card: LayoutCoordinates? = null
    private var title: LayoutCoordinates? = null
    internal val viewport: Rect? get() = sources?.viewport
    internal var onPositionChanged: (() -> Unit)? = null
    internal var hidden by mutableStateOf(false)

    internal val cardModifier = Modifier.onGloballyPositioned {
        card = it
        onPositionChanged?.invoke()
    }.drawWithContent { if (!hidden) drawContent() }
    internal val titleModifier = Modifier.onGloballyPositioned {
        title = it
        onPositionChanged?.invoke()
    }

    internal fun select() { sources?.clicked = this }

    internal fun bounds(): CourseDetailBounds? {
        val coordinates = card ?: return null
        val visible = coordinates.visibleScreenBounds() ?: return null
        val topLeft = coordinates.localToScreen(Offset.Zero)
        val size = coordinates.size
        val bottomRight = coordinates.localToScreen(Offset(size.width.toFloat(), size.height.toFloat()))
        val viewport = sources?.viewport?.intersect(visible) ?: visible
        if (topLeft.x < viewport.left - 1f || topLeft.y < viewport.top - 1f ||
            bottomRight.x > viewport.right + 1f || bottomRight.y > viewport.bottom + 1f) return null
        val name = title?.visibleScreenBounds() ?: return null
        if (!visible.overlaps(name)) return null
        return CourseDetailBounds(visible, name)
    }

    internal fun detach() {
        card = null
        title = null
        onPositionChanged?.invoke()
        if (sources?.clicked === this) sources.clicked = null
    }
}

/** Window-relative clipping includes the scroll viewport. Screen space also spans Dialog windows. */
internal fun LayoutCoordinates.visibleScreenBounds(): Rect? {
    if (!isAttached) return null
    val visible = boundsInWindow()
    if (visible.isEmpty) return null
    val origin = localToScreen(Offset.Zero) - localToWindow(Offset.Zero)
    return if (origin.x.isFinite() && origin.y.isFinite()) visible.translate(origin) else null
}

@Composable
internal fun rememberCourseDetailSource(
    course: Course?,
    occurrenceId: String?,
    color: Color,
    titleColor: Color,
    titleStyle: TextStyle,
    radius: Dp,
    titleLines: Int = 2,
    slotKey: Any? = null,
): CourseDetailSource {
    val sources = LocalCourseDetailSources.current
    val source = remember(sources, course, occurrenceId, color, titleColor, titleStyle, radius, titleLines, slotKey) {
        CourseDetailSource(sources, course, occurrenceId, color, titleColor, titleStyle, radius, titleLines)
    }
    // A reused lazy item or grid slot can retain the very same LayoutCoordinates for another course.
    DisposableEffect(source) { onDispose(source::detach) }
    return source
}
