# W1 任务规格：core:model + core:domain 课程发生引擎

任务编号：P1-03 / 对应清单 P1-03、P1-05（引擎部分）、P0-02（规则样例）
写码代理：GLM-5.3-Flash ｜ 审查代理：GLM-5.3

## 0. 硬性边界

- core:model、core:domain 是**纯 Kotlin JVM 模块**：禁止 import 任何 `android.*`、`androidx.*`、Compose、Room、`java.awt` 类型。允许 `java.time`、`kotlinx.coroutines`、`java.util.UUID`。
- 模块 build.gradle.kts 已就绪，**不要修改任何 build 文件**。
- 参考实现（只读，禁止修改）：`G:\code\openHarmony\ClassSchedule-exp\entry\src\main\ets\` 下
  - `models/CourseModels.ets`（模型与校验器）
  - `models/TimetableModels.ets`
  - `services/CourseScheduleRules.ets`（周规则/时间/重叠/合并）
  - `utils/CourseStatusUtils.ets`（状态计算）
  - `utils/CourseConflictUtils.ets`（冲突检测）
  - `services/ScheduleQueryService.ets`（快照语义，参考其 today/tomorrow 语义，缓存与鸿蒙 AppStorage 部分不迁移）
- 写完删除占位文件 `ModelContract.kt`、`DomainContract.kt`。
- 注释与文档字符串用中文，公共 API 写明语义约束（如半开区间、周一=1）。

## 1. core:model 交付（包 com.gfs.iyango.core.model）

`WeekType`（enum）：EVERY(0)、ODD(1)、EVEN(2)，保留 code 属性与 `fromCode`。

`Course`（data class，字段语义与 ArkTS 一致，不得增删含义）：
- id: Long（0 表示未持久化）、timetableId: Long
- name、teacher、classroom: String；abbreviation: String；remark: String（≤300 字）
- dayOfWeek: Int（**周一=1 … 周日=7**，绝不使用 JS 的周日=0）
- startSection/endSection: Int；sourceStartSection/sourceEndSection: Int（默认 0，展示投影用）
- startWeek/endWeek: Int；weekType: WeekType；selectedWeeks: List<Int>（空=不额外筛选）
- color: String（默认 "#3E7BFA"）；credit: Double = 0.0；isOutdoor: Boolean = false
- enabled: Boolean = true
- timeMode: Int（0=按节次+时间表，1=自定义钟点，2=单次指定日期）
- startTime/endTime: String（HH:mm，timeMode=1 时有效）；date: String（yyyy-MM-dd，timeMode=2 时有效）
- timeUnconfirmed: Boolean = false（只用于读取结果；不持久化，见参考 CourseModels 注释）
- cycleLength: Int = 1、cycleWeek: Int = 0（0=不启用轮换；1..cycleLength）
- overrideOfId: Long = 0、overrideWeek: Int = 0（>0 表示本条是"对 overrideOfId 课程在 overrideWeek 周的单次调整"）
- groupKey: String = ""

`Semester`：id、timetableId、name、startDate: LocalDate、totalWeeks: Int（默认 20）、isCurrent: Boolean。
`TimeSlot`：id、timetableId、section、startTime/endTime: String、name、kind: Int（0=上课节次，2=休息/占位）、hidden: Boolean、defaultCourseName、sortOrder: Double。
`Timetable`：id、name、source: String（"LOCAL"/"YGU"）、schoolTerm、connectionId、isCurrent、lastSyncAt、groupName、autoEnabled: Boolean=false、enabledDays: List<Int>（默认 1..7）、cycleLength=1、cycleWeek=0。
`ImportRecord`：id、timetableId、source、importTime、semester。

`CourseValidator`（object，移植参考语义，错误消息用中文）：
- validate(course): String?（首个问题，null=通过）；validateSlot(slot): String?
- 校验项与消息逐条对齐参考：名称、星期 1–7、节次区间、周次区间、weekType、credit≥0、备注≤300、selectedWeeks 范围、cycle（1≤length，0≤week≤length）、timeMode 0–2、timeMode>0 时 HH:mm 且 start<end、timeMode=2 时日期有效、overrideOfId>0 ⇒ overrideWeek≥1。
- isTimeValid: `^([01]\d|2[0-3]):[0-5]\d$`；isDateValid（严格 y-M-d 合法性，禁止 2026-02-30）；isCycleValid。

`DayLabels`（object）：label(day)="星期一"…；shortLabel(day)="一"…"日"；越界返回 ""。

## 2. core:domain 交付（包 com.gfs.iyango.core.domain）

### 2.1 CourseRules（object）
逐条移植 CourseScheduleRules，**语义以参考实现为准**：
- occursInWeek(course, week): Boolean —— enabled、范围、overrideWeek 过滤、selectedWeeks、单双周、轮换 `(week-1)%cycleLength+1==cycleWeek`。
- weekOfDate(semester, date: LocalDate): Int —— `((date - startDate).days / 7) + 1`，超出 [1,totalWeeks] 返回 0。**必须用 LocalDate 日期运算，不要用毫秒**（跨 DST 安全）。
- forWeek(courses, week, semester: Semester?): List<Course> —— 替换集合语义：本周有 override 指向的基础课不出现；override 指向别周的课程不出现；timeMode=2 的课：weekOfDate(date)==week 才出现，并把 dayOfWeek 换成 date 的星期、start/endWeek 与 selectedWeeks 收敛为该周（复制修改，不改原对象）。
- timeRange(course, slots): TimeSlot —— timeUnconfirmed&&timeMode=0 → 空时间；课程自定义时间有效 → 用之；否则第一节开始/最后节结束。
- isTimeUsable(slot)；minutes(text): Int?（无效=null）；clock(minutes): String。
- slotsOverlap(left, right, slots): Boolean —— 四个时间全部有效时用**半开区间** `aStart < bEnd && bStart < aEnd`；否则退化为节次区间闭重叠 `left.startSection <= right.endSection && right.startSection <= left.endSection`。
- tableApplies(table, weekday, week)。
- mergeAdjacent(courses): List<Course> —— 排序后按参考条件合并相邻节次（groupKey 非空且相等 + 全字段等值 + endSection+1==startSection），合并只扩展 endSection/endTime/sourceEndSection。

### 2.2 CourseStatus（enum）+ CourseStatusCalculator（object）
- NOT_STARTED / UPCOMING（提前 30 分钟窗口）/ IN_PROGRESS / FINISHED。
- resolve(day: LocalDate, now: LocalDateTime, startMinute: Int?, endMinute: Int?)：不同日按前后；同一日 end==start 或时间不可用 → NOT_STARTED；**current<=end 算 IN_PROGRESS**（含结束分钟，参考实现如此，不擅自改半开）。
- progress(day, now, start, end): Double ∈ [0,1]，不可用=0.0。
- isHighlighted(status)=IN_PROGRESS||UPCOMING；label(status) 中文。

### 2.3 ConflictDetector（object）
- sectionsOverlap（闭区间）、sharedWeekCount（逐周判断双 occurs，不用区间算术）、conflictsIgnoring(left, right, ignoreId)、findConflicts(candidate, existing, ignoreId) → List<CourseConflict>（existing + sharedWeeks + sameSections）、warningMessage(conflicts) → `发现课程冲突：A、B，是否继续添加？`（无冲突返回 null）。
- 冲突仅提示不阻断（写进 KDoc）。

### 2.4 CourseOccurrence 与 ResolveScheduleOccurrences（P1 核心用例）
```kotlin
data class CourseOccurrence(
  val occurrenceId: String,   // 稳定实例身份，见下
  val course: Course,          // 来源课程（含原始身份）
  val date: LocalDate,         // 实际发生日期
  val startMinute: Int?,       // 展示钟点（null=未知，显式呈现为"时间待定"）
  val endMinute: Int?,
  val isOverride: Boolean      // 来自单次调整
)
```
- occurrenceId 生成规则（写入 KDoc，作为 P5 提醒/日历契约的雏形）：
  `SHA-256(courseStableKey + "|" + date)[:16hex]`，其中 courseStableKey = `courseId>0 ? "c:$courseId" : "g:${timetableId}:${dayOfWeek}:${startSection}:${endSection}:${name}"`。
  **单次调课改变时间/教室时 occurrenceId 不变（仍引用原实例身份）**——由 override 的 overrideOfId 保证其 courseStableKey 解析到被调整的原课程 id：ResolveScheduleOccurrences 输出 override 实例时 occurrenceId 用**被调整原课程**的 stableKey + 该周实际日期。
- ResolveScheduleOccurrences.invoke(timetable, semester, timeSlots, courses, range: ClosedRange<LocalDate>): List<CourseOccurrence>：
  - 对 range 内每一天：定位周（weekOfDate），forWeek 过滤 → 选出 dayOfWeek==date.dayOfWeekNumber(1..7) 的课 → occursInWeek 复核 → 解析 startMinute/endMinute（timeRange + minutes），未知时间为 null 且不静默套默认。
  - 按 date、startMinute(null 最后)、startSection 排序。
  - 返回结果同时是今日页、周课表、提醒、日历、组件的唯一数据来源（KDoc 注明）。

### 2.5 Repository 端口（接口，实现在 data）
```kotlin
interface TimetableRepositoryPort { fun observeAll(): Flow<List<Timetable>>; fun observeCurrent(): Flow<Timetable?>; suspend fun setCurrent(id: Long); suspend fun upsert(t: Timetable): Long; suspend fun delete(id: Long) }
interface ScheduleDataPort {  // 一个课表的完整读取
  fun observeTimetable(id: Long): Flow<Timetable?>
  fun observeSemester(timetableId: Long): Flow<Semester?>
  fun observeTimeSlots(timetableId: Long): Flow<List<TimeSlot>>
  fun observeCourses(timetableId: Long): Flow<List<Course>>
  suspend fun saveCourse(course: Course): Long
  suspend fun deleteCourse(id: Long)
}
```
命名允许微调，但接口必须放 core:domain、无 Android 依赖。

## 3. 测试（JUnit4，放 core/domain/src/test/kotlin）

至少覆盖（对齐任务清单 P0-02 的 D01–D07 精神）：
1. occursInWeek：每周/单周/双周 × 起止周边界 × selectedWeeks × cycleLength=3 cycleWeek=2（第 2、5、8…周）× enabled=false × overrideWeek 指向别周。
2. forWeek：override 替换基础课（原课不在、调整课在）、override 指向别周两边都不出、timeMode=2 的 date 课映射星期、复制不改原对象。
3. weekOfDate：起始日为周三的学期（对齐参考 `floor((d-start)/7d)+1` 语义）、跨年（2026-12-28 → 2027-01-03）、范围外=0。
4. timeRange：timeUnconfirmed → 空；自定义时间优先；节次回退。
5. slotsOverlap：半开区间（A 09:00-10:00 与 B 10:00-11:00 **不冲突**）；时间缺失退化节次重叠。
6. CourseStatus：UPCOMING 30 分钟窗口边界、IN_PROGRESS 含 end 分钟、跨日。
7. ConflictDetector：共享周计数（单双周不相交）、sameSections、warningMessage 文案。
8. ResolveScheduleOccurrences：一周内含 timeMode=2 事件、未知时间课程出现且 startMinute=null、override 后 occurrenceId 稳定性（调整前后 id 相同）、排序。
9. mergeAdjacent：相邻同 groupKey 合并、不同 groupKey 不合并。

## 4. 通过标准

- `./gradlew :core:model:test :core:domain:test` 全绿。
- 两个模块 grep 不到 `android`/`androidx`/`Compose` import。
- 所有公共 API 有中文 KDoc 说明语义约束。
- 输出一份简短实现说明（文件清单 + 每条规则对应的参考源码位置），附在最终回复里。
