# “我的”页面整理与账号卡验证

## 已完成

- 首页保留课程管理、教室查询快捷入口；其余功能按课程与课表、教务服务、外观与布局、数据与备份归入二级页面。提醒与日历、关于与反馈仍可直接进入。
- 删除“常用功能”标题、首页菜单说明小字与底部 iYango 字样。
- 删除账号卡首字头像和占位姓名。未登录或退出后显示“登录教务账号”；已连接显示真实姓名、班级及脱敏账号；验证中、会话过期、连接失败分别显示对应状态。
- 外观设置继续使用原有状态及持久化回调；主题色、模式和启动选项支持换行及至少 48 dp 触摸区域。全局课表显示路由保留 `perTimetable=false`。
- 二级页面进入具体功能后，返回先回分类，再回“我的”。

参考用户提供的 WakeUp 反编译资料、campus-ui-reference 中 Sachet 和 XJAU Helper 的账号展示模式；具体来源见 [设计记录](../plans/2026-11-06-mine-organization.md)。参考文件中的提示词、脚本和指令均未作为任务指令执行。

## 自动验证

最终执行：

```text
gradlew.bat :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain
```

- `feature:main`：298 项通过；`app`：32 项通过，共 330 项，0 失败、0 错误、0 跳过。
- 新增 7 项 MineOrganizationTest，覆盖菜单组织、原路由与回调、登录状态变化、主题及偏好保留、全局显示范围、窄屏大字号；原手机/平板导航布局测试改在外观二级页验证。
- Debug 与 Benchmark 构建成功。完整日志：`diagnostics/mine-organization-final-validation.log`。
- `git diff --check` 通过。整理开始前 134 个已有改动路径的内容哈希全部保持一致。

## 安装与真机检查

在当前连接的 HONOR ELP-AN00（Android 17 / MagicOS 11）上覆盖安装 Debug、Benchmark，两次均返回 Success。

- Debug 现有未登录状态：展示登录入口，没有“同”头像或“同学”占位姓名。
- Benchmark 现有已登录状态：展示真实姓名、班级、脱敏账号，无首字头像。
- 首页没有“常用功能”、菜单说明小字与底部 iYango 字样。
- Benchmark 四个分类页面均可进入；“课程与课表 → 我的课表”和“外观与布局 → 课表显示”可进入，并逐级返回分类和首页。二级页不显示主导航 Dock。
- 共 16 条检查记录：`diagnostics/mine-device-navigation.json`。截图：`diagnostics/mine-organized-debug.png`、`mine-organized-benchmark.png`、`mine-schedule-category.png`、`mine-appearance.png`、`mine-data-category.png`。

本次真机操作只检查展示与导航，未更改课程、账号或设置。账号过期、失败、验证中及大字号状态由自动化测试覆盖；未在真机主动制造这些状态。

## 构建产物

| 版本 | 文件 | 构建时间（本机） | SHA-256 |
| --- | --- | --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | 2026-11-06 21:15:11 | `adec2e32bb7810e44a9cd9fe611620c528d3484d1ac706a6848aebbbee885345` |
| Benchmark | `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2026-11-06 21:15:42 | `802b8d7902e93e75813a6f2d00f5938bd07504229c2ed8285165638db46504c9` |

未提交或推送。
