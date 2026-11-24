package com.gfs.iyango.data.update

import android.content.Context
import com.gfs.iyango.core.domain.AppUpdateCheckState
import com.gfs.iyango.core.domain.DiagnosticExport
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.DiagnosticLogSummary
import com.gfs.iyango.core.model.DiagnosticEvent
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateManifestParserTest {

    private class FakeDiagnostics : DiagnosticLogPort {
        val events = mutableListOf<DiagnosticEvent>()
        override fun observeRecent(limit: Int): Flow<List<DiagnosticEvent>> = flowOf(events)
        override val summary: Flow<DiagnosticLogSummary> = flowOf(DiagnosticLogSummary())
        override suspend fun record(event: DiagnosticEvent) { events += event }
        override suspend fun export(): DiagnosticExport = DiagnosticExport("diag.txt", "")
        override suspend fun clear() { events.clear() }
    }

    @Test
    fun `parses custom manifest and drops unsafe download schemes`() {
        val manifest = AppUpdateManifestParser.parse(
            """
            {
              "versionCode": 4,
              "versionName": "0.1.3",
              "releaseNotes": "新增跨教学楼预警与 AI 导课",
              "downloadUrl": "https://example.com/iYango-0.1.3.apk",
              "pageUrl": "javascript:alert(1)",
              "forceUpdate": true,
              "minSupportedVersionCode": 2
            }
            """.trimIndent(),
        )
        assertNotNull(manifest)
        manifest!!
        assertEquals(4L, manifest.release.versionCode)
        assertEquals("0.1.3", manifest.release.versionName)
        assertEquals("https://example.com/iYango-0.1.3.apk", manifest.release.downloadUrl)
        assertEquals("", manifest.release.pageUrl)
        assertTrue(manifest.release.forceUpdate)
        assertEquals(2L, manifest.minSupportedVersionCode)
    }

    @Test
    fun `parses GitHub Releases latest response preferring apk asset`() {
        val manifest = AppUpdateManifestParser.parse(
            """
            {
              "tag_name": "v0.1.4",
              "name": "iYango 0.1.4",
              "body": "Bug fixes",
              "html_url": "https://github.com/example/iYango/releases/tag/v0.1.4",
              "published_at": "2026-11-22T00:00:00Z",
              "assets": [
                { "name": "checksums.txt", "browser_download_url": "https://github.com/example/iYango/releases/download/v0.1.4/checksums.txt" },
                { "name": "iYango-release.apk", "browser_download_url": "https://github.com/example/iYango/releases/download/v0.1.4/iYango-release.apk" }
              ]
            }
            """.trimIndent(),
        )
        assertNotNull(manifest)
        manifest!!
        assertEquals("0.1.4", manifest.release.versionName)
        assertTrue(manifest.release.downloadUrl.endsWith(".apk"))
        assertEquals("https://github.com/example/iYango/releases/tag/v0.1.4", manifest.release.pageUrl)
    }

    @Test
    fun `repository skips silently when unconfigured and prompts once when newer version found`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("test_update_prefs", Context.MODE_PRIVATE).apply {
            edit().clear().commit()
        }
        val diagnostics = FakeDiagnostics()
        var endpoint = ""
        var remoteBody = ""
        val repo = HttpAppUpdateRepository(
            diagnostics = diagnostics,
            clock = Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"), ZoneOffset.UTC),
            currentVersionCodeProvider = { 3L },
            currentVersionNameProvider = { "0.1.2" },
            prefsProvider = { prefs },
            defaultEndpointProvider = { endpoint },
            httpGet = { _, _ -> remoteBody },
        )

        repo.checkOnLaunch()
        assertEquals(AppUpdateCheckState.NotConfigured, repo.state.value)
        assertNull(repo.promptRelease.value)

        endpoint = "https://example.com/update.json"
        remoteBody = """{"versionCode":4,"versionName":"0.1.3","releaseNotes":"新功能"}"""
        repo.checkOnLaunch(force = true)
        assertTrue(repo.state.value is AppUpdateCheckState.Available)
        assertEquals("0.1.3", repo.promptRelease.value?.versionName)

        repo.ignoreVersion(repo.promptRelease.value!!.versionKey)
        assertNull(repo.promptRelease.value)

        // Launch check stays quiet for ignored version, while manual check surfaces it.
        repo.checkOnLaunch(force = true)
        assertNull(repo.promptRelease.value)

        repo.checkManually()
        assertEquals("0.1.3", repo.promptRelease.value?.versionName)
    }

    @Test
    fun `repository records failure without throwing or popping prompt on launch`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("test_update_fail_prefs", Context.MODE_PRIVATE).apply {
            edit().clear().commit()
        }
        val diagnostics = FakeDiagnostics()
        val repo = HttpAppUpdateRepository(
            diagnostics = diagnostics,
            clock = Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"), ZoneOffset.UTC),
            currentVersionCodeProvider = { 3L },
            currentVersionNameProvider = { "0.1.2" },
            prefsProvider = { prefs },
            defaultEndpointProvider = { "https://example.com/update.json" },
            httpGet = { _, _ -> throw IOException("timeout") },
        )

        repo.checkOnLaunch()
        assertTrue(repo.state.value is AppUpdateCheckState.Failed)
        assertNull(repo.promptRelease.value)
        assertEquals("update", diagnostics.events.single().category)
    }
}
