# 首页自定义与课程倒计时自查

本次仅实施首页计划 H1–H7。未提交、推送、创建分支或工作树，未安装设备；原有 `docs/superpowers/plans/` 文档保留。

## 实施范围

| 项目 | 结果与实现位置 |
| --- | --- |
| H1 模型 | `HomePreferences` 采用版本 1 和六个稳定模块 ID；顺序去重、未知 ID 剔除、缺失模块补齐。全隐藏和零快捷项保留；快捷项最多八个。注册项均对应现有功能。 |
| H2 编辑与入口 | 首页所有状态的顶栏及“我的→外观与布局”均提供入口。玻璃编辑页只修改草稿，支持恢复默认、保存、取消、未保存返回提示、保存失败保留草稿及重试。模块标题和内容整组排序/隐藏；排序柄长按拖动及上移/下移按钮；快捷入口可增删/排序。预览实时读取草稿。 |
| H3 持久化与备份 | DataStore 双向 DTO、版本规范化和损坏首页局部回退；仓库级原子偏好 transform 同时供首页和壁纸使用。旧设置备份缺失 home 保留当前首页，显式默认及空集合正常恢复。恢复时通过最新偏好 transform 保留缺失首页字段和未恢复的壁纸路径。 |
| H4 精确时间 | `TodayCourseTiming` 为纯投影，使用实例实际日期及 `[start,end)`。当前重叠按开始较晚、同起点按实例 ID 选取；下一门严格在未来，查询今天至 +6 天。未知或无效时间不遮挡已知课程。旧 `CourseStatusCalculator` 行为保持，仅修正注释范围。 |
| H5 数据与生命周期 | 七天有效实例和整月有效月历分别观察，快照共享。秒 tick 只投影缓存；其它首页内容只按分钟及业务变化更新。秒模式仅首页前台、主 Tab 活跃且今日课程模块可见时启用；其它可见时间模块使用分钟边界。返回页面、系统时间/时区变化及午夜更新日期窗口。 |
| H6 验证 | 领域、持久化、ViewModel 和 Robolectric Compose 定向测试覆盖关键语义；结果见下文。 |
| H7 交付 | 本记录说明实施、自查及自动化验证边界；独立主代理继续执行完整测试和 Debug 构建。 |

时间卡保留课程名称、地点、教师、日期/开始时刻及进入实际实例详情的能力。当前长课仍显示分秒，等候不足一小时用分秒、不足一天用时分、更远课程显示日期与天数。数字使用等宽特性。TalkBack 仅稳定状态标题设 Polite live region，倒计时与该标题为独立语义节点；回归测试曾捕获整卡 clickable 合并 live region 的问题，现已修正。

详情和调整弹窗使用被选实例日期对应的学期、教学周、`displayTimeSlots` 和节次数量；临时整天及未来自动选表不沿用今天作息。

## 实现阶段定向证据

- 首轮交接：`diagnostics/home-customization-20261113/home-final-targeted.log`，退出码 0，`BUILD SUCCESSFUL in 51s`。
- 命令：`:data:testDebugUnitTest :feature:main:testDebugUnitTest`，筛选 `HomePreferencesPersistenceTest`、`today.*` 和既有 `WallpaperViewModelTest`。
- 该轮结果：feature 36 项、DataStore 5 项，均为零失败/零错误；之后独立审查又增加了读取失败及恢复的回归，最终完整结果见下文。
- DataStore 覆盖：真实文件存储、关闭存储协程作用域后重新打开文件、首页/壁纸并发 transform、损坏/未来版本 home 局部回退，以及旧备份缺失字段和显式空配置的区别。
- ViewModel 覆盖：60 秒内有效实例/月历观察次数不增加、停止后时钟不继续变化、隐藏秒卡只按分钟发射、午夜只重建七天窗口、同月月历观察保留、时区覆盖，以及有效源变化立即更新今日和明日。
- UI 覆盖：无课表顶栏入口、全隐藏指引、零快捷入口、奇数项等宽及稳定 ID 分发、状态 live region 与倒计时分离、既有账号展示行为。
- 领域测试已通过 `:core:model:test :core:domain:test`；精确开始/结束、相邻、重叠、未知/无效时间、实际日期及七天窗口有独立测试。
- `git diff --check` 通过。

测试优先记录保存在同一 diagnostics 目录。新模型/接口尚不存在时的编译失败、长课倒计时断言失败，以及语义树 live region 合并断言失败均保留日志；后续绿色日志为当前实现结果。此前一次持续运行的测试因测试末尾没有关闭活跃时钟而终止，已补关闭逻辑，最后两轮均正常结束。

## 验证边界

尚未执行真机外观、整页拖动/未保存返回、TalkBack 实际语音、系统广播与多窗口设备验收。现有自动化覆盖组件与基础预测返回处理，不冒充整页设备手势验收。宽屏列数使用现有 Compose 可用宽度自适应，无新增动画；玻璃、暗色和壁纸由现有设计系统提供。

本轮不实现学分进度、WebDAV 或统一备份恢复协调器；首页不注册未实现功能的入口。隐藏设置只影响首页展示，不删除课程数据，不参与同步、提醒或日历调度。

## 主代理独立审查与最终验证

需求审查与代码质量审查均已通过，没有未解决的阻塞项。主代理核对实际 diff、失败 XML、关键实现和构建输出，没有仅依赖实现代理的完成报告。

审查中修复了首页版本/局部容错、旧壁纸测试替身、长课时间精度、实际日期课程详情及作息、秒级更新范围、零入口指引，以及点击区域导致状态通知与倒计时合并的问题。

完整回归首次发现三个旧首页刷新测试失败，原因是其 EffectiveSchedulePort 测试替身只接受 LocalDate，而新观察接口传入日期区间。补齐替身并新增 Ready/账号前置断言，原刷新行为断言保留；生产刷新文案和行为没有为通过测试而更改。首次失败日志保留为 `diagnostics/home-customization-20261113/home-first-full-failed.log`。

质量审查另发现共享流的读取异常会绕过下游 catch。现已在共享前携带显式成功/失败结果，且最终账号/页面组合位于可重新订阅的边界；本地重试和重新进入能恢复，取消正常传播。四项新增回归覆盖首次月历读取失败、已显示课程后读取失败、创建观察时同步失败和账号缓存流失败，不调用网络。

最终统一验证命令：`.\gradlew.bat :core:model:test :core:domain:test :data:testDebugUnitTest :feature:main:testDebugUnitTest :platform:android:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --console=plain`。

结果：退出码 0，`BUILD SUCCESSFUL in 45s`；完整日志为 `diagnostics/home-customization-20261113/home-final-verification.log`。部分未受最后修改影响的任务复用了 Gradle 已通过的输出，不宣称全部任务都重新执行。

| 模块 | 测试数 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| core:model | 31 | 0 / 0 / 0 |
| core:domain | 144 | 0 / 0 / 0 |
| data | 169 | 0 / 0 / 0 |
| feature:main | 389 | 0 / 0 / 0 |
| platform:android | 36 | 0 / 0 / 0 |
| app | 32 | 0 / 0 / 0 |
| 合计 | 801 | 0 / 0 / 0 |

计数来自本次验证任务对应的 Gradle XML 报告。`git diff --check` 通过。

Debug APK：[app-debug.apk](G:/code/Android/iYango/app/build/outputs/apk/debug/app-debug.apk)，18,674,494 字节；SHA-256：`DDFA1CC8424D67ED6DAD70D87544F279A4D5CB6012DB2778EC61EA0AF7FEC7E6`。

未安装设备、未执行真实教务数据或设备视觉验收，未提交或推送。另两项计划及用户原有文件继续保留。

## 后续真机闪退修复（2026-11-13）

用户随后报告从课表切回首页闪退。此前“无阻塞项”的审查漏掉了完整页面切换时的广播监听生命周期，以上自动化结果不能据此证明该路径没有问题。

设备崩溃日志确认 `com.gfs.iyango.debug` 在 `TodayScreen` 的 `onDispose` 抛出 `IllegalArgumentException: Receiver not registered`。原实现用 `rememberUpdatedState` 委托读取 `tabActive`；旧 `DisposableEffect` 清理时实际读到下一次重组的新值。因此隐藏→显示会注销未注册的监听，显示→隐藏则漏掉注销。

修复将 `tabActive` 改为每次组合的普通值，使注册与清理捕获同一个可见状态，不吞掉异常。新增 `TodayTabLifecycleTest` 直接组合真实 `TodayScreen` 与 `RetainedMainTabs`，覆盖预热首页、初始首页、连续往返、二级页面遮挡和销毁，并核对监听注册/注销平衡。旧实现两项测试分别复现异常和监听泄漏，修复后两项通过。另有独立代码审查核对修复和红色测试证据。

- 崩溃证据：`diagnostics/home-customization-20261113/home-tab-crash-before.log`，仅保留目标进程异常栈。
- 红/绿测试：`home-tab-lifecycle-red.log`（2 项失败）；`home-tab-lifecycle-green.log`（2 项通过）。
- 回归命令：`.\gradlew.bat :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --console=plain`。退出码 0，`BUILD SUCCESSFUL in 40s`；feature 391 项、app 32 项，共 423 项，零失败/错误/跳过。本轮没有重新运行其它模块的完整测试。
- 回归日志：`diagnostics/home-customization-20261113/home-tab-crash-verification.log`。
- 已用 `adb install -r` 在当前连接的 ELP-AN00 手机上覆盖安装 Debug 版，保留应用数据。修复包 SHA-256：`917195833A09845A207BDE1C2B0C6E83313B57435A4676E48149BB085F58FFA4`，替代上文历史 APK。
- 真机复测：课表↔首页共四轮往返，以及打开首页设置后返回，均正常；当前进程 PID 22226 始终未变，按该 PID 查询崩溃缓冲区无新异常。记录：`diagnostics/home-customization-20261113/home-tab-device-check.log`。

本轮真机验收仅覆盖上述切换和返回路径；没有验证完整拖动、TalkBack 语音或功耗。修复未提交、未推送。
