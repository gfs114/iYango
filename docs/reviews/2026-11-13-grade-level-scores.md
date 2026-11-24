# 等级制成绩换算（2026-11-13）

## 完成行为

按用户最终修正的规则参与加权平均分计算：优秀 95、良好 85、中等 75、及格 65、不及格 0。普通统计、自选算分、按属性选课和成绩排序使用同一套换算。用户说明及格绩点为 1.5，要求将先前的 60 分改回。

纯等级文本优先按当前规则换算，因此即使缓存中的及格分数为先前规则生成的 60，也无需重新查询，直接按 65 分计入。原始成绩文本、明确的数字成绩（如“优秀（92）”）及学校提供的绩点保持原有处理方式。学校返回的及格绩点 1.5 直接参与平均绩点计算。

统计说明中的纳入课程展示换算值，例如“及格 (65)”。学分、公选课开关和课程选择继续决定统计范围；不及格按 0 分计入，未提供换算规则的“合格”等文本不额外推断。

## 验证

领域测试覆盖五级制分数、旧缓存优先级、排序和学校绩点。界面测试验证普通统计、自选算分、属性面板结果一致，并检查统计说明显示“及格 (65)”以及各统计入口的 1.50 绩点。

完整验证命令：

```powershell
.\gradlew.bat :core:domain:test :core:designsystem:testDebugUnitTest :data:testDebugUnitTest :platform:android:testDebugUnitTest :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain
```

757 项测试通过：领域 136、设计系统 20、数据 164、Android 平台 36、主功能 369、应用 32；0 失败、0 错误、0 跳过。Debug、Benchmark 构建成功。

最终恢复 65 分后的日志与计数：`build/diagnostics/grade-pass-restore-20261113-full.log`、`build/diagnostics/grade-pass-restore-20261113-results.json`。

本次恢复调整 1 个生产文件、2 个测试文件，并更新本记录。改动前 287 个未提交文件中，其余 283 个文件哈希保持一致；差异空白检查通过。未安装或操作设备，未访问真实教务账号，未提交或推送。

安装包 SHA-256：

- Debug：`C3FE6FD82B328BA5499E12CAEEE7B0A9A1A77FE0477F7320EC26FE2006A6FF30`
- Benchmark：`0B2543C02338EA8E01A60E31631A9E750D589C9FA920B59E58EB198510587A94`
