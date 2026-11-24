package com.gfs.iyango.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1 → v2 迁移测试（P2-01 + P2-13，Robolectric + MigrationTestHelper）。
 *
 * 覆盖：
 * 1. v1 中重复的单次调整记录（同 timetableId + overrideOfId + overrideWeek）迁移后
 *    只保留一条（保留最小 id）；
 * 2. 唯一索引 `index_course_override` 存在；
 * 3. 普通课程行（overrideOfId=0 → NULL）在唯一索引下可任意共存；
 * 4. 迁移后再写重复调整记录被唯一索引拒绝。
 *
 * 说明：`data/src/test/assets/com.gfs.iyango.data.db.IYangoDatabase/{1,2}.json` 是
 * `data/schemas/…` 导出产物的测试资产副本（构建文件不允许追加 assets srcDir），
 * schema 变更后必须同步刷新副本。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IYangoDatabase::class.java,
    )

    private val timetableRow =
        "('我的课表','LOCAL','','',1,'','',0,'1,2,3,4,5,6,7',1,0)"

    private fun courseRow(overrideOfId: Int, overrideWeek: Int): String =
        "(1,'高等数学','张老师','A101','','',1,1,2,0,0,1,16,'EVERY','','#3E7BFA',0.0,0,1,0,'','','',1,0," +
            "$overrideOfId,$overrideWeek,'')"

    /** 迁移后表使用的行：非调整记录的调整列写 NULL（与映射层 toEntity 的落库形态一致）。 */
    private fun courseRowNullable(): String =
        "(1,'高等数学','张老师','A101','','',1,1,2,0,0,1,16,'EVERY','','#3E7BFA',0.0,0,1,0,'','','',1,0," +
            "NULL,NULL,'')"

    @Test
    fun `v1 重复调课记录迁移后去重并建立唯一索引`() {
        helper.createDatabase(DB_PATH, 1).use { v1 ->
            v1.execSQL("INSERT INTO timetable (name, source, schoolTerm, connectionId, isCurrent, " +
                "lastSyncAt, groupName, autoEnabled, enabledDays, cycleLength, cycleWeek) VALUES $timetableRow")
            // 两条同 (overrideOfId=10, overrideWeek=8) 的调整记录 + 两条普通课程。
            v1.execSQL(
                "INSERT INTO course (timetableId, name, teacher, classroom, abbreviation, remark, " +
                    "dayOfWeek, startSection, endSection, sourceStartSection, sourceEndSection, " +
                    "startWeek, endWeek, weekType, selectedWeeks, color, credit, isOutdoor, enabled, " +
                    "timeMode, startTime, endTime, date, cycleLength, cycleWeek, overrideOfId, " +
                    "overrideWeek, groupKey) VALUES ${courseRow(10, 8)}",
            )
            v1.execSQL(
                "INSERT INTO course (timetableId, name, teacher, classroom, abbreviation, remark, " +
                    "dayOfWeek, startSection, endSection, sourceStartSection, sourceEndSection, " +
                    "startWeek, endWeek, weekType, selectedWeeks, color, credit, isOutdoor, enabled, " +
                    "timeMode, startTime, endTime, date, cycleLength, cycleWeek, overrideOfId, " +
                    "overrideWeek, groupKey) VALUES ${courseRow(10, 8)}",
            )
            v1.execSQL(
                "INSERT INTO course (timetableId, name, teacher, classroom, abbreviation, remark, " +
                    "dayOfWeek, startSection, endSection, sourceStartSection, sourceEndSection, " +
                    "startWeek, endWeek, weekType, selectedWeeks, color, credit, isOutdoor, enabled, " +
                    "timeMode, startTime, endTime, date, cycleLength, cycleWeek, overrideOfId, " +
                    "overrideWeek, groupKey) VALUES ${courseRow(0, 0)}",
            )
            v1.execSQL(
                "INSERT INTO course (timetableId, name, teacher, classroom, abbreviation, remark, " +
                    "dayOfWeek, startSection, endSection, sourceStartSection, sourceEndSection, " +
                    "startWeek, endWeek, weekType, selectedWeeks, color, credit, isOutdoor, enabled, " +
                    "timeMode, startTime, endTime, date, cycleLength, cycleWeek, overrideOfId, " +
                    "overrideWeek, groupKey) VALUES ${courseRow(0, 0)}",
            )
        }

        helper.runMigrationsAndValidate(DB_PATH, 2, true, IYangoDatabaseMigrations.MIGRATION_1_2).use { v2 ->
            // 重复调整记录只剩一条；普通课程两条共存。
            assertEquals(1, scalar(v2, "SELECT COUNT(*) FROM course WHERE overrideOfId = 10"))
            assertEquals(2, scalar(v2, "SELECT COUNT(*) FROM course WHERE overrideOfId IS NULL"))
            // 保留的是 id 较小的一条。
            assertEquals(1, scalar(v2, "SELECT MIN(id) FROM course WHERE overrideOfId = 10"))
            // 唯一索引存在。
            assertEquals(1, scalar(v2, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' " +
                "AND name='index_course_override' AND sql LIKE '%UNIQUE%'"))

            // 迁移后再写一条重复调整记录 → 唯一索引拒绝。
            var rejected = false
            try {
                v2.execSQL(
                    "INSERT INTO course (timetableId, name, teacher, classroom, abbreviation, remark, " +
                        "dayOfWeek, startSection, endSection, sourceStartSection, sourceEndSection, " +
                        "startWeek, endWeek, weekType, selectedWeeks, color, credit, isOutdoor, enabled, " +
                        "timeMode, startTime, endTime, date, cycleLength, cycleWeek, overrideOfId, " +
                        "overrideWeek, groupKey) VALUES ${courseRow(10, 8)}",
                )
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue("重复调整记录应被唯一索引拒绝", rejected)
        }
    }

    @Test
    fun `v1 普通数据迁移后可继续写普通课程`() {
        helper.createDatabase(DB_PATH, 1).use { v1 ->
            v1.execSQL("INSERT INTO timetable (name, source, schoolTerm, connectionId, isCurrent, " +
                "lastSyncAt, groupName, autoEnabled, enabledDays, cycleLength, cycleWeek) VALUES $timetableRow")
            v1.execSQL(
                "INSERT INTO course (timetableId, name, teacher, classroom, abbreviation, remark, " +
                    "dayOfWeek, startSection, endSection, sourceStartSection, sourceEndSection, " +
                    "startWeek, endWeek, weekType, selectedWeeks, color, credit, isOutdoor, enabled, " +
                    "timeMode, startTime, endTime, date, cycleLength, cycleWeek, overrideOfId, " +
                    "overrideWeek, groupKey) VALUES ${courseRow(0, 0)}",
            )
        }

        helper.runMigrationsAndValidate(DB_PATH, 2, true, IYangoDatabaseMigrations.MIGRATION_1_2).use { v2 ->
            // 普通课程（两列 NULL）不受唯一索引影响，可继续插入。
            v2.execSQL(
                "INSERT INTO course (timetableId, name, teacher, classroom, abbreviation, remark, " +
                    "dayOfWeek, startSection, endSection, sourceStartSection, sourceEndSection, " +
                    "startWeek, endWeek, weekType, selectedWeeks, color, credit, isOutdoor, enabled, " +
                    "timeMode, startTime, endTime, date, cycleLength, cycleWeek, overrideOfId, " +
                    "overrideWeek, groupKey) VALUES ${courseRowNullable()}",
            )
            assertEquals(2, scalar(v2, "SELECT COUNT(*) FROM course"))
            assertEquals(2, scalar(v2, "SELECT COUNT(*) FROM course WHERE overrideOfId IS NULL"))
        }
    }

    @Test
    fun `v2 迁移到 v3 新增表可写入并受外键约束`() {
        helper.createDatabase(DB_PATH, 2).use { v2 ->
            v2.execSQL("INSERT INTO timetable (name, source, schoolTerm, connectionId, isCurrent, " +
                "lastSyncAt, groupName, autoEnabled, enabledDays, cycleLength, cycleWeek) VALUES $timetableRow")
        }

        helper.runMigrationsAndValidate(DB_PATH, 3, true, IYangoDatabaseMigrations.MIGRATION_2_3).use { v3 ->
            // 新表存在。
            listOf(
                "school_connection", "exam", "grade_snapshot", "grade_record", "sync_batch",
                "sync_change", "school_snapshot", "occurrence_adjustment", "temporary_day",
                "system_mapping", "widget_config", "classroom_cache",
            ).forEach { table ->
                assertEquals("表 $table 应存在", 1, scalar(v3, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table'"))
            }
            // 临时调整写入并可通过外键引用课表。
            v3.execSQL(
                "INSERT INTO occurrence_adjustment (timetableId, operationGroupId, type, sourceCourseId, " +
                    "sourceDate, targetDate, targetDayOfWeek, targetStartSection, targetEndSection, " +
                    "targetStartTime, targetEndTime, targetClassroom, note, createdAt) VALUES " +
                    "(1,'g1','MOVE',2,'2026-10-20','2026-10-22',4,3,4,'','','','',0)",
            )
            assertEquals(1, scalar(v3, "SELECT COUNT(*) FROM occurrence_adjustment WHERE timetableId = 1"))
            // 删除课表级联清理临时调整（迁移测试库需显式开启外键；生产 Room 默认开启）。
            v3.execSQL("PRAGMA foreign_keys=ON")
            v3.execSQL("DELETE FROM timetable WHERE id = 1")
            assertEquals(0, scalar(v3, "SELECT COUNT(*) FROM occurrence_adjustment"))
        }
    }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int {
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"

        /**
         * 迁移测试库名传绝对路径：SupportSQLiteDriver 校验"库名与 openHelper 配置一致"
         * 时按 '/' 取基名，Windows 反斜杠路径会误判，而绝对路径在两侧完全一致。
         */
        val DB_PATH: String
            get() = RuntimeEnvironment.getApplication().getDatabasePath(DB_NAME).absolutePath
    }
}
