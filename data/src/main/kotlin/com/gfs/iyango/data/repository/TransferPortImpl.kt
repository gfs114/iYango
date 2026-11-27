package com.gfs.iyango.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.DockGlassSettings
import com.gfs.iyango.core.domain.GlobalGlassSettings
import com.gfs.iyango.core.domain.ImportWriteMode
import com.gfs.iyango.core.domain.LiquidGlassPreset
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.gfs.iyango.core.domain.ScheduleExportResult
import com.gfs.iyango.core.domain.ScheduleImportCommitResult
import com.gfs.iyango.core.domain.ScheduleImportItem
import com.gfs.iyango.core.domain.ScheduleImportOptions
import com.gfs.iyango.core.domain.ScheduleImportPreview
import com.gfs.iyango.core.domain.ScheduleImportReadResult
import com.gfs.iyango.core.domain.ScheduleImportTargetInfo
import com.gfs.iyango.core.domain.ScheduleShareCodec
import com.gfs.iyango.core.domain.ScheduleShareExportResult
import com.gfs.iyango.core.domain.ScheduleTransferPort
import com.gfs.iyango.core.domain.ThemeMode
import com.gfs.iyango.core.domain.ThemeSettings
import com.gfs.iyango.core.domain.ThemeSettingsPort
import com.gfs.iyango.data.json.ExportScheduleService
import com.gfs.iyango.data.json.ImportPreview
import com.gfs.iyango.data.json.ImportReadResult
import com.gfs.iyango.data.json.ImportScheduleService
import com.gfs.iyango.data.json.encodeDisplayConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_settings")

/**
 * [ThemeSettingsPort] 的 DataStore 实现：accentHex（null=默认）+ 染背景开关。
 */
@Singleton
class DataStoreThemeSettings internal constructor(
    private val dataStore: DataStore<Preferences>,
) : ThemeSettingsPort {
    @Inject constructor(@ApplicationContext context: Context) : this(context.themeDataStore)

    private object Keys {
        val ACCENT = stringPreferencesKey("accent_hex")
        val TINT = booleanPreferencesKey("tint_background")
        val THEME_MODE = intPreferencesKey("theme_mode")
        val DOCK_ALPHA_LIGHT = floatPreferencesKey("dock_alpha_light")
        val DOCK_ALPHA_DARK = floatPreferencesKey("dock_alpha_dark")
        val DOCK_GLASS_BLUR_DP = floatPreferencesKey("dock_glass_blur_dp")
        val DOCK_GLASS_LENS_WIDTH_DP = floatPreferencesKey("dock_glass_lens_width_dp")
        val DOCK_GLASS_LENS_HEIGHT_DP = floatPreferencesKey("dock_glass_lens_height_dp")
        val DOCK_GLASS_EDGE_WIDTH_DP = floatPreferencesKey("dock_glass_edge_width_dp")
        val DOCK_GLASS_EDGE_BLUR_DP = floatPreferencesKey("dock_glass_edge_blur_dp")
        val DOCK_GLASS_EDGE_ALPHA_BASE = floatPreferencesKey("dock_glass_edge_alpha_base")
        val DOCK_GLASS_EDGE_ALPHA_HIDDEN = floatPreferencesKey("dock_glass_edge_alpha_hidden")
        val DOCK_GLASS_EDGE_ALPHA_INDICATOR = floatPreferencesKey("dock_glass_edge_alpha_indicator")
        val DOCK_GLASS_IND_LENS_WIDTH_DP = floatPreferencesKey("dock_glass_ind_lens_width_dp")
        val DOCK_GLASS_IND_LENS_HEIGHT_DP = floatPreferencesKey("dock_glass_ind_lens_height_dp")
        val DOCK_GLASS_IND_CHROMATIC = booleanPreferencesKey("dock_glass_ind_chromatic")
        val DOCK_GLASS_IND_SURFACE_ALPHA = floatPreferencesKey("dock_glass_ind_surface_alpha")
        val DOCK_GLASS_IND_PRESSED_ALPHA = floatPreferencesKey("dock_glass_ind_pressed_alpha")
        val DOCK_GLASS_IND_SHADOW_RADIUS = floatPreferencesKey("dock_glass_ind_shadow_radius")
        val DOCK_GLASS_IND_SHADOW_ALPHA = floatPreferencesKey("dock_glass_ind_shadow_alpha")
        val DOCK_GLASS_IND_INNER_RADIUS = floatPreferencesKey("dock_glass_ind_inner_radius")
        val DOCK_GLASS_IND_INNER_ALPHA = floatPreferencesKey("dock_glass_ind_inner_alpha")
        val DOCK_GLASS_LEGACY_TIER0_LIGHT = floatPreferencesKey("dock_glass_legacy_tier0_alpha_light")
        val DOCK_GLASS_LEGACY_TIER0_DARK = floatPreferencesKey("dock_glass_legacy_tier0_alpha_dark")
        val GLOBAL_GLASS_JSON = stringPreferencesKey("global_glass_json")
        val SAVED_GLASS_PRESETS_JSON = stringPreferencesKey("saved_glass_presets_json")
    }

    private val globalGlassJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val savedPresetsSerializer = ListSerializer(LiquidGlassPresetDto.serializer())

    override val settings: Flow<ThemeSettings> = dataStore.data.map { prefs ->
        val defaults = DockGlassSettings()
        val parsedGlobalGlass = prefs[Keys.GLOBAL_GLASS_JSON]
            ?.let { raw ->
                runCatching {
                    globalGlassJson.decodeFromString(GlobalGlassDto.serializer(), raw).toModel()
                }.getOrNull()
            }
            ?: GlobalGlassSettings()
        val parsedPresets = prefs[Keys.SAVED_GLASS_PRESETS_JSON]
            ?.let { raw ->
                runCatching {
                    globalGlassJson.decodeFromString(savedPresetsSerializer, raw)
                        .map { it.toModel() }
                        .distinctBy { it.id }
                        .take(10)
                }.getOrNull()
            }
            ?: emptyList()
        ThemeSettings(
            accentHex = prefs[Keys.ACCENT],
            tintBackground = prefs[Keys.TINT] ?: false,
            themeMode = ThemeMode.fromCode(prefs[Keys.THEME_MODE] ?: 0),
            // 蒙层兜底默认值与 ThemeSettings 默认同步（标准 Apple 液态玻璃亮 0.40 / 暗 0.36）。
            dockSurfaceAlphaLight = (prefs[Keys.DOCK_ALPHA_LIGHT] ?: ThemeSettings.DEFAULT_DOCK_SURFACE_ALPHA_LIGHT)
                .coerceIn(0f, 0.8f),
            dockSurfaceAlphaDark = (prefs[Keys.DOCK_ALPHA_DARK] ?: ThemeSettings.DEFAULT_DOCK_SURFACE_ALPHA_DARK)
                .coerceIn(0f, 0.8f),
            dockGlass = DockGlassSettings(
                blurDp = prefs[Keys.DOCK_GLASS_BLUR_DP] ?: defaults.blurDp,
                lensWidthDp = prefs[Keys.DOCK_GLASS_LENS_WIDTH_DP] ?: defaults.lensWidthDp,
                lensHeightDp = prefs[Keys.DOCK_GLASS_LENS_HEIGHT_DP] ?: defaults.lensHeightDp,
                edgeWidthDp = prefs[Keys.DOCK_GLASS_EDGE_WIDTH_DP] ?: defaults.edgeWidthDp,
                edgeBlurDp = prefs[Keys.DOCK_GLASS_EDGE_BLUR_DP] ?: defaults.edgeBlurDp,
                edgeAlphaBase = prefs[Keys.DOCK_GLASS_EDGE_ALPHA_BASE] ?: defaults.edgeAlphaBase,
                edgeAlphaHidden = prefs[Keys.DOCK_GLASS_EDGE_ALPHA_HIDDEN] ?: defaults.edgeAlphaHidden,
                edgeAlphaIndicator = prefs[Keys.DOCK_GLASS_EDGE_ALPHA_INDICATOR] ?: defaults.edgeAlphaIndicator,
                indicatorLensWidthDp = prefs[Keys.DOCK_GLASS_IND_LENS_WIDTH_DP] ?: defaults.indicatorLensWidthDp,
                indicatorLensHeightDp = prefs[Keys.DOCK_GLASS_IND_LENS_HEIGHT_DP] ?: defaults.indicatorLensHeightDp,
                indicatorChromaticAberration = prefs[Keys.DOCK_GLASS_IND_CHROMATIC] ?: defaults.indicatorChromaticAberration,
                indicatorSurfaceAlpha = prefs[Keys.DOCK_GLASS_IND_SURFACE_ALPHA] ?: defaults.indicatorSurfaceAlpha,
                indicatorSurfacePressedAlpha = prefs[Keys.DOCK_GLASS_IND_PRESSED_ALPHA] ?: defaults.indicatorSurfacePressedAlpha,
                indicatorShadowRadiusDp = prefs[Keys.DOCK_GLASS_IND_SHADOW_RADIUS] ?: defaults.indicatorShadowRadiusDp,
                indicatorShadowAlpha = prefs[Keys.DOCK_GLASS_IND_SHADOW_ALPHA] ?: defaults.indicatorShadowAlpha,
                indicatorInnerRadiusDp = prefs[Keys.DOCK_GLASS_IND_INNER_RADIUS] ?: defaults.indicatorInnerRadiusDp,
                indicatorInnerAlpha = prefs[Keys.DOCK_GLASS_IND_INNER_ALPHA] ?: defaults.indicatorInnerAlpha,
                legacyTier0AlphaLight = prefs[Keys.DOCK_GLASS_LEGACY_TIER0_LIGHT] ?: defaults.legacyTier0AlphaLight,
                legacyTier0AlphaDark = prefs[Keys.DOCK_GLASS_LEGACY_TIER0_DARK] ?: defaults.legacyTier0AlphaDark,
            ).sanitized(),
            globalGlass = parsedGlobalGlass,
            savedGlassPresets = parsedPresets,
        )
    }

    override suspend fun update(settings: ThemeSettings) {
        val glass = settings.dockGlass.sanitized()
        val global = settings.globalGlass.sanitized()
        val encodedGlobal = globalGlassJson.encodeToString(
            GlobalGlassDto.serializer(),
            GlobalGlassDto.fromModel(global),
        )
        val normalizedPresets = settings.savedGlassPresets
            .map { it.sanitized() }
            .distinctBy { it.id }
            .take(10)
        val encodedPresets = globalGlassJson.encodeToString(
            savedPresetsSerializer,
            normalizedPresets.map { LiquidGlassPresetDto.fromModel(it) },
        )
        dataStore.edit { prefs ->
            settings.accentHex
                ?.let { prefs[Keys.ACCENT] = it }
                ?: prefs.remove(Keys.ACCENT)
            prefs[Keys.TINT] = settings.tintBackground
            prefs[Keys.THEME_MODE] = settings.themeMode.code
            prefs[Keys.DOCK_ALPHA_LIGHT] = settings.dockSurfaceAlphaLight.coerceIn(0f, 0.8f)
            prefs[Keys.DOCK_ALPHA_DARK] = settings.dockSurfaceAlphaDark.coerceIn(0f, 0.8f)
            prefs[Keys.DOCK_GLASS_BLUR_DP] = glass.blurDp
            prefs[Keys.DOCK_GLASS_LENS_WIDTH_DP] = glass.lensWidthDp
            prefs[Keys.DOCK_GLASS_LENS_HEIGHT_DP] = glass.lensHeightDp
            prefs[Keys.DOCK_GLASS_EDGE_WIDTH_DP] = glass.edgeWidthDp
            prefs[Keys.DOCK_GLASS_EDGE_BLUR_DP] = glass.edgeBlurDp
            prefs[Keys.DOCK_GLASS_EDGE_ALPHA_BASE] = glass.edgeAlphaBase
            prefs[Keys.DOCK_GLASS_EDGE_ALPHA_HIDDEN] = glass.edgeAlphaHidden
            prefs[Keys.DOCK_GLASS_EDGE_ALPHA_INDICATOR] = glass.edgeAlphaIndicator
            prefs[Keys.DOCK_GLASS_IND_LENS_WIDTH_DP] = glass.indicatorLensWidthDp
            prefs[Keys.DOCK_GLASS_IND_LENS_HEIGHT_DP] = glass.indicatorLensHeightDp
            prefs[Keys.DOCK_GLASS_IND_CHROMATIC] = glass.indicatorChromaticAberration
            prefs[Keys.DOCK_GLASS_IND_SURFACE_ALPHA] = glass.indicatorSurfaceAlpha
            prefs[Keys.DOCK_GLASS_IND_PRESSED_ALPHA] = glass.indicatorSurfacePressedAlpha
            prefs[Keys.DOCK_GLASS_IND_SHADOW_RADIUS] = glass.indicatorShadowRadiusDp
            prefs[Keys.DOCK_GLASS_IND_SHADOW_ALPHA] = glass.indicatorShadowAlpha
            prefs[Keys.DOCK_GLASS_IND_INNER_RADIUS] = glass.indicatorInnerRadiusDp
            prefs[Keys.DOCK_GLASS_IND_INNER_ALPHA] = glass.indicatorInnerAlpha
            prefs[Keys.DOCK_GLASS_LEGACY_TIER0_LIGHT] = glass.legacyTier0AlphaLight
            prefs[Keys.DOCK_GLASS_LEGACY_TIER0_DARK] = glass.legacyTier0AlphaDark
            prefs[Keys.GLOBAL_GLASS_JSON] = encodedGlobal
            prefs[Keys.SAVED_GLASS_PRESETS_JSON] = encodedPresets
        }
    }
}

/**
 * [ScheduleTransferPort] 的 data 层外观：包装 W5 的导入/导出服务。
 *
 * 预览 → 提交的 token 即文件摘要；解析文档缓存在本单例的进程内映射中，
 * 提交完成后清除，避免长期持有大对象。
 *
 * 显示配置的提交边界：Room 事务成功后才写 DataStore；写入失败不回滚课程，但把失败
 * 追加到结果 issues 中，界面必须如实展示。
 */
@Singleton
class RoomScheduleTransferPort @Inject constructor(
    private val exportService: ExportScheduleService,
    private val importService: ImportScheduleService,
    private val appSettings: AppSettingsPort,
) : ScheduleTransferPort {

    /** token(digest) → 预览（含解析文档）；并发安全，避免同时读两个文件时丢会话。 */
    private val sessions = ConcurrentHashMap<String, ImportPreview>()

    override suspend fun export(timetableId: Long): ScheduleExportResult {
        val bundle = appSettings.displaySettings.first()
        val displayConfig = encodeDisplayConfig(bundle.forTimetable(timetableId))
        return exportService.export(timetableId, displayConfig = displayConfig).let {
            ScheduleExportResult(
                json = it.json,
                fileName = it.fileName,
                courseCount = it.courseCount,
                timeSlotCount = it.timeSlotCount,
            )
        }
    }

    override suspend fun exportShareCode(timetableId: Long): ScheduleShareExportResult {
        val compact = exportService.exportCompactScheduleJson(timetableId)
        val shareCode = ScheduleShareCodec.encode(compact.json)
        return ScheduleShareExportResult(
            shareCode = shareCode,
            timetableName = compact.timetableName,
            courseCount = compact.courseCount,
            timeSlotCount = compact.timeSlotCount,
            byteLength = shareCode.toByteArray(Charsets.UTF_8).size,
        )
    }

    override suspend fun read(bytes: ByteArray): ScheduleImportReadResult =
        when (val result = importService.read(bytes)) {
            is ImportReadResult.Ready -> {
                val preview = result.preview
                sessions[preview.fileDigest] = preview
                ScheduleImportReadResult.Ready(preview.toPort())
            }
            is ImportReadResult.Failed -> ScheduleImportReadResult.Failed(
                message = result.message,
                issues = result.issues,
            )
        }

    override suspend fun inspectTarget(timetableId: Long): ScheduleImportTargetInfo? =
        importService.inspectTarget(timetableId)

    override suspend fun commit(
        token: String,
        options: ScheduleImportOptions,
    ): ScheduleImportCommitResult {
        val preview = sessions[token]
            ?: throw IllegalStateException("导入会话已失效，请重新选择文件")
        val result = importService.commit(
            preview = preview,
            targetTimetableId = options.targetTimetableId,
            replace = options.mode == ImportWriteMode.REPLACE,
            force = options.force,
            mode = options.mode,
            selectedRowKeys = options.selectedRowKeys,
            applySemester = options.applySemester,
            applyTimeSlots = options.applyTimeSlots,
            applyDisplayConfig = options.applyDisplayConfig,
        )
        val issues = result.issues.toMutableList()
        if (result.displayConfig != null && options.applyDisplayConfig) {
            runCatching {
                appSettings.updateTimetableDisplay(result.timetableId, result.displayConfig)
            }.onFailure {
                issues += "课程已导入，但显示设置保存失败（保留本机设置）"
            }
        }
        sessions.remove(token)
        return ScheduleImportCommitResult(
            timetableId = result.timetableId,
            replaced = result.replaced,
            appended = result.appended,
            courseCount = result.courseCount,
            issues = issues,
        )
    }

    private fun ImportPreview.toPort() = ScheduleImportPreview(
        courseCount = courseCount,
        issueCount = issueCount,
        issues = issues,
        semesterName = semesterName,
        timeSlotCount = timeSlotCount,
        fileDigest = fileDigest,
        duplicateExisting = duplicateExisting,
        token = fileDigest,
        items = rows.map { row ->
            ScheduleImportItem(
                rowKey = row.rowKey,
                fileId = row.course.id,
                name = row.course.name,
                teacher = row.course.teacher,
                classroom = row.course.classroom,
                dayOfWeek = row.course.dayOfWeek,
                startSection = row.course.startSection,
                endSection = row.course.endSection,
                startWeek = row.course.startWeek,
                endWeek = row.course.endWeek,
                weekType = row.course.weekType,
                isAdjustment = row.course.overrideOfId > 0,
                parentRowKey = row.parentRowKey,
                timeMode = row.course.timeMode,
                date = row.course.date,
            )
        },
        hasDisplayConfig = displayConfig != null,
    )
}
