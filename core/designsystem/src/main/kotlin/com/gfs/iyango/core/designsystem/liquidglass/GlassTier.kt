package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.runtime.Composable
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported

/**
 * 玻璃能力档（统一规范 §8.1）。
 *
 * 使用 Backdrop 库的 Android 平台能力函数，与 LiquidBottomTabs 既有分层一致。
 * Backdrop 2.0.1 的 Android 实现按 SDK 版本判断（31/33），不检测 GPU 或测试栅格器。
 */
enum class GlassTier {
    /** 无 RenderEffect（Android 8–11 或能力缺失）：无模糊与折射，使用主题实体表面。 */
    BASIC,

    /** 有模糊、无 RuntimeShader 折射透镜（Android 12）。 */
    BLUR,

    /** 模糊与折射透镜均可用（Android 13+）。 */
    FULL,
}

/**
 * 当前作用域的玻璃能力档。
 *
 * 在组合中求值。测试环境即使返回 FULL，也不代表已验证设备上的模糊/折射。
 */
@Composable
fun rememberGlassTier(): GlassTier = when {
    !isRenderEffectSupported() -> GlassTier.BASIC
    !isRuntimeShaderSupported() -> GlassTier.BLUR
    else -> GlassTier.FULL
}
