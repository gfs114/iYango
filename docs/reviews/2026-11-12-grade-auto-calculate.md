# 成绩学期选择后直接计算（2026-11-12）

## 最终行为

- 移除成绩页“查询”按钮，学期选择行显示当前计算范围；移除“待查询”提示与旧的查询引导。
- 单学期、全部学期选中后立即更新成绩列表与统计，并关闭选择面板。
- 学期范围入口仍在标题下方。展开时应用当前起止范围；修改任一端点立即计算，不再显示“计算所选学期”按钮。
- 范围编辑期间面板保持打开，即使起止相同、缓存记录或自选上下文更新也可继续调整；关闭后保留已应用范围。切换账号或离开成绩标签仍关闭浮层。
- 只显示“包含 N 个学期”，不显示具体学期枚举。原有公选课开关位置、尺寸、统计规则和主题色不变。
- 选择学期使用本机成绩缓存，不发学校成绩请求；下拉刷新同步当前范围的教务成绩。首次没有缓存时仍沿用自动获取行为。
- 范围刷新继续请求全部学期数据后筛选，单学期刷新使用所选学期。过期账号可计算缓存；会话失效反馈及刷新取消处理保持原逻辑。

本调整替代 `2026-11-12-grade-term-range.md` 中早先的手动应用/查询交互。四个生产文件变化：`GradesFilters.kt`、`GradeTermRangePicker.kt`、`GradesScreen.kt`、`GradesViewModel.kt`；未修改数据库或教务接口。

## 验证

先执行 8 项筛选/范围界面测试，原实现有 6 项失败。更新实现与旧交互断言后，完整验证命令：

```powershell
.\gradlew.bat :core:domain:test :core:designsystem:testDebugUnitTest :data:testDebugUnitTest :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain
```

702 项测试通过：领域 134、设计系统 20、数据 164、主功能 352、应用 32；0 失败、0 错误、0 跳过。Debug、Benchmark 构建成功。完整日志和计数保存在 `build/diagnostics/grade-auto-calculate-20261112-full.log`、`grade-auto-calculate-20261112-full-results.json`。

随后将截图用例改为实际回传选中状态，3 项范围界面测试再次通过。已检查普通字号和 320dp 窗口、实际 2 倍系统字号的 Native Graphics 渲染，截图位于 `build/diagnostics/grade-auto-calculate-20261112/`。没有“查询”或“计算所选学期”按钮，范围计算不使面板自动关闭。

测试桩中的学校学期接口改用类型明确的实现，避免反射代理误处理返回 `Result` 的挂起方法。真实学校账号、手机手势与视觉效果未验证；未安装、提交或推送。

保留改动前已有的 280 个未提交文件：本次涉及 12 个既有生产/测试文件，其余 268 个哈希不变。`git -c core.whitespace=cr-at-eol diff --check` 通过。

安装包 SHA-256：

- Debug：`0576309C8864AFBFD8F695F2000D341A1EDD47DD094FE1971A2304148BEE2562`
- Benchmark：`1EC9F6AD8241B0E54D9084C3594087A8B07BFAD0483FC4C315D8D71353B4C266`

## 后续调整：范围面板内增加查询按钮

按用户后续要求，在“包含 N 个学期”正下方新增随主题色显示的“查询”按钮。起止调整仍立即计算本机成绩；点击查询复用当前范围的教务刷新，关闭面板，让页面显示加载与结果提示。查询期间、登录失效或没有有效范围时按钮不可用。主页面学期行不恢复查询按钮。

更新既有界面测试，确认按钮在数量下方、选范围不触发教务请求、点击恰好刷新当前范围并关闭面板，以及 320dp/实际 2 倍字号下可滚动触达。修改前 3 项界面测试有 2 项因按钮不存在而失败；修改后主功能模块 352 项测试全部通过，Debug、Benchmark 构建成功（`build/diagnostics/grade-range-query-20261112-validation.log`）。普通/大字号 Native Graphics 截图位于 `build/diagnostics/grade-range-query-20261112/`，已检查位置与布局。未安装到手机或访问真实教务账号。

本次只改动 3 个生产文件、1 个既有测试文件及此记录；其余 276 个既有修改文件哈希不变，差异空白检查通过。

本次安装包 SHA-256：Debug `177E6B5DB020651D71DDABA26B1D120753D1CBD01EBCCDB56397D34C4F3262A0`；Benchmark `1AEAF74C008BD56118311594FC94596D1B1CDBF1A40F6605172FC00EDFEC64D7`。
