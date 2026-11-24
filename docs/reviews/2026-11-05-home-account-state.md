# 主页教务登录状态显示修复

## 原因与改动

今日页 `ProfileCard` 原先固定显示“未绑定教务账号”，且 `TodayViewModel` 与 `TodayUiState` 没有账号数据。即使已保存的 Cookie 仍可联网查询，返回主页也会显示未绑定。

- 今日页订阅 `SchoolAuthPort.observeConnections()`，优先显示 ACTIVE 账号；无 ACTIVE 时保留最近的非 LOGGED_OUT 账号，用于展示过期、验证或暂时失败状态。
- 资料卡读取已保存姓名，缺失时使用脱敏账号，再回退“同学”；主动退出后不把历史姓名作为当前身份展示。
- 分别显示已连接、未登录、会话失效、正在确认和连接暂不可用。学期外与未设置学期也分别显示。
- 账号状态与课程状态组合，资料变化不触发课程重算；首次就绪等待账号数据，避免把尚未读取的记录显示成未登录。
- 该展示流程不调用登录、会话验证或学校接口；没有修改凭据保存、登出或服务端过期判断。

## 验证

修复展示前，3 项 UI 回归测试均因姓名/账号状态文案缺失而失败；修复后 9 项新增测试全部通过。覆盖新建 ViewModel 读取已有账号、后台停止订阅后恢复、账号切换、资料更新、退出、会话过期、暂时失败、验证中、首次账号加载，以及卡片跳转。

最终执行：

```powershell
.\gradlew.bat :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

- `feature:main`：273 项通过。
- `app`：22 项通过。
- 合计 295 项，0 失败、0 错误、0 跳过；Debug 构建成功。
- `git diff --check` 通过。
- 本轮开始前 76 个已有改动文件逐一 SHA-256 核对，全部保持原样。
- 日志：`diagnostics/home-account-ui-red.log`、`diagnostics/home-account-green.log`、`diagnostics/home-account-final-verification.log`。

APK：`app/build/outputs/apk/debug/app-debug.apk`。构建时间 2026-11-05 22:17:38（Asia/Shanghai），17,111,422 字节，SHA-256：

```text
E0AC1EF18EDC43FBA943B279F18B98089554A0732116A90C82C1E7D617AC8E74
```

本包包含当前工作区已有的成绩页和桌面组件改动。本轮测试使用本地账号记录桩与 Robolectric，没有调用真实学校接口或安装、操作手机；真实进程关闭与设备后台恢复由真机验收确认。未提交或推送。
