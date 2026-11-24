package com.gfs.iyango.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gfs.iyango.core.model.SystemMappingKind
import com.gfs.iyango.core.model.WidgetAppearance
import com.gfs.iyango.core.model.WidgetLayout

/**
 * 应用拥有的系统记录映射（提醒/日历/组件）。
 *
 * [externalId] 是本机 ID，不参与设置备份；[payloadHash]/[planRevision] 用于
 * 判断是否需要更新或丢弃旧计划。
 */
@Entity(
    tableName = "system_mapping",
    indices = [
        Index(value = ["kind", "occurrenceId", "ownerId"], unique = true),
        Index(value = ["kind", "ownerId"]),
    ],
)
data class SystemMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SystemMappingKind,
    val occurrenceId: String,
    val externalId: String,
    val payloadHash: String,
    val planRevision: Int,
    val ownerId: String,
    val updatedAt: Long,
)

/** 桌面组件实例配置（仅本机）。 */
@Entity(tableName = "widget_config")
data class WidgetConfigEntity(
    @PrimaryKey val widgetId: Int,
    val timetableId: Long,
    val layout: WidgetLayout,
    val appearance: WidgetAppearance,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "25") val glassOpacityPercent: Int = 25,
    @ColumnInfo(defaultValue = "13") val textSizeSp: Int = 13,
    @ColumnInfo(defaultValue = "0") val showTeacher: Boolean = false,
    @ColumnInfo(defaultValue = "1") val showLocation: Boolean = true,
    // v6 WakeUp 样式对齐列；默认值与 MIGRATION_5_6 的 ALTER TABLE DEFAULT 逐字一致。
    @ColumnInfo(defaultValue = "1") val showBg: Boolean = true,
    @ColumnInfo(defaultValue = "-654311425") val bgColor: Int = 0xD8FFFFFF.toInt(),
    @ColumnInfo(defaultValue = "") val backgroundUri: String = "",
    @ColumnInfo(defaultValue = "1") val showHeaderArea: Boolean = true,
    @ColumnInfo(defaultValue = "1") val showDate: Boolean = true,
    @ColumnInfo(defaultValue = "1") val showButton: Boolean = true,
    @ColumnInfo(defaultValue = "1") val showColor: Boolean = true,
    @ColumnInfo(defaultValue = "-16777216") val textColor: Int = 0xFF000000.toInt(),
    @ColumnInfo(defaultValue = "-1") val courseTextColor: Int = 0xFFFFFFFF.toInt(),
    @ColumnInfo(defaultValue = "-2130706433") val strokeColor: Int = 0x80FFFFFF.toInt(),
    @ColumnInfo(defaultValue = "0") val textColorCompose: Boolean = false,
    @ColumnInfo(defaultValue = "0") val strokeColorCompose: Boolean = false,
    @ColumnInfo(defaultValue = "12") val headerTextSizeSp: Int = 12,
    @ColumnInfo(defaultValue = "64") val itemHeightDp: Int = 64,
    @ColumnInfo(defaultValue = "4") val radiusDp: Int = 4,
    @ColumnInfo(defaultValue = "50") val itemAlpha: Int = 50,
    @ColumnInfo(defaultValue = "50") val otherWeekCourseAlpha: Int = 50,
    @ColumnInfo(defaultValue = "0") val itemCenterHorizontal: Boolean = false,
    @ColumnInfo(defaultValue = "0") val itemCenterVertical: Boolean = false,
    @ColumnInfo(defaultValue = "0") val showTime: Boolean = false,
    @ColumnInfo(defaultValue = "1") val showRoomPrefix: Boolean = true,
    @ColumnInfo(defaultValue = "1") val showTimeBar: Boolean = true,
    @ColumnInfo(defaultValue = "1") val showSat: Boolean = true,
    @ColumnInfo(defaultValue = "1") val showSun: Boolean = true,
    @ColumnInfo(defaultValue = "1") val showOtherWeekCourse: Boolean = true,
    @ColumnInfo(defaultValue = "0") val showGrid: Boolean = false,
    @ColumnInfo(defaultValue = "0") val useDottedLine: Boolean = false,
)

/**
 * 教室查询/元数据缓存。
 *
 * [cacheKey] 由实现方按查询维度生成（校区、教学楼、周次、星期、节次组）。
 */
@Entity(
    tableName = "classroom_cache",
    indices = [Index(value = ["connectionId", "termValue"])],
)
data class ClassroomCacheEntity(
    @PrimaryKey val cacheKey: String,
    val connectionId: String,
    val termValue: String,
    val kind: String,
    val payloadJson: String,
    val fetchedAt: Long,
)
