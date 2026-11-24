# P0-03 字段对照契约：ArkTS → Kotlin 业务模型 → Room → JSON

版本：1.0｜日期：2026-10-24｜维护规则：改任何一端字段必须先改本文。

## 1. 星期与周次语义（全局不变式）

| 语义 | 定案 | 依据 |
| --- | --- | --- |
| dayOfWeek | **Int，周一=1 … 周日=7** | 参考 DayOfWeek.MONDAY=1；绝不用 JS 的周日=0 |
| weekType | **code：0=每周，1=单周，2=双周** | 参考 WeekType enum code |
| timeMode | **0=按节次，1=自定义钟点，2=单次日期** | 参考 Course.timeMode |
| 周计算 | `floorDiv(days(start, date), 7) + 1`，落在 [1,totalWeeks] 外=0 | 参考 weekOfDate（LocalDate 天数差，非毫秒） |
| 重叠判定 | 四钟点全有效→**半开区间** `aStart<bEnd && bStart<aEnd`；否则节次闭重叠 | 参考 slotsOverlap |
| 课程进行中 | `current <= end` 算进行中（含结束分钟）；`end <= start` 判 NOT_STARTED | 参考语义 + W1 规格裁决 |
| 调课身份 | occurrenceId = SHA-256(stableKey + "|" + date)[:16]；override 实例 stableKey 解析到被调原课程 id | P5 提醒/日历契约雏形 |
| 调课唯一性 | **每 (timetableId, overrideOfId, overrideWeek) 至多一条**（应用层校验 + v2 唯一部分索引） | P1 审查 P1 项收口 |

## 2. Course 字段四端对照

| ArkTS (CourseModels.ets) | Kotlin (core:model Course) | Room (course 表, v2) | JSON v1 (ScheduleJson) | 备注 |
| --- | --- | --- | --- | --- |
| id: number | id: Long | id PK 自增 | id | JSON 旧整数 id 导入时映射 |
| timetableId | timetableId: Long | timetableId FK CASCADE | （根级归属，不进课程对象） | |
| name/teacher/classroom | 同名 String | 同名列 | name/teacher/classroom（候选键见 §4） | |
| dayOfWeek | dayOfWeek: Int | dayOfWeek INTEGER | dayOfWeek | 1..7 |
| startSection/endSection | Int | INTEGER | startSection/endSection | |
| sourceStartSection/sourceEndSection | Int（默认 0） | INTEGER | **不导出**（展示投影派生） | W5 已按此实现 |
| startWeek/endWeek | Int | INTEGER | startWeek/endWeek | |
| weekType | WeekType(code 0/1/2) | **v2 起注意**：当前列存枚举名字符串；P5 迁移设计需统一（W3 遗留，方案：迁移脚本转 int 或读侧兼容两种） | weekType | ⚠ 迁移债务 |
| selectedWeeks | List<Int> | TEXT 逗号分隔 | selectedWeeks | 空=不筛选 |
| color | String #RRGGBB | TEXT | color | UI 渲染优先读此字段 |
| credit | Double | REAL | credit | |
| isOutdoor | Boolean | INTEGER 0/1 | isOutdoor | |
| enabled | Boolean | INTEGER 0/1 | enabled | |
| timeMode | Int | INTEGER | timeMode | |
| startTime/endTime | String HH:mm | TEXT | startTime/endTime | timeMode=1 有效 |
| timeUnconfirmed | Boolean（参考为三态） | **不建列**（只读派生） | **不导出** | 导入默认 false |
| date | String yyyy-MM-dd | TEXT | date | timeMode=2 |
| cycleLength/cycleWeek | Int | INTEGER | cycleLength/cycleWeek | week=0 不启用 |
| overrideOfId/overrideWeek | Long/Int | INTEGER（v2 唯一部分索引） | overrideOfId/overrideWeek | 导入用映射重建 |
| groupKey | String | TEXT | groupKey | 相邻合并 + 分层键 |
| abbreviation/remark | String | TEXT | remark（abbreviation 以参考导出为准） | remark≤300 |

## 3. 其他实体对照（要点）

| 实体 | 关键约定 |
| --- | --- |
| Timetable | source="LOCAL"/"YGU"；isCurrent 全表至多一行（setCurrent 事务保证）；enabledDays 逗号分隔 |
| Semester | startDate ISO LocalDate；一课表一当前学期；起始日不必是周一 |
| TimeSlot | kind 0=节次 2=占位；sortOrder Double 排序；hidden 午休行按当前分钟显隐 |
| ImportRecord | source 存文件 SHA-256 前 16 hex（重复文件检测）；不存凭据 |
| JSON meta | app="ClassSchedule"（保持旧兼容）、version=1、exportedAt |
| JSON displayConfig | **只能是字符串**；缺失/损坏保留本机默认 |

## 4. JSON 宽容字段候选（导入读取，来自参考 ScheduleJsonFields）

根课程数组：courses/course/list/lessons/data
课程：name=courseName/course_name/title；teacher=teacherName/jsxm/tutor；classroom=room/location/place/cdmc；dayOfWeek=day_of_week/weekday/xqj/day；startSection=start_section/sectionStart/beginSection；endSection=end_section/sectionEnd/finishSection；节次=section/sections/jcs；startWeek=start_week/weekStart/beginWeek；endWeek=end_week/weekEnd/finishWeek；weeks=weekText/zcd/weekRange；weekType=week_type/oddEven/type；color=colour/backgroundColor；remark=note/comment/memo
学期：semester/term；name=semesterName/title；startDate=start_date/beginDate/start；totalWeeks=total_week/weeks/weekCount；isCurrent=is_current/current
节次：timeSlots/time_slots/sections/times；section=index/no；startTime=start_time/start/begin；endTime=end_time/end/finish

## 5. 数据保护契约（P0-05 首版定案摘要）

- 导出 JSON 不含：凭据、Cookie、系统绑定 ID（connectionId 映射置空）、壁纸路径。
- 导入不恢复学校登录态/绑定；学校绑定课表（source=YGU）拒绝文件替换。
- 合并模式不开放；重复文件（同 SHA-256 摘要）需用户二次确认才建副本。
- 整批失败原则：引用缺失/循环/重复旧 id → 全部回滚，不部分成功。
