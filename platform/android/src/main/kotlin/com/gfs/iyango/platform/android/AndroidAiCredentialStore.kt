package com.gfs.iyango.platform.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiConfigStorePort
import com.gfs.iyango.core.domain.AiProviderConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keystore 加密的 BYOK AI 接口配置存储。
 *
 * - 使用独立密钥别名 [ALIAS]（不与教务凭据密钥混用）；
 * - [AiProviderConfig.apiKey] 仅以 AES-256-GCM 密文保存在 `iyango_ai_config` 私有偏好中，
 *   该文件已从系统备份与迁移规则排除；解密失败返回空 key，绝不回退明文。
 */
@Singleton
class AndroidAiCredentialStore internal constructor(
    private val context: Context,
    private val encryptFn: ((String) -> String?)?,
    private val decryptFn: ((String) -> String?)?,
) : AiConfigStorePort {

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context = context, encryptFn = null, decryptFn = null)

    private val mutex = Mutex()

    override suspend fun load(): AiProviderConfig = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = preferences()
            val protocol = AiApiProtocol.fromStorage(prefs.getString(KEY_PROTOCOL, null))
            val baseUrl = prefs.getString(KEY_BASE_URL, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: AiProviderConfig.DEFAULT_BASE_URL
            val model = prefs.getString(KEY_MODEL, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: AiProviderConfig.DEFAULT_MODEL
            val encryptedKey = prefs.getString(KEY_API_KEY_ENC, null)
            val apiKey = encryptedKey?.let { encoded -> decryptFn?.invoke(encoded) ?: decrypt(encoded) }.orEmpty()
            AiProviderConfig(
                baseUrl = baseUrl,
                model = model,
                apiKey = apiKey,
                protocol = protocol,
            )
        }
    }

    override suspend fun save(config: AiProviderConfig) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val baseUrl = config.baseUrl.trim().ifEmpty { AiProviderConfig.DEFAULT_BASE_URL }
                val model = config.model.trim().ifEmpty { AiProviderConfig.DEFAULT_MODEL }
                val apiKey = config.apiKey.trim()
                val editor = preferences().edit()
                    .putString(KEY_PROTOCOL, config.protocol.name)
                    .putString(KEY_BASE_URL, baseUrl)
                    .putString(KEY_MODEL, model)
                if (apiKey.isEmpty()) {
                    editor.remove(KEY_API_KEY_ENC)
                } else {
                    val encrypted = (encryptFn?.invoke(apiKey) ?: encrypt(apiKey))
                        ?: throw IllegalStateException("本机密钥库不可用，无法安全保存 API Key")
                    editor.putString(KEY_API_KEY_ENC, encrypted)
                }
                check(editor.commit()) { "AI 配置写入失败" }
            }
        }
    }

    override suspend fun clearApiKey() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                preferences().edit().remove(KEY_API_KEY_ENC).commit()
            }
        }
    }

    private fun preferences() =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(payload, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(encoded: String): String? = runCatching {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        if (payload.size <= IV_LENGTH) return@runCatching null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, payload, 0, IV_LENGTH)
        cipher.init(Cipher.DECRYPT_MODE, key(), spec)
        val plain = cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH)
        plain.toString(Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "iyango_ai_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val PREFERENCES = "iyango_ai_config"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY_ENC = "api_key_enc"
    }
}
