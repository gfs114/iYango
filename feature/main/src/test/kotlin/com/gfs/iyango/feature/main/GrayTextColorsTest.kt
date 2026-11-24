package com.gfs.iyango.feature.main

import androidx.compose.ui.graphics.Color
import com.gfs.iyango.core.model.GrayTextMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GrayTextColorsTest {

    @Test fun `resolveGrayTextColors covers all 5 modes across light and dark themes`() {
        assertEquals(null to null, resolveGrayTextColors(GrayTextMode.DEFAULT, "", darkTheme = false))
        assertEquals(null to null, resolveGrayTextColors(GrayTextMode.DEFAULT, "", darkTheme = true))

        val (lightSec, lightTer) = resolveGrayTextColors(GrayTextMode.AUTO_ENHANCED, "", darkTheme = false)
        assertEquals(Color(0xFF3A434D), lightSec)
        assertEquals(Color(0xFF4E5863), lightTer)

        val (darkSec, darkTer) = resolveGrayTextColors(GrayTextMode.AUTO_ENHANCED, "", darkTheme = true)
        assertEquals(Color(0xFFC8D0D8), darkSec)
        assertEquals(Color(0xFFA8B2BD), darkTer)

        assertEquals(
            Color(0xFF111418) to Color(0xFF262C33),
            resolveGrayTextColors(GrayTextMode.HIGH_CONTRAST_DARK, "", darkTheme = false),
        )
        assertEquals(
            Color(0xFFF5F7FA) to Color(0xFFDCE2E8),
            resolveGrayTextColors(GrayTextMode.HIGH_CONTRAST_LIGHT, "", darkTheme = true),
        )

        val (customSec, customTer) = resolveGrayTextColors(GrayTextMode.CUSTOM, "3a434d", darkTheme = false)
        assertEquals(Color(0xFF3A434D), customSec)
        assertNotNull(customTer)
        assertEquals(0.85f, customTer!!.alpha, 0.01f)

        val (invalidSec, invalidTer) = resolveGrayTextColors(GrayTextMode.CUSTOM, "not-hex", darkTheme = false)
        assertNull(invalidSec)
        assertNull(invalidTer)
    }
}
