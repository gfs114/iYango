# 刷新提示胶囊接入 Android Liquid Glass

日期：2026-11-12，Asia/Shanghai。分支：`iYango_prelease`。

按用户要求，依据 `docs/plans/2026-10-27-liquid-glass-full-adaptation.md` 中浮层玻璃与单向采样规则，将统一提示接入现有 Backdrop 2.0.1。范围为刷新／账号操作提示胶囊，不代表整份全应用适配方案已完成。

## 实现

- `RefreshNoticeCapsule` 复用 `GlassSurface` 的 `OVERLAY` 材质：背景模糊、透镜折射、细高光和阴影；参数继续由 `GlassTokens` 集中管理。
- 着色取当前主题 `primary`，前景文字明确使用 `onPrimary`，支持预设、自定义颜色及深浅色。文字在效果层上方绘制。
- `GlassScaffold` 新增 `overlay` 插槽，传入同一份安全边距及 L0 + 当前页面正文的组合背景；覆盖层本身放在正文录制范围之外。正文玻璃仍读取原 L0，顶栏仍使用原组合采样来源。
- 今日、课表、成绩、教室、考试、账号页的提示全部移入该覆盖层，避免提示文字被自身采样，也避免错误采样上一页。
- 保留现有位置、圆角、字号、长文案换行、主题色、3 秒自动收起和进出动画。静态胶囊阻止点击穿透，胶囊外仍可操作页面。
- 低版本或无宿主时沿用现有 `GlassSurface` 回退规则，保留主题着色和文字。

本次仅修改共享 UI 与六个页面的提示放置位置；没有修改刷新协调器、业务 ViewModel、数据库或网络接口。修改前的八个源文件副本保留在本机任务临时目录，既有功能改动按原内容继续保留。未提交或推送。

## 验证

新增 `RefreshNoticeGlassTest` 四项测试：

1. 背景从白变黑后，胶囊的最终像素跟随变化，并保留选中的蓝色主题；该用例在接入前因实体底色不采样背景而失败。
2. 覆盖层读取当前正文来源，直接读取录制图层确认其中不含覆盖层自身；正文仍使用原背景来源。
3. Android 9 回退显示正常，主题从蓝色切换为黄色时胶囊同步变化。
4. 胶囊区域阻止点击穿透，区域外的页面操作仍可触达。

最终执行：

```powershell
.\gradlew.bat :core:designsystem:testDebugUnitTest :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain
```

结果：退出码 0，`BUILD SUCCESSFUL`。

| 模块 | 测试数 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| core:designsystem | 20 | 0 / 0 / 0 |
| feature:main | 328 | 0 / 0 / 0 |
| app | 32 | 0 / 0 / 0 |
| 合计 | 380 | 0 / 0 / 0 |

包含既有顶部栏采样、滚动穿过、提示显示／收起／重复展示和非活动标签清理用例。Debug、Benchmark 均构建成功，Benchmark 包含 R8 和 lintVital；`git diff --check` 通过。

日志：`diagnostics/refresh-notice-glass-20261112-red.log`、`refresh-notice-glass-20261112-targeted.log`、`refresh-notice-glass-20261112-full.log`。本地渲染图：`build/diagnostics/refresh-notice-glass-20261112/`。

## 当前 APK 与真机边界

本次产物替代此前实体主题色胶囊版本：

| 构建 | 路径 | SHA-256 |
| --- | --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | `7738D6E20BC47BA1091CB9F94A4917414555CB9893D7FB39934400950DE87EB9` |
| Benchmark | `app/build/outputs/apk/benchmark/app-benchmark.apk` | `A30A7D849370780F8178D8519AF4432DCE5F197D2E79D3B512AAAE1DD5803ECF` |

未安装、未调用真实学校接口。Robolectric Native Graphics 验证了最终像素的背景采样与图层隔离，不代表已验收手机 GPU 上的模糊、折射、滚动帧率和不同壁纸下的观感；这些仍需真机检查。
