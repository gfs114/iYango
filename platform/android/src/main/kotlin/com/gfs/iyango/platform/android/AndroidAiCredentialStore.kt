package com.gfs.iyango.platform.android

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiConfigStorePort
import com.gfs.iyango.core.domain.AiProfilesSnapshot
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiProviderProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.util.UUID
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keystore 加密的 BYOK AI 接口配置与多档案存储。
 *
 * - 使用独立密钥别名 [ALIAS]（不与教务凭据密钥混用）；
 * - 每个档案的 [AiProviderConfig.apiKey] 均以 AES-256-GCM 密文独立保存，
 *   配置文件已从系统备份与迁移规则排除；解密失败返回空 key，绝不回退明文；
 * - 兼容单配置旧键（`protocol` / `base_url` / `model` / `api_key_enc`），首次读取自动迁移为默认档案。
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
            loadSnapshotLocked().activeProfile.config
        }
    }

    override suspend fun save(config: AiProviderConfig) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val snapshot = loadSnapshotLocked()
                val active = snapshot.activeProfile
                val updated = active.copy(config = normalizeConfig(config))
                saveProfileLocked(snapshot, updated, activate = true)
            }
        }
    }

    override suspend fun clearApiKey() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val prefs = preferences()
                val snapshot = loadSnapshotLocked()
                val activeId = snapshot.activeProfile.id
                val editor = prefs.edit()
                    .remove(KEY_API_KEY_ENC)
                    .remove(profileApiKeyPref(activeId))
                check(editor.commit()) { "清除 API Key 失败" }
            }
        }
    }

    override suspend fun loadProfiles(): AiProfilesSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadSnapshotLocked()
        }
    }

    override suspend fun saveProfile(
        profile: AiProviderProfile,
        activate: Boolean,
    ): AiProfilesSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = loadSnapshotLocked()
            val cleanId = profile.id.trim().ifEmpty { UUID.randomUUID().toString() }
            val cleanName = profile.name.trim().ifEmpty { AiProviderProfile.DEFAULT_NAME }.take(32)
            val normalized = AiProviderProfile(
                id = cleanId,
                name = cleanName,
                config = normalizeConfig(profile.config),
            )
            saveProfileLocked(snapshot, normalized, activate)
        }
    }

    override suspend fun selectProfile(profileId: String): AiProviderConfig = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = preferences()
            val snapshot = loadSnapshotLocked()
            val target = snapshot.profiles.firstOrNull { it.id == profileId }
                ?: return@withLock snapshot.activeProfile.config
            val editor = prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, target.id)
            writeLegacyActiveMirror(editor, target.config)
            check(editor.commit()) { "切换 AI 配置档案失败" }
            target.config
        }
    }

    override suspend fun deleteProfile(profileId: String): AiProfilesSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = preferences()
            val snapshot = loadSnapshotLocked()
            val remaining = snapshot.profiles.filterNot { it.id == profileId }
            val editor = prefs.edit().remove(profileApiKeyPref(profileId))
            val finalProfiles = if (remaining.isEmpty()) {
                listOf(AiProviderProfile())
            } else {
                remaining
            }
            val nextActiveId = if (snapshot.activeProfileId == profileId) {
                finalProfiles.first().id
            } else {
                snapshot.activeProfileId
            }
            val nextActive = finalProfiles.firstOrNull { it.id == nextActiveId } ?: finalProfiles.first()
            editor.putString(KEY_PROFILES_META_JSON, encodeProfilesMeta(finalProfiles))
                .putString(KEY_ACTIVE_PROFILE_ID, nextActive.id)
            writeLegacyActiveMirror(editor, nextActive.config)
            check(editor.commit()) { "删除 AI 配置档案失败" }
            AiProfilesSnapshot(profiles = finalProfiles, activeProfileId = nextActive.id)
        }
    }

    private fun loadSnapshotLocked(): AiProfilesSnapshot {
        val prefs = preferences()
        val rawMeta = prefs.getString(KEY_PROFILES_META_JSON, null)
        if (rawMeta.isNullOrBlank()) {
            val legacy = loadLegacyConfig(prefs)
            val initialProfile = AiProviderProfile(
                id = AiProviderProfile.DEFAULT_ID,
                name = AiProviderProfile.DEFAULT_NAME,
                config = legacy,
            )
            return AiProfilesSnapshot(
                profiles = listOf(initialProfile),
                activeProfileId = initialProfile.id,
            )
        }
        val parsed = decodeProfilesMeta(prefs, rawMeta)
        if (parsed.isEmpty()) {
            val legacy = loadLegacyConfig(prefs)
            val fallback = AiProviderProfile(config = legacy)
            return AiProfilesSnapshot(profiles = listOf(fallback), activeProfileId = fallback.id)
        }
        val storedActiveId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)?.trim().orEmpty()
        val activeId = parsed.firstOrNull { it.id == storedActiveId }?.id ?: parsed.first().id
        return AiProfilesSnapshot(profiles = parsed, activeProfileId = activeId)
    }

    private fun saveProfileLocked(
        snapshot: AiProfilesSnapshot,
        profile: AiProviderProfile,
        activate: Boolean,
    ): AiProfilesSnapshot {
        val prefs = preferences()
        val existingIdx = snapshot.profiles.indexOfFirst { it.id == profile.id }
        val updatedList = if (existingIdx >= 0) {
            snapshot.profiles.toMutableList().apply { set(existingIdx, profile) }
        } else {
            (snapshot.profiles + profile).take(MAX_PROFILES)
        }
        val nextActiveId = if (activate) profile.id else snapshot.activeProfileId
        val nextActive = updatedList.firstOrNull { it.id == nextActiveId } ?: updatedList.first()

        val editor = prefs.edit()
            .putString(KEY_PROFILES_META_JSON, encodeProfilesMeta(updatedList))
            .putString(KEY_ACTIVE_PROFILE_ID, nextActive.id)

        val apiKey = profile.config.apiKey.trim()
        if (apiKey.isEmpty()) {
            editor.remove(profileApiKeyPref(profile.id))
        } else {
            val encrypted = (encryptFn?.invoke(apiKey) ?: encrypt(apiKey))
                ?: throw IllegalStateException("本机密钥库不可用，无法安全保存 API Key")
            editor.putString(profileApiKeyPref(profile.id), encrypted)
        }

        if (nextActive.id == profile.id || activate) {
            writeLegacyActiveMirror(editor, nextActive.config)
        }
        check(editor.commit()) { "AI 配置写入失败" }
        return AiProfilesSnapshot(profiles = updatedList, activeProfileId = nextActive.id)
    }

    private fun writeLegacyActiveMirror(
        editor: SharedPreferences.Editor,
        config: AiProviderConfig,
    ) {
        editor.putString(KEY_PROTOCOL, config.protocol.name)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL, config.model)
        val keyPlain = config.apiKey.trim()
        if (keyPlain.isEmpty()) {
            editor.remove(KEY_API_KEY_ENC)
        } else {
            val encrypted = (encryptFn?.invoke(keyPlain) ?: encrypt(keyPlain))
                ?: throw IllegalStateException("本机密钥库不可用，无法安全保存 API Key")
            editor.putString(KEY_API_KEY_ENC, encrypted)
        }
    }

    private fun loadLegacyConfig(prefs: SharedPreferences): AiProviderConfig {
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
        val apiKey = decryptStoredKey(encryptedKey)
        return AiProviderConfig(
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            protocol = protocol,
        )
    }

    private fun decodeProfilesMeta(
        prefs: SharedPreferences,
        rawMeta: String,
    ): List<AiProviderProfile> = runCatching {
        val array = JSONArray(rawMeta)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isEmpty()) continue
                val name = obj.optString("name").trim().ifEmpty { AiProviderProfile.DEFAULT_NAME }
                val protocol = AiApiProtocol.fromStorage(obj.optString("protocol"))
                val baseUrl = obj.optString("baseUrl").trim().ifEmpty { AiProviderConfig.DEFAULT_BASE_URL }
                val model = obj.optString("model").trim().ifEmpty { AiProviderConfig.DEFAULT_MODEL }
                val enc = prefs.getString(profileApiKeyPref(id), null)
                    ?: if (id == AiProviderProfile.DEFAULT_ID) prefs.getString(KEY_API_KEY_ENC, null) else null
                add(
                    AiProviderProfile(
                        id = id,
                        name = name,
                        config = AiProviderConfig(
                            baseUrl = baseUrl,
                            model = model,
                            apiKey = decryptStoredKey(enc),
                            protocol = protocol,
                        ),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeProfilesMeta(profiles: List<AiProviderProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("protocol", profile.config.protocol.name)
                    put("baseUrl", profile.config.baseUrl)
                    put("model", profile.config.model)
                },
            )
        }
        return array.toString()
    }

    private fun normalizeConfig(config: AiProviderConfig): AiProviderConfig = AiProviderConfig(
        baseUrl = config.baseUrl.trim().ifEmpty { AiProviderConfig.DEFAULT_BASE_URL },
        model = config.model.trim().ifEmpty { AiProviderConfig.DEFAULT_MODEL },
        apiKey = config.apiKey.trim(),
        protocol = config.protocol,
    )

    private fun decryptStoredKey(encoded: String?): String =
        encoded?.let { decryptFn?.invoke(it) ?: decrypt(it) }.orEmpty()

    private fun profileApiKeyPref(profileId: String): String =
        "${KEY_PROFILE_API_KEY_PREFIX}$profileId"

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
        const val MAX_PROFILES = 12
        const val PREFERENCES = "iyango_ai_config"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY_ENC = "api_key_enc"
        const val KEY_PROFILES_META_JSON = "profiles_meta_json"
        const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        const val KEY_PROFILE_API_KEY_PREFIX = "profile_api_key_enc_"
    }
}
