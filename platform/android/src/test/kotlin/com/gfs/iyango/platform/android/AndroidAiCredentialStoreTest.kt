package com.gfs.iyango.platform.android

import android.content.Context
import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAiCredentialStoreTest {

    private fun createStore(context: Context): AndroidAiCredentialStore =
        AndroidAiCredentialStore(
            context = context,
            encryptFn = { plain -> "enc:$plain" },
            decryptFn = { encoded -> encoded.removePrefix("enc:") },
        )

    @Test
    fun `persists protocol alongside baseUrl model and apiKey and keeps protocol when clearing apiKey`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("iyango_ai_config", Context.MODE_PRIVATE).edit().clear().commit()
        val store = createStore(context)

        // Legacy preference without protocol key defaults to OPENAI_CHAT
        val initial = store.load()
        assertEquals(AiApiProtocol.OPENAI_CHAT, initial.protocol)

        val responsesConfig = AiProviderConfig(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-5-mini",
            apiKey = "sk-resp-123",
            protocol = AiApiProtocol.OPENAI_RESPONSES,
        )
        store.save(responsesConfig)
        assertEquals(responsesConfig, store.load())

        val anthropicConfig = AiProviderConfig(
            baseUrl = "https://api.anthropic.com/v1",
            model = "claude-sonnet-4-20250514",
            apiKey = "sk-ant-456",
            protocol = AiApiProtocol.ANTHROPIC_MESSAGES,
        )
        store.save(anthropicConfig)
        val reloaded = store.load()
        assertEquals(AiApiProtocol.ANTHROPIC_MESSAGES, reloaded.protocol)
        assertEquals("https://api.anthropic.com/v1", reloaded.baseUrl)
        assertEquals("claude-sonnet-4-20250514", reloaded.model)
        assertEquals("sk-ant-456", reloaded.apiKey)

        store.clearApiKey()
        val afterClear = store.load()
        assertTrue(afterClear.apiKey.isEmpty())
        assertEquals(AiApiProtocol.ANTHROPIC_MESSAGES, afterClear.protocol)
        assertEquals("claude-sonnet-4-20250514", afterClear.model)
    }
}
