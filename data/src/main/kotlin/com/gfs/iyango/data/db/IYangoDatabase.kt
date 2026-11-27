package com.gfs.iyango.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * 应用主数据库（Room）。
 *
 * - version = 8，`exportSchema = true`：schema JSON 由 KSP 导出到 `data/schemas/`；
 *   迁移路径（v1→v8）见 [IYangoDatabaseMigrations]；
 * - v2：course 表唯一索引 `index_course_override`（timetableId+overrideOfId+
 *   overrideWeek），非调整行以 NULL 落库获得"部分唯一"语义；
 * - v3：教务连接、考试、成绩快照/记录、同步批次/变化、学校快照、临时调整、
 *   临时整天、系统映射、组件配置与教室缓存；调整/临时整天外键指向课表并级联删除；
 * - v4：course 增加学校稳定来源键 `sourceKey`（空=本机课程），同步按该键保留行 id；
 * - v5：widget_config 增加背景透明度、字号、教师与地点显示偏好，保留旧实例外观；
 * - v6：widget_config 增加 WakeUp 样式对齐列（背景色/背景图、顶栏与按钮、经典渲染
 *   颜色与尺寸、周视图开关），存量卡片按 MIGRATION_5_6 从 appearance 与
 *   glassOpacityPercent 平滑推导 showBg/bgColor；
 * - v7：成绩快照与记录扩展完整性及原始属性列，新增学分目标、分类配额与人工消歧规则表；
 * - v8：新增 AI 助理会话表 `ai_chat_session` 与消息表 `ai_chat_message`（外键级联删除）；
 * - 转换器：[Converters]（LocalDate、List<Int>）。
 */
@Database(
    entities = [
        TimetableEntity::class,
        SemesterEntity::class,
        TimeSlotEntity::class,
        CourseEntity::class,
        ImportRecordEntity::class,
        SchoolConnectionEntity::class,
        ExamEntity::class,
        GradeSnapshotEntity::class,
        GradeRecordEntity::class,
        SyncBatchEntity::class,
        SyncChangeEntity::class,
        SchoolSnapshotEntity::class,
        OccurrenceAdjustmentEntity::class,
        TemporaryDayEntity::class,
        SystemMappingEntity::class,
        WidgetConfigEntity::class,
        ClassroomCacheEntity::class,
        CreditPlanEntity::class,
        CreditCategoryPlanEntity::class,
        CreditResolutionRuleEntity::class,
        AiChatSessionEntity::class,
        AiChatMessageEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class IYangoDatabase : RoomDatabase() {

    abstract fun timetableDao(): TimetableDao

    abstract fun semesterDao(): SemesterDao

    abstract fun timeSlotDao(): TimeSlotDao

    abstract fun courseDao(): CourseDao

    abstract fun importRecordDao(): ImportRecordDao

    abstract fun schoolConnectionDao(): SchoolConnectionDao

    abstract fun examDao(): ExamDao

    abstract fun gradeSnapshotDao(): GradeSnapshotDao

    abstract fun gradeRecordDao(): GradeRecordDao

    abstract fun syncBatchDao(): SyncBatchDao

    abstract fun syncChangeDao(): SyncChangeDao

    abstract fun schoolSnapshotDao(): SchoolSnapshotDao

    abstract fun occurrenceAdjustmentDao(): OccurrenceAdjustmentDao

    abstract fun temporaryDayDao(): TemporaryDayDao

    abstract fun systemMappingDao(): SystemMappingDao

    abstract fun widgetConfigDao(): WidgetConfigDao

    abstract fun classroomCacheDao(): ClassroomCacheDao

    abstract fun creditProgressDao(): CreditProgressDao

    abstract fun aiChatDao(): AiChatDao

    companion object {
        /** 数据库文件名。 */
        const val NAME = "iyango.db"

        /** 当前 schema 版本。 */
        const val VERSION = 8
    }
}
