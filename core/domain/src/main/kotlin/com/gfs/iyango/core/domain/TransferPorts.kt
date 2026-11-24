package com.gfs.iyango.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * 课表 JSON 导入导出端口（实现在 data 层，W5 服务的外观）。
 *
 * 预览与提交之间用不透明 [ScheduleImportPreview.token]（文件摘要）衔接：
 * token 仅在本次进程内有效，由实现方缓存解析文档。
 */
interface ScheduleTransferPort {

    /** 导出指定课表为 v1 交换文档（含该课表生效中的显示配置）。 */
    suspend fun export(timetableId: Long): ScheduleExportResult

    /** 导出指定课表为同班分享短口令（不包含个人显示配置）。 */
    suspend fun exportShareCode(timetableId: Long): ScheduleShareExportResult =
        throw UnsupportedOperationException("当前实现暂不支持口令分享导出")

    /** 读取并解析导入文件（不落库）；成功返回预览与 token。 */
    suspend fun read(bytes: ByteArray): ScheduleImportReadResult

    /** 查看追加/替换目标的现状（学期/作息/课程数），用于预览冲突提示；不存在返回 null。 */
    suspend fun inspectTarget(timetableId: Long): ScheduleImportTargetInfo?

    /**
     * 提交导入。
     *
     * 调用方必须在发起前冻结 [options]（模式/目标/选择集/配置采纳项）；实现方不得
     * 再读取界面状态。写库在单个 Room 事务内完成，显示配置在事务成功后单独写入。
     *
     * @param token [ScheduleImportPreview.token]。
     * @param options 冻结后的写入参数。
     */
    suspend fun commit(
        token: String,
        options: ScheduleImportOptions,
    ): ScheduleImportCommitResult
}

/** 导入写入模式。 */
enum class ImportWriteMode {
    /** 新建课表导入（可只选部分课程）。 */
    CREATE,

    /** 追加到现有 LOCAL 课表：原课程保留，新记录重新分配 id。 */
    APPEND,

    /** 替换现有 LOCAL 课表的全部内容（必须选择全部课程）。 */
    REPLACE,
}

/**
 * 一次导入提交的冻结参数。
 *
 * @property mode 写入模式。
 * @property targetTimetableId APPEND/REPLACE 的目标课表；CREATE 忽略。
 * @property selectedRowKeys 选中的预览行键；null 表示全部。REPLACE 必须为全部。
 * @property applySemester 是否采用文件学期。APPEND 下仅在目标课表没有学期时自动采用，
 *   已有学期时不覆盖（由实现给出提示）。
 * @property applyTimeSlots 是否采用文件作息。APPEND 下为 true 会替换目标课表现有作息，
 *   必须由用户在预览中显式确认。
 * @property applyDisplayConfig 是否采用文件显示配置（写入该课表的覆盖配置）。
 * @property force 重复文件二次确认后传 true。
 */
data class ScheduleImportOptions(
    val mode: ImportWriteMode,
    val targetTimetableId: Long? = null,
    val selectedRowKeys: Set<String>? = null,
    val applySemester: Boolean = true,
    val applyTimeSlots: Boolean = true,
    val applyDisplayConfig: Boolean = true,
    val force: Boolean = false,
)

/** 导出结果。 */
data class ScheduleExportResult(
    val json: String,
    val fileName: String,
    val courseCount: Int,
    val timeSlotCount: Int,
)

/** 同班分享口令导出结果。 */
data class ScheduleShareExportResult(
    val shareCode: String,
    val timetableName: String,
    val courseCount: Int,
    val timeSlotCount: Int,
    val byteLength: Int,
)

/** 导入文件预览。 */
data class ScheduleImportPreview(
    val courseCount: Int,
    val issueCount: Int,
    val issues: List<String>,
    val semesterName: String?,
    val timeSlotCount: Int,
    val fileDigest: String,
    val duplicateExisting: Boolean,
    /** 提交用 token（= fileDigest）。 */
    val token: String,
    /** 逐条安排（选择/搜索用），顺序与文件一致。 */
    val items: List<ScheduleImportItem> = emptyList(),
    /** 文件是否携带可解析的显示配置。 */
    val hasDisplayConfig: Boolean = false,
)

/**
 * 预览中的一条安排。
 *
 * [rowKey] 只在本次预览内稳定（课程选择状态用它，不用名称）。
 *
 * @property rowKey 本次导入内的稳定行键。
 * @property fileId 文件内旧 id；0 表示文件未携带。
 * @property parentRowKey 调整记录指向的基础课行键；非调整为 null。
 * @property isAdjustment 是否单次调整记录。
 */
data class ScheduleImportItem(
    val rowKey: String,
    val fileId: Long,
    val name: String,
    val teacher: String,
    val classroom: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int,
    val isAdjustment: Boolean,
    val parentRowKey: String? = null,
    val timeMode: Int = 0,
    val date: String = "",
)

/** [ScheduleTransferPort.read] 的结果。 */
sealed interface ScheduleImportReadResult {
    data class Ready(val preview: ScheduleImportPreview) : ScheduleImportReadResult
    data class Failed(val message: String, val issues: List<String>) : ScheduleImportReadResult
}

/** 导入提交结果。 */
data class ScheduleImportCommitResult(
    val timetableId: Long,
    val replaced: Boolean,
    val appended: Boolean,
    val courseCount: Int,
    /** 提交阶段提示（学期/作息冲突、显示配置未应用等）。 */
    val issues: List<String>,
)

/**
 * 追加/替换目标的现状快照（只读，用于预览阶段的冲突提示）。
 *
 * @property semesterName 目标当前学期名；null=未设置。
 * @property timeSlotCount 目标现有作息行数。
 * @property courseCount 目标现有课程数（追加后为总数基线）。
 */
data class ScheduleImportTargetInfo(
    val timetableId: Long,
    val name: String,
    val isLocal: Boolean,
    val courseCount: Int,
    val semesterName: String?,
    val timeSlotCount: Int,
)

/**
 * 主题外观设置端口（实现在 data 层 DataStore）。
 */
interface ThemeSettingsPort {

    /** 观察设置；无记录时发射默认值（跟随经典橙、不染背景）。 */
    val settings: Flow<ThemeSettings>

    /** 更新设置。 */
    suspend fun update(settings: ThemeSettings)
}

/** 深色模式策略。 */
enum class ThemeMode(val code: Int, val label: String) {
    /** 跟随系统深色模式（默认）。 */
    SYSTEM(0, "跟随系统"),

    /** 强制浅色。 */
    LIGHT(1, "浅色"),

    /** 强制深色。 */
    DARK(2, "深色");

    companion object {
        fun fromCode(code: Int): ThemeMode = entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

/** 液态玻璃底栏高级视觉参数（默认值与现项目 DockGlass 一致）。 */
data class DockGlassSettings(
    /** 基础条与隐藏层背景模糊半径（dp），0 = 纯折射无模糊。 */
    val blurDp: Float = 2f,
    /** 基础条透镜宽（dp）。 */
    val lensWidthDp: Float = 12f,
    /** 基础条透镜高（dp）。 */
    val lensHeightDp: Float = 14f,
    /** 细亮边宽度（dp）。 */
    val edgeWidthDp: Float = 0.5f,
    /** 细亮边模糊半径（dp）。 */
    val edgeBlurDp: Float = 0.6f,
    /** 基础条细亮边不透明度（0..1）。 */
    val edgeAlphaBase: Float = 0.22f,
    /** 隐藏层细亮边峰值（0..1）。 */
    val edgeAlphaHidden: Float = 0.25f,
    /** 指示器细亮边峰值（0..1）。 */
    val edgeAlphaIndicator: Float = 0.35f,
    /** 指示器按压透镜宽（dp）。 */
    val indicatorLensWidthDp: Float = 8f,
    /** 指示器按压透镜高（dp）。 */
    val indicatorLensHeightDp: Float = 10f,
    /** 指示器透镜色散开关。 */
    val indicatorChromaticAberration: Boolean = true,
    /** 指示器常态底色不透明度（0..0.5）。 */
    val indicatorSurfaceAlpha: Float = 0.02f,
    /** 指示器按压加深层不透明度（0..0.5）。 */
    val indicatorSurfacePressedAlpha: Float = 0.02f,
    /** 指示器外投影半径（dp）。 */
    val indicatorShadowRadiusDp: Float = 12f,
    /** 指示器外投影峰值不透明度（0..1）。 */
    val indicatorShadowAlpha: Float = 0.3f,
    /** 指示器内阴影峰值半径（dp）。 */
    val indicatorInnerRadiusDp: Float = 4f,
    /** 指示器内阴影峰值不透明度（0..1）。 */
    val indicatorInnerAlpha: Float = 0.4f,
    /** Android 8-11 浅色主题老设备底栏实体底色不透明度（0..1）。 */
    val legacyTier0AlphaLight: Float = 0.55f,
    /** Android 8-11 暗色主题老设备底栏实体底色不透明度（0..1）。 */
    val legacyTier0AlphaDark: Float = 0.35f,
) {
    /** 将非有限浮点与越界数值钳制到安全渲染范围，避免底层 RenderEffect/RuntimeShader 异常。 */
    fun sanitized(): DockGlassSettings = DockGlassSettings(
        blurDp = blurDp.finiteOr(2f).coerceIn(0f, 24f),
        lensWidthDp = lensWidthDp.finiteOr(12f).coerceIn(0f, 40f),
        lensHeightDp = lensHeightDp.finiteOr(14f).coerceIn(0f, 40f),
        edgeWidthDp = edgeWidthDp.finiteOr(0.5f).coerceIn(0f, 3f),
        edgeBlurDp = edgeBlurDp.finiteOr(0.6f).coerceIn(0f, 4f),
        edgeAlphaBase = edgeAlphaBase.finiteOr(0.22f).coerceIn(0f, 1f),
        edgeAlphaHidden = edgeAlphaHidden.finiteOr(0.25f).coerceIn(0f, 1f),
        edgeAlphaIndicator = edgeAlphaIndicator.finiteOr(0.35f).coerceIn(0f, 1f),
        indicatorLensWidthDp = indicatorLensWidthDp.finiteOr(8f).coerceIn(0f, 32f),
        indicatorLensHeightDp = indicatorLensHeightDp.finiteOr(10f).coerceIn(0f, 32f),
        indicatorChromaticAberration = indicatorChromaticAberration,
        indicatorSurfaceAlpha = indicatorSurfaceAlpha.finiteOr(0.02f).coerceIn(0f, 0.5f),
        indicatorSurfacePressedAlpha = indicatorSurfacePressedAlpha.finiteOr(0.02f).coerceIn(0f, 0.5f),
        indicatorShadowRadiusDp = indicatorShadowRadiusDp.finiteOr(12f).coerceIn(0f, 32f),
        indicatorShadowAlpha = indicatorShadowAlpha.finiteOr(0.3f).coerceIn(0f, 1f),
        indicatorInnerRadiusDp = indicatorInnerRadiusDp.finiteOr(4f).coerceIn(0f, 20f),
        indicatorInnerAlpha = indicatorInnerAlpha.finiteOr(0.4f).coerceIn(0f, 1f),
        legacyTier0AlphaLight = legacyTier0AlphaLight.finiteOr(0.55f).coerceIn(0f, 1f),
        legacyTier0AlphaDark = legacyTier0AlphaDark.finiteOr(0.35f).coerceIn(0f, 1f),
    )

    companion object {
        private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
    }
}

/** 单类全局液态玻璃（导航栏 / 卡片 / 控件 / 浮层）材质参数，所有数值均允许调为 0（全透明无模糊无折射）。 */
data class GlassLayerSettings(
    val blurDp: Float,
    val lensWidthDp: Float,
    val lensHeightDp: Float,
    val edgeWidthDp: Float,
    val edgeBlurDp: Float,
    val edgeAlpha: Float,
    val tintAlphaLight: Float,
    val tintAlphaDark: Float,
    val tier0AlphaLight: Float,
    val tier0AlphaDark: Float,
    val depthEffect: Boolean = false,
    val chromaticAberration: Boolean = false,
) {
    fun sanitized(fallback: GlassLayerSettings): GlassLayerSettings = GlassLayerSettings(
        blurDp = blurDp.finiteOr(fallback.blurDp).coerceIn(0f, 32f),
        lensWidthDp = lensWidthDp.finiteOr(fallback.lensWidthDp).coerceIn(0f, 40f),
        lensHeightDp = lensHeightDp.finiteOr(fallback.lensHeightDp).coerceIn(0f, 40f),
        edgeWidthDp = edgeWidthDp.finiteOr(fallback.edgeWidthDp).coerceIn(0f, 3f),
        edgeBlurDp = edgeBlurDp.finiteOr(fallback.edgeBlurDp).coerceIn(0f, 4f),
        edgeAlpha = edgeAlpha.finiteOr(fallback.edgeAlpha).coerceIn(0f, 1f),
        tintAlphaLight = tintAlphaLight.finiteOr(fallback.tintAlphaLight).coerceIn(0f, 1f),
        tintAlphaDark = tintAlphaDark.finiteOr(fallback.tintAlphaDark).coerceIn(0f, 1f),
        tier0AlphaLight = tier0AlphaLight.finiteOr(fallback.tier0AlphaLight).coerceIn(0f, 1f),
        tier0AlphaDark = tier0AlphaDark.finiteOr(fallback.tier0AlphaDark).coerceIn(0f, 1f),
        depthEffect = depthEffect,
        chromaticAberration = chromaticAberration,
    )

    fun cleared(): GlassLayerSettings = copy(
        blurDp = 0f,
        lensWidthDp = 0f,
        lensHeightDp = 0f,
        edgeAlpha = 0f,
        tintAlphaLight = 0f,
        tintAlphaDark = 0f,
        tier0AlphaLight = 0f,
        tier0AlphaDark = 0f,
        depthEffect = false,
        chromaticAberration = false,
    )

    companion object {
        private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
    }
}

/** 全项目四类全局液态玻璃（NAVIGATION / CONTENT / CONTROL / OVERLAY）可调配置。 */
data class GlobalGlassSettings(
    val navigation: GlassLayerSettings = DefaultNavigation,
    val content: GlassLayerSettings = DefaultContent,
    val control: GlassLayerSettings = DefaultControl,
    val overlay: GlassLayerSettings = DefaultOverlay,
) {
    fun sanitized(): GlobalGlassSettings = GlobalGlassSettings(
        navigation = navigation.sanitized(DefaultNavigation),
        content = content.sanitized(DefaultContent),
        control = control.sanitized(DefaultControl),
        overlay = overlay.sanitized(DefaultOverlay),
    )

    companion object {
        val DefaultNavigation = GlassLayerSettings(
            blurDp = 3f,
            lensWidthDp = 12f,
            lensHeightDp = 14f,
            edgeWidthDp = 0.5f,
            edgeBlurDp = 0.6f,
            edgeAlpha = 0.22f,
            tintAlphaLight = 0f,
            tintAlphaDark = 0f,
            tier0AlphaLight = 0.55f,
            tier0AlphaDark = 0.35f,
        )
        val DefaultContent = GlassLayerSettings(
            blurDp = 14f,
            lensWidthDp = 10f,
            lensHeightDp = 12f,
            edgeWidthDp = 0.6f,
            edgeBlurDp = 0.8f,
            edgeAlpha = 0.34f,
            tintAlphaLight = 0.48f,
            tintAlphaDark = 0.38f,
            tier0AlphaLight = 0.95f,
            tier0AlphaDark = 0.88f,
        )
        val DefaultControl = GlassLayerSettings(
            blurDp = 4f,
            lensWidthDp = 12f,
            lensHeightDp = 24f,
            edgeWidthDp = 0.6f,
            edgeBlurDp = 0.6f,
            edgeAlpha = 0.36f,
            tintAlphaLight = 0.32f,
            tintAlphaDark = 0.24f,
            tier0AlphaLight = 0.90f,
            tier0AlphaDark = 0.80f,
        )
        val DefaultOverlay = GlassLayerSettings(
            blurDp = 24f,
            lensWidthDp = 14f,
            lensHeightDp = 20f,
            edgeWidthDp = 0.75f,
            edgeBlurDp = 0.8f,
            edgeAlpha = 0.42f,
            tintAlphaLight = 0.72f,
            tintAlphaDark = 0.62f,
            tier0AlphaLight = 0.96f,
            tier0AlphaDark = 0.90f,
        )
        val ClearPreset = GlobalGlassSettings(
            navigation = DefaultNavigation.cleared(),
            content = DefaultContent.cleared(),
            control = DefaultControl.cleared(),
            overlay = DefaultOverlay.cleared(),
        )
    }
}

/** 液态玻璃风格预设方案（涵盖全局四类 + 底栏玻璃参数）。 */
data class LiquidGlassPreset(
    val id: String,
    val name: String,
    val globalGlass: GlobalGlassSettings = GlobalGlassSettings(),
    val dockGlass: DockGlassSettings = DockGlassSettings(),
    val dockSurfaceAlphaLight: Float = 0f,
    val dockSurfaceAlphaDark: Float = 0f,
) {
    fun sanitized(): LiquidGlassPreset = copy(
        id = id.trim().ifBlank { "custom" },
        name = name.trim().take(24).ifBlank { "自定义方案" },
        globalGlass = globalGlass.sanitized(),
        dockGlass = dockGlass.sanitized(),
        dockSurfaceAlphaLight = dockSurfaceAlphaLight.takeIf { it.isFinite() }?.coerceIn(0f, 0.8f) ?: 0f,
        dockSurfaceAlphaDark = dockSurfaceAlphaDark.takeIf { it.isFinite() }?.coerceIn(0f, 0.8f) ?: 0f,
    )

    companion object {
        val BuiltIns: List<LiquidGlassPreset> = listOf(
            LiquidGlassPreset(
                id = "default",
                name = "默认均衡",
                globalGlass = GlobalGlassSettings(),
                dockGlass = DockGlassSettings(),
                dockSurfaceAlphaLight = 0f,
                dockSurfaceAlphaDark = 0f,
            ),
            LiquidGlassPreset(
                id = "clear",
                name = "极简全透",
                globalGlass = GlobalGlassSettings.ClearPreset,
                dockGlass = DockGlassSettings(
                    blurDp = 0f,
                    lensWidthDp = 0f,
                    lensHeightDp = 0f,
                    edgeAlphaBase = 0f,
                    edgeAlphaHidden = 0f,
                    edgeAlphaIndicator = 0f,
                    indicatorLensWidthDp = 0f,
                    indicatorLensHeightDp = 0f,
                    indicatorSurfaceAlpha = 0f,
                    indicatorSurfacePressedAlpha = 0f,
                    indicatorShadowAlpha = 0f,
                    indicatorInnerAlpha = 0f,
                    legacyTier0AlphaLight = 0f,
                    legacyTier0AlphaDark = 0f,
                ),
                dockSurfaceAlphaLight = 0f,
                dockSurfaceAlphaDark = 0f,
            ),
            LiquidGlassPreset(
                id = "crystal",
                name = "清透水滴",
                globalGlass = GlobalGlassSettings(
                    navigation = GlobalGlassSettings.DefaultNavigation.copy(
                        blurDp = 6f,
                        lensWidthDp = 16f,
                        lensHeightDp = 20f,
                        edgeAlpha = 0.46f,
                        tintAlphaLight = 0.04f,
                        tintAlphaDark = 0.04f,
                    ),
                    content = GlobalGlassSettings.DefaultContent.copy(
                        blurDp = 6f,
                        lensWidthDp = 16f,
                        lensHeightDp = 20f,
                        edgeAlpha = 0.46f,
                        tintAlphaLight = 0.14f,
                        tintAlphaDark = 0.10f,
                    ),
                    control = GlobalGlassSettings.DefaultControl.copy(
                        blurDp = 4f,
                        lensWidthDp = 16f,
                        lensHeightDp = 20f,
                        edgeAlpha = 0.46f,
                        tintAlphaLight = 0.08f,
                        tintAlphaDark = 0.06f,
                    ),
                    overlay = GlobalGlassSettings.DefaultOverlay.copy(
                        blurDp = 14f,
                        lensWidthDp = 16f,
                        lensHeightDp = 20f,
                        edgeAlpha = 0.46f,
                        tintAlphaLight = 0.40f,
                        tintAlphaDark = 0.34f,
                    ),
                ),
                dockGlass = DockGlassSettings(
                    blurDp = 1.5f,
                    lensWidthDp = 14f,
                    lensHeightDp = 16f,
                    edgeAlphaBase = 0.35f,
                ),
                dockSurfaceAlphaLight = 0f,
                dockSurfaceAlphaDark = 0f,
            ),
            LiquidGlassPreset(
                id = "frosted",
                name = "高可读磨砂",
                globalGlass = GlobalGlassSettings(
                    navigation = GlobalGlassSettings.DefaultNavigation.copy(
                        blurDp = 10f,
                        lensWidthDp = 4f,
                        lensHeightDp = 6f,
                        edgeAlpha = 0.20f,
                        tintAlphaLight = 0.25f,
                        tintAlphaDark = 0.25f,
                    ),
                    content = GlobalGlassSettings.DefaultContent.copy(
                        blurDp = 22f,
                        lensWidthDp = 4f,
                        lensHeightDp = 6f,
                        edgeAlpha = 0.22f,
                        tintAlphaLight = 0.72f,
                        tintAlphaDark = 0.62f,
                    ),
                    control = GlobalGlassSettings.DefaultControl.copy(
                        blurDp = 10f,
                        lensWidthDp = 4f,
                        lensHeightDp = 6f,
                        edgeAlpha = 0.24f,
                        tintAlphaLight = 0.50f,
                        tintAlphaDark = 0.40f,
                    ),
                    overlay = GlobalGlassSettings.DefaultOverlay.copy(
                        blurDp = 28f,
                        lensWidthDp = 4f,
                        lensHeightDp = 6f,
                        edgeAlpha = 0.28f,
                        tintAlphaLight = 0.85f,
                        tintAlphaDark = 0.78f,
                    ),
                ),
                dockGlass = DockGlassSettings(
                    blurDp = 12f,
                    lensWidthDp = 6f,
                    lensHeightDp = 8f,
                ),
                dockSurfaceAlphaLight = 0.18f,
                dockSurfaceAlphaDark = 0.30f,
            ),
        )
    }
}

/** 主题外观设置。 */
data class ThemeSettings(
    val accentHex: String? = null,
    val tintBackground: Boolean = false,
    /** 深色模式策略（跟随系统/浅色/深色）。 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 液态玻璃底栏可读性蒙层不透明度（亮色模式），范围 0..0.8；默认 0 = 无蒙层（纯折射玻璃）。 */
    val dockSurfaceAlphaLight: Float = 0f,
    /** 液态玻璃底栏可读性蒙层不透明度（暗色模式），范围 0..0.8；默认 0 = 无蒙层（纯折射玻璃）。 */
    val dockSurfaceAlphaDark: Float = 0f,
    /** 液态玻璃底栏高级视觉参数。 */
    val dockGlass: DockGlassSettings = DockGlassSettings(),
    /** 全局四类液态玻璃参数（导航栏 / 卡片内容 / 交互控件 / 弹窗浮层）。 */
    val globalGlass: GlobalGlassSettings = GlobalGlassSettings(),
    /** 用户保存的自定义液态玻璃预设方案（最多 10 个）。 */
    val savedGlassPresets: List<LiquidGlassPreset> = emptyList(),
)
