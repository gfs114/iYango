# 自选算分按课程属性选择（2026-11-12）

## 最终行为

- 自选算分底部操作栏新增“按属性”，打开“课程属性”面板。
- 根据当前学期/学期范围内的成绩生成属性列表，沿用学校属性文字；只去掉首尾空白，空属性显示“未标注”。
- 支持同时选择多个属性：勾选时批量选中该属性的课程，再次点击已全选的属性会取消这组课程；其他组和手动选中的课程继续保留。
- 属性状态从课程勾选状态派生，支持未选、部分选中、全选。单独取消一门课后，对应属性显示部分选中；点击部分选中的属性会补选该组。
- 面板内实时显示加权平均、平均绩点、统计学分和已选数量，关闭后继续使用底部计算栏。
- 选择公选/公共选修/通选等属性时同步开启“统计包含公选课”；用户仍可手动关闭。计算沿用已有学分、成绩换算、重修记录及学校绩点规则。
- 使用当前范围的缓存，不发教务请求，也不写入成绩数据库；登录过期仍可使用。账号、范围或快照改变时旧操作回调失效，属性面板关闭。

## 实现与验证

`GradeAttributeSelection.kt` 对当前记录按属性分组，`GradesViewModel.toggleAttributeSelection` 更新现有课程 ID 集合。`GradeAttributePickerSheet` 与逐门选择共用该集合和已有统计计算器，不另存属性筛选状态。

新增 8 项测试：4 项界面测试及 4 项 ViewModel 测试。覆盖多属性合并、部分勾选、精确加权结果、公选开关联动、未标注/无效记录、离线范围计算、过期上下文、账号/快照切换、关闭面板保留选择，以及 320dp/实际 2 倍系统字号下的可操作性。最先运行的入口界面测试在实现前因入口不存在失败，实现后通过。

已检查 Native Graphics 截图：`build/diagnostics/grade-attributes-20261112/selection-bar.png`、`attribute-picker.png`、`attribute-picker-large.png`。入口位于现有操作栏，属性面板使用主题色，大字号下长属性名称可以换行。

完整回归期间发现既有 `WidgetRemoteViewsTest` 将 23:20 视为尚未下课，夜间会误报。为 `TodayModernFactory` 增加默认读取当前系统时间的可注入时间函数，测试固定为上午 9 点，并让日期和分钟使用同一次时间读取。保留该工厂的系统时区动态读取行为。

没有操作手机或真实教务账号，未提交或推送。

最终验证命令：

```powershell
.\gradlew.bat :core:domain:test :core:designsystem:testDebugUnitTest :data:testDebugUnitTest :platform:android:testDebugUnitTest :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain
```

746 项测试通过：领域 134、设计系统 20、数据 164、Android 平台 36、主功能 360、应用 32；0 失败、0 错误、0 跳过。Debug、Benchmark 构建成功。日志及计数：`build/diagnostics/grade-attributes-20261112-final.log`、`grade-attributes-20261112-results.json`。

保留原有未提交工作：改动前 281 个已存在的修改文件中，本次涉及 7 个（包括修复时间依赖测试的 2 个文件），其余 274 个哈希一致。另新增 2 个功能文件、1 个界面测试文件及本记录。差异空白检查通过。

安装包 SHA-256：

- Debug：`0A23360F4D3CAD13F67EE685890C1FF0FFD860F07E78D5E515F1EA730FA381E4`
- Benchmark：`B2E38A3449BF308F890DDCD3FEDC3A7087F99E2DACF8CA54FBFAF9B860CDF36D`
