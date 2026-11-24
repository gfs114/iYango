package com.gfs.iyango.data.db

import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import com.gfs.iyango.data.seed.DemoSeeder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 级联删除行为验证（P2-13，Robolectric）：
 * 删除课表后 semester/time_slot/course/import_record 全部级联清空。
 *
 * 级联依赖外键 ON DELETE CASCADE；这里用**全表计数**断言（而非按 timetableId 查询），
 * 排除"查询条件过滤掉了行但行还在"的假阳性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CascadeDeleteTest {

    private lateinit var db: IYangoDatabase
    private lateinit var seeder: DemoSeeder

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, IYangoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seeder = DemoSeeder(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tableCount(table: String): Int {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    @Test
    fun `删除课表后子表全部级联清空`() = runTest {
        seeder.seedDemoIfEmpty()
        // 播种后：1 课表、1 学期、11 节、7 课程、0 导入记录（额外补一条导入记录验证级联）。
        db.importRecordDao().insert(
            ImportRecordEntity(timetableId = 1, source = "TEST", importTime = "2026-10-24 08:00:00", semester = ""),
        )
        assertEquals(1, tableCount("timetable"))
        assertEquals(1, tableCount("semester"))
        assertEquals(11, tableCount("time_slot"))
        assertEquals(7, tableCount("course"))
        assertEquals(1, tableCount("import_record"))

        db.timetableDao().deleteById(1)

        assertEquals(0, tableCount("timetable"))
        assertEquals(0, tableCount("semester"))
        assertEquals(0, tableCount("time_slot"))
        assertEquals(0, tableCount("course"))
        assertEquals(0, tableCount("import_record"))
    }
}
