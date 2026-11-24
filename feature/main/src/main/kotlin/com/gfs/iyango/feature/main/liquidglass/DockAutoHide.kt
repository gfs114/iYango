package com.gfs.iyango.feature.main.liquidglass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * 底部 Dock 的滚动感知自动收起状态（上一版：阈值触发的滑出/滑入）。
 *
 * 内容向前滚动（手指上滑、查看后续内容）→ 累计超过阈值后收起；
 * 向回滚动（手指下滑）→ 立即弹出。切 Tab / 页面恢复时通过 [expand] 复位。
 * 动画由调用方的 AnimatedVisibility（滑出/滑入 + 淡入淡出）承担。
 */
class DockAutoHideState {

    /** 是否展开（true = Dock 可见）。 */
    var expanded by mutableStateOf(true)
        private set

    /** 临时抑制底部栏展示的持有者令牌（如长按拖动/暂存调课期间）。 */
    private var suppressors by mutableStateOf<Set<Any>>(emptySet())

    /** 是否有外部交互正在抑制底部栏显示。 */
    val isSuppressed: Boolean get() = suppressors.isNotEmpty()

    /** 当前是否允许显示底部栏（未被滚动收起且未被交互抑制）。 */
    val visible: Boolean get() = expanded && !isSuppressed

    /** 设置或解除指定令牌对底部栏显示的抑制。 */
    fun setSuppressed(token: Any, suppressed: Boolean) {
        suppressors = if (suppressed) suppressors + token else suppressors - token
    }

    /** 触发收起所需的累计滚动距离（px），由主骨架按密度设置。 */
    private var thresholdPx = 48f

    /** 收起方向的累计量（仅在 expanded 时累加）。 */
    private var accumulated = 0f

    /** 由调用方按当前密度设置阈值（默认 48px ≈ 16dp）。 */
    fun setThresholdPx(px: Float) {
        if (px > 0f) thresholdPx = px
    }

    /**
     * 接收滚动增量的纵向分量（NestedScroll onPreScroll 的 available.y）。
     * 负值 = 内容向前滚动（应收起）；正值 = 向回滚动（应弹出）。
     */
    fun onScroll(availableY: Float) {
        if (isSuppressed) return
        if (availableY < 0f) {
            if (!expanded) return
            accumulated -= availableY
            if (accumulated >= thresholdPx) {
                expanded = false
                accumulated = 0f
            }
        } else if (availableY > 0f) {
            accumulated = 0f
            if (!expanded) expanded = true
        }
    }

    /** 强制展开（切 Tab、返回主页面、恢复状态时调用）。 */
    fun expand() {
        expanded = true
        accumulated = 0f
    }
}

/** 页面滚动容器通过它把纵向滚动上报给 Dock（未提供时页面行为不变）。 */
val LocalDockAutoHide = staticCompositionLocalOf<DockAutoHideState?> { null }

/**
 * 把滚动容器的纵向滚动接入 Dock 自动收起；不消费滚动事件（返回 Offset.Zero）。
 */
fun Modifier.dockAutoHideScroll(state: DockAutoHideState): Modifier = this.nestedScroll(
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            state.onScroll(available.y)
            return Offset.Zero
        }
    },
)

/** 供页面使用：存在控制器时附加滚动监听，否则原样返回。 */
@Composable
fun Modifier.dockAutoHideAware(): Modifier =
    LocalDockAutoHide.current?.let { dockAutoHideScroll(it) } ?: this
