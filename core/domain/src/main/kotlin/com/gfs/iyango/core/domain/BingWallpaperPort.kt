package com.gfs.iyango.core.domain

/** 微软必应每日一图元数据。 */
data class BingWallpaperInfo(
    val date: String,
    val title: String,
    val copyright: String,
    val imageUrl: String,
    val fallbackUrl: String = "",
)

/** 获取必应每日壁纸元数据与图片字节的网络端口。 */
interface BingWallpaperPort {

    /** 拉取今日必应壁纸信息。 */
    suspend fun fetchToday(): Result<BingWallpaperInfo>

    /** 下载指定图片地址的原始字节（受大小上限保护）。 */
    suspend fun downloadImage(url: String): Result<ByteArray>
}
