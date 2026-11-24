package com.gfs.iyango.data.seed

import androidx.room.withTransaction
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.CourseValidator
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WeekType
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.repository.toEntity
import com.gfs.iyango.data.repository.toModel
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 演示种子数据：首次启动（课表表为空）时插入一套覆盖全部周规则的示例课表。
 *
 * 幂等：课表表非空则直接返回，绝不重复播种（另加 [Mutex] 防并发双播种）。
 *
 * 种子内容（学期锚点 2026-09-07 周一，20 周；按 `CourseRules.weekOfDate` 计算，
 * 2026-10-24 应解析为第 7 周 —— 这是周规则的测试锚点）：
 * 1. 高等数学：周一 1-2 节，1-16 周每周；
 * 2. 大学英语：周二 3-4 节，1-16 周单周（ODD）；
 * 3. 数据结构：周三 6-7 节，1-16 周双周（EVEN）；
 * 4. 体育：周四 1-2 节，selectedWeeks=[2,4,6,8]，户外操场；
 * 5. 程序设计实践：周五 9-10 节，timeMode=1 自定义 19:00-20:35；
 * 6. 校运会：timeMode=2 单次日期课，date=2026-11-06（第 9 周周五）；
 * 7. 单次调课记录：把第 8 周周一的高数调到周三第 8 节（overrideOfId=课程1，D404；
 *    调往空档节次，避免与第 3 条数据结构（周三 6-7 节，双周）在第 8 周同槽重叠）。
 *
 * 课程颜色按 core:designsystem `CoursePalette.colors` 的顺序取前几项
 * （data 模块不依赖 designsystem，故在此以同序常量硬编码；调整记录与被调课程同色）。
 *
 * 本类直接写 DAO 而不走 ScheduleDataPort（需同时建课表/学期/作息）；种子课程仍逐条
 * 过 [CourseValidator] 自检，防止演示数据本身非法。
 *
 * DAO 层的播种行为验证留给 P2 的 Robolectric/仪器测试。
 */
@Singleton
class DemoSeeder @Inject constructor(
    private val db: IYangoDatabase,
) {

    /** 防止并发首次启动重复播种。 */
    private val mutex = Mutex()

    /**
     * 课表表为空时播种演示数据；已存在任何课表则不做任何写入。
     */
    suspend fun seedDemoIfEmpty() {
        mutex.withLock {
            db.withTransaction {
                if (db.timetableDao().countAll() > 0) {
                    return@withTransaction
                }
                seedInsideTransaction()
            }
        }
    }

    /**
     * 补充全周（周一至周日）的测试展示课程，便于周末或任意工作日预览桌面组件效果；
     * 按节次/星期/单双周去重，可幂等重复调用。
     */
    suspend fun ensureTestShowcaseCourses() {
        mutex.withLock {
            db.withTransaction {
                if (db.timetableDao().countAll() == 0) {
                    seedInsideTransaction()
                }
                val table = db.timetableDao().getAll().let { all ->
                    val localTables = all.filter { it.source.equals("LOCAL", ignoreCase = true) }
                    localTables.firstOrNull { it.isCurrent } ?: localTables.firstOrNull()
                } ?: return@withTransaction

                if (db.semesterDao().getCurrent(table.id) == null) {
                    db.semesterDao().upsert(
                        Semester(
                            timetableId = table.id,
                            name = "2026 秋季学期",
                            startDate = LocalDate.of(2026, 9, 7),
                            totalWeeks = 20,
                            isCurrent = true,
                        ).toEntity()
                    )
                }

                if (db.timeSlotDao().getByTimetable(table.id).isEmpty()) {
                    db.timeSlotDao().upsertAll(
                        SECTION_TIMES.mapIndexed { index, (start, end) ->
                            TimeSlot(
                                timetableId = table.id,
                                section = index + 1,
                                startTime = start,
                                endTime = end,
                                name = "第${index + 1}节",
                            ).toEntity()
                        }
                    )
                }

                val existing = db.courseDao().getAllByTimetable(table.id).map { it.toModel() }
                val toInsert = buildList {
                    for (spec in SHOWCASE_COURSES) {
                        val course = spec.toCourse(table.id)
                        val conflicts = existing.any { item ->
                            item.overrideOfId == 0L &&
                                item.timeMode != 2 &&
                                item.dayOfWeek == course.dayOfWeek &&
                                item.startSection <= course.endSection &&
                                item.endSection >= course.startSection &&
                                item.selectedWeeks.isEmpty() &&
                                (
                                    item.weekType == WeekType.EVERY ||
                                        course.weekType == WeekType.EVERY ||
                                        item.weekType == course.weekType
                                    )
                        }
                        if (!conflicts) {
                            val problem = CourseValidator.validate(course)
                            check(problem == null) { "演示课程数据非法：$problem（${course.name}）" }
                            add(course.toEntity(table.id))
                        }
                    }
                }
                if (toInsert.isNotEmpty()) {
                    db.courseDao().upsertAll(toInsert)
                }
            }
        }
    }

    /** 事务内执行的实际播种流程（调用方已保证课表表为空）。 */
    private suspend fun seedInsideTransaction() {
        // 课表：本地手填，唯一且当前。
        val timetableId = db.timetableDao().upsert(
            Timetable(name = "我的课表", source = "LOCAL", isCurrent = true).toEntity()
        )

        // 学期：2026-09-07（周一）起，20 周；2026-10-24 → 第 7 周（测试锚点）。
        db.semesterDao().upsert(
            Semester(
                timetableId = timetableId,
                name = "2026 秋季学期",
                startDate = LocalDate.of(2026, 9, 7),
                totalWeeks = 20,
                isCurrent = true,
            ).toEntity()
        )

        // 作息：11 节。
        db.timeSlotDao().upsertAll(
            SECTION_TIMES.mapIndexed { index, (start, end) ->
                TimeSlot(
                    timetableId = timetableId,
                    section = index + 1,
                    startTime = start,
                    endTime = end,
                    name = "第${index + 1}节",
                ).toEntity()
            }
        )

        // 课程 1：高等数学，周一 1-2 节，1-16 周每周（id 后面给调课记录引用）。
        val mathId = insertCourse(
            Course(
                timetableId = timetableId,
                name = "高等数学",
                teacher = "张老师",
                classroom = "A101",
                dayOfWeek = 1,
                startSection = 1,
                endSection = 2,
                startWeek = 1,
                endWeek = 16,
                weekType = WeekType.EVERY,
                color = PALETTE[0],
            )
        )

        // 课程 2：大学英语，周二 3-4 节，1-16 周单周。
        insertCourse(
            Course(
                timetableId = timetableId,
                name = "大学英语",
                teacher = "李老师",
                classroom = "B202",
                dayOfWeek = 2,
                startSection = 3,
                endSection = 4,
                startWeek = 1,
                endWeek = 16,
                weekType = WeekType.ODD,
                color = PALETTE[1],
            )
        )

        // 课程 3：数据结构，周三 6-7 节，1-16 周双周。
        insertCourse(
            Course(
                timetableId = timetableId,
                name = "数据结构",
                teacher = "王老师",
                classroom = "C303",
                dayOfWeek = 3,
                startSection = 6,
                endSection = 7,
                startWeek = 1,
                endWeek = 16,
                weekType = WeekType.EVEN,
                color = PALETTE[2],
            )
        )

        // 课程 4：体育，周四 1-2 节，仅第 2/4/6/8 周（所选周筛选），户外。
        insertCourse(
            Course(
                timetableId = timetableId,
                name = "体育",
                classroom = "操场",
                dayOfWeek = 4,
                startSection = 1,
                endSection = 2,
                startWeek = 1,
                endWeek = 16,
                selectedWeeks = listOf(2, 4, 6, 8),
                isOutdoor = true,
                color = PALETTE[3],
            )
        )

        // 课程 5：程序设计实践，周五 9-10 节，自定义钟点 19:00-20:35。
        insertCourse(
            Course(
                timetableId = timetableId,
                name = "程序设计实践",
                dayOfWeek = 5,
                startSection = 9,
                endSection = 10,
                startWeek = 1,
                endWeek = 16,
                timeMode = 1,
                startTime = "19:00",
                endTime = "20:35",
                color = PALETTE[4],
            )
        )

        // 课程 6：校运会，单次指定日期课（2026-11-06，第 9 周周五），全天。
        // timeMode>0 的共享校验要求合法钟点，故带上与全天节次一致的 08:00-21:35。
        insertCourse(
            Course(
                timetableId = timetableId,
                name = "校运会",
                dayOfWeek = 5,
                startSection = 1,
                endSection = 11,
                startWeek = 9,
                endWeek = 9,
                timeMode = 2,
                startTime = "08:00",
                endTime = "21:35",
                date = "2026-11-06",
                color = PALETTE[5],
            )
        )

        // 课程 7：单次调课记录 —— 第 8 周周一的高数调到周三第 8 节（D404）。
        // 与被调整课程同名同色，保证 UI 识别为同一门课；仅第 8 周发生。
        // 调往空档节次（周三 8-8），避免与数据结构（周三 6-7，双周）在第 8 周同槽冲突。
        insertCourse(
            Course(
                timetableId = timetableId,
                name = "高等数学",
                teacher = "张老师",
                classroom = "D404",
                dayOfWeek = 3,
                startSection = 8,
                endSection = 8,
                startWeek = 8,
                endWeek = 8,
                color = PALETTE[0],
                overrideOfId = mathId,
                overrideWeek = 8,
            )
        )
    }

    /** 校验并写入一条课程；种子数据必须通过共享校验器，防止演示数据本身非法。 */
    private suspend fun insertCourse(course: Course): Long {
        val problem = CourseValidator.validate(course)
        check(problem == null) { "演示课程数据非法：$problem（${course.name}）" }
        val entity = course.toEntity(course.timetableId)
        val rowId = db.courseDao().upsert(entity)
        return if (rowId > 0) rowId else entity.id
    }

    private companion object {
        /**
         * 课程配色（#RRGGBB），顺序对齐 core:designsystem `CoursePalette.colors` 的
         * 前 7 项：蓝/亮蓝/绿/橙/红/紫。
         */
        val PALETTE = listOf(
            "#3E7BFA", "#0A59F7", "#2BB673", "#F2A03D", "#F2634E", "#8B6BF0", "#2BB2B2",
        )

        /** 11 节作息（节次顺序 → 起止钟点）。 */
        val SECTION_TIMES = listOf(
            "08:00" to "08:45",
            "08:55" to "09:40",
            "10:00" to "10:45",
            "10:55" to "11:40",
            "14:00" to "14:45",
            "14:55" to "15:40",
            "16:00" to "16:45",
            "16:55" to "17:40",
            "19:00" to "19:45",
            "19:55" to "20:40",
            "20:50" to "21:35",
        )

        private data class ShowcaseCourseSpec(
            val dayOfWeek: Int,
            val startSection: Int,
            val endSection: Int,
            val name: String,
            val teacher: String,
            val classroom: String,
            val color: String,
            val weekType: WeekType = WeekType.EVERY,
        ) {
            fun toCourse(timetableId: Long): Course = Course(
                timetableId = timetableId,
                name = name,
                teacher = teacher,
                classroom = classroom,
                dayOfWeek = dayOfWeek,
                startSection = startSection,
                endSection = endSection,
                startWeek = 1,
                endWeek = 20,
                weekType = weekType,
                color = color,
            )
        }

        /** 覆盖周一至周日的展示课程模板。 */
        private val SHOWCASE_COURSES = listOf(
            // 周一
            ShowcaseCourseSpec(1, 3, 4, "线性代数", "周老师", "明理楼 A103", PALETTE[2]),
            ShowcaseCourseSpec(1, 5, 6, "操作系统", "吴教授", "信息楼 B301", PALETTE[3]),
            ShowcaseCourseSpec(1, 7, 8, "大学物理实验", "徐老师", "物理馆 C205", PALETTE[5]),
            // 周二
            ShowcaseCourseSpec(2, 1, 2, "概率论与数理统计", "张老师", "理学楼 A201", PALETTE[1]),
            ShowcaseCourseSpec(2, 3, 4, "学术英语写作", "李老师", "外语楼 B202", PALETTE[2], WeekType.EVEN),
            ShowcaseCourseSpec(2, 5, 6, "数据库系统原理", "陈老师", "信息楼 B208", PALETTE[6]),
            ShowcaseCourseSpec(2, 7, 8, "计算机组成原理", "杨教授", "明理楼 C104", PALETTE[4]),
            // 周三
            ShowcaseCourseSpec(3, 1, 2, "离散数学", "王教授", "理学楼 A105", PALETTE[5]),
            ShowcaseCourseSpec(3, 3, 4, "编译原理", "赵老师", "信息楼 B402", PALETTE[0]),
            ShowcaseCourseSpec(3, 9, 10, "算法设计与分析", "李教授", "科创楼 A301", PALETTE[3]),
            // 周四
            ShowcaseCourseSpec(4, 3, 4, "计算机网络", "刘老师", "明理楼 A204", PALETTE[2]),
            ShowcaseCourseSpec(4, 5, 6, "软件工程", "赵老师", "信息楼 B305", PALETTE[0]),
            ShowcaseCourseSpec(4, 7, 8, "形势与政策", "马老师", "人文楼 D102", PALETTE[4]),
            // 周五
            ShowcaseCourseSpec(5, 1, 2, "操作系统实验", "吴教授", "实验中心 C301", PALETTE[6]),
            ShowcaseCourseSpec(5, 3, 4, "马克思主义基本原理", "林老师", "人文楼 A101", PALETTE[1]),
            ShowcaseCourseSpec(5, 6, 7, "移动应用开发", "孙老师", "科创楼 B202", PALETTE[5]),
            // 周六（今日）
            ShowcaseCourseSpec(6, 1, 2, "人工智能导论", "陈教授", "科创楼 B305", PALETTE[0]),
            ShowcaseCourseSpec(6, 3, 4, "计算机视觉", "刘老师", "明理楼 A204", PALETTE[2]),
            ShowcaseCourseSpec(6, 5, 6, "软件工程实训", "赵老师、孙老师", "实验中心 C402", PALETTE[3]),
            ShowcaseCourseSpec(6, 9, 10, "学术前沿讲座", "周教授", "报告厅 101", PALETTE[5]),
            // 周日（明日）
            ShowcaseCourseSpec(7, 1, 2, "工程数学建模", "吴老师", "理学楼 A108", PALETTE[1]),
            ShowcaseCourseSpec(7, 3, 4, "数字媒体设计", "郑老师", "艺术楼 D206", PALETTE[4]),
            ShowcaseCourseSpec(7, 6, 7, "创新创业实践", "林老师", "创客空间 302", PALETTE[6]),
        )
    }
}
