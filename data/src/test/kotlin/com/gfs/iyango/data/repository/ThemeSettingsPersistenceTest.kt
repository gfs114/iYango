package com.gfs.iyango.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.gfs.iyango.core.domain.DockGlassSettings
import com.gfs.iyango.core.domain.GlassLayerSettings
import com.gfs.iyango.core.domain.GlobalGlassSettings
import com.gfs.iyango.core.domain.LiquidGlassPreset
import com.gfs.iyango.core.domain.ThemeMode
import com.gfs.iyango.core.domain.ThemeSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeSettingsPersistenceTest {

    @Test
    fun `dock glass settings round-trip through DataStore`() = runBlocking {
        val file = File.createTempFile(
            "theme-dock-glass",
            ".preferences_pb",
            RuntimeEnvironment.getApplication().cacheDir,
        )
        suspend fun withThemeStore(block: suspend (DataStoreThemeSettings) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                block(DataStoreThemeSettings(PreferenceDataStoreFactory.create(scope = scope) { file }))
            } finally {
                scope.cancel()
                scope.coroutineContext[Job]!!.join()
            }
        }
        val expected = ThemeSettings(
            accentHex = "#336699",
            tintBackground = true,
            themeMode = ThemeMode.DARK,
            dockSurfaceAlphaLight = 0.08f,
            dockSurfaceAlphaDark = 0.24f,
            dockGlass = DockGlassSettings(
                blurDp = 9.5f,
                lensWidthDp = 18f,
                lensHeightDp = 20f,
                edgeWidthDp = 1.2f,
                edgeBlurDp = 1.5f,
                edgeAlphaBase = 0.3f,
                edgeAlphaHidden = 0.4f,
                edgeAlphaIndicator = 0.5f,
                indicatorLensWidthDp = 14f,
                indicatorLensHeightDp = 16f,
                indicatorChromaticAberration = false,
                indicatorSurfaceAlpha = 0.1f,
                indicatorSurfacePressedAlpha = 0.12f,
                indicatorShadowRadiusDp = 18f,
                indicatorShadowAlpha = 0.45f,
                indicatorInnerRadiusDp = 8f,
                indicatorInnerAlpha = 0.55f,
                legacyTier0AlphaLight = 0.65f,
                legacyTier0AlphaDark = 0.45f,
            ),
            globalGlass = GlobalGlassSettings.ClearPreset.copy(
                content = GlassLayerSettings(
                    blurDp = 8f,
                    lensWidthDp = 4f,
                    lensHeightDp = 6f,
                    edgeWidthDp = 0.4f,
                    edgeBlurDp = 0.5f,
                    edgeAlpha = 0.15f,
                    tintAlphaLight = 0.25f,
                    tintAlphaDark = 0.18f,
                    tier0AlphaLight = 0.40f,
                    tier0AlphaDark = 0.30f,
                ),
            ),
            savedGlassPresets = listOf(
                LiquidGlassPreset(
                    id = "custom_1",
                    name = "我的清透",
                    globalGlass = GlobalGlassSettings.ClearPreset,
                    dockSurfaceAlphaLight = 0.12f,
                ),
            ),
        )
        try {
            withThemeStore { store ->
                assertEquals(ThemeSettings(), store.settings.first())
                store.update(expected)
            }
            withThemeStore { store ->
                assertEquals(expected, store.settings.first())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `backup document preserves dock glass fields and falls back on legacy json`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val legacyTheme = json.decodeFromString(SettingsBackupDocument.serializer(), """{"theme":{}}""").theme.toModel()
        assertEquals(ThemeSettings(), legacyTheme)

        val custom = DockGlassSettings(
            blurDp = 11f,
            lensWidthDp = 22f,
            indicatorChromaticAberration = false,
            indicatorShadowAlpha = 0.55f,
            legacyTier0AlphaLight = 0.72f,
            legacyTier0AlphaDark = 0.48f,
        )
        val doc = SettingsBackupDocument(
            theme = ThemeDto(
                accentHex = "#112233",
                dockSurfaceAlphaLight = 0.12f,
                dockGlass = DockGlassDto.fromModel(custom),
            ),
        )
        val encoded = json.encodeToString(SettingsBackupDocument.serializer(), doc)
        val restored = json.decodeFromString(SettingsBackupDocument.serializer(), encoded).theme.toModel()
        assertEquals("#112233", restored.accentHex)
        assertEquals(0.12f, restored.dockSurfaceAlphaLight, 0f)
        assertEquals(custom, restored.dockGlass)
        assertEquals(GlobalGlassSettings(), restored.globalGlass)

        val zeroDoc = SettingsBackupDocument(
            theme = ThemeDto(
                globalGlass = GlobalGlassDto.fromModel(GlobalGlassSettings.ClearPreset),
                savedGlassPresets = listOf(
                    LiquidGlassPresetDto.fromModel(
                        LiquidGlassPreset(id = "backup_1", name = "导出预设", globalGlass = GlobalGlassSettings.ClearPreset),
                    ),
                ),
            ),
        )
        val zeroRestored = json.decodeFromString(
            SettingsBackupDocument.serializer(),
            json.encodeToString(SettingsBackupDocument.serializer(), zeroDoc),
        ).theme.toModel()
        assertEquals(GlobalGlassSettings.ClearPreset, zeroRestored.globalGlass)
        assertEquals(1, zeroRestored.savedGlassPresets.size)
        assertEquals("导出预设", zeroRestored.savedGlassPresets.first().name)
    }
}
