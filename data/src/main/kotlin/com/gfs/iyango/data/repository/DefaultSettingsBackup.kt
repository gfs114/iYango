package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.CalendarCoordinatorPort
import com.gfs.iyango.core.domain.CreditProgressPort
import com.gfs.iyango.core.domain.DockGlassSettings
import com.gfs.iyango.core.domain.GlassLayerSettings
import com.gfs.iyango.core.domain.GlobalGlassSettings
import com.gfs.iyango.core.domain.GradeMonitorCoordinatorPort
import com.gfs.iyango.core.domain.LiquidGlassPreset
import com.gfs.iyango.core.domain.NoopGradeMonitorCoordinator
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.SettingsBackupExport
import com.gfs.iyango.core.domain.SettingsBackupPort
import com.gfs.iyango.core.domain.SettingsBackupPreview
import com.gfs.iyango.core.domain.SettingsBackupReadResult
import com.gfs.iyango.core.domain.ThemeMode
import com.gfs.iyango.core.domain.ThemeSettings
import com.gfs.iyango.core.domain.ThemeSettingsPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.domain.WallpaperStore
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.BingWallpaperTarget
import com.gfs.iyango.core.model.CreditRecordAnchor
import com.gfs.iyango.core.model.GrayTextMode
import com.gfs.iyango.core.model.NavigationLayout
import com.gfs.iyango.core.model.PinnedClassroomBuilding
import com.gfs.iyango.core.model.ReminderSettings
import com.gfs.iyango.core.model.WallpaperSwitchMode
import com.gfs.iyango.data.json.DisplayConfigJson
import com.gfs.iyango.data.json.encodeDisplayConfig
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [SettingsBackupPort] 的 data 层实现（F17）。
 *
 * 备份是独立于课程交换格式的版本化 JSON：只保存可跨设备解释的个性化配置。
 * 明确不写入口令、Cookie、登录连接、系统日历/闹钟 ID、组件宿主 ID；按课表覆盖只
 * 接收本机已存在的课表 id，避免把别机的内部 id 写进本机配置。
 *
 * `read` 完整校验并缓存解析结果，`restore` 只应用缓存内容；壁纸先解码到临时文件，
 * 提交成功后才写偏好，失败保留旧壁纸与旧路径。
 */
@Singleton
class DefaultSettingsBackup @Inject constructor(
    private val appSettings: AppSettingsPort,
    private val themeSettings: ThemeSettingsPort,
    private val wallpapers: WallpaperStore,
    private val timetableRepository: TimetableRepositoryPort,
    private val reminderCoordinator: ReminderCoordinatorPort,
    private val calendarCoordinator: CalendarCoordinatorPort,
    private val clock: Clock,
    private val creditProgressRepository: CreditProgressPort,
    private val schoolAuth: SchoolAuthPort,
    private val gradeMonitorCoordinator: GradeMonitorCoordinatorPort = NoopGradeMonitorCoordinator,
) : SettingsBackupPort {

    /** token(sha256) → 已校验备份；容量很小，只服务“预览 → 确认”这一小段流程。 */
    private val sessions = ConcurrentHashMap<String, ParsedBackup>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    override suspend fun export(includeWallpaper: Boolean): SettingsBackupExport {
        val prefs = appSettings.appPreferences.first().sanitized()
        val theme = themeSettings.settings.first()
        val display = appSettings.displaySettings.first()
        val reminder = appSettings.reminderSettings.first().sanitized()
        val nowMillis = clock.millis()

        val wallpaperMap = if (!includeWallpaper) {
            emptyMap()
        } else {
            buildMap {
                wallpapers.readBytes(prefs.wallpaperPath)
                    ?.let { put(WallpaperStore.SLOT_GLOBAL, base64(it)) }
                wallpapers.readBytes(prefs.wallpaperPathDark)
                    ?.let { put(SLOT_GLOBAL_DARK, base64(it)) }
                wallpapers.readBytes(prefs.timetableWallpaperPath)
                    ?.let { put(WallpaperStore.SLOT_TIMETABLE, base64(it)) }
                wallpapers.readBytes(prefs.timetableWallpaperPathDark)
                    ?.let { put(SLOT_TIMETABLE_DARK, base64(it)) }
            }
        }

        val exportedCreditPlans = creditProgressRepository.exportAllPlans().map { it.toDto() }
        val document = SettingsBackupDocument(
            version = FORMAT_VERSION,
            exportedAt = nowMillis,
            theme = ThemeDto(
                accentHex = theme.accentHex,
                tintBackground = theme.tintBackground,
                themeMode = theme.themeMode.name,
                dockSurfaceAlphaLight = theme.dockSurfaceAlphaLight,
                dockSurfaceAlphaDark = theme.dockSurfaceAlphaDark,
                dockGlass = DockGlassDto.fromModel(theme.dockGlass),
                globalGlass = GlobalGlassDto.fromModel(theme.globalGlass),
                savedGlassPresets = theme.savedGlassPresets.map { LiquidGlassPresetDto.fromModel(it) },
            ),
            preferences = PreferencesDto(
                defaultStartTab = prefs.defaultStartTab,
                navigationLayout = prefs.navigationLayout.name,
                animationsEnabled = prefs.animationsEnabled,
                predictiveBackEnabled = prefs.predictiveBackEnabled,
                wallpaperScrim = prefs.wallpaperScrim,
                wallpaperBlur = prefs.wallpaperBlur,
                home = HomePreferencesDto.fromModel(prefs.home),
                fontScaleFactor = prefs.fontScaleFactor,
                grayTextMode = prefs.grayTextMode.name,
                grayTextCustomHex = prefs.grayTextCustomHex,
                savedAccentPresets = prefs.savedAccentPresets,
                wallpaperSwitchMode = prefs.wallpaperSwitchMode.name,
                sunriseCityId = prefs.sunriseCityId,
                sunriseCustomMinutes = prefs.sunriseCustomMinutes,
                sunsetCustomMinutes = prefs.sunsetCustomMinutes,
                sunriseSyncTheme = prefs.sunriseSyncTheme,
                wallpaperDynamicColor = prefs.wallpaperDynamicColor,
                bingAutoUpdateEnabled = prefs.bingAutoUpdateEnabled,
                bingAutoUpdateWifiOnly = prefs.bingAutoUpdateWifiOnly,
                bingAutoUpdateTarget = prefs.bingAutoUpdateTarget.name,
                pinnedClassroomBuildings = prefs.pinnedClassroomBuildings.map {
                    PinnedBuildingDto.fromModel(it)
                },
            ),
            display = DisplayDto(
                global = toDisplayJson(display.global),
                byTimetable = display.byTimetable.entries.associate { (id, value) ->
                    id.toString() to toDisplayJson(value)
                },
            ),
            reminder = ReminderDto(
                enabled = reminder.enabled,
                leadMinutes = reminder.leadMinutes,
                disabledCourseKeys = reminder.disabledCourseKeys.sorted(),
                ongoingProgressEnabled = reminder.ongoingProgressEnabled,
                gradeNotifyEnabled = reminder.gradeNotifyEnabled,
                gradeCheckIntervalHours = reminder.gradeCheckIntervalHours,
                gradeNotifyHideScoreOnLockscreen = reminder.gradeNotifyHideScoreOnLockscreen,
                gradeSprintEnabled = reminder.gradeSprintEnabled,
                gradeSprintIntervalMinutes = reminder.gradeSprintIntervalMinutes,
                gradeSprintUntilMillis = reminder.gradeSprintUntilMillis,
                gradeSprintDurationDays = reminder.gradeSprintDurationDays,
                gradeContextTriggerEnabled = reminder.gradeContextTriggerEnabled,
            ),
            wallpapers = wallpaperMap,
            creditPlans = exportedCreditPlans,
        )

        val fileName = FILE_NAME_FORMATTER.format(
            Instant.ofEpochMilli(nowMillis).atZone(clock.zone),
        )
        return SettingsBackupExport(
            fileName = fileName,
            bytes = json.encodeToString(SettingsBackupDocument.serializer(), document)
                .toByteArray(Charsets.UTF_8),
            includesWallpaper = wallpaperMap.isNotEmpty(),
        )
    }

    override suspend fun read(bytes: ByteArray): SettingsBackupReadResult {
        if (bytes.isEmpty()) return SettingsBackupReadResult.Failed("备份文件为空")
        if (bytes.size > MAX_BACKUP_BYTES) {
            return SettingsBackupReadResult.Failed("备份文件过大，无法预览")
        }

        val document = runCatching {
            json.decodeFromString(
                SettingsBackupDocument.serializer(),
                bytes.toString(Charsets.UTF_8),
            )
        }.getOrNull() ?: return SettingsBackupReadResult.Failed("备份文件已损坏或不是有效的 JSON")

        if (document.app != APP_ID) {
            return SettingsBackupReadResult.Failed("这不是 iYango 设置备份文件")
        }
        if (document.version > FORMAT_VERSION) {
            return SettingsBackupReadResult.Failed("备份由更新版本的应用创建，请先升级应用")
        }
        if (document.version < 1) {
            return SettingsBackupReadResult.Failed("备份格式版本不受支持")
        }

        val issues = mutableListOf<String>()
        val existingIds = runCatching {
            timetableRepository.observeAll().first().map { it.id }.toSet()
        }.getOrDefault(emptySet())

        val byTimetable = document.display.byTimetable.mapNotNull { (key, value) ->
            key.toLongOrNull()?.takeIf { it in existingIds }?.let { it to value.toModel() }
        }.toMap()
        if (byTimetable.size < document.display.byTimetable.size) {
            issues += "部分按课表显示覆盖因课表不存在已跳过"
        }

        val wallpaperBytes = document.wallpapers.mapNotNull { (slot, text) ->
            if (slot != WallpaperStore.SLOT_GLOBAL && slot != WallpaperStore.SLOT_TIMETABLE &&
                slot != SLOT_GLOBAL_DARK && slot != SLOT_TIMETABLE_DARK
            ) {
                return@mapNotNull null
            }
            val decoded = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
            if (decoded == null || decoded.isEmpty()) {
                issues += "备份中的壁纸图片已损坏，恢复时将保留本机壁纸"
                return@mapNotNull null
            }
            slot to decoded
        }.toMap()

        val theme = document.theme.toModel()
        if (theme.accentHex == null && document.theme.accentHex != null) {
            issues += "主题强调色无效，已回退为默认色"
        }
        val prefs = document.preferences.toModel()

        val creditBackups = document.creditPlans.map { it.toModel() }
        val creditPreview = if (creditBackups.isEmpty()) {
            com.gfs.iyango.core.model.CreditBackupPreview()
        } else {
            creditProgressRepository.previewBackupImport(
                backups = creditBackups,
                knownAccountKeysByFingerprint = buildKnownAccountKeysByFingerprint(),
            )
        }
        if (creditPreview.conflicts.isNotEmpty()) {
            issues += "检测到 ${creditPreview.conflicts.size} 个账号的本地学分计划冲突（默认保留本地配置）"
        }
        if (creditPreview.unmatchedPendingPlansCount > 0) {
            issues += "${creditPreview.unmatchedPendingPlansCount} 份学分计划暂未匹配本机已登录账号，将在对应学号登录后待核验"
        }

        val token = digest(bytes)
        remember(token, ParsedBackup(document, byTimetable, wallpaperBytes))

        return SettingsBackupReadResult.Ready(
            SettingsBackupPreview(
                token = token,
                formatVersion = document.version,
                exportedAt = document.exportedAt,
                includesWallpaper = wallpaperBytes.isNotEmpty(),
                themeMode = theme.themeMode.name,
                accentHex = theme.accentHex,
                defaultStartTab = prefs.defaultStartTab,
                navigationLayout = prefs.navigationLayout.name,
                animationsEnabled = prefs.animationsEnabled,
                displayOverrideCount = byTimetable.size,
                reminderEnabled = document.reminder.enabled,
                reminderLeadMinutes = document.reminder.toModel().leadMinutes,
                issues = issues,
                creditBackupPreview = creditPreview,
            ),
        )
    }

    override suspend fun restore(
        token: String,
        includeWallpaper: Boolean,
        overwriteCreditPlans: Boolean,
    ): Result<Unit> {
        val parsed = sessions[token]
            ?: return Result.failure(IllegalStateException("备份预览已失效，请重新选择文件"))
        return runCatching {
            val document = parsed.document
            themeSettings.update(document.theme.toModel())
            appSettings.updateDisplaySettings(
                com.gfs.iyango.core.model.DisplaySettingsBundle(
                    global = document.display.global.toModel(),
                    byTimetable = parsed.byTimetable,
                ),
            )
            appSettings.updateReminderSettings(document.reminder.toModel())

            val current = appSettings.appPreferences.first().sanitized()
            val applyWallpaper = includeWallpaper && parsed.wallpaperBytes.isNotEmpty()
            val globalPath = if (applyWallpaper) {
                commitWallpaper(WallpaperStore.SLOT_GLOBAL, parsed.wallpaperBytes, current.wallpaperPath)
            } else {
                current.wallpaperPath
            }
            val globalPathDark = if (applyWallpaper) {
                commitWallpaper(SLOT_GLOBAL_DARK, parsed.wallpaperBytes, current.wallpaperPathDark)
            } else {
                current.wallpaperPathDark
            }
            val timetablePath = if (applyWallpaper) {
                commitWallpaper(
                    WallpaperStore.SLOT_TIMETABLE,
                    parsed.wallpaperBytes,
                    current.timetableWallpaperPath,
                )
            } else {
                current.timetableWallpaperPath
            }
            val timetablePathDark = if (applyWallpaper) {
                commitWallpaper(
                    SLOT_TIMETABLE_DARK,
                    parsed.wallpaperBytes,
                    current.timetableWallpaperPathDark,
                )
            } else {
                current.timetableWallpaperPathDark
            }

            appSettings.transformAppPreferences { latest ->
                document.preferences.applyTo(latest).copy(
                    wallpaperPath = if (applyWallpaper) globalPath else latest.wallpaperPath,
                    wallpaperPathDark = if (applyWallpaper) globalPathDark else latest.wallpaperPathDark,
                    timetableWallpaperPath = if (applyWallpaper) timetablePath else latest.timetableWallpaperPath,
                    timetableWallpaperPathDark = if (applyWallpaper) timetablePathDark else latest.timetableWallpaperPathDark,
                ).sanitized()
            }

            val creditBackups = document.creditPlans.map { it.toModel() }
            if (creditBackups.isNotEmpty()) {
                creditProgressRepository.importBackupPlans(
                    backups = creditBackups,
                    overwriteConflicts = overwriteCreditPlans,
                    knownAccountKeysByFingerprint = buildKnownAccountKeysByFingerprint(),
                )
            }

            runCatching { reminderCoordinator.refresh() }
            sessions.remove(token)
        }
    }

    private suspend fun buildKnownAccountKeysByFingerprint(): Map<String, String> {
        val connections = runCatching { schoolAuth.observeConnections().first() }.getOrDefault(emptyList())
        val map = LinkedHashMap<String, String>()
        for (conn in connections) {
            val schoolId = conn.schoolId.ifBlank { RoomCreditProgressRepository.DEFAULT_SCHOOL_ID }
            val activeKey = conn.accountSubject.ifEmpty { conn.id }
            if (activeKey.isNotBlank()) {
                map[CreditRecordAnchor.accountSubjectFingerprint(schoolId, activeKey)] = activeKey
            }
            if (conn.profile.studentNo.isNotBlank()) {
                map[CreditRecordAnchor.accountSubjectFingerprint(schoolId, conn.profile.studentNo)] = activeKey
            }
            if (conn.id.isNotBlank()) {
                map[CreditRecordAnchor.accountSubjectFingerprint(schoolId, conn.id)] = activeKey
            }
        }
        return map
    }

    override suspend fun resetSettings(): Result<Unit> = runCatching {
        val current = appSettings.appPreferences.first().sanitized()
        // 先关闭提醒/日历：此时 ownerId 仍在，才能清理本应用写入的系统记录。
        runCatching { reminderCoordinator.setEnabled(false) }
        runCatching { calendarCoordinator.setEnabled(false) }
        runCatching { gradeMonitorCoordinator.setEnabled(false) }

        appSettings.reset()
        themeSettings.update(ThemeSettings())

        wallpapers.delete(current.wallpaperPath)
        wallpapers.delete(current.wallpaperPathDark)
        wallpapers.delete(current.timetableWallpaperPath)
        wallpapers.delete(current.timetableWallpaperPathDark)
    }

    /** 恢复单个壁纸槽位；成功返回新相对路径，失败保留旧路径。 */
    private suspend fun commitWallpaper(
        slot: String,
        images: Map<String, ByteArray>,
        oldPath: String,
    ): String {
        val bytes = images[slot]
        if (bytes == null) {
            wallpapers.delete(oldPath)
            return ""
        }
        val temp = wallpapers.prepareFromBytes(bytes) ?: return oldPath
        val committed = wallpapers.commit(temp.tempPath, slot)
        return if (committed == null) {
            wallpapers.discard(temp.tempPath)
            oldPath
        } else {
            committed
        }
    }

    private fun remember(token: String, parsed: ParsedBackup) {
        sessions[token] = parsed
        while (sessions.size > MAX_SESSIONS) {
            sessions.keys.firstOrNull()?.let(sessions::remove)
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private data class ParsedBackup(
        val document: SettingsBackupDocument,
        val byTimetable: Map<Long, com.gfs.iyango.core.model.ScheduleDisplaySettings>,
        val wallpaperBytes: Map<String, ByteArray>,
    )

    private companion object {
        const val APP_ID = "iYango-Settings"
        const val FORMAT_VERSION = 2
        const val MAX_SESSIONS = 4

        /** 亮暗分槽的备份槽位名（正式槽位名加 _dark 后缀）。 */
        const val SLOT_GLOBAL_DARK = WallpaperStore.SLOT_GLOBAL + "_dark"
        const val SLOT_TIMETABLE_DARK = WallpaperStore.SLOT_TIMETABLE + "_dark"

        /** 备份文件上限：四张 JPEG 壁纸 + 配置远小于此值。 */
        const val MAX_BACKUP_BYTES = 16 * 1024 * 1024

        val FILE_NAME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("'iyango-settings-'yyyyMMdd-HHmm'.json'")
    }
}

@Serializable
internal data class SettingsBackupDocument(
    val app: String = "iYango-Settings",
    val version: Int = 1,
    val exportedAt: Long = 0L,
    val theme: ThemeDto = ThemeDto(),
    val preferences: PreferencesDto = PreferencesDto(),
    val display: DisplayDto = DisplayDto(),
    val reminder: ReminderDto = ReminderDto(),
    /** 槽位 → base64 图片；用户未选择携带壁纸时为空。 */
    val wallpapers: Map<String, String> = emptyMap(),
    val creditPlans: List<CreditAccountPlanBackupDto> = emptyList(),
)

@Serializable
internal data class DockGlassDto(
    val blurDp: Float = 2f,
    val lensWidthDp: Float = 12f,
    val lensHeightDp: Float = 14f,
    val edgeWidthDp: Float = 0.5f,
    val edgeBlurDp: Float = 0.6f,
    val edgeAlphaBase: Float = 0.22f,
    val edgeAlphaHidden: Float = 0.25f,
    val edgeAlphaIndicator: Float = 0.35f,
    val indicatorLensWidthDp: Float = 8f,
    val indicatorLensHeightDp: Float = 10f,
    val indicatorChromaticAberration: Boolean = true,
    val indicatorSurfaceAlpha: Float = 0.02f,
    val indicatorSurfacePressedAlpha: Float = 0.02f,
    val indicatorShadowRadiusDp: Float = 12f,
    val indicatorShadowAlpha: Float = 0.3f,
    val indicatorInnerRadiusDp: Float = 4f,
    val indicatorInnerAlpha: Float = 0.4f,
    val legacyTier0AlphaLight: Float = 0.55f,
    val legacyTier0AlphaDark: Float = 0.35f,
) {
    fun toModel(): DockGlassSettings = DockGlassSettings(
        blurDp = blurDp,
        lensWidthDp = lensWidthDp,
        lensHeightDp = lensHeightDp,
        edgeWidthDp = edgeWidthDp,
        edgeBlurDp = edgeBlurDp,
        edgeAlphaBase = edgeAlphaBase,
        edgeAlphaHidden = edgeAlphaHidden,
        edgeAlphaIndicator = edgeAlphaIndicator,
        indicatorLensWidthDp = indicatorLensWidthDp,
        indicatorLensHeightDp = indicatorLensHeightDp,
        indicatorChromaticAberration = indicatorChromaticAberration,
        indicatorSurfaceAlpha = indicatorSurfaceAlpha,
        indicatorSurfacePressedAlpha = indicatorSurfacePressedAlpha,
        indicatorShadowRadiusDp = indicatorShadowRadiusDp,
        indicatorShadowAlpha = indicatorShadowAlpha,
        indicatorInnerRadiusDp = indicatorInnerRadiusDp,
        indicatorInnerAlpha = indicatorInnerAlpha,
        legacyTier0AlphaLight = legacyTier0AlphaLight,
        legacyTier0AlphaDark = legacyTier0AlphaDark,
    ).sanitized()

    companion object {
        fun fromModel(model: DockGlassSettings): DockGlassDto {
            val safe = model.sanitized()
            return DockGlassDto(
                blurDp = safe.blurDp,
                lensWidthDp = safe.lensWidthDp,
                lensHeightDp = safe.lensHeightDp,
                edgeWidthDp = safe.edgeWidthDp,
                edgeBlurDp = safe.edgeBlurDp,
                edgeAlphaBase = safe.edgeAlphaBase,
                edgeAlphaHidden = safe.edgeAlphaHidden,
                edgeAlphaIndicator = safe.edgeAlphaIndicator,
                indicatorLensWidthDp = safe.indicatorLensWidthDp,
                indicatorLensHeightDp = safe.indicatorLensHeightDp,
                indicatorChromaticAberration = safe.indicatorChromaticAberration,
                indicatorSurfaceAlpha = safe.indicatorSurfaceAlpha,
                indicatorSurfacePressedAlpha = safe.indicatorSurfacePressedAlpha,
                indicatorShadowRadiusDp = safe.indicatorShadowRadiusDp,
                indicatorShadowAlpha = safe.indicatorShadowAlpha,
                indicatorInnerRadiusDp = safe.indicatorInnerRadiusDp,
                indicatorInnerAlpha = safe.indicatorInnerAlpha,
                legacyTier0AlphaLight = safe.legacyTier0AlphaLight,
                legacyTier0AlphaDark = safe.legacyTier0AlphaDark,
            )
        }
    }
}

@Serializable
internal data class GlassLayerDto(
    val blurDp: Float = 0f,
    val lensWidthDp: Float = 0f,
    val lensHeightDp: Float = 0f,
    val edgeWidthDp: Float = 0.5f,
    val edgeBlurDp: Float = 0.6f,
    val edgeAlpha: Float = 0f,
    val tintAlphaLight: Float = 0f,
    val tintAlphaDark: Float = 0f,
    val tier0AlphaLight: Float = 0f,
    val tier0AlphaDark: Float = 0f,
    val depthEffect: Boolean = false,
    val chromaticAberration: Boolean = false,
) {
    fun toModel(fallback: GlassLayerSettings): GlassLayerSettings = GlassLayerSettings(
        blurDp = blurDp,
        lensWidthDp = lensWidthDp,
        lensHeightDp = lensHeightDp,
        edgeWidthDp = edgeWidthDp,
        edgeBlurDp = edgeBlurDp,
        edgeAlpha = edgeAlpha,
        tintAlphaLight = tintAlphaLight,
        tintAlphaDark = tintAlphaDark,
        tier0AlphaLight = tier0AlphaLight,
        tier0AlphaDark = tier0AlphaDark,
        depthEffect = depthEffect,
        chromaticAberration = chromaticAberration,
    ).sanitized(fallback)

    companion object {
        fun fromModel(model: GlassLayerSettings): GlassLayerDto = GlassLayerDto(
            blurDp = model.blurDp,
            lensWidthDp = model.lensWidthDp,
            lensHeightDp = model.lensHeightDp,
            edgeWidthDp = model.edgeWidthDp,
            edgeBlurDp = model.edgeBlurDp,
            edgeAlpha = model.edgeAlpha,
            tintAlphaLight = model.tintAlphaLight,
            tintAlphaDark = model.tintAlphaDark,
            tier0AlphaLight = model.tier0AlphaLight,
            tier0AlphaDark = model.tier0AlphaDark,
            depthEffect = model.depthEffect,
            chromaticAberration = model.chromaticAberration,
        )
    }
}

@Serializable
internal data class GlobalGlassDto(
    val navigation: GlassLayerDto = GlassLayerDto.fromModel(GlobalGlassSettings.DefaultNavigation),
    val content: GlassLayerDto = GlassLayerDto.fromModel(GlobalGlassSettings.DefaultContent),
    val control: GlassLayerDto = GlassLayerDto.fromModel(GlobalGlassSettings.DefaultControl),
    val overlay: GlassLayerDto = GlassLayerDto.fromModel(GlobalGlassSettings.DefaultOverlay),
) {
    fun toModel(): GlobalGlassSettings = GlobalGlassSettings(
        navigation = navigation.toModel(GlobalGlassSettings.DefaultNavigation),
        content = content.toModel(GlobalGlassSettings.DefaultContent),
        control = control.toModel(GlobalGlassSettings.DefaultControl),
        overlay = overlay.toModel(GlobalGlassSettings.DefaultOverlay),
    ).sanitized()

    companion object {
        fun fromModel(model: GlobalGlassSettings): GlobalGlassDto {
            val safe = model.sanitized()
            return GlobalGlassDto(
                navigation = GlassLayerDto.fromModel(safe.navigation),
                content = GlassLayerDto.fromModel(safe.content),
                control = GlassLayerDto.fromModel(safe.control),
                overlay = GlassLayerDto.fromModel(safe.overlay),
            )
        }
    }
}

@Serializable
internal data class LiquidGlassPresetDto(
    val id: String = "custom",
    val name: String = "自定义方案",
    val globalGlass: GlobalGlassDto = GlobalGlassDto(),
    val dockGlass: DockGlassDto = DockGlassDto(),
    val dockSurfaceAlphaLight: Float = 0f,
    val dockSurfaceAlphaDark: Float = 0f,
) {
    fun toModel(): LiquidGlassPreset = LiquidGlassPreset(
        id = id,
        name = name,
        globalGlass = globalGlass.toModel(),
        dockGlass = dockGlass.toModel(),
        dockSurfaceAlphaLight = dockSurfaceAlphaLight,
        dockSurfaceAlphaDark = dockSurfaceAlphaDark,
    ).sanitized()

    companion object {
        fun fromModel(model: LiquidGlassPreset): LiquidGlassPresetDto {
            val safe = model.sanitized()
            return LiquidGlassPresetDto(
                id = safe.id,
                name = safe.name,
                globalGlass = GlobalGlassDto.fromModel(safe.globalGlass),
                dockGlass = DockGlassDto.fromModel(safe.dockGlass),
                dockSurfaceAlphaLight = safe.dockSurfaceAlphaLight,
                dockSurfaceAlphaDark = safe.dockSurfaceAlphaDark,
            )
        }
    }
}

@Serializable
internal data class ThemeDto(
    val accentHex: String? = null,
    val tintBackground: Boolean = false,
    val themeMode: String = "SYSTEM",
    val dockSurfaceAlphaLight: Float = 0f,
    val dockSurfaceAlphaDark: Float = 0f,
    val dockGlass: DockGlassDto = DockGlassDto(),
    val globalGlass: GlobalGlassDto = GlobalGlassDto(),
    val savedGlassPresets: List<LiquidGlassPresetDto> = emptyList(),
) {
    fun toModel(): ThemeSettings = ThemeSettings(
        accentHex = accentHex?.takeIf { HEX_COLOR.matches(it) },
        tintBackground = tintBackground,
        themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM),
        dockSurfaceAlphaLight = dockSurfaceAlphaLight.coerceIn(0f, 0.8f),
        dockSurfaceAlphaDark = dockSurfaceAlphaDark.coerceIn(0f, 0.8f),
        dockGlass = dockGlass.toModel(),
        globalGlass = globalGlass.toModel(),
        savedGlassPresets = savedGlassPresets.map { it.toModel() }.distinctBy { it.id }.take(10),
    )

    private companion object {
        val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")
    }
}

@Serializable
internal data class PinnedBuildingDto(
    val campusCode: String = "",
    val campusName: String = "",
    val buildingCode: String = "",
    val buildingName: String = "",
) {
    fun toModel(): PinnedClassroomBuilding = PinnedClassroomBuilding(
        campusCode = campusCode,
        campusName = campusName,
        buildingCode = buildingCode,
        buildingName = buildingName,
    )

    companion object {
        fun fromModel(model: PinnedClassroomBuilding): PinnedBuildingDto = PinnedBuildingDto(
            campusCode = model.campusCode,
            campusName = model.campusName,
            buildingCode = model.buildingCode,
            buildingName = model.buildingName,
        )
    }
}

@Serializable
internal data class PreferencesDto(
    val defaultStartTab: Int = 0,
    val navigationLayout: String = "AUTO",
    val animationsEnabled: Boolean = true,
    val predictiveBackEnabled: Boolean = true,
    val wallpaperScrim: Float = 0.18f,
    /** 旧备份缺省此字段时按 0（关闭）恢复。 */
    val wallpaperBlur: Float = 0f,
    /** null means absent in old backups; an explicit {} means restore homepage defaults. */
    val home: HomePreferencesDto? = null,
    val fontScaleFactor: Float = 1.0f,
    val grayTextMode: String = "DEFAULT",
    val grayTextCustomHex: String = "",
    val savedAccentPresets: List<String> = emptyList(),
    val wallpaperSwitchMode: String = WallpaperSwitchMode.FOLLOW_THEME.name,
    val sunriseCityId: String = "beijing",
    val sunriseCustomMinutes: Int = 6 * 60 + 30,
    val sunsetCustomMinutes: Int = 18 * 60 + 30,
    val sunriseSyncTheme: Boolean = false,
    val wallpaperDynamicColor: Boolean = false,
    val bingAutoUpdateEnabled: Boolean = false,
    val bingAutoUpdateWifiOnly: Boolean = true,
    val bingAutoUpdateTarget: String = BingWallpaperTarget.GLOBAL_LIGHT.name,
    /** null 表示旧备份缺省此字段，恢复时保留本机已有常用置顶楼栋。 */
    val pinnedClassroomBuildings: List<PinnedBuildingDto>? = null,
) {
    fun applyTo(current: AppPreferences): AppPreferences = current.copy(
        defaultStartTab = defaultStartTab,
        navigationLayout = toModel().navigationLayout,
        animationsEnabled = animationsEnabled,
        predictiveBackEnabled = predictiveBackEnabled,
        wallpaperScrim = wallpaperScrim,
        wallpaperBlur = wallpaperBlur,
        home = home?.toModel() ?: current.home,
        fontScaleFactor = fontScaleFactor,
        grayTextMode = runCatching { GrayTextMode.valueOf(grayTextMode) }.getOrDefault(GrayTextMode.DEFAULT),
        grayTextCustomHex = grayTextCustomHex,
        savedAccentPresets = savedAccentPresets,
        wallpaperSwitchMode = runCatching { WallpaperSwitchMode.valueOf(wallpaperSwitchMode) }
            .getOrDefault(WallpaperSwitchMode.FOLLOW_THEME),
        sunriseCityId = sunriseCityId,
        sunriseCustomMinutes = sunriseCustomMinutes,
        sunsetCustomMinutes = sunsetCustomMinutes,
        sunriseSyncTheme = sunriseSyncTheme,
        wallpaperDynamicColor = wallpaperDynamicColor,
        bingAutoUpdateEnabled = bingAutoUpdateEnabled,
        bingAutoUpdateWifiOnly = bingAutoUpdateWifiOnly,
        bingAutoUpdateTarget = runCatching { BingWallpaperTarget.valueOf(bingAutoUpdateTarget) }
            .getOrDefault(BingWallpaperTarget.GLOBAL_LIGHT),
        pinnedClassroomBuildings = pinnedClassroomBuildings?.map { it.toModel() }
            ?: current.pinnedClassroomBuildings,
    ).sanitized()
    fun toModel(): AppPreferences = AppPreferences(
        defaultStartTab = defaultStartTab.coerceIn(0, 3),
        navigationLayout = runCatching { NavigationLayout.valueOf(navigationLayout) }
            .getOrDefault(NavigationLayout.AUTO),
        animationsEnabled = animationsEnabled,
        predictiveBackEnabled = predictiveBackEnabled,
        wallpaperScrim = wallpaperScrim.coerceIn(0f, 0.8f),
        wallpaperBlur = wallpaperBlur.coerceIn(0f, 1f),
        home = home?.toModel() ?: com.gfs.iyango.core.model.HomePreferences(),
        fontScaleFactor = fontScaleFactor,
        grayTextMode = runCatching { GrayTextMode.valueOf(grayTextMode) }.getOrDefault(GrayTextMode.DEFAULT),
        grayTextCustomHex = grayTextCustomHex,
        savedAccentPresets = savedAccentPresets,
        wallpaperSwitchMode = runCatching { WallpaperSwitchMode.valueOf(wallpaperSwitchMode) }
            .getOrDefault(WallpaperSwitchMode.FOLLOW_THEME),
        sunriseCityId = sunriseCityId,
        sunriseCustomMinutes = sunriseCustomMinutes,
        sunsetCustomMinutes = sunsetCustomMinutes,
        sunriseSyncTheme = sunriseSyncTheme,
        wallpaperDynamicColor = wallpaperDynamicColor,
        bingAutoUpdateEnabled = bingAutoUpdateEnabled,
        bingAutoUpdateWifiOnly = bingAutoUpdateWifiOnly,
        bingAutoUpdateTarget = runCatching { BingWallpaperTarget.valueOf(bingAutoUpdateTarget) }
            .getOrDefault(BingWallpaperTarget.GLOBAL_LIGHT),
        pinnedClassroomBuildings = pinnedClassroomBuildings?.map { it.toModel() }.orEmpty(),
    ).sanitized()
}

@Serializable
internal data class DisplayDto(
    val global: DisplayConfigJson = DisplayConfigJson(),
    val byTimetable: Map<String, DisplayConfigJson> = emptyMap(),
)

@Serializable
internal data class ReminderDto(
    val enabled: Boolean = false,
    val leadMinutes: Int = 10,
    val disabledCourseKeys: List<String> = emptyList(),
    val ongoingProgressEnabled: Boolean = true,
    val gradeNotifyEnabled: Boolean = false,
    val gradeCheckIntervalHours: Int = 4,
    val gradeNotifyHideScoreOnLockscreen: Boolean = true,
    val gradeSprintEnabled: Boolean = false,
    val gradeSprintIntervalMinutes: Int = 30,
    val gradeSprintUntilMillis: Long = 0L,
    val gradeSprintDurationDays: Int = 7,
    val gradeContextTriggerEnabled: Boolean = true,
) {
    fun toModel(): ReminderSettings = ReminderSettings(
        enabled = enabled,
        leadMinutes = leadMinutes,
        disabledCourseKeys = disabledCourseKeys.filter { it.isNotBlank() }.toSet(),
        ongoingProgressEnabled = ongoingProgressEnabled,
        gradeNotifyEnabled = gradeNotifyEnabled,
        gradeCheckIntervalHours = gradeCheckIntervalHours,
        gradeNotifyHideScoreOnLockscreen = gradeNotifyHideScoreOnLockscreen,
        gradeSprintEnabled = gradeSprintEnabled,
        gradeSprintIntervalMinutes = gradeSprintIntervalMinutes,
        gradeSprintUntilMillis = gradeSprintUntilMillis,
        gradeSprintDurationDays = gradeSprintDurationDays,
        gradeContextTriggerEnabled = gradeContextTriggerEnabled,
    ).sanitized()
}

/** [DisplayConfigJson] 的逆向构造：保持与课程交换格式同一字段集。 */
private fun toDisplayJson(
    settings: com.gfs.iyango.core.model.ScheduleDisplaySettings,
): DisplayConfigJson = DISPLAY_JSON.decodeFromString(
    DisplayConfigJson.serializer(),
    encodeDisplayConfig(settings),
)

private val DISPLAY_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
