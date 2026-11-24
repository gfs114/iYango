# 2026-11-06 课前提醒与荣耀灵动胶囊排查

问题：用户反馈课前 10 分钟时，普通通知栏和灵动胶囊均没有课程提醒。排查设备为当前 ADB 连接的 HONOR ELP-AN00，Android 17 / API 37，MagicOS 11.0.0。本文时间均为 Asia/Shanghai。

## 诊断证据

- 19:51 左右，Debug 的提醒总开关为开启、提前量为 10 分钟，通知与精确闹钟权限均允许，通知渠道 importance=3。
- Debug 私有存储有 26 条提醒快照，其中 24 条触发时刻仍在未来；系统 AlarmManager 中没有待触发的课程闹钟，只有清除历史。19:38:11 附近存在课程提醒 PendingIntent 被取消的记录，与当时的覆盖安装时间相邻。
- `AndroidReminderPlatform.schedule()` 原先把“本地快照内容没有变化”等同于“系统闹钟仍存在”，直接跳过注册。重启、覆盖安装、权限撤销后再恢复等场景中，快照可保留而系统闹钟已丢失，因此状态仍显示排程成功，实际不会触发。
- `CourseReminderPlanner` 原先在 `triggerAt <= now` 时剔除课程。恰好到课前 10 分钟或在这个窗口内首次恢复计划时，会漏掉尚未开始的课程。
- 原平台还会保留旧快照中“结束时间未到”的项目，即使课程已不在当前有效计划内；恢复通知时会使无效旧提醒重新出现。此次将当前有效实例作为唯一依据。

上述状态和回归测试能证明这些缺陷会造成同类漏提醒。没有取得用户此前那一次课前时刻的完整系统投递日志，不能把清除历史当作那一次漏提醒的唯一原因。

## 荣耀文档核对

已阅读用户提供的[荣耀灵动胶囊开发指南](https://developer.honor.com/cn/doc/guides/101771)。页面“接入范式四、Android 原生胶囊”支持通过原生实时通知接入。

当前渲染使用标准 BigTextStyle，具有标题、ongoing 标记、提升请求与 POST_PROMOTED_NOTIFICATIONS 权限，没有自定义 RemoteViews、群组摘要或 colorized=true；设备渠道也未被降为最低重要性。因此保留现有原生接入方式，修复上游调度。

Android 的[闹钟文档](https://developer.android.com/develop/background-work/services/alarms)也要求在获得精确闹钟权限后重建所需闹钟；相同 PendingIntent 再注册会替换原闹钟。

## 修改

- 每次对齐计划时重新注册未来的课前、上课触发点，使用稳定 PendingIntent 身份替换已有项目。
- 课前窗口及仍在进行中的有效课程继续进入计划，保存快照后立即恢复通知；不注册过去时刻的闹钟。缺结束时间的课程只保留到开始后 3 分钟。
- 删除、停用、切表和单课程关闭后，移除不在当前计划中的旧闹钟与通知；不再从旧快照保留无效课程。
- 保留“知道了”/划掉通知的关闭状态，通知以稳定 ID 原地更新，避免重复响铃。
- 增加精确闹钟授权变化广播的重建入口。保留原有跨日和组件刷新改动。

## 自动验证

新增 13 项回归测试：平台 9 项、领域规划器 4 项。先分别复现闹钟丢失、权限升级、课前边界、课中恢复和无效旧快照保留问题，再修复。

最终命令：

```powershell
.\gradlew.bat :core:domain:test :platform:android:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain
```

结果：199 项通过，0 失败、0 跳过。其中 core:domain 132 项，platform:android 35 项，app 32 项。Debug 与 Benchmark 均构建成功，Benchmark 包含 R8 构建与 lintVital。日志：`diagnostics/reminder-recovery-final-verification.log`。

曾尝试额外的 API 36 Robolectric 结构测试，但项目默认 Java 17 不满足该沙箱的 Java 21 要求；该临时测试未纳入最终源码，未修改项目 Java 配置。环境失败记录保留于 `diagnostics/reminder-native-jvm-environment.log`。原生胶囊资格采用以下真机系统记录验证。

## 真机验证与安装

- 最终 Debug 于 20:10:52、Benchmark 于 20:10:54 覆盖安装成功；两者安装后的 base.apk SHA-256 均与本地最终产物一致。
- 两个版本分别恢复 42 个精确课程触发点（21 个有效计划，各含课前与上课），系统显示 `window=0`、`exactAllowReason=permission`。
- 单独启动 Benchmark 提醒设置页，确认“已排程 21 / 计划 21”、通知“已授权”、精确提醒“可用”，没有“实时通知已关闭”提示。
- 诊断中，Debug 恢复的课程通知被系统赋予 `ONGOING_EVENT|ONLY_ALERT_ONCE|PROMOTED_ONGOING`，标准模板与提升请求均可见，证明原生提升链路能够生效。
- 最终有效计划当天无课，旧快照对应的两条通知已清理。因此未将旧通知作为当天有效课程的验收，也未把系统提升标记等同于 Benchmark 的胶囊视觉验收。
- 尚待下一次真实课程验证：自然到达课前 10 分钟时的唤醒、实际胶囊展示，以及锁屏/长时间后台表现。本轮未改手机时钟、课程数据或通知权限来制造触发。

产物 SHA-256：

| 版本 | SHA-256 |
| --- | --- |
| Debug | `7186a0e02b5ce00da7209b342d8df1b6e8ba378386cdffefa538db0145711da7` |
| Benchmark | `25fe9181fb2df13cf471b3af132aac34cbac5d9319920e28927981ca35e9f8fb` |

任务开始时记录了 101 个已有改动路径的哈希；其中本任务修改了 Manifest 和 AndroidReminderPlatform，其余 99 个路径保持一致。未提交、未推送。
