package com.gfs.iyango.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.gfs.iyango.core.model.AppPreferences
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
class DeveloperModePersistenceTest {
    @Test fun `unlock survives reopening storage and disabling persists without changing preferences`() = runBlocking {
        val file = java.io.File.createTempFile("developer-mode", ".preferences_pb", RuntimeEnvironment.getApplication().cacheDir)
        suspend fun withSettings(block: suspend (DataStoreAppSettings) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                block(DataStoreAppSettings(PreferenceDataStoreFactory.create(scope = scope) { file }))
            } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
        }
        try {
            withSettings { settings ->
                assertFalse(settings.appPreferences.first().developerModeEnabled)
                settings.updateAppPreferences(AppPreferences(defaultStartTab = 2, wallpaperPath = "saved.jpg"))
                settings.transformAppPreferences { it.copy(developerModeEnabled = true) }
            }
            withSettings { settings ->
                assertTrue(settings.appPreferences.first().developerModeEnabled)
                settings.transformAppPreferences { it.copy(developerModeEnabled = false) }
            }
            withSettings { settings ->
                val saved = settings.appPreferences.first()
                assertFalse(saved.developerModeEnabled)
                assertEquals(2, saved.defaultStartTab)
                assertEquals("saved.jpg", saved.wallpaperPath)
            }
        } finally { file.delete() }
    }

    @Test fun `legacy settings stay locked and a backup cannot enable developer mode`() {
        val json = Json { ignoreUnknownKeys = true }
        assertFalse(json.decodeFromString(DataStoreAppSettings.AppPreferencesDto.serializer(), "{}").toModel().developerModeEnabled)
        val backup = json.decodeFromString(PreferencesDto.serializer(), "{\"developerModeEnabled\":true}")
        assertFalse(backup.applyTo(AppPreferences()).developerModeEnabled)
        assertTrue(backup.applyTo(AppPreferences(developerModeEnabled = true)).developerModeEnabled)
    }
}
