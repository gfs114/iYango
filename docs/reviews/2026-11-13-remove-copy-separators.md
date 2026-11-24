# 移除界面文案分隔点

用户要求去掉截图中用于连接教室、教师、日期和时刻的中点，并要求未来新增文案也不再采用这种信息分隔符。

已统一替换首页、成绩、课表、课程管理、教室查询、考试、设置、同步记录、导入导出、图片导出和小组件等应用生成文案里的分隔中点。首页课程卡将教室与教师分行显示，日期与时间用空格连接。提示列表去掉装饰性前缀。

成绩属性只转换展示标签，原始值继续用于筛选与数据存储。考试提醒新文案采用空格；旧提醒快照继续可读，在渲染时去掉分隔点，并兼容旧、新科目前缀。姓名解析、原始课程/账号数据、数学注释和带含义的日历/分页指示不受此次文案修改影响。未来文案偏好已按用户要求写入记忆补充记录。

验证命令：`.\gradlew.bat :core:domain:test :feature:main:testDebugUnitTest :platform:android:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --console=plain`。

结果：`BUILD SUCCESSFUL in 1m 16s`，领域 144、主功能 391、Android 平台 36、应用 32，共 603 项测试，零失败、零错误、零跳过。现有文案断言同步更新；通知测试保留旧 JSON 输入并验证去分隔符后的显示，考试正文覆盖旧、新两种格式。`git diff --check` 通过，独立审查未发现阻断项。日志：`diagnostics/ui-copy-20261113/remove-separators-verification.log`。

已使用 `adb install --no-streaming -r` 保留数据覆盖安装到 ELP-AN00 手机的 Debug 版。安装后的手机处于锁屏，未完成本轮首页视觉验收，不将空截图/无 UI 节点视为通过。

APK SHA-256：`0B8C714B885C2FFBB1EBC883598D70F716C9CAAF8B7AC5AECD612C1311ADF8FB`。

未提交或推送，既有首页和课程弹窗改动保留。
