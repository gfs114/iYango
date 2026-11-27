package com.gfs.iyango.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** WebDAV 预设服务商模板。 */
enum class WebDavPreset(
    val label: String,
    val defaultUrl: String,
    val defaultDirectory: String,
) {
    JIANGUOYUN("坚果云", "https://dav.jianguoyun.com/dav/", "iYango"),
    ALIST("Alist", "", "dav/iYango"),
    NEXTCLOUD("Nextcloud", "", "remote.php/dav/files/"),
    CUSTOM("自定义", "", "iYango"),
}

/** WebDAV 同步配置（密码经 Keystore 加密存储，不放普通明文偏好）。 */
data class WebDavConfig(
    val preset: WebDavPreset = WebDavPreset.JIANGUOYUN,
    val serverUrl: String = WebDavPreset.JIANGUOYUN.defaultUrl,
    val username: String = "",
    val directory: String = "iYango",
    val autoSyncEnabled: Boolean = false,
    val lastSyncAtMillis: Long = 0L,
    val lastError: String = "",
)

/** 远端备份文件名与元信息。 */
data class WebDavRemoteBackup(
    val fileName: String,
    val href: String,
    val sizeBytes: Long = 0L,
    val lastModified: String = "",
)

/** 云端全量备份预览。 */
data class WebDavBackupPreview(
    val fileName: String,
    val exportedAt: Long,
    val timetableCount: Int,
    val courseCount: Int,
    val examCount: Int,
    val hasSettings: Boolean,
)

/** WebDAV 密码加密存储端口（AndroidKeyStore AES-256-GCM 实现）。 */
interface WebDavCredentialStore {
    suspend fun savePassword(password: String)
    suspend fun loadPassword(): String
    suspend fun clear()
}

object NoopWebDavCredentialStore : WebDavCredentialStore {
    override suspend fun savePassword(password: String) {}
    override suspend fun loadPassword(): String = ""
    override suspend fun clear() {}
}

/** WebDAV 加密云备份与跨设备同步端口。 */
interface WebDavSyncPort {
    val config: StateFlow<WebDavConfig>

    suspend fun saveConfig(config: WebDavConfig, password: String?)
    suspend fun testConnection(config: WebDavConfig, password: String?): Result<Unit>
    suspend fun backupNow(): Result<String>
    suspend fun listBackups(): Result<List<WebDavRemoteBackup>>
    suspend fun downloadPreview(remote: WebDavRemoteBackup): Result<Pair<WebDavBackupPreview, ByteArray>>
    suspend fun restoreFromBytes(bytes: ByteArray): Result<WebDavBackupPreview>
    suspend fun syncIfDue()
}

object NoopWebDavSync : WebDavSyncPort {
    override val config: StateFlow<WebDavConfig> = MutableStateFlow(WebDavConfig())
    override suspend fun saveConfig(config: WebDavConfig, password: String?) {}
    override suspend fun testConnection(config: WebDavConfig, password: String?): Result<Unit> =
        Result.failure(UnsupportedOperationException("WebDAV 未配置"))
    override suspend fun backupNow(): Result<String> =
        Result.failure(UnsupportedOperationException("WebDAV 未配置"))
    override suspend fun listBackups(): Result<List<WebDavRemoteBackup>> = Result.success(emptyList())
    override suspend fun downloadPreview(remote: WebDavRemoteBackup): Result<Pair<WebDavBackupPreview, ByteArray>> =
        Result.failure(UnsupportedOperationException("WebDAV 未配置"))
    override suspend fun restoreFromBytes(bytes: ByteArray): Result<WebDavBackupPreview> =
        Result.failure(UnsupportedOperationException("WebDAV 未配置"))
    override suspend fun syncIfDue() {}
}
