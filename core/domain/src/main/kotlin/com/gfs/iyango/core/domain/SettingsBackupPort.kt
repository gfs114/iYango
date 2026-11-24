package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.CreditBackupPreview

/**
 * 设置备份（F17）：版本化 JSON，只包含可跨设备解释的个性化配置。
 *
 * 明确不包含：口令、Cookie、登录连接、系统日历/闹钟 ID、组件宿主 ID。
 * 壁纸图片由用户在导出/恢复时单独选择是否携带。
 */
data class SettingsBackupExport(
    val fileName: String,
    val bytes: ByteArray,
    val includesWallpaper: Boolean,
) {
    // ByteArray 的默认 equals 是引用比较，这里按内容比较以便测试。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SettingsBackupExport) return false
        return fileName == other.fileName &&
            bytes.contentEquals(other.bytes) &&
            includesWallpaper == other.includesWallpaper
    }

    override fun hashCode(): Int =
        (fileName.hashCode() * 31 + bytes.contentHashCode()) * 31 + includesWallpaper.hashCode()
}

/** 恢复前预览：只描述影响，不改变任何设置。 */
data class SettingsBackupPreview(
    /** 提交用不透明 token（本次进程内有效）。 */
    val token: String,
    val formatVersion: Int,
    val exportedAt: Long,
    val includesWallpaper: Boolean,
    val themeMode: String,
    val accentHex: String?,
    val defaultStartTab: Int,
    val navigationLayout: String,
    val animationsEnabled: Boolean,
    val displayOverrideCount: Int,
    val reminderEnabled: Boolean,
    val reminderLeadMinutes: Int,
    /** 旧版本/损坏图片/被跳过的按课表覆盖等提示。 */
    val issues: List<String>,
    val creditBackupPreview: CreditBackupPreview = CreditBackupPreview(),
)

sealed interface SettingsBackupReadResult {
    data class Ready(val preview: SettingsBackupPreview) : SettingsBackupReadResult
    data class Failed(val message: String) : SettingsBackupReadResult
}

/**
 * 设置备份/恢复/重置端口。
 *
 * 恢复流程：`read` 完整校验并缓存解析结果 → 用户确认 → `restore` 应用。
 * 壁纸字节在 `restore` 时先解码到临时文件再提交，失败保留旧设置。
 */
interface SettingsBackupPort {

    suspend fun export(includeWallpaper: Boolean): SettingsBackupExport

    suspend fun read(bytes: ByteArray): SettingsBackupReadResult

    /**
     * 应用已预览的备份。
     *
     * @param token [SettingsBackupPreview.token]
     * @param includeWallpaper 是否恢复备份中的壁纸；false 时保留本机壁纸。
     * @param overwriteCreditPlans 已存在本地学分计划冲突时是否用备份覆盖；false 时默认保留本地。
     */
    suspend fun restore(
        token: String,
        includeWallpaper: Boolean,
        overwriteCreditPlans: Boolean = false,
    ): Result<Unit>

    /**
     * 重置全部个性化设置与壁纸。
     *
     * 不触碰课程、学期、考试与登录；关闭提醒/日历后立即请求系统清理。
     */
    suspend fun resetSettings(): Result<Unit>
}
