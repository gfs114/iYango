package com.gfs.iyango.platform.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gfs.iyango.core.domain.WebDavCredentialStore
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
 * AndroidKeyStore AES-256-GCM WebDAV 密码加密存储（不参与系统备份/迁移）。
 */
@Singleton
class AndroidWebDavCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : WebDavCredentialStore {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val mutex = Mutex()

    override suspend fun savePassword(password: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (password.isBlank()) {
                prefs.edit().remove(KEY_PASSWORD_ENC).apply()
            } else {
                val encrypted = encrypt(password)
                prefs.edit().putString(KEY_PASSWORD_ENC, encrypted).apply()
            }
        }
    }

    override suspend fun loadPassword(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val encoded = prefs.getString(KEY_PASSWORD_ENC, null) ?: return@withLock ""
            decrypt(encoded) ?: ""
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().clear().apply()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ct.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ct, 0, combined, 1 + iv.size, ct.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val combined = Base64.decode(encoded, Base64.DEFAULT)
        if (combined.size < 2) return null
        val ivLen = combined[0].toInt() and 0xFF
        if (ivLen <= 0 || 1 + ivLen >= combined.size) return null
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val ct = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "iyango_webdav_credential_key"
        private const val PREFS_NAME = "iyango_webdav_credentials"
        private const val KEY_PASSWORD_ENC = "password_enc"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
