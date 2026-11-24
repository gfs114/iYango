package com.gfs.iyango.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.gfs.iyango.core.model.*
import com.gfs.iyango.data.repository.RoomWidgetConfigRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetConfigMigrationTest {
    private val databasePath get() = RuntimeEnvironment.getApplication().getDatabasePath("widget-migration").absolutePath
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), IYangoDatabase::class.java)
    @Test fun migrationPreservesSolidAndAddsDisplayDefaults() {
        helper.createDatabase(databasePath, 4).use { db ->
            db.execSQL("INSERT INTO widget_config (widgetId,timetableId,layout,appearance,updatedAt) VALUES (7,42,'TODAY_2X4','SOLID',123)")
        }
        helper.runMigrationsAndValidate(databasePath, 6, true, *IYangoDatabaseMigrations.ALL).use { db ->
            db.query("SELECT * FROM widget_config WHERE widgetId=7").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("SOLID", row.getString(row.getColumnIndexOrThrow("appearance")))
                for ((name, value) in mapOf("widgetId" to 7, "timetableId" to 42, "updatedAt" to 123, "glassOpacityPercent" to 25, "textSizeSp" to 13, "showTeacher" to 0, "showLocation" to 1)) {
                    assertEquals(name, value, row.getInt(row.getColumnIndexOrThrow(name)))
                }
            }
        }
    }

    /** 文档 3.3：v5 → v6 存量卡片外观平滑映射到 showBg/bgColor，避免升级后突然变回 85% 白底。 */
    @Test fun migrationDerivesShowBgAndBgColorFromLegacyAppearance() {
        helper.createDatabase(databasePath, 5).use { db ->
            val rows = listOf(
                // widgetId, appearance, glassOpacityPercent
                Triple(1, "'TRANSPARENT'", 0),   // 全透明 → showBg=0, bgColor=0
                Triple(2, "'TRANSPARENT'", 25),  // 轻玻璃 → showBg=1, bgColor=0x40FFFFFF
                Triple(3, "'TRANSPARENT'", 50),  // 柔雾 → showBg=1, bgColor=0x80FFFFFF
                Triple(4, "'SOLID'", 50),        // 实色 → showBg=1, bgColor=0xFFFFFFFF
                Triple(5, "'SOLID'", 0),         // SOLID 优先于透明度 → 白色不透明
            )
            rows.forEach { (id, appearance, opacity) ->
                db.execSQL("INSERT INTO widget_config (widgetId,timetableId,layout,appearance,updatedAt,glassOpacityPercent) VALUES ($id,42,'TODAY_2X4',$appearance,123,$opacity)")
            }
        }
        helper.runMigrationsAndValidate(databasePath, 6, true, *IYangoDatabaseMigrations.ALL).use { db ->
            val expected = mapOf(
                1 to (0 to 0),
                2 to (1 to 1090519039),     // 0x40FFFFFF
                3 to (1 to -2130706433),    // 0x80FFFFFF
                4 to (1 to -1),             // 0xFFFFFFFF
                5 to (1 to -1),             // 0xFFFFFFFF
            )
            expected.forEach { (id, pair) ->
                val (showBg, bgColor) = pair
                db.query("SELECT showBg, bgColor, showButton, itemHeightDp, radiusDp, useDottedLine FROM widget_config WHERE widgetId=$id").use { row ->
                    assertTrue("row $id", row.moveToFirst())
                    assertEquals("showBg of $id", showBg, row.getInt(0))
                    assertEquals("bgColor of $id", bgColor, row.getInt(1))
                    // 其余 v6 列取字节码实测默认值（文档 3.1/3.2）。
                    assertEquals(1, row.getInt(2))
                    assertEquals(64, row.getInt(3))
                    assertEquals(4, row.getInt(4))
                    assertEquals(0, row.getInt(5))
                }
            }
        }
    }

    @Test fun repositoryRoundTripAndObservationKeepEveryDisplayChoice() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), IYangoDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = RoomWidgetConfigRepository(db)
            val config = WidgetConfig(9, 42, WidgetLayout.WEEK_4X4, WidgetAppearance.SOLID, 123, 50, 17, true, false)
            repository.save(config)
            assertEquals(config, repository.get(9))
            assertEquals(listOf(config), repository.observeAll().first())
            repository.save(config.copy(widgetId = 10, glassOpacityPercent = Int.MAX_VALUE, textSizeSp = Int.MIN_VALUE))
            assertEquals(50, repository.get(10)!!.glassOpacityPercent)
            assertEquals(12, repository.get(10)!!.textSizeSp)
            // v6 字段越界值被 normalized 收敛（文档 3.2）。
            repository.save(config.copy(widgetId = 11, itemHeightDp = 200, radiusDp = 99, itemAlpha = -5, headerTextSizeSp = 1))
            assertEquals(96, repository.get(11)!!.itemHeightDp)
            assertEquals(24, repository.get(11)!!.radiusDp)
            assertEquals(0, repository.get(11)!!.itemAlpha)
            assertEquals(10, repository.get(11)!!.headerTextSizeSp)
            repository.save(WidgetConfig(widgetId = 12))
            assertEquals(WidgetAppearance.TRANSPARENT, repository.get(12)!!.appearance)
            assertEquals(25, repository.get(12)!!.glassOpacityPercent)
            assertEquals(0xD8FFFFFF.toInt(), repository.get(12)!!.bgColor)
            assertEquals(WidgetLayout.TODAY_2X4, repository.get(12)!!.layout)
        } finally { db.close() }
    }
}
