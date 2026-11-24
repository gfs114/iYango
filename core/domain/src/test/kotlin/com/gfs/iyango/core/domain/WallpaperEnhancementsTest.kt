package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.DayPeriod
import com.gfs.iyango.core.model.WallpaperSwitchMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperEnhancementsTest {

    @Test
    fun solarCalculator_computesRealisticTimesAndUrumqiOffset() {
        val zone = ZoneId.of("Asia/Shanghai")
        val equinox = LocalDate.of(2026, 3, 21)
        val beijing = WallpaperScheduler.SolarCities.first { it.id == "beijing" }
        val urumqi = WallpaperScheduler.SolarCities.first { it.id == "urumqi" }

        val (bjRise, bjSet) = WallpaperScheduler.calculateSunTimes(equinox, beijing.latitude, beijing.longitude, zone)
        val (urRise, urSet) = WallpaperScheduler.calculateSunTimes(equinox, urumqi.latitude, urumqi.longitude, zone)

        assertTrue("Beijing sunrise expected around 06:00-06:30, got $bjRise", bjRise in LocalTime.of(5, 50)..LocalTime.of(6, 40))
        assertTrue("Beijing sunset expected around 18:00-18:40, got $bjSet", bjSet in LocalTime.of(17, 50)..LocalTime.of(18, 45))
        // Urumqi is ~28.8° west -> ~115 minutes later than Beijing in CST.
        assertTrue("Urumqi sunrise ($urRise) should be much later than Beijing ($bjRise)", urRise.isAfter(bjRise.plusMinutes(90)))
        assertTrue("Urumqi sunset ($urSet) should be much later than Beijing ($bjSet)", urSet.isAfter(bjSet.plusMinutes(90)))

        val summer = LocalDate.of(2026, 6, 21)
        val winter = LocalDate.of(2026, 12, 21)
        val (bjSummerRise, _) = WallpaperScheduler.calculateSunTimes(summer, beijing.latitude, beijing.longitude, zone)
        val (bjWinterRise, _) = WallpaperScheduler.calculateSunTimes(winter, beijing.latitude, beijing.longitude, zone)
        assertTrue("Summer sunrise ($bjSummerRise) earlier than winter ($bjWinterRise)", bjSummerRise.isBefore(bjWinterRise))
    }

    @Test
    fun scheduler_resolvesAllFiveModesAndFallbacks() {
        val zone = ZoneId.of("Asia/Shanghai")
        val noon = ZonedDateTime.of(2026, 6, 1, 12, 0, 0, 0, zone)
        val midnight = ZonedDateTime.of(2026, 6, 1, 23, 0, 0, 0, zone)

        val base = AppPreferences(
            wallpaperPath = "light.jpg",
            wallpaperPathDark = "dark.jpg",
            periodMorningPath = "m.jpg",
            periodAfternoonPath = "a.jpg",
            periodEveningPath = "e.jpg",
            periodNightPath = "n.jpg",
            carouselPaths = listOf("c0.jpg", "c1.jpg", "c2.jpg"),
            carouselIndex = 1,
        )

        assertEquals(
            "light.jpg",
            WallpaperScheduler.resolveGlobalWallpaperRelativePath(
                base.copy(wallpaperSwitchMode = WallpaperSwitchMode.FOLLOW_THEME),
                isDarkTheme = false,
                now = midnight,
            ),
        )
        assertEquals(
            "dark.jpg",
            WallpaperScheduler.resolveGlobalWallpaperRelativePath(
                base.copy(wallpaperSwitchMode = WallpaperSwitchMode.FOLLOW_THEME),
                isDarkTheme = true,
                now = noon,
            ),
        )

        // SUNRISE_SUNSET overrides system dark/light theme choice with solar time.
        val solar = base.copy(
            wallpaperSwitchMode = WallpaperSwitchMode.SUNRISE_SUNSET,
            sunriseCityId = WallpaperScheduler.CUSTOM_CITY_ID,
            sunriseCustomMinutes = 6 * 60,
            sunsetCustomMinutes = 19 * 60,
        )
        assertTrue(WallpaperScheduler.isSolarDaytime(solar, noon))
        assertFalse(WallpaperScheduler.isSolarDaytime(solar, midnight))
        assertEquals("light.jpg", WallpaperScheduler.resolveGlobalWallpaperRelativePath(solar, isDarkTheme = true, now = noon))
        assertEquals("dark.jpg", WallpaperScheduler.resolveGlobalWallpaperRelativePath(solar, isDarkTheme = false, now = midnight))

        // FOUR_PERIODS resolves each clock window and falls back if blank.
        val periods = base.copy(wallpaperSwitchMode = WallpaperSwitchMode.FOUR_PERIODS)
        assertEquals(DayPeriod.MORNING, WallpaperScheduler.currentDayPeriod(noon.withHour(8)))
        assertEquals(DayPeriod.AFTERNOON, WallpaperScheduler.currentDayPeriod(noon.withHour(14)))
        assertEquals(DayPeriod.EVENING, WallpaperScheduler.currentDayPeriod(noon.withHour(18)))
        assertEquals(DayPeriod.NIGHT, WallpaperScheduler.currentDayPeriod(noon.withHour(2)))
        assertEquals("m.jpg", WallpaperScheduler.resolveGlobalWallpaperRelativePath(periods, false, noon.withHour(8)))
        assertEquals("a.jpg", WallpaperScheduler.resolveGlobalWallpaperRelativePath(periods, false, noon.withHour(14)))
        assertEquals("e.jpg", WallpaperScheduler.resolveGlobalWallpaperRelativePath(periods, false, noon.withHour(18)))
        assertEquals("n.jpg", WallpaperScheduler.resolveGlobalWallpaperRelativePath(periods, false, noon.withHour(23)))
        assertEquals(
            "dark.jpg",
            WallpaperScheduler.resolveGlobalWallpaperRelativePath(
                periods.copy(periodMorningPath = ""),
                isDarkTheme = true,
                now = noon.withHour(8),
            ),
        )

        // CAROUSEL_DAILY changes across consecutive days, CAROUSEL_LAUNCH uses carouselIndex.
        val daily = base.copy(wallpaperSwitchMode = WallpaperSwitchMode.CAROUSEL_DAILY)
        val day0 = WallpaperScheduler.resolveGlobalWallpaperRelativePath(daily, false, noon)
        val day1 = WallpaperScheduler.resolveGlobalWallpaperRelativePath(daily, false, noon.plusDays(1))
        assertFalse(day0 == day1)

        val launch = base.copy(wallpaperSwitchMode = WallpaperSwitchMode.CAROUSEL_LAUNCH, carouselIndex = 2)
        assertEquals("c2.jpg", WallpaperScheduler.resolveGlobalWallpaperRelativePath(launch, false, noon))
        assertEquals(
            "light.jpg",
            WallpaperScheduler.resolveGlobalWallpaperRelativePath(
                launch.copy(carouselPaths = emptyList()),
                isDarkTheme = false,
                now = noon,
            ),
        )
    }

    @Test
    fun extractDominantAccentHex_picksChromaticColorAndIgnoresGrayscale() {
        assertNull(WallpaperTone.extractDominantAccentHex(intArrayOf()))

        val grays = IntArray(100) { 0xFF808080.toInt() }
        assertNull(WallpaperTone.extractDominantAccentHex(grays))

        // Mostly blue pixels + a few red pixels -> dominant hex must stay strongly blue.
        val mixedBlue = IntArray(100) { idx -> if (idx < 80) 0xFF2563EB.toInt() else 0xFFEF4444.toInt() }
        val hexBlue = WallpaperTone.extractDominantAccentHex(mixedBlue)
        assertNotNull(hexBlue)
        val rB = hexBlue!!.substring(1, 3).toInt(16)
        val gB = hexBlue.substring(3, 5).toInt(16)
        val bB = hexBlue.substring(5, 7).toInt(16)
        assertTrue("Expected blue dominance in $hexBlue", bB > rB && bB > gB)

        val greens = IntArray(64) { 0xFF16A34A.toInt() }
        val hexGreen = WallpaperTone.extractDominantAccentHex(greens)
        assertNotNull(hexGreen)
        val rG = hexGreen!!.substring(1, 3).toInt(16)
        val gG = hexGreen.substring(3, 5).toInt(16)
        val bG = hexGreen.substring(5, 7).toInt(16)
        assertTrue("Expected green dominance in $hexGreen", gG > rG && gG > bG)
    }
}
