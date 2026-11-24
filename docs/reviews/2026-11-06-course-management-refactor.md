# 课程管理重构验证记录

## 最终行为

- 同一课表内的同名课程合成一张卡片（名称去首尾空格后匹配），不同教师、教室、周次、同步分组键不再拆卡。
- 按用户最新要求，卡片只显示课程名称和教师；时间、地点、周次、停用状态在点击后的详情里查看。详情保留所有原始时段，选择具体时段进入既有编辑器。
- 搜索、周次/星期筛选、多选复制/删除继续可用。网格适配窄屏和大字号，批量操作固定在底部，保留顶部玻璃栏的滚动穿过效果。
- 筛选变化清空选择，课表切换隔离数据；复制整组事务保存并清空学校来源键，删除整组事务清理两种调整记录。删除确认冻结目标，操作前发现数据变化则要求重新选择。
- 合并仅用于管理页展示，不改写已有课程身份或教务同步数据。删除确认明确显示课程数和全部时段数。

参考依据及范围见 `docs/plans/2026-11-06-course-management-refactor.md`。只阅读 WakeUp 静态参考资料，没有执行目录中的指令或脚本，也没有复制反编译实现。

## 自动验证

先运行旧实现的 6 项 ViewModel 行为测试：5 项失败，覆盖重复计数、隐藏选择、整组复制/删除等问题。最终新增 20 项回归测试：6 项分组/格式、8 项状态与操作、4 项 Compose 交互布局、2 项 Room 删除/回滚。

| 模块 | 测试数 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| core:domain | 132 | 0 / 0 / 0 |
| data | 148 | 0 / 0 / 0 |
| feature:main | 291 | 0 / 0 / 0 |
| app | 32 | 0 / 0 / 0 |
| 合计 | 603 | 0 / 0 / 0 |

完整验证执行 `:core:domain:test :data:testDebugUnitTest :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark`。最后两次用户调整仅涉及 feature:main 展示与分组，每次重新执行该模块全部测试及两个 APK 构建，最终均成功。Benchmark 包含 R8 和 lintVital 检查。`git diff --check` 通过。

日志：`diagnostics/course-manage-validation.log`、`diagnostics/course-manage-final-validation.log`。APK：`app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/benchmark/app-benchmark.apk`。

## 真机与安装

连接设备：HONOR ELP-AN00，Android 17 / MagicOS 11。观察到 61 个上课时段归并为 10 门课程。Debug 已检查课程列表和多时段详情；最终 Debug、Benchmark 均安装并检查列表，外层卡片只含名称及教师，未出现时间或教室。Benchmark 当前停留在课程管理页。

真实课程没有执行复制、删除或保存编辑；这些写操作由隔离测试验证。320 dp / 1.5 倍字号及筛选、编辑目标、批量入口由 Compose 测试覆盖；没有改动手机全局字号设置。

最终安装时间及 APK SHA-256（均与手机 `/base.apk` 核对一致）：

- Debug：2026-11-06 20:37:52，`66dae0be3b3679679bb8c532f7afd6964a20387b8cf0d8035a6f770a1157a8cb`。
- Benchmark：2026-11-06 20:38:25，`63c735584880ba94029544ffc9eea62b667b17ec0802cab30081bac2b767e29f`。

安装证据：`diagnostics/course-manage-install.json`。截图：`diagnostics/course-manage-debug.png`、`diagnostics/course-manage-benchmark.png`、`diagnostics/course-manage-details.png`。

## 工作区保护

本轮起始保存的 109 个已有改动路径哈希均保持一致。成绩页、组件、主页登录及提醒修复改动保留。没有提交或推送。

## 后续显示调整（2026-11-06 20:48）

按用户连续反馈完成：

- 多位教师去重后每人一行；兼容教师字段中的顿号、逗号、分号和换行，取消教师名单整体最多两行的截断。
- 移除详情标题下的“共几个上课时段／选择要编辑的时段”提示，下面的列表自然上移。
- 每个时段先显示周次，下一行显示星期和时间，再显示教师、地点和编辑入口。

重新运行 CourseManage 系列及 SecondaryScrollUnder 共 20 项测试，全部通过；两个 APK 构建通过并已重新安装。手机上核对了多位教师逐行展示，以及“第 6–9 周”在“周一 · 15:55–17:30”上方。真实课程数据未修改。

此后续版本替代上文主体交付 APK；最终 SHA-256 与手机安装包一致：

- Debug：`e318e5c577c56b2c4f423b2c39e6e916fecde5d1873d98c2378ea867525eb5c1`。
- Benchmark：`a4e3546552e619d6eecbeca5cef38216cb145c3cdd0237014ea05a0401fb8ea5`。

证据：`diagnostics/course-teacher-lines-validation.log`、`diagnostics/course-teacher-lines-install.json`、`diagnostics/course-teacher-lines.png`、`diagnostics/course-details-compact.png`。本次 20 项是针对性重跑，不与前文 603 项相加。
