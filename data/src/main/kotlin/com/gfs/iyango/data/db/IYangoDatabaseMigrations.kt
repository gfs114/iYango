package com.gfs.iyango.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 手写迁移集合（P2-01 调课唯一约束收口）。
 *
 * 供生产构建（[IYangoDatabaseProvider]）与 Robolectric 迁移测试
 * （`MigrationTestHelper.runMigrationsAndValidate`）共用。
 */
object IYangoDatabaseMigrations {

    /**
     * v1 → v2：course 表单次调整唯一约束（`index_course_override`）。
     *
     * 两步走：
     * 1. **清理重复**：同一 `(timetableId, overrideOfId, overrideWeek)` 只保留 id 最小
     *    的一条（老库若因历史版本存在重复调整记录，直接建唯一索引会炸库）；
     * 2. **重建表**：v2 把 `overrideOfId`/`overrideWeek` 改为可空列——普通课程行
     *    （overrideOfId=0）以 NULL 落库，SQLite 唯一索引将 NULL 视为互不相同，从而获得
     *    "仅调整记录参与唯一性"的部分唯一语义（实体声明 + Room 新建库 DDL 与迁移产物
     *    完全一致，保证 Room 打开时的 schema 校验通过）。
     *
     * 课程行 id 全程保留（拷贝时带原主键），因此调整记录的 `overrideOfId` 引用在重建后
     * 仍然指向正确的行，无需重映射。
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1) 去重：同 (timetableId, overrideOfId, overrideWeek) 保留最小 id。
            db.execSQL(
                "DELETE FROM `course` WHERE `overrideOfId` > 0 AND `id` NOT IN (" +
                    "SELECT MIN(`id`) FROM `course` WHERE `overrideOfId` > 0 " +
                    "GROUP BY `timetableId`, `overrideOfId`, `overrideWeek`)",
            )
            // 2) 重建为 v2 列定义（两个调整列可空），拷贝时把"非调整记录"的 0 转为 NULL。
            db.execSQL(COURSE_V2_CREATE_SQL)
            db.execSQL(COURSE_V2_COPY_SQL)
            db.execSQL("DROP TABLE `course`")
            db.execSQL("ALTER TABLE `_new_course` RENAME TO `course`")
            // 重建随 DROP 丢失的索引。
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_course_timetableId` ON `course` (`timetableId`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_course_override` " +
                    "ON `course` (`timetableId`, `overrideOfId`, `overrideWeek`)",
            )
        }
    }

    /**
     * v2 → v3：新增教务连接、考试、成绩、同步历史、临时调整、系统映射与缓存表。
     *
     * 全部为新表，不修改 v2 既有列；外键只在新建表内声明，因此不需要重建旧表。
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V3_TABLES.forEach(db::execSQL)
            V3_INDICES.forEach(db::execSQL)
        }
    }

    /**
     * v3 → v4：课程新增学校稳定来源键 `sourceKey`。
     *
     * 教务同步按 (timetableId, sourceKey) 更新基础安排并保留课程行 id，
     * 使引用该行的单次调整不会因整表重写而错挂。旧行一律为空串（本机课程）。
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `course` ADD COLUMN `sourceKey` TEXT NOT NULL DEFAULT ''",
            )
        }
    }

    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `widget_config` ADD COLUMN `glassOpacityPercent` INTEGER NOT NULL DEFAULT 25")
            db.execSQL("ALTER TABLE `widget_config` ADD COLUMN `textSizeSp` INTEGER NOT NULL DEFAULT 13")
            db.execSQL("ALTER TABLE `widget_config` ADD COLUMN `showTeacher` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `widget_config` ADD COLUMN `showLocation` INTEGER NOT NULL DEFAULT 1")
        }
    }

    /**
     * v5 → v6：widget_config 增加 WakeUp 样式对齐列，并把存量卡片外观平滑映射到
     * `showBg`/`bgColor`，避免半透明/全透明卡片升级后突然变回 85% 白底。
     *
     * 列 DEFAULT 与 [WidgetConfigEntity] 的 `@ColumnInfo(defaultValue=…)` 逐字一致；
     * 存量映射规则：
     * - `showBg`：仅“全透明（TRANSPARENT 且 opacity=0）”映射为 0，其余为 1；
     * - `bgColor`：SOLID → 白，opacity 0 → 全透明，25 → 0x40FFFFFF，50 → 0x80FFFFFF。
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V6_WIDGET_COLUMNS.forEach(db::execSQL)
            db.execSQL(
                "UPDATE `widget_config` SET " +
                    "`showBg` = CASE WHEN `appearance` = 'TRANSPARENT' AND `glassOpacityPercent` = 0 THEN 0 ELSE 1 END, " +
                    "`bgColor` = CASE " +
                    "WHEN `appearance` = 'SOLID' THEN -1 " + // 0xFFFFFFFF
                    "WHEN `glassOpacityPercent` = 0 THEN 0 " + // 0x00000000
                    "WHEN `glassOpacityPercent` = 25 THEN 1090519039 " + // 0x40FFFFFF
                    "ELSE -2130706433 END", // 0x80FFFFFF
            )
        }
    }

    /**
     * v6 → v7：扩展成绩覆盖与原始属性字段（保留存量缓存并标记旧零学分与未知属性待更新），
     * 并新增学分目标计划、分类配置与人工消歧规则表。
     */
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V7_GRADE_SNAPSHOT_COLUMNS.forEach(db::execSQL)
            V7_GRADE_RECORD_COLUMNS.forEach(db::execSQL)
            db.execSQL(
                "UPDATE `grade_record` SET " +
                    "`rawAttributesKnown` = 0, " +
                    "`creditStatus` = CASE " +
                    "WHEN `credit` < 0 THEN 'MISSING_OR_INVALID' " +
                    "WHEN `credit` = 0 THEN 'LEGACY_UNKNOWN_ZERO' " +
                    "ELSE 'VALID' END",
            )
            V7_CREDIT_TABLES.forEach(db::execSQL)
        }
    }

    /** 全部迁移，按版本顺序排列；生产构建一次性注册，Room 自行择路。 */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
    )

    /** v2 course 表建表 SQL（与 data/schemas/…/2.json 的 createSql 逐字一致）。 */
    private const val COURSE_V2_CREATE_SQL =
        "CREATE TABLE IF NOT EXISTS `_new_course` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`timetableId` INTEGER NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`teacher` TEXT NOT NULL, " +
            "`classroom` TEXT NOT NULL, " +
            "`abbreviation` TEXT NOT NULL, " +
            "`remark` TEXT NOT NULL, " +
            "`dayOfWeek` INTEGER NOT NULL, " +
            "`startSection` INTEGER NOT NULL, " +
            "`endSection` INTEGER NOT NULL, " +
            "`sourceStartSection` INTEGER NOT NULL, " +
            "`sourceEndSection` INTEGER NOT NULL, " +
            "`startWeek` INTEGER NOT NULL, " +
            "`endWeek` INTEGER NOT NULL, " +
            "`weekType` TEXT NOT NULL, " +
            "`selectedWeeks` TEXT NOT NULL, " +
            "`color` TEXT NOT NULL, " +
            "`credit` REAL NOT NULL, " +
            "`isOutdoor` INTEGER NOT NULL, " +
            "`enabled` INTEGER NOT NULL, " +
            "`timeMode` INTEGER NOT NULL, " +
            "`startTime` TEXT NOT NULL, " +
            "`endTime` TEXT NOT NULL, " +
            "`date` TEXT NOT NULL, " +
            "`cycleLength` INTEGER NOT NULL, " +
            "`cycleWeek` INTEGER NOT NULL, " +
            "`overrideOfId` INTEGER, " +
            "`overrideWeek` INTEGER, " +
            "`groupKey` TEXT NOT NULL, " +
            "FOREIGN KEY(`timetableId`) REFERENCES `timetable`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"

    /** v1 → v2 数据拷贝：`overrideOfId`/`overrideWeek` 为 0（非调整记录）时落 NULL。 */
    private const val COURSE_V2_COPY_SQL =
        "INSERT INTO `_new_course` (" +
            "`id`,`timetableId`,`name`,`teacher`,`classroom`,`abbreviation`,`remark`," +
            "`dayOfWeek`,`startSection`,`endSection`,`sourceStartSection`,`sourceEndSection`," +
            "`startWeek`,`endWeek`,`weekType`,`selectedWeeks`,`color`,`credit`,`isOutdoor`," +
            "`enabled`,`timeMode`,`startTime`,`endTime`,`date`,`cycleLength`,`cycleWeek`," +
            "`overrideOfId`,`overrideWeek`,`groupKey`) " +
            "SELECT `id`,`timetableId`,`name`,`teacher`,`classroom`,`abbreviation`,`remark`," +
            "`dayOfWeek`,`startSection`,`endSection`,`sourceStartSection`,`sourceEndSection`," +
            "`startWeek`,`endWeek`,`weekType`,`selectedWeeks`,`color`,`credit`,`isOutdoor`," +
            "`enabled`,`timeMode`,`startTime`,`endTime`,`date`,`cycleLength`,`cycleWeek`," +
            "CASE WHEN `overrideOfId` > 0 THEN `overrideOfId` END," +
            "CASE WHEN `overrideOfId` > 0 THEN `overrideWeek` END," +
            "`groupKey` FROM `course`"

    /** v3 新表 DDL（与 Room 由实体生成的定义一致）。 */
    private val V3_TABLES: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `school_connection` (" +
            "`id` TEXT NOT NULL, " +
            "`schoolId` TEXT NOT NULL, " +
            "`accountSubject` TEXT NOT NULL, " +
            "`maskedAccount` TEXT NOT NULL, " +
            "`sessionRevision` INTEGER NOT NULL, " +
            "`status` TEXT NOT NULL, " +
            "`credentialRef` TEXT NOT NULL, " +
            "`rememberPassword` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "`lastLoginAt` INTEGER NOT NULL, " +
            "`lastError` TEXT NOT NULL, " +
            "`displayName` TEXT NOT NULL, " +
            "`studentNo` TEXT NOT NULL, " +
            "`college` TEXT NOT NULL, " +
            "`major` TEXT NOT NULL, " +
            "`className` TEXT NOT NULL, " +
            "`gender` TEXT NOT NULL, " +
            "`avatarUrl` TEXT NOT NULL, " +
            "PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `exam` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`source` TEXT NOT NULL, " +
            "`connectionId` TEXT NOT NULL, " +
            "`semester` TEXT NOT NULL, " +
            "`subject` TEXT NOT NULL, " +
            "`type` TEXT NOT NULL, " +
            "`date` TEXT NOT NULL, " +
            "`endDate` TEXT NOT NULL, " +
            "`startTime` TEXT NOT NULL, " +
            "`endTime` TEXT NOT NULL, " +
            "`timeZone` TEXT NOT NULL, " +
            "`location` TEXT NOT NULL, " +
            "`seat` TEXT NOT NULL, " +
            "`note` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, " +
            "`sourceExamId` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `grade_snapshot` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`accountKey` TEXT NOT NULL, " +
            "`semester` TEXT NOT NULL, " +
            "`fetchedAt` INTEGER NOT NULL, " +
            "`complete` INTEGER NOT NULL, " +
            "`parserVersion` INTEGER NOT NULL, " +
            "`queryFingerprint` TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `grade_record` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`accountKey` TEXT NOT NULL, " +
            "`semester` TEXT NOT NULL, " +
            "`courseName` TEXT NOT NULL, " +
            "`courseCode` TEXT NOT NULL, " +
            "`credit` REAL NOT NULL, " +
            "`scoreText` TEXT NOT NULL, " +
            "`scoreValue` REAL, " +
            "`gradePoint` REAL, " +
            "`attribute` TEXT NOT NULL, " +
            "`examType` TEXT NOT NULL, " +
            "`schoolRecordId` TEXT NOT NULL, " +
            "`rowOrder` INTEGER NOT NULL, " +
            "`isNew` INTEGER NOT NULL, " +
            "`isModified` INTEGER NOT NULL, " +
            "`previousScoreText` TEXT NOT NULL, " +
            "`updateVersion` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `sync_batch` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`connectionId` TEXT NOT NULL, " +
            "`timetableId` INTEGER NOT NULL, " +
            "`semester` TEXT NOT NULL, " +
            "`version` INTEGER NOT NULL, " +
            "`startedAt` INTEGER NOT NULL, " +
            "`finishedAt` INTEGER NOT NULL, " +
            "`result` TEXT NOT NULL, " +
            "`changeCount` INTEGER NOT NULL, " +
            "`firstBaseline` INTEGER NOT NULL, " +
            "`message` TEXT NOT NULL, " +
            "`read` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `sync_change` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`batchId` INTEGER NOT NULL, " +
            "`stableKey` TEXT NOT NULL, " +
            "`courseName` TEXT NOT NULL, " +
            "`changeType` TEXT NOT NULL, " +
            "`field` TEXT NOT NULL, " +
            "`beforeValue` TEXT NOT NULL, " +
            "`afterValue` TEXT NOT NULL, " +
            "FOREIGN KEY(`batchId`) REFERENCES `sync_batch`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `school_snapshot` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`connectionId` TEXT NOT NULL, " +
            "`timetableId` INTEGER NOT NULL, " +
            "`semester` TEXT NOT NULL, " +
            "`payloadJson` TEXT NOT NULL, " +
            "`digest` TEXT NOT NULL, " +
            "`savedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `occurrence_adjustment` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`timetableId` INTEGER NOT NULL, " +
            "`operationGroupId` TEXT NOT NULL, " +
            "`type` TEXT NOT NULL, " +
            "`sourceCourseId` INTEGER NOT NULL, " +
            "`sourceDate` TEXT NOT NULL, " +
            "`targetDate` TEXT NOT NULL, " +
            "`targetDayOfWeek` INTEGER NOT NULL, " +
            "`targetStartSection` INTEGER NOT NULL, " +
            "`targetEndSection` INTEGER NOT NULL, " +
            "`targetStartTime` TEXT NOT NULL, " +
            "`targetEndTime` TEXT NOT NULL, " +
            "`targetClassroom` TEXT NOT NULL, " +
            "`note` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "FOREIGN KEY(`timetableId`) REFERENCES `timetable`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `temporary_day` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`timetableId` INTEGER NOT NULL, " +
            "`operationGroupId` TEXT NOT NULL, " +
            "`date` TEXT NOT NULL, " +
            "`sourceTimetableId` INTEGER NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "FOREIGN KEY(`timetableId`) REFERENCES `timetable`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `system_mapping` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`kind` TEXT NOT NULL, " +
            "`occurrenceId` TEXT NOT NULL, " +
            "`externalId` TEXT NOT NULL, " +
            "`payloadHash` TEXT NOT NULL, " +
            "`planRevision` INTEGER NOT NULL, " +
            "`ownerId` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `widget_config` (" +
            "`widgetId` INTEGER NOT NULL, " +
            "`timetableId` INTEGER NOT NULL, " +
            "`layout` TEXT NOT NULL, " +
            "`appearance` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`widgetId`))",
        "CREATE TABLE IF NOT EXISTS `classroom_cache` (" +
            "`cacheKey` TEXT NOT NULL, " +
            "`connectionId` TEXT NOT NULL, " +
            "`termValue` TEXT NOT NULL, " +
            "`kind` TEXT NOT NULL, " +
            "`payloadJson` TEXT NOT NULL, " +
            "`fetchedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`cacheKey`))",
    )

    /** v3 索引 DDL（名称与 Room 默认命名一致）。 */
    private val V3_INDICES: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `index_school_connection_accountSubject` " +
            "ON `school_connection` (`accountSubject`)",
        "CREATE INDEX IF NOT EXISTS `index_school_connection_status` " +
            "ON `school_connection` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_exam_date` ON `exam` (`date`)",
        "CREATE INDEX IF NOT EXISTS `index_exam_connectionId_semester` " +
            "ON `exam` (`connectionId`, `semester`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_grade_snapshot_accountKey_semester` " +
            "ON `grade_snapshot` (`accountKey`, `semester`)",
        "CREATE INDEX IF NOT EXISTS `index_grade_record_accountKey_semester` " +
            "ON `grade_record` (`accountKey`, `semester`)",
        "CREATE INDEX IF NOT EXISTS `index_grade_record_accountKey_isNew` " +
            "ON `grade_record` (`accountKey`, `isNew`)",
        "CREATE INDEX IF NOT EXISTS `index_grade_record_accountKey_isModified` " +
            "ON `grade_record` (`accountKey`, `isModified`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_batch_connectionId_timetableId` " +
            "ON `sync_batch` (`connectionId`, `timetableId`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_batch_semester` ON `sync_batch` (`semester`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_change_batchId` ON `sync_change` (`batchId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_school_snapshot_connectionId_timetableId_semester` " +
            "ON `school_snapshot` (`connectionId`, `timetableId`, `semester`)",
        "CREATE INDEX IF NOT EXISTS `index_occurrence_adjustment_timetableId` " +
            "ON `occurrence_adjustment` (`timetableId`)",
        "CREATE INDEX IF NOT EXISTS `index_occurrence_adjustment_operationGroupId` " +
            "ON `occurrence_adjustment` (`operationGroupId`)",
        "CREATE INDEX IF NOT EXISTS `index_occurrence_adjustment_timetableId_sourceDate` " +
            "ON `occurrence_adjustment` (`timetableId`, `sourceDate`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_temporary_day_timetableId_date` " +
            "ON `temporary_day` (`timetableId`, `date`)",
        "CREATE INDEX IF NOT EXISTS `index_temporary_day_operationGroupId` " +
            "ON `temporary_day` (`operationGroupId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_system_mapping_kind_occurrenceId_ownerId` " +
            "ON `system_mapping` (`kind`, `occurrenceId`, `ownerId`)",
        "CREATE INDEX IF NOT EXISTS `index_system_mapping_kind_ownerId` " +
            "ON `system_mapping` (`kind`, `ownerId`)",
        "CREATE INDEX IF NOT EXISTS `index_classroom_cache_connectionId_termValue` " +
            "ON `classroom_cache` (`connectionId`, `termValue`)",
    )

    /** v6 widget_config 新列 DDL（DEFAULT 与实体声明逐字一致）。 */
    private val V6_WIDGET_COLUMNS: List<String> = listOf(
        "ALTER TABLE `widget_config` ADD COLUMN `showBg` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `widget_config` ADD COLUMN `bgColor` INTEGER NOT NULL DEFAULT -654311425",
        "ALTER TABLE `widget_config` ADD COLUMN `backgroundUri` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `widget_config` ADD COLUMN `showHeaderArea` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `widget_config` ADD COLUMN `showDate` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `widget_config` ADD COLUMN `showButton` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `widget_config` ADD COLUMN `showColor` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `widget_config` ADD COLUMN `textColor` INTEGER NOT NULL DEFAULT -16777216",
        "ALTER TABLE `widget_config` ADD COLUMN `courseTextColor` INTEGER NOT NULL DEFAULT -1",
        "ALTER TABLE `widget_config` ADD COLUMN `strokeColor` INTEGER NOT NULL DEFAULT -2130706433",
        "ALTER TABLE `widget_config` ADD COLUMN `textColorCompose` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `widget_config` ADD COLUMN `strokeColorCompose` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `widget_config` ADD COLUMN `headerTextSizeSp` INTEGER NOT NULL DEFAULT 12",
        "ALTER TABLE `widget_config` ADD COLUMN `itemHeightDp` INTEGER NOT NULL DEFAULT 64",
        "ALTER TABLE `widget_config` ADD COLUMN `radiusDp` INTEGER NOT NULL DEFAULT 4",
        "ALTER TABLE `widget_config` ADD COLUMN `itemAlpha` INTEGER NOT NULL DEFAULT 50",
        "ALTER TABLE `widget_config` ADD COLUMN `otherWeekCourseAlpha` INTEGER NOT NULL DEFAULT 50",
        "ALTER TABLE `widget_config` ADD COLUMN `itemCenterHorizontal` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `widget_config` ADD COLUMN `itemCenterVertical` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `widget_config` ADD COLUMN `showTime` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `widget_config` ADD COLUMN `showRoomPrefix` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `widget_config` ADD COLUMN `showTimeBar` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `widget_config` ADD COLUMN `showSat` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `widget_config` ADD COLUMN `showSun` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `widget_config` ADD COLUMN `showOtherWeekCourse` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `widget_config` ADD COLUMN `showGrid` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `widget_config` ADD COLUMN `useDottedLine` INTEGER NOT NULL DEFAULT 0",
    )

    private val V7_GRADE_SNAPSHOT_COLUMNS: List<String> = listOf(
        "ALTER TABLE `grade_snapshot` ADD COLUMN `targetSemestersJson` TEXT NOT NULL DEFAULT '[]'",
        "ALTER TABLE `grade_snapshot` ADD COLUMN `queriedSemestersJson` TEXT NOT NULL DEFAULT '[]'",
        "ALTER TABLE `grade_snapshot` ADD COLUMN `truncatedTermCount` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `grade_snapshot` ADD COLUMN `directAllEmptyUnverified` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `grade_snapshot` ADD COLUMN `invalidatedByPartialUpdate` INTEGER NOT NULL DEFAULT 0",
    )

    private val V7_GRADE_RECORD_COLUMNS: List<String> = listOf(
        "ALTER TABLE `grade_record` ADD COLUMN `rawCreditText` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `grade_record` ADD COLUMN `creditStatus` TEXT NOT NULL DEFAULT 'VALID'",
        "ALTER TABLE `grade_record` ADD COLUMN `rawCourseAttribute` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `grade_record` ADD COLUMN `rawCourseType` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `grade_record` ADD COLUMN `rawGeneralElectiveType` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `grade_record` ADD COLUMN `rawAttributesKnown` INTEGER NOT NULL DEFAULT 1",
    )

    private val V7_CREDIT_TABLES: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `credit_plan` (" +
            "`accountKey` TEXT NOT NULL, " +
            "`schoolId` TEXT NOT NULL DEFAULT 'YGU', " +
            "`totalTargetCreditsText` TEXT, " +
            "`userNote` TEXT NOT NULL DEFAULT '', " +
            "`curriculumSnapshotJson` TEXT NOT NULL DEFAULT '', " +
            "`updatedAtEpochMillis` INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(`accountKey`))",
        "CREATE TABLE IF NOT EXISTS `credit_category_plan` (" +
            "`accountKey` TEXT NOT NULL, " +
            "`categoryId` TEXT NOT NULL, " +
            "`displayName` TEXT NOT NULL, " +
            "`targetCreditsText` TEXT, " +
            "`mappedRawAttributesJson` TEXT NOT NULL DEFAULT '[]', " +
            "`sortOrder` INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(`accountKey`, `categoryId`))",
        "CREATE INDEX IF NOT EXISTS `index_credit_category_plan_accountKey` " +
            "ON `credit_category_plan` (`accountKey`)",
        "CREATE TABLE IF NOT EXISTS `credit_resolution_rule` (" +
            "`accountKey` TEXT NOT NULL, " +
            "`ruleKey` TEXT NOT NULL, " +
            "`action` TEXT NOT NULL, " +
            "`assignedCategoryId` TEXT, " +
            "`chosenAnchorKey` TEXT, " +
            "`splitAssignmentsJson` TEXT NOT NULL DEFAULT '[]', " +
            "`expectedFingerprintsJson` TEXT NOT NULL DEFAULT '[]', " +
            "`requiresPostRestoreVerification` INTEGER NOT NULL DEFAULT 0, " +
            "`manualPassConfirmed` INTEGER NOT NULL DEFAULT 0, " +
            "`note` TEXT NOT NULL DEFAULT '', " +
            "`updatedAtEpochMillis` INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(`accountKey`, `ruleKey`))",
        "CREATE INDEX IF NOT EXISTS `index_credit_resolution_rule_accountKey` " +
            "ON `credit_resolution_rule` (`accountKey`)",
    )
}
