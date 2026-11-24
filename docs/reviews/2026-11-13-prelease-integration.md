# 当前版本纳入 iYango_prelease

按用户最终指定，将当前工作版本收录到本地 `iYango_prelease`。操作前 `iYango_release` 与 `iYango_prelease` 同在 `9949d6f`，功能改动尚未提交；因此保留工作区内容切换到 `iYango_prelease` 后直接提交，无需制造额外合并提交。`iYango_release` 保持原提交。

本次包含首页模块排序/隐藏、快捷入口自选、课程倒计时与进度、课表切首页闪退修复、课程详情与调整弹窗布局、去除文案分隔点、课程起止时间显示、明日空状态精简，以及七次点击版本号解锁的开发者模式。手机型号、近期状态和诊断日志仅在开发者模式中显示。学分进度、统一备份与 WebDAV 此次仅收录既有计划文档，不计作已实现功能。

验证命令：`.\gradlew.bat :core:model:test :core:domain:test :data:testDebugUnitTest :feature:main:testDebugUnitTest :platform:android:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --console=plain`。

结果：模型 31、领域 144、数据 171、主功能 395、Android 平台 36、应用 32，共 809 项测试，零失败、零错误、零跳过；Debug 构建通过，耗时 52 秒。`git diff --check` 通过。各次真机检查的范围见对应功能评审记录，本次整合未重复安装或扩展真机验收。

源码、测试和说明纳入版本控制，临时诊断日志保留本机。此次为本地分支提交；仓库没有配置远程地址。
