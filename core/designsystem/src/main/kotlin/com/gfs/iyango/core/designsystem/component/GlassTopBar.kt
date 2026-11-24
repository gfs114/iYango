package com.gfs.iyango.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassBackdrop
import com.gfs.iyango.core.designsystem.liquidglass.applyLiquidPressLayer
import com.gfs.iyango.core.designsystem.liquidglass.rememberTopBarHighlight
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

private val TopBarShape = RoundedRectangle(0.dp)
private val TitleClickShape = RoundedRectangle(12.dp)
private val ControlCapsuleShape = Capsule()

/**
 * Fixed full-width liquid-glass page header (`RoundedRectangle(0.dp)`).
 *
 * The status-bar inset and the 60dp header slot form a single edge-to-edge liquid-glass sheet
 * with color controls, blur, and lens refraction. Child buttons sample the bar's `exportedBackdrop`
 * ("Glass on Glass") rather than piercing through to the raw background.
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets.safeDrawing.only(
        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
    ),
) {
    val activeBackdrop = LocalTopBarBackdrop.current ?: LocalGlassBackdrop.current
    val exportedTopBarBackdrop = rememberLayerBackdrop()
    val childBackdrop = if (activeBackdrop != null) exportedTopBarBackdrop else null

    CompositionLocalProvider(
        LocalGlassBackdrop provides activeBackdrop,
    ) {
        GlassSurface(
            modifier = modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // Claim the fixed header's empty/inset/title area in hit testing so occluded
                    // body items cannot receive taps. Child controls consume their own taps first.
                    detectTapGestures(onTap = {})
                },
            shape = TopBarShape,
            material = GlassMaterial.NAVIGATION,
            allowRefraction = true,
            includeShadow = false,
            exportedBackdrop = exportedTopBarBackdrop,
        ) {
            CompositionLocalProvider(LocalGlassBackdrop provides childBackdrop) {
                HeaderRow(
                    title = title,
                    subtitle = subtitle,
                    navigationIcon = navigationIcon,
                    actions = actions,
                    onTitleClick = onTitleClick,
                    modifier = Modifier
                        .graphicsLayer()
                        .windowInsetsPadding(windowInsets),
                    contentPadding = PaddingValues(
                        start = if (navigationIcon == null) 16.dp else 8.dp,
                        end = 8.dp,
                    ),
                )
            }
        }
    }
}

/**
 * Sheet/Dialog-local heading: inherits its container material and adds no system inset.
 * Use zero [contentPadding] when the parent already supplies the body content's padding.
 */
@Composable
fun GlassOverlayHeader(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    HeaderRow(title, null, navigationIcon, actions, onTitleClick, modifier, contentPadding)
}

@Composable
private fun HeaderRow(
    title: String,
    subtitle: String?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    onTitleClick: (() -> Unit)?,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val titleHighlight = if (onTitleClick != null) {
        rememberTopBarHighlight()
    } else {
        null
    }
    val titlePressLayerBlock = remember(titleHighlight) {
        if (titleHighlight != null) {
            val block: GraphicsLayerScope.() -> Unit = { applyLiquidPressLayer(titleHighlight) }
            block
        } else {
            null
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigationIcon != null) {
            navigationIcon()
            Spacer(Modifier.width(8.dp))
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onTitleClick != null && titleHighlight != null && titlePressLayerBlock != null) {
                        Modifier
                            .heightIn(min = 48.dp)
                            .graphicsLayer(titlePressLayerBlock)
                            .clip(TitleClickShape)
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                role = Role.Button,
                                onClick = onTitleClick,
                            )
                            .then(titleHighlight.modifier)
                            .then(titleHighlight.gestureModifier)
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onTitleClick != null) {
                val color = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.padding(start = 4.dp).size(18.dp)) {
                    val stroke = 2.dp.toPx()
                    drawLine(color, Offset(size.width * .25f, size.height * .4f), Offset(size.width * .5f, size.height * .65f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(size.width * .5f, size.height * .65f), Offset(size.width * .75f, size.height * .4f), stroke, StrokeCap.Round)
                }
            }
        }
        Row(
            modifier = Modifier.padding(start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** A 40dp liquid-glass control surface inside an explicit, non-overlapping 48dp touch slot. */
@Composable
fun GlassTopBarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val highlight = rememberTopBarHighlight()
    val pressLayerBlock = remember(highlight) {
        val block: GraphicsLayerScope.() -> Unit = { applyLiquidPressLayer(highlight) }
        block
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .then(if (enabled) highlight.gestureModifier else Modifier)
            .clip(ControlCapsuleShape),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier.size(40.dp),
            shape = ControlCapsuleShape,
            material = GlassMaterial.CONTROL,
            includeShadow = false,
            layerBlock = if (enabled) pressLayerBlock else null,
        ) {
            val color = MaterialTheme.colorScheme.onSurface
            CompositionLocalProvider(LocalContentColor provides color.copy(alpha = if (enabled) 1f else .38f)) {
                Box(
                    Modifier
                        .size(40.dp)
                        .then(if (enabled) highlight.modifier else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Explicit tinted liquid-glass submit action (`BlendMode.Hue` + 0.75f accent tint with white text).
 * Loading blocks duplicate clicks while preserving the label; disabled falls back to neutral glass.
 */
@Composable
fun GlassTopBarTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interactive = enabled && !loading
    val highlight = rememberTopBarHighlight()
    val pressLayerBlock = remember(highlight) {
        val block: GraphicsLayerScope.() -> Unit = { applyLiquidPressLayer(highlight) }
        block
    }
    val activeVisual = interactive || loading
    val tintColor = if (activeVisual) MaterialTheme.colorScheme.primary else Color.Unspecified
    val contentColor = if (activeVisual) {
        Color.White.copy(alpha = if (loading) 0.9f else 1f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                enabled = interactive,
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .then(if (interactive) highlight.gestureModifier else Modifier)
            .clip(ControlCapsuleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            shape = ControlCapsuleShape,
            material = GlassMaterial.CONTROL,
            includeShadow = false,
            tintColor = tintColor,
            layerBlock = if (interactive) pressLayerBlock else null,
        ) {
            Row(
                Modifier
                    .then(if (interactive) highlight.modifier else Modifier)
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = contentColor, strokeWidth = 2.dp)
                Text(
                    text = text,
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
