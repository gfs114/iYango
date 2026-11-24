# W5 任务规格：P2 领域投影 + JSON 导入导出 + 调课唯一约束 + data 行为测试

任务编号：P2-01（唯一索引）/ P2-02（投影）/ P2-10/11（JSON）/ P2-13（行为测试）
写码：GLM-5.3-Flash ｜审查：GLM-5.3

## 0. 边界

- 允许修改：core/domain/src、core/model/src、data/src。build 文件已由协调者配好（robolectric/room-testing 已入 data），**不要改 build 文件**。
- 参考实现（只读）：
  - `G:\code\openHarmony\ClassSchedule-exp\entry\src\main\ets\services\CourseScheduleRules.ets` 下半部（displaySlots / project / sourceSection）
  - `G:\code\openHarmony\ClassSchedule-exp\entry\src\main\ets\models\ScheduleJson.ets`
  - `G:\code\openHarmony\ClassSchedule-exp\entry\src\main\ets\services\ScheduleJsonParser.ets`、`ScheduleJsonService.ets`
  - `G:\code\openHarmony\ClassSchedule-exp\entry\src\main\ets\models\CourseExtras.ets`（JSON 携带的课程扩展项）
- 语义以参考实现为准；冲突时以本规格为准并报告。注释中文，KDoc 写明语义约束。

## 1. 展示投影（core:domain，包不变，追加文件 ScheduleDisplay.kt）

移植 displaySlots / project / sourceSection 三个函数到 Kotlin（放 CourseRules 或独立 object）：
- `displaySlots(slots, courses, now: LocalTime)`：当无 hidden/kind≠0、无 timeMode≠0 课程且节次连续时原样返回；否则按参考算法合成展示行（含自定义钟点边界、kind=2 空占位行、sortOrder 排序与 section 重编号）。
- `project(course, base, display)`：把课程节次映射到展示行（sourceStartSection 保留原始节次；时间不可用原样返回；hidden 全覆盖时 endSection=0 语义保留）。
- `sourceSection(slot, base)`：展示行对应的基础节次（kind=0 且覆盖其开始分钟）。
- 注意：参考用 `now.getHours()` 分钟数决定 hidden 行是否显示，Kotlin 用 LocalTime 分钟数等价实现。
- **测试**：无特殊配置原样返回；hidden 午休行在午间显示、早晚隐藏；自定义钟点课合成边界行；project 映射与 endSection=0。

## 2. JSON 导入导出（core:model DTO 纯 Kotlin + data 层服务）

### 2.1 DTO（core/model，kotlinx.serialization 可选——core:model 没有 serialization 插件！）
决定：DTO 放 **data** 模块（包 com.gfs.iyango.data.json），用 kotlinx.serialization。字段与 ScheduleJson.ets 对齐：
- 根：meta{app="ClassSchedule", version=1, exportedAt}、courses[]、semester?、timeSlots?、displayConfig（**String，可空/缺失**，绝不当嵌套对象解析）。
- 课程字段：id、name、teacher、classroom、dayOfWeek、startSection、endSection、startWeek、endWeek、weekType、color、remark + CourseExtras 携带的扩展（selectedWeeks、timeMode、startTime、endTime、date、cycleLength、cycleWeek、enabled、groupKey、overrideOfId、overrideWeek、abbreviation、credit、isOutdoor、sourceStartSection/sourceEndSection 不导出——以参考导出实际字段为准，读 ScheduleJsonService.ets 确认后再定）。
- 未知字段：`ignoreUnknownKeys=true` 保留宽容读取。

### 2.2 解析宽容性（对齐 ScheduleJsonParser）
- 根课程数组候选键：courses/course/list/lessons/data；字段候选名按 ScheduleJsonFields 全套实现（name/courseName/jsxm 等）。
- 手写宽松解析：Jackson 风格 JsonObject 遍历 + 候选键查找，而不是强类型反序列化（参考就是这么做的）。
- 周次文本解析（weeks/weekText/zcd 如 "1-16(单)" 之类）按参考实现移植其支持的形式。

### 2.3 导入服务（data，ImportScheduleService）
流程：读文件（大小上限 5MB）→ 解析 → 产出 `ImportPreview(courseCount, issueCount, issues: List<String>, semester?, timeSlotCount)` → 用户选目标（新建 / 替换某本地课表）→ `commit(preview, targetTimetableIdOrNull, replace: Boolean)`：
- ID 映射：旧整数 id → 新自增 id；overrideOfId 用映射重建；引用缺失/循环/重复旧 id → 整批失败列出问题。
- 新建：创建"导入课表 yyyy-MM-dd"命名的 LOCAL 课表（学期/节次/课程一并写入）+ ImportRecord。
- 替换：仅 LOCAL 课表；保留课表名称/分组；替换学期/节次/课程；displayConfig 损坏仅提示并保留本机默认；提醒计划重算（当前无提醒，KDoc 注明）。
- 重复文件提示：比对 ImportRecord（同 source 摘要 = 文件 SHA-256 前 16 hex），已存在则返回需确认状态，由 UI 层二次确认后 `force=true` 再建副本。
- 学校绑定课表拒绝文件替换（source != LOCAL 时抛 IllegalStateException）。
- 事务：全部写入在单个 withTransaction；失败整体回滚。
- **合并模式不实现**（API 预留 merge=false 参数并抛 UnsupportedOperation 或 KDoc 注明后续版本）。

### 2.4 导出服务（data，ExportScheduleService）
- 输入课表 id → 组装 v1 文档（meta.app="ClassSchedule"（保持兼容，即使应用叫 iYango）、version=1、exportedAt=yyyy-MM-dd HH:mm:ss）；主动剔除壁纸路径类字段（当前模型没有，KDoc 注明）；displayConfig 输出为字符串（当前无本机显示配置 JSON，可输出 ""）。
- 返回 JSON 字符串 + 建议 文件名（如 iYango-课表名-20261024.json）。
- **往返测试**：种子课表 → 导出 → 导入新建 → 比较实际课程发生实例（ResolveScheduleOccurrences 全学期）逐一相等，不是只比条数。

## 3. 调课唯一约束（P2-01 + 审查 P1 项收口）

- CourseValidator.validate 增加规则：overrideOfId>0 时 overrideWeek 必须 ∈ [1, totalWeeks 无法在此知道——只校验 ≥1]；**新增跨记录校验函数** `validateOverrideUniqueness(candidate, existing): String?`：同 timetableId 下已存在相同 (overrideOfId, overrideWeek) 的其他记录 → 返回错误"该周已有针对此课程的调整"。
- 数据库：schema **v2**——course 表加唯一索引 `index_override` ON (timetableId, overrideOfId, overrideWeek)（注意 SQLite 唯一索引对多行 overrideOfId=0/overrideWeek=0 的普通课程会冲突！改用**部分语义**：Room 不支持 partial index 直接声明 → 用 `@Entity(indices=[Index(..., unique=true)])` 不行就在 Migration 里执行 `CREATE UNIQUE INDEX ... WHERE overrideOfId > 0`（Room 支持手写 SQL 的部分索引）。DAO upsert 前先跑 validateOverrideUniqueness 双保险。
- Migration(v1→v2) + Room MigrationTestHelper（Robolectric 环境）测试：v1 库插入两条同 (overrideOfId, overrideWeek) → 迁移应失败？**决定**：为避免老数据迁移炸库，v2 用非唯一索引 + 应用层强校验？——不，契约要求唯一。**折中定案**：迁移时先清理重复（保留 rowid 最小一条，其余删除并记录到 ImportRecord？不行，简单点：迁移 SQL 用 `DELETE FROM course WHERE id NOT IN (SELECT MIN(id) FROM course WHERE overrideOfId>0 GROUP BY timetableId, overrideOfId, overrideWeek) AND overrideOfId>0`），再建唯一部分索引。测试覆盖：v1 重复数据迁移后只剩一条 + 唯一索引存在。
- data/schemas/ 导出 2.json。

## 4. data 行为测试（P2-13，Robolectric，包 com.gfs.iyango.data）

- CascadeDeleteTest：删课表 → semester/time_slot/course/import_record 全部级联清空。
- TimetableRepositoryTest：setCurrent 事务后全表仅一行 isCurrent；upsert 新建首条自动置当前；删除当前课后 observeCurrent 发射 null。
- DemoSeederTest：空库播种 1 课表/1 学期/11 节/7 课程；二次调用不重复；2026-10-24 解析第 7 周（用 ResolveScheduleOccurrences 抽查周一高数在第 1、8 周、周三 8 节调课只在第 8 周）。
- ImportRoundTripTest：见 2.4。
- MigrationTest：v1 建库（MigrationTestHelper）→ 插入重复 override → runMigrationsAndValidate(v2) → 断言去重与索引。
- JsonParserTest：宽容键名（jsxm/cdmc/xqj/zcd）、displayConfig 为嵌套对象时不崩（取原始字符串或忽略）、未知字段忽略、根数组候选键。

## 5. 通过标准

- `./gradlew :core:domain:test :core:model:test :data:testDebugUnitTest assembleDebug` 全绿；schemas/ 出现 2.json。
- feature/app 不受影响（W6 并行开发中，若 feature 编译失败不阻塞本任务，报告注明即可）。
- 最终回复：文件清单、测试统计、迁移策略说明、与参考实现的行为差异清单。
