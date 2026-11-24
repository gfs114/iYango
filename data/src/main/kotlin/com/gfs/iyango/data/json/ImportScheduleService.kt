package com.gfs.iyango.data.json

import androidx.room.withTransaction
import com.gfs.iyango.core.domain.ImportWriteMode
import com.gfs.iyango.core.domain.ScheduleImportTargetInfo
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.CourseValidator
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.data.db.ImportRecordEntity
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SemesterEntity
import com.gfs.iyango.data.db.TimetableEntity
import com.gfs.iyango.data.db.TimeSlotEntity
import com.gfs.iyango.data.repository.toEntity
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 预览中的一条安排及其本次导入内的稳定行键。
 *
 * @property rowKey 选择状态使用的键（与课程名称无关）。
 * @property course 文件原始记录。
 * @property parentRowKey 调整记录指向的基础课行键；非调整或目标缺失时为 null。
 */
data class ImportRow(
    val rowKey: String,
    val course: ScheduleJsonCourse,
    val parentRowKey: String?,
)

/**
 * 一次 JSON 文件读取的预览（解析成功、尚未写库），见 [ImportScheduleService.read]。
 *
 * @property courseCount 解析到的课程条数。
 * @property issueCount 解析提示/纠正条数（与 [issues] 一致）。
 * @property issues 全部提示的单行文本（[ImportIssue.display]）。
 * @property semesterName 文件携带的学期名；未携带为 null。
 * @property timeSlotCount 文件携带的作息行数。
 * @property fileDigest 文件内容摘要（SHA-256 前 16 位十六进制），写入导入记录的 source。
 * @property duplicateExisting 是否已存在同摘要的历史导入（重复文件提示）。
 * @property rows 逐条安排与行键（选择/依赖校验用）。
 * @property displayConfig 文件显示配置；未携带或无法解析为 null。
 * @property document 解析出的文档（导入服务内部使用）。
 */
data class ImportPreview(
    val courseCount: Int,
    val issueCount: Int,
    val issues: List<String>,
    val semesterName: String?,
    val timeSlotCount: Int,
    val fileDigest: String,
    val duplicateExisting: Boolean,
    internal val rows: List<ImportRow>,
    internal val displayConfig: ScheduleDisplaySettings?,
    internal val document: ScheduleJsonDocument,
)

/** [ImportScheduleService.read] 的结果。 */
sealed interface ImportReadResult {
    /** 解析成功，可以进入预览/确认流程。 */
    data class Ready(val preview: ImportPreview) : ImportReadResult

    /** 解析失败；[message] 为首个致命原因，[issues] 为全部提示。 */
    data class Failed(val message: String, val issues: List<String>) : ImportReadResult
}

/** [ImportScheduleService.commit] 的结果。 */
data class ImportCommitResult(
    /** 写入的课表 id（新建课表、替换或追加的目标课表）。 */
    val timetableId: Long,
    /** 是否为替换模式。 */
    val replaced: Boolean,
    /** 是否为追加模式。 */
    val appended: Boolean,
    /** 实际写入的课程条数。 */
    val courseCount: Int,
    /** 提交阶段的追加提示（学期/作息冲突、显示设置未应用等）。 */
    val issues: List<String>,
    /** 文件携带且可解析的显示配置；由调用方在事务外写入 DataStore。 */
    val displayConfig: ScheduleDisplaySettings?,
)

/**
 * 课表 JSON 导入服务（P2-11）。
 *
 * 流程：`read`（读字节 → 大小校验 5MB → 宽容解析 → 重复文件检测）→ 用户确认目标 →
 * `commit`（单个 `db.withTransaction` 写入，失败整体回滚）：
 * - **新建**（[ImportWriteMode.CREATE]）：创建"导入课表 yyyy-MM-dd"的 LOCAL 课表，
 *   学期/作息/课程按选项写入；课表全空时自动置为当前；追加导入记录；
 * - **追加**（[ImportWriteMode.APPEND]）：保留目标 LOCAL 课表全部原有课程，只写入选中的
 *   新记录并重新分配 id；已有学期不覆盖（文件学期仅作提示），作息只有显式选择才替换；
 * - **替换**（[ImportWriteMode.REPLACE]）：仅允许 LOCAL 来源课表（学校绑定课表抛
 *   [IllegalStateException]）；必须选择全部课程；保留课表名称/分组等行属性；
 * - **ID 映射**：文件内旧课程 id → 新自增 id；调整记录 `overrideOfId` 按映射重建；
 *   旧 id 重复/调整目标缺失/循环引用/批内 (overrideOfId, overrideWeek) 重复 →
 *   整批失败（写入前拦截，[IllegalArgumentException] 列出全部问题）；选中调整记录但
 *   未选中其基础课时整批拒绝，绝不指向同 id 的既有课程；
 * - **重复文件**：导入记录的 source 存文件摘要（SHA-256 前 16 hex）；再次导入同一文件
 *   时 preview 标记 `duplicateExisting`，未显式 `force=true` 则拒绝（替换路径同样需要
 *   用户确认目标，但仍受 force 门约束）；
 * - **displayConfig**：解析为 [ScheduleDisplaySettings] 随结果返回，由调用方在 Room
 *   事务成功后单独写入 DataStore；写入失败不回滚课程，但必须向用户报告。
 *
 * 提醒计划重算：当前版本没有提醒子系统；接入后（P5）替换/新建路径在此处追加重算调用。
 */
@Singleton
class ImportScheduleService @Inject constructor(
    private val db: IYangoDatabase,
) {

    /** 单个导入文件的大小上限（5MB）。 */
    val maxFileBytes: Int = 5 * 1024 * 1024

    /**
     * 读取并解析一个 JSON 文件（字节），不触碰业务表。
     *
     * @throws IllegalArgumentException 文件超过 [maxFileBytes] 时抛出。
     */
    suspend fun read(bytes: ByteArray): ImportReadResult {
        if (bytes.size > maxFileBytes) {
            throw IllegalArgumentException("文件超过 5MB 上限，无法导入")
        }
        val parsed = ScheduleJsonParser.parse(bytes.toString(Charsets.UTF_8))
        val document = parsed.document
            ?: return ImportReadResult.Failed(
                parsed.issues.firstOrNull()?.message ?: "文件内容无法解析",
                parsed.issues.map { it.display() },
            )
        val digest = sha256Hex16(bytes)
        val rows = buildRows(document.courses)
        val displayConfig = decodeDisplayConfig(document.displayConfig)
        return ImportReadResult.Ready(
            ImportPreview(
                courseCount = document.courses.size,
                issueCount = parsed.issues.size,
                issues = parsed.issues.map { it.display() },
                semesterName = document.semester?.name,
                timeSlotCount = document.timeSlots.size,
                fileDigest = digest,
                duplicateExisting = db.importRecordDao().getBySource(digest) != null,
                rows = rows,
                displayConfig = displayConfig,
                document = document,
            ),
        )
    }

    /**
     * 为每条安排生成稳定行键。
     *
     * 行键 = 文件内序号 + 内容摘要前 8 位：同一份文件重复读取时稳定，且不依赖课程名称
     * （同名课程可分别勾选）。调整记录的第二遍扫描解析父行键。
     */
    private fun buildRows(courses: List<ScheduleJsonCourse>): List<ImportRow> {
        val keys = courses.mapIndexed { index, course ->
            "r%04d-%s".format(index, contentHash8(course))
        }
        val rowKeyById = HashMap<Long, String>()
        courses.forEachIndexed { index, course ->
            if (course.id > 0) {
                rowKeyById.putIfAbsent(course.id, keys[index])
            }
        }
        return courses.mapIndexed { index, course ->
            ImportRow(
                rowKey = keys[index],
                course = course,
                parentRowKey = if (course.overrideOfId > 0) rowKeyById[course.overrideOfId] else null,
            )
        }
    }

    private fun contentHash8(course: ScheduleJsonCourse): String {
        val text = listOf(
            course.name, course.teacher, course.classroom,
            course.dayOfWeek.toString(), course.startSection.toString(), course.endSection.toString(),
            course.startWeek.toString(), course.endWeek.toString(), course.weekType.toString(),
            course.overrideOfId.toString(), course.overrideWeek.toString(), course.date,
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(8)
    }

    /** 目标当前状态（学期/作息/课程数）；课表不存在返回 null。 */
    suspend fun inspectTarget(timetableId: Long): ScheduleImportTargetInfo? {
        val timetable = db.timetableDao().getById(timetableId) ?: return null
        return ScheduleImportTargetInfo(
            timetableId = timetableId,
            name = timetable.name,
            isLocal = timetable.source == "LOCAL",
            courseCount = db.courseDao().getAllByTimetable(timetableId).size,
            semesterName = db.semesterDao().getCurrent(timetableId)?.name,
            timeSlotCount = db.timeSlotDao().getByTimetable(timetableId).size,
        )
    }

    /**
     * 把预览写入数据库。
     *
     * @param preview [read] 产出的预览。
     * @param targetTimetableId APPEND/REPLACE 的目标课表 id；CREATE 忽略。
     * @param replace 兼容旧调用的快捷方式；等价于 `mode = REPLACE`。
     * @param force 重复文件时是否强制继续（UI 二次确认后传 true）。
     * @param mode 写入模式；null 时按 [replace] 推断。
     * @param selectedRowKeys 选中的预览行键；null=全部。选中调整记录必须同时选中其基础课。
     * @param applySemester 是否采用文件学期（APPEND 已有学期时始终保留本机）。
     * @param applyTimeSlots 是否采用文件作息（APPEND 下会替换目标课表作息）。
     * @param applyDisplayConfig 是否采用文件显示配置（随结果返回，由调用方写 DataStore）。
     * @throws IllegalStateException 目标不存在/为学校绑定课表，或重复文件未确认。
     * @throws IllegalArgumentException 文件内课程关系无法安全导入，或选择集不满足依赖。
     */
    suspend fun commit(
        preview: ImportPreview,
        targetTimetableId: Long? = null,
        replace: Boolean = false,
        force: Boolean = false,
        mode: ImportWriteMode? = null,
        selectedRowKeys: Set<String>? = null,
        applySemester: Boolean = true,
        applyTimeSlots: Boolean = true,
        applyDisplayConfig: Boolean = true,
    ): ImportCommitResult {
        val resolvedMode = mode ?: if (replace) ImportWriteMode.REPLACE else ImportWriteMode.CREATE
        if (resolvedMode != ImportWriteMode.CREATE) {
            checkNotNull(targetTimetableId) { "追加/替换导入必须指定目标课表" }
        }
        if (!force && preview.duplicateExisting) {
            throw IllegalStateException("该文件此前已导入过，请确认后重试（force=true）")
        }
        val rows = selectRows(preview, selectedRowKeys, resolvedMode)
        val problems = validateBatch(rows.map { it.course })
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException("导入文件存在无法处理的问题：\n" + problems.joinToString("\n"))
        }
        val committedIssues = mutableListOf<String>()
        if (preview.document.displayConfig.isNotBlank() && preview.displayConfig == null) {
            committedIssues += "文件显示配置无法解析，已保留本机设置"
        }
        val result = db.withTransaction {
            when (resolvedMode) {
                ImportWriteMode.CREATE -> commitCreate(
                    preview, rows, applySemester, applyTimeSlots, committedIssues,
                )
                ImportWriteMode.APPEND -> commitAppend(
                    preview, rows, checkNotNull(targetTimetableId),
                    applySemester, applyTimeSlots, committedIssues,
                )
                ImportWriteMode.REPLACE -> commitReplace(
                    preview, rows, checkNotNull(targetTimetableId),
                    applySemester, applyTimeSlots, committedIssues,
                )
            }
        }
        return result.copy(
            displayConfig = preview.displayConfig.takeIf { applyDisplayConfig },
        )
    }

    /**
     * 解析选择集并校验依赖闭包。
     *
     * REPLACE 必须选择全部行；调整记录与其基础课必须同时选中，避免 `overrideOfId`
     * 指向目标课表中恰好同 id 的既有课程。
     */
    private fun selectRows(
        preview: ImportPreview,
        selectedRowKeys: Set<String>?,
        mode: ImportWriteMode,
    ): List<ImportRow> {
        val all = preview.rows
        if (all.isEmpty()) {
            throw IllegalArgumentException("文件没有可导入的课程")
        }
        val selected = if (selectedRowKeys == null) all else all.filter { it.rowKey in selectedRowKeys }
        if (selected.isEmpty()) {
            throw IllegalArgumentException("请至少选择一条课程")
        }
        if (mode == ImportWriteMode.REPLACE && selected.size != all.size) {
            throw IllegalArgumentException("替换导入必须选择全部课程（已选 ${selected.size}/${all.size}）")
        }
        val selectedKeys = selected.mapTo(HashSet()) { it.rowKey }
        val missingInFile = selected.filter { it.course.overrideOfId > 0 && it.parentRowKey == null }
        if (missingInFile.isNotEmpty()) {
            throw IllegalArgumentException(
                "以下单次调整指向的课程不存在于文件中：" +
                    missingInFile.joinToString("、") { it.course.name },
            )
        }
        val missingParent = selected.filter {
            it.course.overrideOfId > 0 && it.parentRowKey != null && it.parentRowKey !in selectedKeys
        }
        if (missingParent.isNotEmpty()) {
            throw IllegalArgumentException(
                "以下单次调整缺少被调整的课程，请一并选择：" +
                    missingParent.joinToString("、") { it.course.name },
            )
        }
        return selected
    }

    /** 新建"导入课表 yyyy-MM-dd"并写入学期/作息/课程/导入记录。 */
    private suspend fun commitCreate(
        preview: ImportPreview,
        rows: List<ImportRow>,
        applySemester: Boolean,
        applyTimeSlots: Boolean,
        issues: MutableList<String>,
    ): ImportCommitResult {
        val timetableId = db.timetableDao().upsert(
            TimetableEntity(
                name = "导入课表 ${LocalDate.now()}",
                source = "LOCAL",
                schoolTerm = "",
                connectionId = "",
                isCurrent = false,
                lastSyncAt = "",
                groupName = "",
                autoEnabled = false,
                enabledDays = (1..7).toList(),
                cycleLength = 1,
                cycleWeek = 0,
            ),
        )
        // 维持"全表至多一行 isCurrent=1"不变式：库中还没有当前课表时自动置当前。
        if (db.timetableDao().countCurrent() == 0) {
            db.timetableDao().setCurrent(timetableId)
        }
        if (applySemester) {
            applySemester(timetableId, preview.document.semester, issues, replaceExisting = false)
        } else if (preview.document.semester != null) {
            issues += "未采用文件学期，课表暂无学期"
        }
        if (applyTimeSlots) {
            applyTimeSlots(timetableId, preview.document.timeSlots)
        } else if (preview.document.timeSlots.isNotEmpty()) {
            issues += "未采用文件作息，课表暂无作息时间"
        }
        val written = insertCourses(timetableId, rows.map { it.course })
        recordImport(timetableId, preview, issues)
        return ImportCommitResult(
            timetableId = timetableId,
            replaced = false,
            appended = false,
            courseCount = written,
            issues = issues,
            displayConfig = null,
        )
    }

    /**
     * 追加到现有 LOCAL 课表：原课程一行不动，新记录重新分配 id。
     *
     * 学期不覆盖已有值；作息只有显式 [applyTimeSlots] 才替换（并提示旧课程时间会变化）。
     */
    private suspend fun commitAppend(
        preview: ImportPreview,
        rows: List<ImportRow>,
        targetTimetableId: Long,
        applySemester: Boolean,
        applyTimeSlots: Boolean,
        issues: MutableList<String>,
    ): ImportCommitResult {
        val target = db.timetableDao().getById(targetTimetableId)
            ?: throw IllegalStateException("目标课表不存在：$targetTimetableId")
        if (target.source != "LOCAL") {
            throw IllegalStateException("学校绑定课表不支持追加导入：${target.name}")
        }
        val existingSemester = db.semesterDao().getCurrent(targetTimetableId)
        when {
            existingSemester != null ->
                issues += "目标课表已有学期「${existingSemester.name}」，已保留本机学期"
            applySemester ->
                applySemester(targetTimetableId, preview.document.semester, issues, replaceExisting = false)
        }
        val existingSlots = db.timeSlotDao().getByTimetable(targetTimetableId)
        when {
            preview.document.timeSlots.isEmpty() -> Unit
            applyTimeSlots -> {
                db.timeSlotDao().deleteByTimetable(targetTimetableId)
                applyTimeSlots(targetTimetableId, preview.document.timeSlots)
                if (existingSlots.isNotEmpty()) {
                    issues += "已按文件替换作息时间，原有课程的上下课时间可能随之变化"
                }
            }
            existingSlots.isNotEmpty() ->
                issues += "目标课表已有作息，已保留本机作息"
        }
        val written = insertCourses(targetTimetableId, rows.map { it.course })
        recordImport(targetTimetableId, preview, issues)
        return ImportCommitResult(
            timetableId = targetTimetableId,
            replaced = false,
            appended = true,
            courseCount = written,
            issues = issues,
            displayConfig = null,
        )
    }

    /** 替换指定 LOCAL 课表的学期/作息/课程，保留课表行属性（名称/分组等）。 */
    private suspend fun commitReplace(
        preview: ImportPreview,
        rows: List<ImportRow>,
        targetTimetableId: Long,
        applySemester: Boolean,
        applyTimeSlots: Boolean,
        issues: MutableList<String>,
    ): ImportCommitResult {
        val target = db.timetableDao().getById(targetTimetableId)
            ?: throw IllegalStateException("目标课表不存在：$targetTimetableId")
        if (target.source != "LOCAL") {
            throw IllegalStateException("学校绑定课表不支持文件替换：${target.name}")
        }
        if (applySemester) {
            applySemester(targetTimetableId, preview.document.semester, issues, replaceExisting = true)
        } else {
            issues += "已保留本机学期（未采用文件学期）"
        }
        if (applyTimeSlots) {
            db.timeSlotDao().deleteByTimetable(targetTimetableId)
            applyTimeSlots(targetTimetableId, preview.document.timeSlots)
        } else {
            issues += "已保留本机作息（未采用文件作息）"
        }
        db.courseDao().deleteByTimetable(targetTimetableId)
        val written = insertCourses(targetTimetableId, rows.map { it.course })
        recordImport(targetTimetableId, preview, issues)
        return ImportCommitResult(
            timetableId = targetTimetableId,
            replaced = true,
            appended = false,
            courseCount = written,
            issues = issues,
            displayConfig = null,
        )
    }

    /**
     * 写入文件携带的学期。
     *
     * 新建路径：仅有文件学期时写入（isCurrent=true）；替换路径：先清空本机学期再写入，
     * 文件未携带学期时保留本机学期并提示（不做破坏性清空）。
     * 开始日期非法时跳过学期导入并提示，其余数据照常写入。
     */
    private suspend fun applySemester(
        timetableId: Long,
        semester: ScheduleJsonSemester?,
        issues: MutableList<String>,
        replaceExisting: Boolean,
    ) {
        if (semester == null) {
            if (replaceExisting) {
                issues += "文件未携带学期信息，本机学期保留"
            }
            return
        }
        val startDate = runCatching { LocalDate.parse(semester.startDate) }.getOrNull()
        if (startDate == null) {
            issues += "学期开始日期无效（${semester.startDate}），已跳过学期导入"
            return
        }
        if (replaceExisting) {
            db.semesterDao().deleteByTimetable(timetableId)
        }
        db.semesterDao().upsert(
            SemesterEntity(
                timetableId = timetableId,
                name = semester.name,
                startDate = startDate,
                totalWeeks = semester.totalWeeks,
                isCurrent = true,
            ),
        )
    }

    /** 作息整批写入（调用方已处理清旧逻辑；新建路径本无旧行）。 */
    private suspend fun applyTimeSlots(timetableId: Long, slots: List<ScheduleJsonTimeSlot>) {
        if (slots.isEmpty()) {
            return
        }
        db.timeSlotDao().upsertAll(
            slots.map { slot ->
                TimeSlotEntity(
                    timetableId = timetableId,
                    section = slot.section,
                    startTime = slot.startTime,
                    endTime = slot.endTime,
                    name = slot.name,
                    kind = slot.kind,
                    hidden = slot.hidden,
                    defaultCourseName = slot.defaultCourseName,
                    sortOrder = slot.sortOrder,
                )
            },
        )
    }

    /**
     * 按依赖顺序写入课程并建立"旧 id → 新 id"映射。
     *
     * 课程 id 一律置 0（导入总是生成新 id）；调整记录的 `overrideOfId` 按映射重编。
     * 非调整课先插、调整课在其目标落库后插（多轮扫描，环已被 [validateBatch] 排除）。
     */
    private suspend fun insertCourses(
        timetableId: Long,
        courses: List<ScheduleJsonCourse>,
    ): Int {
        val idMap = HashMap<Long, Long>()
        val pending = courses.toMutableList()
        var written = 0
        while (pending.isNotEmpty()) {
            var progressed = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.overrideOfId > 0 && !idMap.containsKey(item.overrideOfId)) {
                    continue
                }
                val overrideOfId = if (item.overrideOfId > 0) {
                    checkNotNull(idMap[item.overrideOfId])
                } else {
                    0L
                }
                val entity = item.toCoreCourse(timetableId)
                    .copy(id = 0, overrideOfId = overrideOfId)
                    .toEntity(timetableId)
                val rowId = db.courseDao().upsert(entity)
                val newId = if (rowId > 0) rowId else entity.id
                if (item.id > 0) {
                    idMap[item.id] = newId
                }
                written++
                iterator.remove()
                progressed = true
            }
            // 环在 validateBatch 已拒绝；此处仅作防御。
            check(progressed) { "调整记录存在循环引用，无法写入" }
        }
        return written
    }

    /** 追加导入记录：source 存文件摘要（重复文件检测键），semester 存文件学期名。 */
    private suspend fun recordImport(
        timetableId: Long,
        preview: ImportPreview,
        issues: MutableList<String>,
    ) {
        val recordId = db.importRecordDao().insert(
            ImportRecordEntity(
                timetableId = timetableId,
                source = preview.fileDigest,
                importTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                semester = preview.semesterName.orEmpty(),
            ),
        )
        if (recordId <= 0) {
            issues += "课程已导入，导入记录保存失败"
        }
    }

    /**
     * 写库前的整批关系校验：旧 id 重复 / 调整目标缺失 / 循环引用 / 批内
     * (overrideOfId, overrideWeek) 重复。任一命中即整批失败（列出全部问题）。
     */
    private fun validateBatch(courses: List<ScheduleJsonCourse>): List<String> {
        val problems = mutableListOf<String>()
        courses.filter { it.id > 0 }.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, _) ->
            problems += "文件中存在重复的课程 id：$id"
        }
        val ids = courses.map { it.id }.toSet()
        courses.filter { it.overrideOfId > 0 }.forEach { course ->
            if (course.overrideOfId !in ids) {
                problems += "调整记录指向的课程不存在于文件中：${course.name}（overrideOfId=${course.overrideOfId}）"
            }
        }
        val byId = courses.filter { it.id > 0 }.associateBy { it.id }
        for (course in courses) {
            if (course.overrideOfId <= 0) {
                continue
            }
            val seen = mutableSetOf<Long>()
            var current: ScheduleJsonCourse? = course
            while (current != null && current.overrideOfId > 0) {
                if (!seen.add(current.id)) {
                    problems += "调整记录存在循环引用：${course.name}"
                    break
                }
                current = byId[current.overrideOfId]
            }
        }
        val inserted = mutableListOf<Course>()
        for (course in courses) {
            val core = course.toCoreCourse(timetableId = 0)
            val duplicate = CourseValidator.validateOverrideUniqueness(core, inserted)
            if (duplicate != null) {
                problems += "${course.name}：$duplicate"
            } else {
                inserted += core
            }
        }
        return problems
    }

    /** 文件字节 SHA-256 的前 16 位小写十六进制。 */
    private fun sha256Hex16(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
            .take(16)
}
