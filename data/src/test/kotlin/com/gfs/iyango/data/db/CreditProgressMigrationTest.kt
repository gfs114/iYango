package com.gfs.iyango.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.gfs.iyango.core.model.CreditValueStatus
import com.gfs.iyango.data.repository.toModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreditProgressMigrationTest {

    private val databasePath: String
        get() = RuntimeEnvironment.getApplication().getDatabasePath(DB_NAME).absolutePath

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IYangoDatabase::class.java,
    )

    @Test
    fun `v6 to v7 preserves grade cache flags legacy zero and creates credit tables`() {
        helper.createDatabase(databasePath, 6).apply {
            execSQL(
                "INSERT INTO `grade_snapshot` " +
                    "(`accountKey`, `semester`, `fetchedAt`, `complete`, `parserVersion`, `queryFingerprint`) " +
                    "VALUES ('2024001', '*', 123456, 1, 1, '*')",
            )
            execSQL(
                "INSERT INTO `grade_record` " +
                    "(`accountKey`, `semester`, `courseName`, `courseCode`, `credit`, `scoreText`, " +
                    "`scoreValue`, `gradePoint`, `attribute`, `examType`, `schoolRecordId`, `rowOrder`, " +
                    "`isNew`, `isModified`, `previousScoreText`, `updateVersion`, `updatedAt`) " +
                    "VALUES " +
                    "('2024001', '2024-2025-1', '劳动教育', 'C0', 0.0, '优秀', 95.0, 4.5, '必修', '正常', 'id0', 0, 0, 0, '', 0, 1000)," +
                    "('2024001', '2024-2025-1', '高等数学', 'C1', 3.5, '88', 88.0, 3.8, '必修', '正常', 'id1', 1, 0, 0, '', 0, 1000)," +
                    "('2024001', '2024-2025-1', '异常课', 'C2', -1.0, '80', 80.0, 3.0, '必修', '正常', 'id2', 2, 0, 0, '', 0, 1000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(databasePath, 7, true, IYangoDatabaseMigrations.MIGRATION_6_7)
        migrated.close()

        val roomDb = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(),
            IYangoDatabase::class.java,
            DB_NAME,
        ).addMigrations(*IYangoDatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val snapshot = roomDb.gradeSnapshotDao().get("2024001", "*")!!
                val snapshotModel = snapshot.toModel()
                assertTrue(snapshotModel.targetSemesters.isEmpty())
                assertTrue(snapshotModel.queriedSemesters.isEmpty())
                assertEquals(0, snapshotModel.truncatedTermCount)
                assertFalse(snapshotModel.directAllEmptyUnverified)
                assertFalse(snapshotModel.invalidatedByPartialUpdate)

                val records = roomDb.gradeRecordDao().getAll("2024001", "2024-2025-1")
                    .map { it.toModel() }
                    .associateBy { it.courseCode }
                assertFalse(records["C0"]!!.rawAttributesKnown)
                assertEquals(CreditValueStatus.LEGACY_UNKNOWN_ZERO, records["C0"]!!.creditStatus)

                assertFalse(records["C1"]!!.rawAttributesKnown)
                assertEquals(CreditValueStatus.VALID, records["C1"]!!.creditStatus)

                assertFalse(records["C2"]!!.rawAttributesKnown)
                assertEquals(CreditValueStatus.MISSING_OR_INVALID, records["C2"]!!.creditStatus)

                roomDb.creditProgressDao().upsertPlan(
                    CreditPlanEntity(
                        accountKey = "2024001",
                        totalTargetCreditsText = "160.00",
                        userNote = "四年总计",
                        updatedAtEpochMillis = 999L,
                    ),
                )
                assertEquals("160.00", roomDb.creditProgressDao().getPlan("2024001")?.totalTargetCreditsText)
            }
        } finally {
            roomDb.close()
        }
    }

    private companion object {
        const val DB_NAME = "credit-migration-6-7.db"
    }
}
