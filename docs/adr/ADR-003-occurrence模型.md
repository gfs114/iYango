# ADR-003：课程发生（occurrence）模型与单次调整约束

- 状态：已采纳（P1 定型，P2 落唯一索引）

## 决策

1. **唯一数据来源**：任何展示面（今日/周课表/提醒/日历/组件）判断"某天有哪些课"
   必须经 `ResolveScheduleOccurrences`，禁止用 `Course.dayOfWeek` 自行推导。
2. **stableId**：`SHA-256(stableKey + "|" + date)[:16]`；`stableKey = "c:<id>"`
   （override 实例取 `"c:<overrideOfId>"`）→ **调课不改变实例身份**，提醒/日历
   可把 override 视为对同一实例的就地更新。
3. **override 语义**（`CourseRules.forWeek`）：某周若存在
   `(overrideOfId=B, overrideWeek=W)` 记录，则基础课 B 在 W 周被**替换/压制**；
   `enabled=false` 的 override 自身不参与发生 → "仅取消本周这次" = 写入停用
   override（课表页与今日页同语义，见 ScheduleViewModel.cancelThisWeek）。
4. **唯一约束（P2 v2 迁移）**：`UNIQUE(timetableId, overrideOfId, overrideWeek)`
   上的部分唯一语义用 **NULL 惯例**实现——普通课程两列落 NULL（SQLite 视 NULL
   互不相同），调整记录落值。原因：Room 2.8.5 的 schema 校验按 `PRAGMA index_list`
   全集比对，手写 SQL 部分索引不可行（W5 核实）。应用层
   `CourseValidator.validateOverrideUniqueness` 双保险。
5. **状态/进度**：全部经 `CourseStatusCalculator`（current<=end 为 IN_PROGRESS、
   end<=start 或时间缺失恒 NOT_STARTED、30 分钟 UPCOMING 窗口）。

## 后果

- 调课（时间/教室改动）跨周稳定；取消与调整共用同一行，就地更新幂等；
- 编辑课程时第 0 条安排保留原 id 原地更新（W7），避免 override 引用断链；
- 复制课表时 override 记录展平为普通课程（id 换新，引用不可保留）。
