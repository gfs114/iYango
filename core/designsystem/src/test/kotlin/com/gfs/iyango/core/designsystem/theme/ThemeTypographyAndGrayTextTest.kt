package com.gfs.iyango.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeTypographyAndGrayTextTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun `buildIYangoTypography applies fontFamily and clamped scale factor`() {
        assertSame(IYangoTypography, buildIYangoTypography())

        val scaled = buildIYangoTypography(fontFamily = FontFamily.Serif, fontScaleFactor = 1.2f)
        assertEquals(FontFamily.Serif, scaled.titleLarge.fontFamily)
        assertEquals(FontFamily.Serif, scaled.bodyMedium.fontFamily)
        assertEquals(18.sp.value, scaled.bodyMedium.fontSize.value, 0.01f)
        assertEquals(26.4f, scaled.bodyMedium.lineHeight.value, 0.05f)

        val upperClamped = buildIYangoTypography(fontScaleFactor = 2.5f)
        assertEquals(15f * 1.30f, upperClamped.bodyMedium.fontSize.value, 0.01f)

        val lowerClamped = buildIYangoTypography(fontScaleFactor = 0.2f)
        assertEquals(15f * 0.85f, lowerClamped.bodyMedium.fontSize.value, 0.01f)
    }

    @Test fun `IYangoTheme propagates gray text overrides and typography`() {
        var actualSecondary: Color? = null
        var actualTertiary: Color? = null
        var actualBodyFont: FontFamily? = null
        compose.setContent {
            IYangoTheme(
                darkTheme = false,
                fontFamily = FontFamily.Monospace,
                fontScaleFactor = 1.1f,
                secondaryTextColorOverride = Color(0xFF112233),
                tertiaryTextColorOverride = Color(0xFF445566),
            ) {
                actualSecondary = MaterialTheme.colorScheme.onSurfaceVariant
                actualTertiary = LocalTertiaryTextColor.current
                actualBodyFont = MaterialTheme.typography.bodyMedium.fontFamily
            }
        }
        compose.runOnIdle {
            assertEquals(Color(0xFF112233), actualSecondary)
            assertEquals(Color(0xFF445566), actualTertiary)
            assertEquals(FontFamily.Monospace, actualBodyFont)
        }
    }
}
