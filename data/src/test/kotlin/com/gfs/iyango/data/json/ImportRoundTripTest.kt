package com.gfs.iyango.data.json

import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import com.gfs.iyango.core.domain.ImportWriteMode
import com.gfs.iyango.core.domain.ResolveScheduleOccurrences
import com.gfs.iyango.core.model.CellTextAlign
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.GridLineStyle
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WeekType
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SemesterEntity
import com.gfs.iyango.data.db.TimeSlotEntity
import com.gfs.iyango.data.repository.toEntity
import com.gfs.iyango.data.repository.toModel
import com.gfs.iyango.data.seed.DemoSeeder
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导入/导出往返行为测试（P2-10/11 + P2-13，Robolectric）。
 *
 * 核心断言（规格 2.4）：种子课表 → 导出 → 导入新建 → 全学期
 * [ResolveScheduleOccurrences] 发生实例**逐一相等**（而非只比条数）。
 * 另覆盖：重复文件确认门（source 存文件摘要）、学校绑定课表拒绝替换、
 * 替换保留课表名称、批内关系损坏整批失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportRoundTripTest {

    private lateinit var db: IYangoDatabase
    private lateinit var seeder: DemoSeeder
    private lateinit var exportService: ExportScheduleService
    private lateinit var importService: ImportScheduleService

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, IYangoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seeder = DemoSeeder(db)
        exportService = ExportScheduleService(db)
        importService = ImportScheduleService(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 一次"发生实例"的可比投影（剔除跨库必然不同的身份字段 id/timetableId/occurrenceId）。 */
    private data class OccurrenceKey(
        val date: LocalDate,
        val name: String,
        val teacher: String,
        val classroom: String,
        val dayOfWeek: Int,
        val startSection: Int,
        val endSection: Int,
        val startWeek: Int,
        val endWeek: Int,
        val weekType: WeekType,
        val selectedWeeks: List<Int>,
        val timeMode: Int,
        val startTime: String,
        val endTime: String,
        val singleDate: String,
        val color: String,
        val isOverride: Boolean,
        val startMinute: Int?,
        val endMinute: Int?,
    )

    private suspend fun occurrencesOf(timetableId: Long): List<OccurrenceKey> {
        val timetable = db.timetableDao().getById(timetableId)!!.toModel()
        val semester = db.semesterDao().getCurrent(timetableId)?.toModel()
        val slots = db.timeSlotDao().getByTimetable(timetableId).map { it.toModel() }
        val courses = db.courseDao().getAllByTimetable(timetableId).map { it.toModel() }
        val range = semester!!.startDate..semester.startDate.plusDays((semester.totalWeeks * 7 - 1).toLong())
        return ResolveScheduleOccurrences.invoke(timetable, semester, slots, courses, range).map {
            OccurrenceKey(
                date = it.date,
                name = it.course.name,
                teacher = it.course.teacher,
                classroom = it.course.classroom,
                dayOfWeek = it.course.dayOfWeek,
                startSection = it.course.startSection,
                endSection = it.course.endSection,
                startWeek = it.course.startWeek,
                endWeek = it.course.endWeek,
                weekType = it.course.weekType,
                selectedWeeks = it.course.selectedWeeks,
                timeMode = it.course.timeMode,
                startTime = it.course.startTime,
                endTime = it.course.endTime,
                singleDate = it.course.date,
                color = it.course.color,
                isOverride = it.isOverride,
                startMinute = it.startMinute,
                endMinute = it.endMinute,
            )
        }
    }

    private suspend fun readPreview(json: String): ImportPreview =
        (importService.read(json.toByteArray(Charsets.UTF_8)) as ImportReadResult.Ready).preview

    @Test
    fun `导出再导入后全学期发生实例逐一相等`() = runTest {
        seeder.seedDemoIfEmpty()
        val sourceId = 1L

        val export = exportService.export(sourceId)
        assertEquals(7, export.courseCount)
        assertEquals(11, export.timeSlotCount)
        assertTrue(export.fileName.startsWith("iYango-"))
        assertTrue(export.fileName.endsWith(".json"))

        val preview = readPreview(export.json)
        assertEquals(7, preview.courseCount)
        assertEquals(11, preview.timeSlotCount)
        assertEquals("2026 秋季学期", preview.semesterName)
        assertTrue(preview.issues.isEmpty())
        assertFalse(preview.duplicateExisting)

        val committed = importService.commit(preview)
        val importedId = committed.timetableId
        assertTrue(importedId != sourceId)
        assertEquals(7, committed.courseCount)
        assertTrue(committed.issues.isEmpty())

        // 新建课表：学期/作息/课程齐备；当前课表已存在，故不抢占 isCurrent。
        assertFalse(db.timetableDao().getById(importedId)!!.isCurrent)
        assertEquals(1, db.semesterDao().getAllByTimetable(importedId).size)
        assertEquals(11, db.timeSlotDao().getByTimetable(importedId).size)

        val expected = occurrencesOf(sourceId)
        val actual = occurrencesOf(importedId)
        assertEquals(expected.size, actual.size)
        assertEquals(expected, actual)
        // 种子课表确实解析出了覆盖全周期的实例（含调课/单次日期课），比较不是空转。
        assertTrue(expected.size > 50)
        assertTrue(expected.any { it.isOverride })
    }

    @Test
    fun `重复文件需确认后 force 才可再导`() = runTest {
        seeder.seedDemoIfEmpty()
        val export = exportService.export(1L)
        importService.commit(readPreview(export.json))

        // 第二次读同一文件：preview 标记重复；未确认直接提交被拒绝。
        val second = readPreview(export.json)
        assertTrue(second.duplicateExisting)
        val rejected = runCatching { importService.commit(second, replace = false) }
        assertTrue(rejected.exceptionOrNull() is IllegalStateException)

        // force=true 建副本成功。
        val forced = importService.commit(second, replace = false, force = true)
        assertEquals(7, forced.courseCount)
        // 种子 1 + 首次导入 1 + 强制副本 1。
        assertEquals(3, db.timetableDao().countAll())
    }

    @Test
    fun `学校绑定课表拒绝文件替换`() = runTest {
        seeder.seedDemoIfEmpty()
        val yguId = db.timetableDao().upsert(Timetable(name = "教务课表", source = "YGU").toEntity())
        val export = exportService.export(1L)
        val preview = readPreview(export.json)

        val rejected = runCatching { importService.commit(preview, yguId, replace = true) }
        assertTrue(rejected.exceptionOrNull() is IllegalStateException)
        // 目标课表数据未被破坏。
        assertEquals(0, db.courseDao().getAllByTimetable(yguId).size)
    }

    @Test
    fun `替换本地课表保留名称并整体换血`() = runTest {
        seeder.seedDemoIfEmpty()
        val blankId = db.timetableDao().upsert(Timetable(name = "空白课表", source = "LOCAL").toEntity())
        val export = exportService.export(1L)
        val preview = readPreview(export.json)

        val committed = importService.commit(preview, blankId, replace = true)
        assertEquals(blankId, committed.timetableId)
        assertTrue(committed.replaced)
        assertEquals(7, committed.courseCount)

        // 课表行属性保留（名称/来源），内容整体替换。
        val target = db.timetableDao().getById(blankId)!!
        assertEquals("空白课表", target.name)
        assertEquals("LOCAL", target.source)
        assertEquals(7, db.courseDao().getAllByTimetable(blankId).size)
        assertEquals(11, db.timeSlotDao().getByTimetable(blankId).size)

        // 替换后全学期发生实例与源课表逐一相等。
        assertEquals(occurrencesOf(1L), occurrencesOf(blankId))
    }

    @Test
    fun `批内关系损坏整批失败且无残留`() = runTest {
        seeder.seedDemoIfEmpty()
        // 调整记录指向文件中不存在的课程 → 整批失败，事务无残留。
        val text = """
            {"courses":[
              {"name":"高数","xqj":1,"startSection":1,"endSection":2,"startWeek":1,"endWeek":16},
              {"name":"高数","xqj":3,"startSection":8,"endSection":8,"startWeek":8,"endWeek":8,
               "overrideOfId":999,"overrideWeek":8}]}
        """.trimIndent()
        val preview = readPreview(text)
        val rejected = runCatching { importService.commit(preview) }
        val error = rejected.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue((error!!.message ?: "").contains("不存在于文件中"))
        // 事务无残留：源课表课程数不变，也没有多出导入课表。
        assertEquals(7, db.courseDao().getAllByTimetable(1L).size)
        assertEquals(1, db.timetableDao().countAll())
    }

    @Test
    fun `追加导入保留原课程与学期作息并重映射调整引用`() = runTest {
        seeder.seedDemoIfEmpty()
        val targetId = db.timetableDao().upsert(Timetable(name = "本地课表", source = "LOCAL").toEntity())
        val originalId = db.courseDao().upsert(
            Course(
                name = "原有课程",
                dayOfWeek = 5,
                startSection = 1,
                endSection = 2,
                startWeek = 1,
                endWeek = 18,
            ).toEntity(targetId),
        )
        db.semesterDao().upsert(
            SemesterEntity(
                timetableId = targetId,
                name = "本机学期",
                startDate = LocalDate.of(2026, 9, 1),
                totalWeeks = 18,
                isCurrent = true,
            ),
        )
        db.timeSlotDao().upsertAll(
            listOf(
                TimeSlotEntity(
                    timetableId = targetId,
                    section = 1,
                    startTime = "08:00",
                    endTime = "08:45",
                    name = "",
                    kind = 0,
                    hidden = false,
                    defaultCourseName = "",
                    sortOrder = 1.0,
                ),
            ),
        )

        val preview = readPreview(exportService.export(1L).json)
        val adjustment = preview.rows.first { it.course.overrideOfId > 0 }
        val parentRowKey = requireNotNull(adjustment.parentRowKey)
        val selected = setOf(adjustment.rowKey, parentRowKey)

        val committed = importService.commit(
            preview = preview,
            targetTimetableId = targetId,
            mode = ImportWriteMode.APPEND,
            selectedRowKeys = selected,
            applySemester = false,
            applyTimeSlots = false,
        )
        assertTrue(committed.appended)
        assertFalse(committed.replaced)
        assertEquals(2, committed.courseCount)

        val courses = db.courseDao().getAllByTimetable(targetId).map { it.toModel() }
        // 原课程一行未动，且总数 = 原有 1 + 追加 2。
        assertEquals(3, courses.size)
        assertTrue(courses.any { it.id == originalId && it.name == "原有课程" })
        // 调整记录指向本次新写入的基础课，而不是文件旧 id 或目标中同 id 的既有课。
        val importedParent = courses.first { it.name == adjustment.course.name && it.overrideOfId == 0L }
        val importedOverride = courses.first { it.overrideOfId > 0 }
        assertEquals(importedParent.id, importedOverride.overrideOfId)
        // 学期/作息默认保留本机。
        assertEquals("本机学期", db.semesterDao().getCurrent(targetId)!!.name)
        assertEquals(1, db.timeSlotDao().getByTimetable(targetId).size)
    }

    @Test
    fun `只选部分课程时不写入未选行`() = runTest {
        seeder.seedDemoIfEmpty()
        val preview = readPreview(exportService.export(1L).json)
        val bases = preview.rows.filter { it.course.overrideOfId == 0L }.take(2)
        val committed = importService.commit(
            preview = preview,
            mode = ImportWriteMode.CREATE,
            selectedRowKeys = bases.map { it.rowKey }.toSet(),
        )
        assertEquals(2, committed.courseCount)
        assertEquals(2, db.courseDao().getAllByTimetable(committed.timetableId).size)
    }

    @Test
    fun `选中调整记录但未选基础课整批拒绝`() = runTest {
        seeder.seedDemoIfEmpty()
        val preview = readPreview(exportService.export(1L).json)
        val adjustment = preview.rows.first { it.course.overrideOfId > 0 }
        val rejected = runCatching {
            importService.commit(
                preview = preview,
                mode = ImportWriteMode.CREATE,
                selectedRowKeys = setOf(adjustment.rowKey),
            )
        }
        val error = rejected.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue((error!!.message ?: "").contains("缺少被调整的课程"))
        assertEquals(1, db.timetableDao().countAll())
    }

    @Test
    fun `显示配置随导出写入并随导入返回`() = runTest {
        seeder.seedDemoIfEmpty()
        val settings = ScheduleDisplaySettings(
            showWeekends = false,
            mergeAdjacent = true,
            rowHeightDp = 64,
            align = CellTextAlign.START,
            courseColorOverrides = mapOf("高等数学|陈老师" to "#2BB673"),
            headerTextSizeSp = 14f,
            gridLineDashed = true,
            borderWidthDp = 1.5f,
            borderUseCourseColor = false,
            backgroundColorHex = "#F0F5FF",
            paletteLight = listOf("#111111", "#222222"),
            paletteDark = listOf("#EEEEEE"),
        )
        val exported = exportService.export(1L, displayConfig = encodeDisplayConfig(settings))
        assertTrue(exported.json.contains("displayConfig"))
        val preview = readPreview(exported.json)
        val committed = importService.commit(preview)
        val actual = committed.displayConfig
        assertEquals(false, actual?.showWeekends)
        assertEquals(true, actual?.mergeAdjacent)
        assertEquals(64, actual?.rowHeightDp)
        assertEquals(CellTextAlign.START, actual?.align)
        assertEquals("#2BB673", actual?.courseColorOverrides?.get("高等数学|陈老师"))
        assertEquals(14f, actual?.headerTextSizeSp)
        assertEquals(true, actual?.gridLineDashed)
        assertEquals(1.5f, actual?.borderWidthDp)
        assertEquals(false, actual?.borderUseCourseColor)
        assertEquals("#F0F5FF", actual?.backgroundColorHex)
        assertEquals(listOf("#111111", "#222222"), actual?.paletteLight)
        assertEquals(listOf("#EEEEEE"), actual?.paletteDark)
        assertEquals(GridLineStyle.DASHED, actual?.gridLineStyle)
        // 显示配置不写入 Room，课程数据仍完整。
        assertEquals(7, db.courseDao().getAllByTimetable(committed.timetableId).size)
    }

    @Test
    fun `网格线三态样式随导出往返且旧版gridLineDashed回退为虚线`() = runTest {
        seeder.seedDemoIfEmpty()
        for (style in listOf(GridLineStyle.SOLID, GridLineStyle.DASHED, GridLineStyle.DOTTED)) {
            val configured = ScheduleDisplaySettings(
                showGridLines = true,
                gridLineStyle = style,
                gridLineDashed = style == GridLineStyle.DASHED,
            )
            val exported = exportService.export(1L, displayConfig = encodeDisplayConfig(configured))
            val imported = importService.commit(readPreview(exported.json)).displayConfig
            assertEquals(style, imported?.gridLineStyle)
            assertEquals(style == GridLineStyle.DASHED, imported?.gridLineDashed)
        }

        // 旧版配置仅含 gridLineDashed=true、缺少 gridLineStyle 字段时，应回退为 DASHED
        val legacy = decodeDisplayConfig("""{"showGridLines":true,"gridLineDashed":true}""")
        assertEquals(GridLineStyle.DASHED, legacy?.gridLineStyle)
        assertEquals(true, legacy?.gridLineDashed)
    }
}
