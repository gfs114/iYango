# 等级制成绩纳入平均绩点（2026-11-13）

## 行为

用户确认五级制分数为 95、85、75、65、0，并要求等级成绩参与平均绩点，公选课默认排除。

| 等级 | 分数 | 教务未提供绩点时的换算 |
| --- | ---: | ---: |
| 优秀 | 95 | 4.5 |
| 良好 | 85 | 3.5 |
| 中等 | 75 | 2.5 |
| 及格 | 65 | 1.5 |
| 不及格 | 0 | 0 |

原实现只累计教务提供的绩点，导致已进入均分的等级成绩在缺少教务绩点时没有进入平均绩点。本次由 `resolvedGradePoint` 统一处理：学校返回值优先，缺失时按五级制换算，按学分加权；不及格的 0 绩点及其学分均计入。数字成绩仍使用学校提供的绩点，没有扩展未知文本的换算规则。

公选课默认排除；既有“统计包含公选课”开关仍可显式纳入。普通统计、自选算分、按属性算分共用规则。换算结果不写回学校数据，旧缓存直接适用。课程卡片、无障碍描述、课程详情及统计说明显示“换算绩点”，区别于学校返回的绩点。

## 验证与安装

新增 2 项领域测试，覆盖五级制、按学分加权、不及格计入分母、学校值优先、公选课筛选及未提供数字成绩绩点的行为。连同旧缓存用例，旧实现上 3 项测试失败；实现后全部通过。既有界面测试检查普通、自选和属性面板的 2.63 平均绩点，以及统计说明中的学校绩点与换算绩点。

完整验证：

```powershell
.\gradlew.bat :core:domain:test :core:designsystem:testDebugUnitTest :data:testDebugUnitTest :platform:android:testDebugUnitTest :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain
```

759 项测试通过：领域 138、设计系统 20、数据 164、Android 平台 36、主功能 369、应用 32。0 失败、0 错误、0 跳过。两个版本构建成功，并通过 `adb install -r` 保留数据覆盖安装；设备 APK 的 SHA-256 与本地构建逐一匹配。

- Debug：`CAA926E8CDF3E1FBD9DCBFBFBC6538B1D91B31F639ACA03BB27A9BE4DF6DAC04`
- Benchmark：`24C45F4B6A0D577D9939B642C709A61E395ADE0851893CC25D5C37CA847A9FD3`

证据文件为 `build/diagnostics/grade-level-gpa-20261113-full.log`、`grade-level-gpa-20261113-results.json`、`grade-level-gpa-20261113-installed.json`。本次没有查询真实教务账号，未确认手机上“思政课社会实践”的实际字段或统计结果。

本次修改 4 个生产文件、2 个测试文件并新增本记录；基线 287 个未提交文件中的其余 281 个哈希保持一致。未提交或推送。
