package com.gfs.iyango.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassShareCodecTest {

    @Test
    fun `encode and decode preserves full layer and dock parameters`() {
        val original = LiquidGlassPreset(
            id = "src",
            name = "夜景冰透",
            globalGlass = GlobalGlassSettings(
                navigation = GlobalGlassSettings.DefaultNavigation.copy(
                    blurDp = 8.5f,
                    edgeWidthDp = 1.25f,
                    edgeBlurDp = 1.5f,
                    depthEffect = true,
                    chromaticAberration = true,
                ),
                content = GlobalGlassSettings.DefaultContent.copy(
                    blurDp = 18f,
                    tintAlphaLight = 0.28f,
                    depthEffect = true,
                ),
                control = GlobalGlassSettings.DefaultControl.copy(
                    lensHeightDp = 26f,
                    chromaticAberration = true,
                ),
                overlay = GlobalGlassSettings.DefaultOverlay.copy(
                    edgeAlpha = 0.65f,
                ),
            ),
            dockGlass = DockGlassSettings(
                blurDp = 3.5f,
                edgeWidthDp = 1.1f,
                edgeBlurDp = 1.4f,
                indicatorChromaticAberration = false,
                indicatorShadowAlpha = 0.45f,
            ),
            dockSurfaceAlphaLight = 0.12f,
            dockSurfaceAlphaDark = 0.24f,
        )

        val token = LiquidGlassShareCodec.encode(original)
        assertTrue(token.startsWith(LiquidGlassShareCodec.PREFIX))

        val decoded = LiquidGlassShareCodec.decode(token, generatedId = "dst").getOrThrow()
        assertEquals("dst", decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.globalGlass, decoded.globalGlass)
        assertEquals(original.dockGlass, decoded.dockGlass)
        assertEquals(original.dockSurfaceAlphaLight, decoded.dockSurfaceAlphaLight, 1e-3f)
        assertEquals(original.dockSurfaceAlphaDark, decoded.dockSurfaceAlphaDark, 1e-3f)
    }

    @Test
    fun `decode extracts share code embedded in chat text`() {
        val builtIn = LiquidGlassPreset.BuiltIns.first { it.id == "crystal" }
        val token = LiquidGlassShareCodec.encode(builtIn)
        val chatMessage = "分享一套好看的玻璃参数给你～ 复制整段去 iYango 导入：$token 记得配暗色壁纸！"

        val decoded = LiquidGlassShareCodec.decode(chatMessage).getOrThrow()
        assertEquals(builtIn.name, decoded.name)
        assertEquals(builtIn.globalGlass, decoded.globalGlass)
        assertEquals(builtIn.dockGlass, decoded.dockGlass)
    }

    @Test
    fun `decode rejects malformed or truncated share codes gracefully`() {
        assertTrue(LiquidGlassShareCodec.decode("完全无关的一段文本").isFailure)
        assertTrue(LiquidGlassShareCodec.decode("IYANGO-GLASS-V1:abc").isFailure)
        assertTrue(LiquidGlassShareCodec.decode("IYANGO-GLASS-V1:not_valid_deflate_payload_12345").isFailure)
    }
}
