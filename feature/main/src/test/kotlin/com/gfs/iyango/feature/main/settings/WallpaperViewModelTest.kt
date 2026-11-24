package com.gfs.iyango.feature.main.settings

import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.BingWallpaperTarget
import java.lang.reflect.Proxy
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WallpaperViewModelTest {
    @Test fun confirmingCropPersistsPathAndScrimTogetherBeforeDeletingOldFile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val preferences = MutableStateFlow(AppPreferences(wallpaperPathDark = "old-dark", wallpaperPath = "old-light", wallpaperBlur = 0.3f))
            val writes = mutableListOf<AppPreferences>()
            val settings = object : AppSettingsPort by unusedPort() {
                override val appPreferences = preferences
                override suspend fun transformAppPreferences(transform: (AppPreferences) -> AppPreferences) {
                    updateAppPreferences(transform(preferences.value))
                }
                override suspend fun updateAppPreferences(value: AppPreferences) {
                    delay(10) // DataStore writes suspend; two read/copy launches can read the same old value.
                    writes += value
                    preferences.value = value
                }
            }
            var committedSlot: String? = null
            val deleted = mutableListOf<String>()
            val store = object : WallpaperStore by unusedPort() {
                override fun resolve(relativePath: String): String? = null
                override suspend fun prepare(sourceUri: String) = PreparedWallpaper("temp", 64, 128, 0.9f)
                override suspend fun commit(tempAbsolutePath: String, slot: String, crop: CropPixelRegion?): String {
                    committedSlot = slot
                    return "new-dark"
                }
                override suspend fun delete(relativePath: String) {
                    assertEquals("new-dark", preferences.value.wallpaperPathDark)
                    deleted += relativePath
                }
            }
            val vm = WallpaperViewModel(settings, store, unusedPort())
            advanceUntilIdle()
            vm.prepare("content://test/image", WallpaperStore.SLOT_GLOBAL, true)
            advanceUntilIdle()
            vm.confirmCrop(null, true)
            advanceUntilIdle()
            assertEquals("global_dark", committedSlot)
            assertEquals("new-dark", preferences.value.wallpaperPathDark)
            assertEquals("old-light", preferences.value.wallpaperPath)
            assertEquals(0.3f, preferences.value.wallpaperBlur)
            assertEquals(WallpaperTone.recommendedScrim(0.9f, true), preferences.value.wallpaperScrim)
            assertEquals(1, writes.size)
            assertEquals(listOf("old-dark"), deleted)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun applyBingToSlotDownloadsImageAndPreparesPendingCrop() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val preferences = MutableStateFlow(AppPreferences())
            val settings = object : AppSettingsPort by unusedPort() {
                override val appPreferences = preferences
                override suspend fun transformAppPreferences(transform: (AppPreferences) -> AppPreferences) {
                    preferences.value = transform(preferences.value)
                }
            }
            val store = object : WallpaperStore by unusedPort() {
                override fun resolve(relativePath: String): String? = null
                override suspend fun prepareFromBytes(bytes: ByteArray): PreparedWallpaper? =
                    if (bytes.contentEquals(byteArrayOf(1, 2, 3))) PreparedWallpaper("bing_temp", 1080, 1920, 0.4f) else null
            }
            val bing = object : BingWallpaperPort {
                override suspend fun fetchToday() = Result.success(
                    BingWallpaperInfo("20261121", "Bing Title", "Copyright", "https://cn.bing.com/test_UHD.jpg", "https://cn.bing.com/test.jpg"),
                )
                override suspend fun downloadImage(url: String) =
                    if (url.endsWith("_UHD.jpg")) Result.failure(RuntimeException("404"))
                    else Result.success(byteArrayOf(1, 2, 3))
            }
            val vm = WallpaperViewModel(settings, store, bing)
            advanceUntilIdle()
            vm.applyBingToSlot(WallpaperStore.SLOT_TIMETABLE, dark = true)
            advanceUntilIdle()

            assertNotNull(vm.bingInfo.value)
            assertEquals("Bing Title", vm.bingInfo.value?.title)
            assertEquals(WallpaperStore.SLOT_TIMETABLE, vm.pending.value?.slot)
            assertEquals(true, vm.pending.value?.dark)
            assertEquals("bing_temp", vm.pending.value?.image?.tempPath)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun enablingBingAutoUpdate_immediatelyUpdatesOncePerDayAndSkipsRepeatSameDay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val preferences = MutableStateFlow(
                AppPreferences(bingAutoUpdateTarget = BingWallpaperTarget.GLOBAL_DARK),
            )
            val settings = object : AppSettingsPort by unusedPort() {
                override val appPreferences = preferences
                override suspend fun transformAppPreferences(transform: (AppPreferences) -> AppPreferences) {
                    delay(5)
                    preferences.value = transform(preferences.value)
                }
            }
            var fetchCalls = 0
            val store = object : WallpaperStore by unusedPort() {
                override fun resolve(relativePath: String): String? = null
                override suspend fun prepareFromBytes(bytes: ByteArray) =
                    PreparedWallpaper("bing_auto_temp", 1080, 1920, 0.25f)
                override suspend fun commit(tempAbsolutePath: String, slot: String, crop: CropPixelRegion?) =
                    "wallpapers/$slot/auto.webp"
            }
            val bing = object : BingWallpaperPort {
                override suspend fun fetchToday(): Result<BingWallpaperInfo> {
                    fetchCalls++
                    return Result.success(
                        BingWallpaperInfo("20261121", "Today", "Copyright", "https://cn.bing.com/auto.jpg", ""),
                    )
                }
                override suspend fun downloadImage(url: String) = Result.success(byteArrayOf(7, 8, 9))
            }
            val vm = WallpaperViewModel(settings, store, bing)
            advanceUntilIdle()

            // Even when app is currently in light theme (darkTheme = false), updating GLOBAL_DARK computes scrim for dark mode.
            vm.setBingAutoUpdate(enabled = true, isWifiConnected = true, darkTheme = false)
            advanceUntilIdle()

            val todayStr = LocalDate.now().toString()
            assertEquals(1, fetchCalls)
            assertEquals("wallpapers/global_dark/auto.webp", preferences.value.wallpaperPathDark)
            assertEquals(WallpaperTone.recommendedScrim(0.25f, true), preferences.value.wallpaperScrim)
            assertEquals(todayStr, preferences.value.bingLastAutoUpdateDate)
            assertEquals("已自动更新今日 Bing 壁纸（全局暗色）", vm.message.value)

            vm.checkAutoUpdateBing(isWifiConnected = true, darkTheme = true)
            advanceUntilIdle()
            assertEquals(1, fetchCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun bingAutoUpdate_respectsWifiOnlyThenUpdatesImmediatelyWhenDisabled() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val preferences = MutableStateFlow(AppPreferences(bingAutoUpdateWifiOnly = true))
            val settings = object : AppSettingsPort by unusedPort() {
                override val appPreferences = preferences
                override suspend fun transformAppPreferences(transform: (AppPreferences) -> AppPreferences) {
                    preferences.value = transform(preferences.value)
                }
            }
            var fetchCalls = 0
            val store = object : WallpaperStore by unusedPort() {
                override fun resolve(relativePath: String): String? = null
                override suspend fun prepareFromBytes(bytes: ByteArray) =
                    PreparedWallpaper("bing_auto_temp2", 1080, 1920, 0.8f)
                override suspend fun commit(tempAbsolutePath: String, slot: String, crop: CropPixelRegion?) =
                    "wallpapers/$slot/auto2.webp"
            }
            val bing = object : BingWallpaperPort {
                override suspend fun fetchToday(): Result<BingWallpaperInfo> {
                    fetchCalls++
                    return Result.success(
                        BingWallpaperInfo("20261121", "Today", "Copyright", "https://cn.bing.com/auto.jpg", ""),
                    )
                }
                override suspend fun downloadImage(url: String) = Result.success(byteArrayOf(4, 5, 6))
            }
            val vm = WallpaperViewModel(settings, store, bing)
            advanceUntilIdle()

            vm.setBingAutoUpdate(enabled = true, isWifiConnected = false, darkTheme = false)
            advanceUntilIdle()

            assertEquals(0, fetchCalls)
            assertEquals("", preferences.value.wallpaperPath)
            assertEquals("", preferences.value.bingLastAutoUpdateDate)

            vm.setBingAutoUpdateWifiOnly(wifiOnly = false, isWifiConnected = false, darkTheme = false)
            advanceUntilIdle()

            assertEquals(1, fetchCalls)
            assertEquals("wallpapers/global/auto2.webp", preferences.value.wallpaperPath)
            assertEquals(LocalDate.now().toString(), preferences.value.bingLastAutoUpdateDate)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private inline fun <reified T> unusedPort(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected call: ${method.name}") } as T
}
