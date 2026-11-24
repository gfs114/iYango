package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.DayPeriod
import com.gfs.iyango.core.model.WallpaperSwitchMode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** 内置城市坐标（供离线日出日落时间推算）。 */
data class SolarCity(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * 离线日出日落推算与多模式壁纸调度器（纯 JVM，不依赖网络与定位权限）。
 */
object WallpaperScheduler {

    const val CUSTOM_CITY_ID = "custom"

    val SolarCities: List<SolarCity> = listOf(
        SolarCity("beijing", "北京", 39.9042, 116.4074),
        SolarCity("shanghai", "上海", 31.2304, 121.4737),
        SolarCity("guangzhou", "广州", 23.1291, 113.2644),
        SolarCity("shenzhen", "深圳", 22.5431, 114.0579),
        SolarCity("chengdu", "成都", 30.5728, 104.0668),
        SolarCity("wuhan", "武汉", 30.5928, 114.3055),
        SolarCity("xian", "西安", 34.3416, 108.9398),
        SolarCity("nanjing", "南京", 32.0603, 118.7969),
        SolarCity("hangzhou", "杭州", 30.2741, 120.1551),
        SolarCity("chongqing", "重庆", 29.5630, 106.5516),
        SolarCity("harbin", "哈尔滨", 45.8038, 126.5350),
        SolarCity("urumqi", "乌鲁木齐", 43.8256, 87.6168),
    )

    private val DefaultSunrise = LocalTime.of(6, 30)
    private val DefaultSunset = LocalTime.of(18, 30)

    /** 使用 NOAA 太阳位置近似算法计算指定日期与经纬度的本地日出/日落时刻。 */
    fun calculateSunTimes(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Pair<LocalTime, LocalTime> {
        val offsetMinutes = zoneId.rules.getOffset(date.atStartOfDay()).totalSeconds / 60.0
        val offsetHours = offsetMinutes / 60.0
        val doy = date.dayOfYear.toDouble()
        val gamma = (2.0 * PI / 365.0) * (doy - 1.0 + (12.0 - offsetHours) / 24.0)

        val eqTime = 229.18 * (
            0.000075 +
                0.001868 * cos(gamma) -
                0.032077 * sin(gamma) -
                0.014615 * cos(2.0 * gamma) -
                0.040849 * sin(2.0 * gamma)
            )
        val decl = 0.006918 -
            0.399912 * cos(gamma) +
            0.070257 * sin(gamma) -
            0.006758 * cos(2.0 * gamma) +
            0.000907 * sin(2.0 * gamma) -
            0.002697 * cos(3.0 * gamma) +
            0.00148 * sin(3.0 * gamma)

        val latRad = Math.toRadians(latitude.coerceIn(-89.0, 89.0))
        val zenithRad = Math.toRadians(90.833)
        val cosHa = cos(zenithRad) / (cos(latRad) * cos(decl)) - tan(latRad) * tan(decl)
        if (!cosHa.isFinite() || cosHa < -1.0 || cosHa > 1.0) {
            return DefaultSunrise to DefaultSunset
        }

        val haDeg = Math.toDegrees(acos(cosHa))
        val sunriseMinutesLocal = (720.0 - 4.0 * (longitude + haDeg) - eqTime + offsetMinutes).roundToInt()
        val sunsetMinutesLocal = (720.0 - 4.0 * (longitude - haDeg) - eqTime + offsetMinutes).roundToInt()

        val sunrise = LocalTime.ofSecondOfDay((sunriseMinutesLocal.coerceIn(0, 1439) * 60).toLong())
        val sunset = LocalTime.ofSecondOfDay((sunsetMinutesLocal.coerceIn(0, 1439) * 60).toLong())
        return if (sunrise < sunset) sunrise to sunset else DefaultSunrise to DefaultSunset
    }

    /** 读取当前配置在给定日期下的日出与日落时刻。 */
    fun sunTimesFor(
        prefs: AppPreferences,
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Pair<LocalTime, LocalTime> {
        if (prefs.sunriseCityId == CUSTOM_CITY_ID) {
            val sr = LocalTime.ofSecondOfDay((prefs.sunriseCustomMinutes.coerceIn(0, 1439) * 60).toLong())
            val ss = LocalTime.ofSecondOfDay((prefs.sunsetCustomMinutes.coerceIn(0, 1439) * 60).toLong())
            return sr to ss
        }
        val city = SolarCities.firstOrNull { it.id == prefs.sunriseCityId } ?: SolarCities.first()
        return calculateSunTimes(date, city.latitude, city.longitude, zoneId)
    }

    /** 判断指定时间处于日间（[sunrise, sunset)）还是夜间。 */
    fun isSolarDaytime(prefs: AppPreferences, now: ZonedDateTime): Boolean {
        val (sunrise, sunset) = sunTimesFor(prefs, now.toLocalDate(), now.zone)
        val current = now.toLocalTime()
        return if (sunrise <= sunset) {
            !current.isBefore(sunrise) && current.isBefore(sunset)
        } else {
            !current.isBefore(sunrise) || current.isBefore(sunset)
        }
    }

    /** 根据本地时钟返回四时段枚举。 */
    fun currentDayPeriod(now: ZonedDateTime): DayPeriod = when (now.hour) {
        in 6..11 -> DayPeriod.MORNING
        in 12..16 -> DayPeriod.AFTERNOON
        in 17..20 -> DayPeriod.EVENING
        else -> DayPeriod.NIGHT
    }

    /**
     * 计算距离下一次自动切换时刻的毫秒数（含 1 秒边界裕量）；
     * 不需要定时唤醒的模式（[WallpaperSwitchMode.FOLLOW_THEME] / [WallpaperSwitchMode.CAROUSEL_LAUNCH]）返回 0。
     */
    fun millisUntilNextTransition(prefs: AppPreferences, now: ZonedDateTime): Long {
        val next: ZonedDateTime? = when (prefs.wallpaperSwitchMode) {
            WallpaperSwitchMode.SUNRISE_SUNSET -> {
                val (srToday, ssToday) = sunTimesFor(prefs, now.toLocalDate(), now.zone)
                val todaySr = now.toLocalDate().atTime(srToday).atZone(now.zone)
                val todaySs = now.toLocalDate().atTime(ssToday).atZone(now.zone)
                when {
                    now.isBefore(todaySr) -> todaySr
                    now.isBefore(todaySs) -> todaySs
                    else -> {
                        val tomorrow = now.toLocalDate().plusDays(1)
                        val (srTomorrow, _) = sunTimesFor(prefs, tomorrow, now.zone)
                        tomorrow.atTime(srTomorrow).atZone(now.zone)
                    }
                }
            }
            WallpaperSwitchMode.FOUR_PERIODS -> {
                val today = now.toLocalDate()
                val candidates = listOf(6, 12, 17, 21).map { hour ->
                    today.atTime(hour, 0).atZone(now.zone)
                }
                candidates.firstOrNull { now.isBefore(it) }
                    ?: today.plusDays(1).atTime(6, 0).atZone(now.zone)
            }
            WallpaperSwitchMode.CAROUSEL_DAILY -> {
                now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            }
            WallpaperSwitchMode.FOLLOW_THEME,
            WallpaperSwitchMode.CAROUSEL_LAUNCH,
            -> null
        }
        val target = next ?: return 0L
        return (Duration.between(now, target).toMillis() + 1_000L).coerceAtLeast(1_000L)
    }

    /** 根据当前切换模式、主题明暗与时钟解析最终使用的全局壁纸相对路径。 */
    fun resolveGlobalWallpaperRelativePath(
        prefs: AppPreferences,
        isDarkTheme: Boolean,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String {
        val themeFallback = if (isDarkTheme) {
            prefs.wallpaperPathDark.ifBlank { prefs.wallpaperPath }
        } else {
            prefs.wallpaperPath.ifBlank { prefs.wallpaperPathDark }
        }

        return when (prefs.wallpaperSwitchMode) {
            WallpaperSwitchMode.FOLLOW_THEME -> themeFallback
            WallpaperSwitchMode.SUNRISE_SUNSET -> {
                if (isSolarDaytime(prefs, now)) {
                    prefs.wallpaperPath.ifBlank { prefs.wallpaperPathDark }
                } else {
                    prefs.wallpaperPathDark.ifBlank { prefs.wallpaperPath }
                }
            }
            WallpaperSwitchMode.FOUR_PERIODS -> {
                val periodPath = when (currentDayPeriod(now)) {
                    DayPeriod.MORNING -> prefs.periodMorningPath
                    DayPeriod.AFTERNOON -> prefs.periodAfternoonPath
                    DayPeriod.EVENING -> prefs.periodEveningPath
                    DayPeriod.NIGHT -> prefs.periodNightPath
                }
                periodPath.ifBlank { themeFallback }
            }
            WallpaperSwitchMode.CAROUSEL_DAILY -> {
                if (prefs.carouselPaths.isNotEmpty()) {
                    val index = Math.floorMod(now.toLocalDate().toEpochDay().toInt(), prefs.carouselPaths.size)
                    prefs.carouselPaths[index]
                } else {
                    themeFallback
                }
            }
            WallpaperSwitchMode.CAROUSEL_LAUNCH -> {
                if (prefs.carouselPaths.isNotEmpty()) {
                    val index = Math.floorMod(prefs.carouselIndex, prefs.carouselPaths.size)
                    prefs.carouselPaths[index]
                } else {
                    themeFallback
                }
            }
        }
    }
}
