package com.gfs.iyango.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DockGlassSettings] 纯 JVM 测试：默认值对齐与异常数值清洗。
 */
class DockGlassSettingsTest {

    @Test
    fun `defaults match project DockGlass constants and stay unchanged when sanitized`() {
        val defaults = DockGlassSettings()
        assertEquals(2f, defaults.blurDp, 0f)
        assertEquals(12f, defaults.lensWidthDp, 0f)
        assertEquals(14f, defaults.lensHeightDp, 0f)
        assertEquals(0.5f, defaults.edgeWidthDp, 0f)
        assertEquals(0.6f, defaults.edgeBlurDp, 0f)
        assertEquals(0.22f, defaults.edgeAlphaBase, 0f)
        assertEquals(0.25f, defaults.edgeAlphaHidden, 0f)
        assertEquals(0.35f, defaults.edgeAlphaIndicator, 0f)
        assertEquals(8f, defaults.indicatorLensWidthDp, 0f)
        assertEquals(10f, defaults.indicatorLensHeightDp, 0f)
        assertTrue(defaults.indicatorChromaticAberration)
        assertEquals(0.02f, defaults.indicatorSurfaceAlpha, 0f)
        assertEquals(0.02f, defaults.indicatorSurfacePressedAlpha, 0f)
        assertEquals(12f, defaults.indicatorShadowRadiusDp, 0f)
        assertEquals(0.3f, defaults.indicatorShadowAlpha, 0f)
        assertEquals(4f, defaults.indicatorInnerRadiusDp, 0f)
        assertEquals(0.4f, defaults.indicatorInnerAlpha, 0f)
        assertEquals(0.55f, defaults.legacyTier0AlphaLight, 0f)
        assertEquals(0.35f, defaults.legacyTier0AlphaDark, 0f)
        assertEquals(defaults, defaults.sanitized())
    }

    @Test
    fun `sanitized clamps non-finite and out-of-range values to safe bounds`() {
        val corrupted = DockGlassSettings(
            blurDp = Float.NaN,
            lensWidthDp = Float.POSITIVE_INFINITY,
            lensHeightDp = -5f,
            edgeWidthDp = 99f,
            edgeBlurDp = Float.NEGATIVE_INFINITY,
            edgeAlphaBase = 2.5f,
            edgeAlphaHidden = -0.3f,
            edgeAlphaIndicator = Float.NaN,
            indicatorLensWidthDp = 120f,
            indicatorLensHeightDp = -1f,
            indicatorChromaticAberration = false,
            indicatorSurfaceAlpha = 0.9f,
            indicatorSurfacePressedAlpha = -2f,
            indicatorShadowRadiusDp = 100f,
            indicatorShadowAlpha = Float.POSITIVE_INFINITY,
            indicatorInnerRadiusDp = 64f,
            indicatorInnerAlpha = -1f,
            legacyTier0AlphaLight = Float.NaN,
            legacyTier0AlphaDark = 2.5f,
        )
        val safe = corrupted.sanitized()
        assertEquals(2f, safe.blurDp, 0f)
        assertEquals(12f, safe.lensWidthDp, 0f)
        assertEquals(0f, safe.lensHeightDp, 0f)
        assertEquals(3f, safe.edgeWidthDp, 0f)
        assertEquals(0.6f, safe.edgeBlurDp, 0f)
        assertEquals(1f, safe.edgeAlphaBase, 0f)
        assertEquals(0f, safe.edgeAlphaHidden, 0f)
        assertEquals(0.35f, safe.edgeAlphaIndicator, 0f)
        assertEquals(32f, safe.indicatorLensWidthDp, 0f)
        assertEquals(0f, safe.indicatorLensHeightDp, 0f)
        assertFalse(safe.indicatorChromaticAberration)
        assertEquals(0.5f, safe.indicatorSurfaceAlpha, 0f)
        assertEquals(0f, safe.indicatorSurfacePressedAlpha, 0f)
        assertEquals(32f, safe.indicatorShadowRadiusDp, 0f)
        assertEquals(0.3f, safe.indicatorShadowAlpha, 0f)
        assertEquals(20f, safe.indicatorInnerRadiusDp, 0f)
        assertEquals(0f, safe.indicatorInnerAlpha, 0f)
        assertEquals(0.55f, safe.legacyTier0AlphaLight, 0f)
        assertEquals(1f, safe.legacyTier0AlphaDark, 0f)
    }
}
