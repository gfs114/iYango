package com.gfs.iyango.feature.main.coursedetail

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.gfs.iyango.core.designsystem.liquidglass.LiquidDialogSurface
import com.gfs.iyango.core.designsystem.liquidglass.applyWindowBlur
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.feature.main.blockInputWhenInactive
import kotlin.math.roundToInt

@Composable
internal fun detailTitleStyle() = MaterialTheme.typography.titleLarge.copy(
    fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
)

private data class DetailMorph(
    val source: CourseDetailSource,
    val start: CourseDetailBounds,
    val end: CourseDetailBounds,
    val origin: Offset,
)

/** The native modal owns focus and back throughout. Only its drawing layer spans the two windows. */
@Composable
internal fun CourseDetailMotionWindow(
    source: CourseDetailSource?,
    closing: Boolean,
    onRequestClose: () -> Unit,
    onReleased: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val enabled = LocalMotionEnabled.current
    val latestRelease by rememberUpdatedState(onReleased)
    val animation = remember { Animatable(if (enabled) 0f else 1f) }
    // Both card and title can be repositioned in one layout pass. Read their final bounds together.
    // The other cards only retain their coordinates until selected.
    var positionChanges by remember { mutableIntStateOf(0) }
    DisposableEffect(source, enabled) {
        if (enabled) source?.onPositionChanged = { positionChanges++ }
        onDispose { source?.onPositionChanged = null }
    }
    val viewport = if (enabled) source?.viewport else null
    val from = remember(source, enabled, closing, positionChanges, viewport) {
        if (enabled) source?.bounds() else null
    }
    var host by remember { mutableStateOf<Rect?>(null) }
    var card by remember { mutableStateOf<Rect?>(null) }
    var title by remember { mutableStateOf<Rect?>(null) }
    val measured = host != null && card != null
    LaunchedEffect(closing, enabled, measured) {
        if (enabled && !measured && !closing) return@LaunchedEffect
        val target = if (closing) 0f else 1f
        if (enabled) {
            animation.animateTo(target, tween(if (closing) AppMotion.DialogExit else AppMotion.DialogEnter))
        } else animation.snapTo(target)
        if (closing) latestRelease()
    }
    if (closing && !enabled) return

    val progress = { if (enabled) animation.value else 1f }
    val geometry = remember(source, from, host, card, title) {
        val origin = host?.topLeft
        val target = card
        val name = title
        val start = from
        if (source != null && start != null && origin != null && target != null && name != null)
            DetailMorph(source, start, CourseDetailBounds(target, name), origin) else null
    }
    val inTransition by remember { derivedStateOf { animation.value < 1f } }
    val moving = geometry.takeIf { enabled && inTransition }
    DisposableEffect(source, geometry != null, enabled) {
        if (source != null) source.hidden = geometry != null && enabled
        onDispose { source?.hidden = false }
    }
    Dialog(onDismissRequest = onRequestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val blurPx = with(LocalDensity.current) { 28.dp.toPx().toInt() }
        SideEffect {
            window?.setWindowAnimations(0)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (moving == null && !closing) applyWindowBlur(view, blurPx)
        }
        val focusManager = LocalFocusManager.current
        LaunchedEffect(closing) { if (closing) focusManager.clearFocus(force = true) }
        val scrim = MaterialTheme.colorScheme.scrim
        val surface = MaterialTheme.colorScheme.surface
        val destinationStyle = detailTitleStyle()
        val foreground = MaterialTheme.colorScheme.onSurface
        Box(Modifier.fillMaxSize().onGloballyPositioned { host = it.visibleScreenBounds() }) {
            Box(Modifier.fillMaxSize().testTag("courseDetailScrim")
                .drawBehind { drawRect(scrim.copy(alpha = .32f * progress())) }
                .pointerInput(onRequestClose) { detectTapGestures { onRequestClose() } })

            if (moving != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val p = progress()
                    val bounds = lerp(moving.start.card, moving.end.card, p).translate(-moving.origin)
                    val radius = androidx.compose.ui.unit.lerp(moving.source.radius, 16.dp, p).toPx()
                    drawRoundRect(lerp(moving.source.color, surface, p), bounds.topLeft, bounds.size, CornerRadius(radius))
                }
            }

            Box(Modifier.align(Alignment.Center).padding(horizontal = 24.dp, vertical = 8.dp)
                .widthIn(min = 280.dp, max = 360.dp)
                .onGloballyPositioned { card = it.visibleScreenBounds() }
                .then(if (closing) Modifier.clearAndSetSemantics {} else Modifier)
                .blockInputWhenInactive(!closing)
                .onPreviewKeyEvent { closing }
                .drawWithContent {
                    if (moving != null) {
                        val bounds = lerp(moving.start.card, moving.end.card, progress()).translate(-moving.end.card.topLeft)
                        clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) { this@drawWithContent.drawContent() }
                    } else drawContent()
                }
                .graphicsLayer {
                    val p = progress()
                    alpha = if (moving != null) ((p - .45f) / .55f).coerceIn(0f, 1f) else p
                    scaleX = if (from != null && (geometry != null || !measured)) 1f else .96f + .04f * p
                    scaleY = scaleX
                },
            ) {
                // Surface intercepts taps on its empty areas so only the scrim outside closes the window.
                val innerContent: @Composable () -> Unit = {
                    content(Modifier.onGloballyPositioned { title = it.visibleScreenBounds() }
                        .graphicsLayer { alpha = if (moving != null) 0f else 1f })
                }
                if (moving != null) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent,
                        contentColor = foreground) {
                        innerContent()
                    }
                } else {
                    LiquidDialogSurface(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) },
                    ) {
                        innerContent()
                    }
                }
            }
            if (moving != null) {
                MovingCourseTitle(moving.source, moving.start.title.translate(-moving.origin),
                    moving.end.title.translate(-moving.origin), destinationStyle, foreground, progress)
            }
        }
    }
}

/** Only the short shared title reflows as its measured bounds change. Body text keeps its final layout. */
@Composable
private fun BoxScope.MovingCourseTitle(
    source: CourseDetailSource,
    start: Rect,
    end: Rect,
    endStyle: TextStyle,
    endColor: Color,
    progress: () -> Float,
) {
    val p = progress()
    val frame = lerp(start, end, p)
    Text(
        text = source.course?.name.orEmpty(),
        modifier = Modifier.align(AbsoluteAlignment.TopLeft)
            .absoluteOffset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
            .width(with(LocalDensity.current) { frame.width.coerceAtLeast(1f).toDp() })
            .testTag("courseDetailMovingTitle").clearAndSetSemantics {},
        style = lerp(source.titleStyle, endStyle, p),
        color = lerp(source.titleColor, endColor, p),
        maxLines = (source.titleLines + (2 - source.titleLines) * p).roundToInt().coerceAtLeast(1),
        overflow = TextOverflow.Ellipsis,
    )
}
