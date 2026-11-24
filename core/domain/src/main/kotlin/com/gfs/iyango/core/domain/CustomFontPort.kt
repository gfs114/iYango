package com.gfs.iyango.core.domain

/** 已导入私有目录的自定义字体信息。 */
data class ImportedFontInfo(
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
)

/**
 * 自定义字体存储端口（TTF / OTF）。
 *
 * 字体文件从系统文件选择器复制到应用私有目录，仅持久化相对路径，
 * 原文件移走或删除后仍可正常工作。
 */
interface CustomFontStore {

    /** 校验并将 [sourceUri] 复制为私有字体文件，失败返回 null。 */
    suspend fun importFromUri(sourceUri: String, suggestedName: String? = null): ImportedFontInfo?

    /** 相对路径 → 绝对文件路径；非法/越界或文件不存在返回 null。 */
    fun resolve(relativePath: String): String?

    /** 安全删除私有目录内的字体文件（不存在时静默）。 */
    suspend fun delete(relativePath: String)
}
