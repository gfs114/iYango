package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.RoundedRectangle

private val DefaultFieldShape: Shape = RoundedRectangle(14.dp)

@Composable
private fun LiquidFieldBackdropSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val tier = rememberGlassTier()
    val isLight = isGlassLightTheme()
    val hasWallpaper = LocalGlassHasWallpaper.current
    val spec = LocalGlassPalette.current.control
    val alpha = when (tier) {
        GlassTier.BASIC -> if (isLight) spec.tier0AlphaLight else spec.tier0AlphaDark
        GlassTier.BLUR -> ((if (isLight) spec.tintAlphaLight else spec.tintAlphaDark) * GlassTokens.BLUR_TIER_TINT_GAIN)
            .coerceAtMost(GlassTokens.BLUR_TIER_TINT_MAX)
        GlassTier.FULL -> if (isLight) spec.tintAlphaLight else spec.tintAlphaDark
    }
    val baseColor = (if (isLight) GlassTokens.tintLight else GlassTokens.tintDark).copy(alpha = alpha)

    if (tier == GlassTier.BASIC || !hasWallpaper || spec.blurDp <= 0.01f) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = if (baseColor.alpha > 0.004f) baseColor else Color.Transparent,
        ) {}
    } else {
        val backdrop = rememberGlassFallbackBackdrop()
        Box(
            modifier = modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    if (spec.blurDp > 0.01f) {
                        blur(spec.blurDp.dp.toPx())
                    }
                },
                highlight = {
                    if (spec.edgeAlpha > 0.004f && spec.edgeWidthDp > 0.01f) {
                        Highlight(
                            width = spec.edgeWidthDp.dp,
                            blurRadius = spec.edgeBlurDp.dp,
                            alpha = spec.edgeAlpha * 0.75f,
                            style = HighlightStyle.Default,
                        )
                    } else {
                        null
                    }
                },
                onDrawSurface = {
                    if (baseColor.alpha > 0.004f) {
                        drawRect(baseColor)
                    }
                },
            ),
        )
    }
}

/**
 * 液态玻璃描边输入框：
 * - 底座采用无透镜畸变的磨砂玻璃采样（文字、光标、选区与错误状态绝不折射变形，§1.3）；
 * - 直接把调用方的 `modifier`（含 `testTag`、`weight` 等）绑定在 `BasicTextField` 上，
 *   完整保留焦点、IME、密码遮蔽与 `SemanticsActions.SetText` 语义。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = DefaultFieldShape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
    ),
) {
    val activeInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val resolvedTextColor = textStyle.color.takeOrElse { MaterialTheme.colorScheme.onSurface }
    val mergedTextStyle = textStyle.merge(TextStyle(color = resolvedTextColor))
    val selectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
    )

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = value,
            modifier = modifier.defaultMinSize(
                minWidth = OutlinedTextFieldDefaults.MinWidth,
                minHeight = OutlinedTextFieldDefaults.MinHeight,
            ),
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = mergedTextStyle,
            cursorBrush = SolidColor(
                if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = activeInteractionSource,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            decorationBox = @Composable { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    visualTransformation = visualTransformation,
                    innerTextField = innerTextField,
                    placeholder = placeholder,
                    label = label,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    prefix = prefix,
                    suffix = suffix,
                    supportingText = supportingText,
                    singleLine = singleLine,
                    enabled = enabled,
                    isError = isError,
                    interactionSource = activeInteractionSource,
                    colors = colors,
                    contentPadding = OutlinedTextFieldDefaults.contentPadding(),
                    container = {
                        Box {
                            LiquidFieldBackdropSurface(
                                shape = shape,
                                modifier = Modifier.matchParentSize(),
                            )
                            OutlinedTextFieldDefaults.Container(
                                enabled = enabled,
                                isError = isError,
                                interactionSource = activeInteractionSource,
                                colors = colors,
                                shape = shape,
                            )
                        }
                    },
                )
            },
        )
    }
}

/**
 * 液态玻璃填充式输入框（替代 Material 3 TextField）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = DefaultFieldShape,
    colors: TextFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
        unfocusedIndicatorColor = Color.Transparent,
    ),
) {
    val activeInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val resolvedTextColor = textStyle.color.takeOrElse { MaterialTheme.colorScheme.onSurface }
    val mergedTextStyle = textStyle.merge(TextStyle(color = resolvedTextColor))
    val selectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
    )

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = value,
            modifier = modifier.defaultMinSize(
                minWidth = TextFieldDefaults.MinWidth,
                minHeight = TextFieldDefaults.MinHeight,
            ),
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = mergedTextStyle,
            cursorBrush = SolidColor(
                if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = activeInteractionSource,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            decorationBox = @Composable { innerTextField ->
                TextFieldDefaults.DecorationBox(
                    value = value,
                    visualTransformation = visualTransformation,
                    innerTextField = innerTextField,
                    placeholder = placeholder,
                    label = label,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    prefix = prefix,
                    suffix = suffix,
                    supportingText = supportingText,
                    shape = shape,
                    singleLine = singleLine,
                    enabled = enabled,
                    isError = isError,
                    interactionSource = activeInteractionSource,
                    colors = colors,
                    contentPadding = if (label == null) {
                        TextFieldDefaults.contentPaddingWithoutLabel()
                    } else {
                        TextFieldDefaults.contentPaddingWithLabel()
                    },
                    container = {
                        Box {
                            LiquidFieldBackdropSurface(
                                shape = shape,
                                modifier = Modifier.matchParentSize(),
                            )
                            TextFieldDefaults.Container(
                                enabled = enabled,
                                isError = isError,
                                interactionSource = activeInteractionSource,
                                colors = colors,
                                shape = shape,
                            )
                        }
                    },
                )
            },
        )
    }
}
