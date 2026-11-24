package com.gfs.iyango.data.repository

import com.gfs.iyango.core.model.*
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomePreferencesPersistenceTest {
    @Test fun `preferences reload after datastore scope is stopped and reopened from disk`() = runBlocking {
        val file = java.io.File.createTempFile("home-restart", ".preferences_pb", RuntimeEnvironment.getApplication().cacheDir)
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val home = HomePreferences(hiddenModules = HomeModule.entries.map { it.id }.toSet(), quickActions = emptyList())
        DataStoreAppSettings(firstStore).updateAppPreferences(AppPreferences(home = home, wallpaperBlur = 0.6f))
        firstScope.cancel(); firstScope.coroutineContext[Job]!!.join()
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = DataStoreAppSettings(PreferenceDataStoreFactory.create(scope = secondScope) { file }).appPreferences.first()
            assertEquals(home, reopened.home)
            assertEquals(0.6f, reopened.wallpaperBlur)
        } finally { secondScope.cancel(); secondScope.coroutineContext[Job]!!.join(); file.delete() }
    }
    @Test fun `damaged or future homepage locally falls back while wallpaper and start tab survive`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val key = stringPreferencesKey("app_preferences")
        listOf("{\"quickActions\":false}", "{\"version\":99}", "null").forEach { home ->
            context.appSettingsDataStore.edit { it[key] = "{\"defaultStartTab\":2,\"wallpaperPath\":\"preserved.jpg\",\"home\":$home}" }
            val restored = DataStoreAppSettings(context).appPreferences.first()
            assertEquals(HomePreferences(), restored.home)
            assertEquals(2, restored.defaultStartTab)
            assertEquals("preserved.jpg", restored.wallpaperPath)
        }
    }
    @Test fun `reopened repository retains order all hidden and zero shortcuts`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val settings = DataStoreAppSettings(context)
        val home = HomePreferences(moduleOrder = HomeModule.entries.reversed().map { it.id },
            hiddenModules = HomeModule.entries.map { it.id }.toSet(), quickActions = emptyList())
        settings.updateAppPreferences(AppPreferences(home = home))
        assertEquals(home, DataStoreAppSettings(context).appPreferences.first().home)
    }
    @Test fun `atomic home and wallpaper transforms preserve concurrent unrelated preferences`() = runBlocking {
        val settings = DataStoreAppSettings(RuntimeEnvironment.getApplication())
        settings.updateAppPreferences(AppPreferences())
        coroutineScope {
            launch { repeat(20) { settings.transformAppPreferences { it.copy(home = HomePreferences(quickActions = emptyList())) } } }
            launch { repeat(20) { settings.transformAppPreferences { it.copy(wallpaperBlur = 0.7f, wallpaperPath = "wallpaper/new.jpg") } } }
        }
        val result = settings.appPreferences.first()
        assertTrue(result.home.quickActions.isEmpty())
        assertEquals(0.7f, result.wallpaperBlur)
        assertEquals("wallpaper/new.jpg", result.wallpaperPath)
    }
    @Test fun `backup absent home preserves current and explicit defaults or empty choices restore`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val current = AppPreferences(home = HomePreferences(quickActions = emptyList(), hiddenModules = setOf("today")))
        val old = json.decodeFromString(PreferencesDto.serializer(), "{}")
        assertEquals(current.home, old.applyTo(current).home)
        val defaults = json.decodeFromString(PreferencesDto.serializer(), "{\"home\":{}}")
        assertEquals(HomePreferences(), defaults.applyTo(current).home)
        val empty = HomePreferences(hiddenModules = HomeModule.entries.map { it.id }.toSet(), quickActions = emptyList())
        val dto = PreferencesDto(home = HomePreferencesDto.fromModel(empty))
        val restored = json.decodeFromString(PreferencesDto.serializer(), json.encodeToString(PreferencesDto.serializer(), dto))
        assertEquals(empty, restored.applyTo(current).home)
    }
}
