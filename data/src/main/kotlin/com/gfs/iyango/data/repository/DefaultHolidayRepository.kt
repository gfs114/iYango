package com.gfs.iyango.data.repository

import android.content.Context
import com.gfs.iyango.core.domain.BuiltInHolidayData
import com.gfs.iyango.core.domain.HolidayCnJsonParser
import com.gfs.iyango.core.domain.HolidayDayInfo
import com.gfs.iyango.core.domain.HolidayRepositoryPort
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 法定节假日与调休仓储：内置基准离线数据 + 本地缓存叠加 + 在线拉取更新。
 */
@Singleton
class DefaultHolidayRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : HolidayRepositoryPort {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, "holidays").apply { mkdirs() }
    }

    private val _holidays = MutableStateFlow<Map<LocalDate, HolidayDayInfo>>(loadInitial())
    override val holidays: StateFlow<Map<LocalDate, HolidayDayInfo>> = _holidays.asStateFlow()

    private fun loadInitial(): Map<LocalDate, HolidayDayInfo> {
        val merged = LinkedHashMap(BuiltInHolidayData.all())
        runCatching {
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("holiday_") && file.name.endsWith(".json")
            }?.sortedBy { it.name }?.forEach { file ->
                val parsed = HolidayCnJsonParser.parse(file.readText(Charsets.UTF_8))
                merged.putAll(parsed)
            }
        }
        return merged
    }

    override suspend fun refreshYear(year: Int): Result<Int> = withContext(Dispatchers.IO) {
        if (year !in 2020..2040) {
            return@withContext Result.failure(IllegalArgumentException("年份超出支持范围"))
        }
        val urls = listOf(
            "https://fastly.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json",
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$year.json",
        )
        var lastError: Throwable? = null
        for (url in urls) {
            val attempt = runCatching {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                    val body = response.body.string()
                    val parsed = HolidayCnJsonParser.parse(body)
                    if (parsed.isEmpty()) {
                        throw IllegalStateException("节假日数据为空或格式不匹配")
                    }
                    File(cacheDir, "holiday_$year.json").writeText(body, Charsets.UTF_8)
                    val next = LinkedHashMap(_holidays.value)
                    next.putAll(parsed)
                    _holidays.value = next
                    parsed.size
                }
            }
            if (attempt.isSuccess) return@withContext attempt
            lastError = attempt.exceptionOrNull()
        }
        Result.failure(lastError ?: IllegalStateException("拉取节假日数据失败"))
    }
}
