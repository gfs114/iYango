# W3 任务规格：data 层（Room + Repository 实现 + 演示种子数据）

任务编号：P1-05 ｜写码：GLM-5.3-Flash ｜审查：GLM-5.3

## 0. 边界

- 只改 `data/` 目录下的源码与测试；build.gradle.kts 已配好（room+ksp、schemaLocation=schemas/），**不要修改**。
- 删除占位 `DataContract.kt`。
- 端口接口在 `core/domain/src/main/kotlin/com/gfs/iyango/core/domain/RepositoryPorts.kt`（先读它和 core:model 全部模型）。
- **Hilt 绑定不放 data**（实施方案 5.1：绑定集中在 app）。data 只提供可构造的实现类；如需 Context 用 `@ApplicationContext` 构造参数（Hilt 注解在构造函数上是允许的，但不要写 @Module）。
- 注释中文；DAO 查询写明语义。

## 1. Room 结构（包 com.gfs.iyango.data.db）

数据库 `IYangoDatabase`（version = 1，exportSchema = true，schemas 已配置自动导出）。实体与字段对齐 core:model（映射时转换，不丢字段）：

- `TimetableEntity`（table "timetable"）：id 自增 PK；name、source、schoolTerm、connectionId、isCurrent（索引）、lastSyncAt、groupName、autoEnabled、enabledDays（逗号分隔 Int）、cycleLength、cycleWeek。
- `SemesterEntity`（"semester"）：id 自增 PK；timetableId FK→timetable ON DELETE CASCADE（索引）；name、startDate（TEXT ISO yyyy-MM-dd）、totalWeeks、isCurrent。
- `TimeSlotEntity`（"time_slot"）：id 自增 PK；timetableId FK CASCADE（索引）；section、startTime、endTime、name、kind、hidden、defaultCourseName、sortOrder（REAL）。
- `CourseEntity`（"course"）：id 自增 PK；timetableId FK CASCADE（索引）；模型其余字段全存；selectedWeeks 逗号分隔；timeUnconfirmed **不建列**（只用于读取结果）。
- `ImportRecordEntity`（"import_record"）：id 自增 PK；timetableId FK CASCADE；source、importTime、semester。

TypeConverter：LocalDate ↔ TEXT；List<Int> ↔ "1,3,5"。

## 2. DAO（每实体一个，Flow 返回）

- TimetableDao：observeAll（按 id 升序）、observeCurrent（isCurrent=1 LIMIT 1）、setCurrent（@Transaction：先全部清零再置位）、upsert（@Upsert 返回 id）、deleteById。
- SemesterDao：observeByTimetable、observeCurrent（timetableId + isCurrent=1 LIMIT 1）、upsert、deleteByTimetable。
- TimeSlotDao：observeByTimetable（按 section 排序）、upsertAll、deleteByTimetable。
- CourseDao：observeByTimetable（按 dayOfWeek,startSection 排序）、upsert 返回 id、deleteById、getById suspend。
- ImportRecordDao：observeByTimetable、insert。

## 3. 映射与实现（包 com.gfs.iyango.data.repository）

- `CourseEntity.toModel()` / `Course.toEntity(timetableId)` 等成对映射函数（独立 Mappers.kt）。
- `RoomScheduleDataPort @Singleton` 构造注入 db，实现 `ScheduleDataPort`：全部 observe 直接返回 DAO Flow.map(模型)；saveCourse 用 upsert；**保存前用 CourseValidator 校验，非法抛 IllegalArgumentException**（KDoc 注明）。
- `RoomTimetableRepository @Singleton` 实现 `TimetableRepositoryPort`：setCurrent 用 DAO 事务；delete 依赖 FK 级联；upsert 保持 isCurrent 语义（新建第一条自动成为当前）。
- 数据库构造封装为 `IYangoDatabaseProvider @Singleton`（@ApplicationContext + Room.databaseBuilder，fallbackToDestructiveMigration 仅限 debug —— 用 BuildConfig 不可用（library 无），改为构造参数 `allowDestructive: Boolean`，由 app 传入 true/false）。

## 4. 演示种子（包 com.gfs.iyango.data.seed）

`DemoSeeder @Singleton`，`suspend fun seedDemoIfEmpty()`：timetable 表为空时插入：
- 课表"我的课表"（LOCAL，isCurrent=true）。
- 学期：名称"2026 秋季学期"，startDate=2026-09-07（周一），totalWeeks=20，isCurrent=true。**注意今天是 2026-10-24，应落在第 7 周**（测试锚点）。
- 作息 11 节：1:08:00-08:45、2:08:55-09:40、3:10:00-10:45、4:10:55-11:40、5:14:00-14:45、6:14:55-15:40、7:16:00-16:45、8:16:55-17:40、9:19:00-19:45、10:19:55-20:40、11:20:50-21:35。
- 课程（覆盖规则样例，颜色用 CoursePalette 顺序）：
  1. 高等数学，周一 1-2 节，1-16 周每周，A101，张老师
  2. 大学英语，周二 3-4 节，1-16 周单周（ODD），B202，李老师
  3. 数据结构，周三 6-7 节，1-16 周双周（EVEN），C303，王老师
  4. 体育，周四 1-2 节，selectedWeeks=[2,4,6,8]，操场（isOutdoor）
  5. 程序设计实践，周五 9-10 节，timeMode=1 自定义 19:00-20:35
  6. 校运会（timeMode=2 单次日期课）：date=2026-11-06（第 9 周周五）
  7. 一条单次调课记录：overrideOfId=课程1、overrideWeek=8（第 8 周周一），dayOfWeek=3、startSection=6、endSection=7、classroom=D404（把第 8 周周一的高数调到周三 6-7 节）
- 幂等：非空不重复播种。

## 5. 测试（纯 JVM，放 data/src/test）

- MappersTest：entity↔model 往返无损（含 selectedWeeks/enabledDays/sortOrder）。
- ConvertersTest：LocalDate、List<Int> 往返。
- （Room DAO 行为验证留给 P2 的 Robolectric/仪器测试，本轮不做，KDoc 注明。）

## 6. 通过标准

- `./gradlew :data:compileDebugKotlin :data:testDebugUnitTest` 全绿；`assembleDebug` 后 `data/schemas/` 出现 `1.json`。
- grep 无对 feature/app 的依赖；无 @Module。
- 最终回复：文件清单、测试结果、schema 导出确认、遗留问题。
