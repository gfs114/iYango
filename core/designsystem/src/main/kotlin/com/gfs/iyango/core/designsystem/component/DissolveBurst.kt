package com.gfs.iyango.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import kotlin.math.cos
import kotlin.math.sin

/** 单颗消散粒子的几何与时间参数。 */
data class DissolveParticleSpec(
    val angleDeg: Float,
    val distanceDp: Float,
    val radiusDp: Float,
    val delayMs: Int,
)

/** 生成确定性的径向粒子参数列表，用于删除卡片、配色收藏或清空日志时的粒子爆散。 */
fun defaultDissolveParticles(count: Int = 24): List<DissolveParticleSpec> {
    val safeCount = count.coerceIn(1, 64)
    val step = 360f / safeCount
    return List(safeCount) { i ->
        DissolveParticleSpec(
            angleDeg = i * step + (i % 3) * 7f,
            distanceDp = 36f + (i % 5) * 10f,
            radiusDp = 2.2f + (i % 4) * 0.7f,
            delayMs = (i % 6) * 24,
        )
    }
}

/**
 * Canvas 粒子消散覆盖层：当 [trigger] 自增为 >0 时，从容器中心爆开 [particleCount] 颗粒子，
 * 并在 [durationMs] 结束后回调 [onFinished]。
 *
 * 不会拦截底层指针事件；当 [LocalMotionEnabled] 关闭时立即执行 [onFinished]。
 */
@Composable
fun DissolveBurst(
    trigger: Int,
    color: Color = LocalBrand.current.accent,
    particleCount: Int = 24,
    durationMs: Int = 560,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
) {
    if (trigger <= 0) return
    val motionEnabled = LocalMotionEnabled.current
    val latestOnFinished by rememberUpdatedState(onFinished)
    val specs = remember(particleCount) { defaultDissolveParticles(particleCount) }
    var active by remember { mutableStateOf(false) }
    val clock = remember { Animatable(0f) }

    LaunchedEffect(trigger, motionEnabled) {
        if (!motionEnabled) {
            active = false
            latestOnFinished()
            return@LaunchedEffect
        }
        active = true
        clock.snapTo(0f)
        clock.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMs.coerceAtLeast(120), easing = LinearEasing),
        )
        active = false
        latestOnFinished()
    }

    if (active) {
        Canvas(
            modifier = modifier
                .fillMaxSize()
                .testTag("dissolve_burst_canvas"),
        ) {
            val safeDuration = durationMs.coerceAtLeast(120).toFloat()
            val elapsedMs = clock.value * safeDuration
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val gravityPx = 18.dp.toPx()

            for (spec in specs) {
                val activeWindow = (safeDuration - spec.delayMs).coerceAtLeast(120f)
                val localT = ((elapsedMs - spec.delayMs) / activeWindow).coerceIn(0f, 1f)
                if (localT <= 0f) continue
                val inv = 1f - localT
                val eased = 1f - inv * inv * inv
                val alpha = (1f - localT * 1.15f).coerceIn(0f, 1f)
                if (alpha <= 0.01f) continue

                val rad = Math.toRadians(spec.angleDeg.toDouble()).toFloat()
                val distPx = spec.distanceDp.dp.toPx() * eased
                val x = centerX + cos(rad) * distPx
                val y = centerY + sin(rad) * distPx + gravityPx * eased * eased
                val r = spec.radiusDp.dp.toPx() * (1f - 0.55f * eased)
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = r,
                    center = Offset(x, y),
                )
            }
        }
    }
}
