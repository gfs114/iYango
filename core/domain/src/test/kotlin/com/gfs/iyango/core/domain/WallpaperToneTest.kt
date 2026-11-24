package com.gfs.iyango.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WallpaperTone] 纯 JVM 测试：亮度抽样与对比度适配遮罩建议。
 */
class WallpaperToneTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `纯色像素的亮度等于理论值`() {
        val black = WallpaperTone.averageLuminance(IntArray(16) { argb(0, 0, 0) })
        assertEquals(0f, black, 1e-4f)
        val white = WallpaperTone.averageLuminance(IntArray(16) { argb(255, 255, 255) })
        assertEquals(1f, white, 1e-4f)
    }

    @Test
    fun `空像素表返回未知标记`() {
        assertEquals(WallpaperTone.LUMINANCE_UNKNOWN, WallpaperTone.averageLuminance(IntArray(0)))
    }

    @Test
    fun `亮壁纸在浅色模式下遮罩建议趋近下限`() {
        val scrim = WallpaperTone.recommendedScrim(luminance = 0.9f, darkTheme = false)
        assertTrue("亮壁纸+浅色主题无需重遮罩: $scrim", scrim <= WallpaperTone.SCRIM_FLOOR + 1e-4f)
    }

    @Test
    fun `暗壁纸在浅色模式下给出更高遮罩`() {
        val scrim = WallpaperTone.recommendedScrim(luminance = 0.1f, darkTheme = false)
        assertTrue("暗壁纸+浅色主题应显著加重遮罩: $scrim", scrim > 0.2f)
        assertTrue(scrim <= WallpaperTone.SCRIM_CEILING)
    }

    @Test
    fun `亮壁纸在深色模式下遮罩建议接近上限`() {
        val scrim = WallpaperTone.recommendedScrim(luminance = 0.9f, darkTheme = true)
        assertTrue("亮壁纸+深色主题必须重遮罩: $scrim", scrim > 0.5f)
        assertTrue(scrim <= WallpaperTone.SCRIM_CEILING)
    }

    @Test
    fun `暗壁纸在深色模式下遮罩建议趋近下限`() {
        val scrim = WallpaperTone.recommendedScrim(luminance = 0.08f, darkTheme = true)
        assertTrue(scrim <= WallpaperTone.SCRIM_FLOOR + 1e-4f)
    }

    @Test
    fun `无效亮度回落默认值`() {
        assertEquals(WallpaperTone.DEFAULT_SCRIM, WallpaperTone.recommendedScrim(-1f, false))
        assertEquals(WallpaperTone.DEFAULT_SCRIM, WallpaperTone.recommendedScrim(Float.NaN, true))
    }

    @Test
    fun `端到端：纯黑壁纸在浅色主题下的建议满足下限约束`() {
        val luminance = WallpaperTone.averageLuminance(IntArray(64) { argb(10, 10, 10) })
        val scrim = WallpaperTone.recommendedScrim(luminance, darkTheme = false)
        assertTrue(scrim in WallpaperTone.SCRIM_FLOOR..WallpaperTone.SCRIM_CEILING)
    }
}
