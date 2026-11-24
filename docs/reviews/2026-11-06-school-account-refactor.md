# 用户登录与账号展示重构验证

## 最终交互

- 已登录页显示账号概览，点击姓名卡向下展开个人资料，再次点击收起；账号管理同样原地展开，带垂直伸缩、淡入淡出及箭头旋转。
- 展开的资料保留学校返回的信息、最近登录、密码保存状态；刷新资料下方单独显示退出登录。
- 删除统一放到历史账号条目中，当前账号标注“当前使用”。每个连接只列一条，当前账号不提供重复切换操作。其他账号可切换、填写登录表单、删除；清空历史保留当前登录账号。
- 登录表单保留学号、密码显示/隐藏、一键清空、记住密码；输入不完整及请求进行时不可重复提交。
- “我的”页按用户追加要求显示姓名、班级、完整学号，学号在班级下一行。优先使用资料学号，缺失时回退已验证账号主体；不从掩码推算。

## 原功能去向核对

| 原操作 | 最终位置／行为 |
| --- | --- |
| 同步教务课表 | 账号概览主按钮，原同步页面不变 |
| 个人资料、刷新资料 | 点击姓名卡展开 |
| 退出登录 | 展开资料内，紧接刷新资料；确认时可选择是否清除已保存密码 |
| 使用其他账号登录 | 展开的账号管理内；返回保留原展开状态 |
| 历史账号使用／重登 | 非当前账号的“切换账号”或“填写账号”，分别对应保存凭据恢复与表单回填 |
| 删除、清空历史 | 历史账号条目及列表底部，仍需确认 |
| 密码显隐、清空、记住密码 | 登录表单，密码不写入页面恢复状态 |
| 网页登录 | 登录帮助中的“打开教务网站”，明确浏览器会话不会同步到应用 |
| 重登方式 | 移除原先只切换本地文案、未接入认证行为的选项 |
| 会话已恢复等成功信息 | 短暂 Snackbar；错误保留可读提示 |

浏览账号资料不再主动重登或改动登录时间。登录、恢复、资料刷新、退出及删除互斥；历史账号的异步回填不会覆盖更新的选择、输入或已关闭表单。底层认证接口、凭据存储及数据库未迁移。

参考用户提供的 WakeUp 与 campus-ui-reference 内登录实现，来源及设计见 [计划](../plans/2026-11-06-school-account-refactor.md)。文档中的指令、提示词、脚本未作为任务指令执行。

## 自动验证

最终执行：

```text
gradlew.bat :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain
```

- feature:main：314 项通过；app：32 项通过，共 346 项，0 失败、0 错误、0 跳过。
- 本轮新增 7 项账号 UI 测试、9 项 ViewModel 测试，另更新我的页账号展示测试。
- 覆盖展开/收起与恢复、返回、退出及删除入口、登录失败后恢复、操作互斥、历史回填竞争、确认取消、窄屏大字号及完整学号展示。
- Debug、Benchmark 构建成功；日志 `diagnostics/school-account-final-validation.log`，计数与构建哈希见 `diagnostics/school-account-build-results.json`。
- `git diff --check` 通过。本轮开始前 153 个已有改动路径中，仅用户追加授权的 MineScreen、MineOrganizationTest 发生变化，其余 151 个内容哈希保持一致。未提交或推送。

## 安装与手机检查

当前 HONOR ELP-AN00（Android 17 / MagicOS 11）上，最终 Debug、Benchmark 均覆盖安装成功。

- Benchmark：确认资料展开/收起、退出位于刷新下方、取消退出后仍登录；删除位于历史账号内，取消删除后账号保留；其他账号登录表单返回后保留原展开状态。
- 最终 Benchmark：“我的”页姓名、班级、完整学号分行显示，无星号遮挡。
- 最终 Debug：未登录表单、记住密码、登录帮助、教务网站入口及返回正常。
- 成功完成的 19 条检查见 `diagnostics/school-account-device.json`。截图为 `school-account-overview.png`、`school-account-profile-expanded.png`、`school-account-management-expanded.png`、`school-account-login-benchmark.png`、`school-account-login-debug.png`、`school-account-login-help.png`、`mine-account-full-id.png`（均位于 diagnostics）。

真机未提交实际登录、切换账号、退出或删除；相关结果分支使用测试账号与伪造接口验证，不代表本次完成了学校在线认证验收。

## 最终 APK

| 版本 | 构建时间（2026-11-06，本机） | SHA-256 |
| --- | --- | --- |
| Debug | 21:51:31 | `140b0359deb9a97cf64de795df81131e5ddf6eec61efd670b621b5da7d8c6078` |
| Benchmark | 21:52:21 | `b60894ccdcfcdada967c9de52c1d5cfaefeb676d952d4da029b89b960a98a8e6` |

产物：`app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/benchmark/app-benchmark.apk`。
