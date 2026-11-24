package com.gfs.iyango.platform.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gfs.iyango.core.domain.SchoolCredential
import com.gfs.iyango.core.domain.SchoolCredentialStore
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
 * Keystore 加密的教务凭据存储。
 *
 * - 主密钥只存在于 AndroidKeyStore（不可导出），每次写入使用随机 IV 的 AES/GCM；
 * - 密文落在 `iyango_credentials` 私有 SharedPreferences，该文件已从备份规则排除；
 * - 读取失败（密钥被清除、数据损坏）返回 null，由上层提示重新登录，绝不回退明文。
 */
@Singleton
class AndroidSchoolCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SchoolCredentialStore {

    private val mutex = Mutex()

    override suspend fun read(credentialRef: String): SchoolCredential? = withContext(Dispatchers.IO) {
        if (!validRef(credentialRef)) return@withContext null
        mutex.withLock {
            val encoded = preferences().getString(credentialRef, null) ?: return@withContext null
            val plain = decrypt(encoded) ?: return@withContext null
            val fields = plain.split('\n')
            if (fields.size != 3) return@withContext null
            runCatching {
                SchoolCredential(
                    account = decodeField(fields[0]),
                    password = decodeField(fields[1]),
                    cookieHeader = decodeField(fields[2]),
                )
            }.getOrNull()
        }
    }

    override suspend fun write(credentialRef: String, credential: SchoolCredential) {
        if (!validRef(credentialRef)) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val plain = listOf(
                    encodeField(credential.account),
                    encodeField(credential.password),
                    encodeField(credential.cookieHeader),
                ).joinToString("\n")
                val encrypted = encrypt(plain) ?: return@withContext
                preferences().edit().putString(credentialRef, encrypted).commit()
            }
        }
    }

    override suspend fun clear(credentialRef: String) {
        if (!validRef(credentialRef)) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                preferences().edit().remove(credentialRef).commit()
            }
        }
    }

    private fun preferences() =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun validRef(value: String): Boolean =
        value.isNotEmpty() && value.length <= 128 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

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

    private fun encodeField(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decodeField(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "iyango_school_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val PREFERENCES = "iyango_credentials"
    }
}
