package com.gfs.iyango.core.domain

/**
 * 供裁剪页使用的待裁剪图片（未裁剪）。
 *
 * 图片已按长边限界采样解码、EXIF 转正并写入临时文件；[averageLuminance]
 * 供导入时计算可读性遮罩初值（见 [WallpaperTone.recommendedScrim]）。
 */
data class PreparedWallpaper(
    val tempPath: String,
    val width: Int,
    val height: Int,
    /** 平均相对亮度 0..1（抽样近似）；[WallpaperTone.LUMINANCE_UNKNOWN] = 分析失败。 */
    val averageLuminance: Float,
)

/**
 * 壁纸文件存储端口（F10）。
 *
 * 图片一律复制到应用私有目录，偏好只保存相对路径；原图库文件被删除后已复制的
 * 壁纸仍可用。流程分两段：[prepare] 只做解码/转正/限界并写临时文件，裁剪页在其上
 * 调整取景；[commit] 按像素窗口裁剪后写入正式文件。临时文件与正式文件分离，
 * 取消不会破坏旧壁纸；正式文件按"槽位 + 时间戳"唯一命名，替换后旧文件由实现方
 * 清理，避免同路径覆盖导致图片缓存不刷新。
 *
 * 所有方法在 IO 线程执行；失败返回 null 而不抛异常，调用方保留旧设置。
 */
interface WallpaperStore {

    /**
     * 解码系统 URI 图片（校正方向、限制尺寸、分析亮度），**不裁剪**，
     * 写入临时文件并返回裁剪所需的元数据。
     *
     * @param sourceUri 系统 Photo Picker / 文件选择返回的 content URI。
     */
    suspend fun prepare(sourceUri: String): PreparedWallpaper?

    /**
     * 把临时文件提交为正式壁纸，返回可持久化的相对路径。
     *
     * @param tempAbsolutePath [prepare]/[prepareFromBytes] 返回的临时文件绝对路径。
     * @param slot 完整壁纸槽位：[SLOT_GLOBAL] 或 [SLOT_TIMETABLE] 为亮色，
     *   暗色在其后追加 `_dark`（与设置备份中的槽位名一致）。
     * @param crop 裁剪像素窗口（相对临时文件的像素空间）；null 表示整图采用。
     *   窗口会先映射到实际解码尺寸并夹进位图边界。
     */
    suspend fun commit(tempAbsolutePath: String, slot: String, crop: CropPixelRegion? = null): String?

    /** 取消预览：只删除临时文件。 */
    suspend fun discard(tempAbsolutePath: String)

    /** 相对路径 → 绝对路径；空路径、越界路径或文件不存在时返回 null。 */
    fun resolve(relativePath: String): String?

    /** 删除已保存的壁纸文件（不存在时静默）。 */
    suspend fun delete(relativePath: String)

    /** 读取已保存壁纸的原始字节（用于设置备份）；不存在/越界返回 null。 */
    suspend fun readBytes(relativePath: String): ByteArray?

    /** 从设置备份中的图片字节按同一规则限界解码（不裁剪），写临时文件。 */
    suspend fun prepareFromBytes(bytes: ByteArray): PreparedWallpaper?

    /** 从已保存的壁纸图片中提取主色调强调色 `#RRGGBB`；不支持或失败返回 null。 */
    suspend fun extractAccentHex(relativePath: String): String? = null

    companion object {
        /** 全局壁纸槽位。 */
        const val SLOT_GLOBAL = "global"

        /** 课表页独立背景槽位。 */
        const val SLOT_TIMETABLE = "timetable"

        /** 四时段壁纸槽位前缀。 */
        const val SLOT_PERIOD_MORNING = "period_morning"
        const val SLOT_PERIOD_AFTERNOON = "period_afternoon"
        const val SLOT_PERIOD_EVENING = "period_evening"
        const val SLOT_PERIOD_NIGHT = "period_night"

        /** 多图轮播槽位前缀（每次生成唯一后缀以保留全部图片）。 */
        const val SLOT_CAROUSEL_PREFIX = "carousel"
    }
}
