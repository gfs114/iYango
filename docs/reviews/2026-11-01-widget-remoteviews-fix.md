# 桌面小组件“无法添加小部件”修复记录

日期：2026-11-01。GPT 6.1 Sol 实现，主代理独立定位、审查差异并执行最终验证。

## 现象与证据

用户提供截图：桌面已经为小组件分配矩形区域，区域内显示“无法添加小部件”。本轮没有操作手机或采集设备日志。

四份实际组件布局都使用裸 `<View>` 作为 `layout_height="0dp"`、`layout_weight="1"` 的空白占位。桌面使用 RemoteViews 加载布局，与应用内部普通布局加载的支持范围不同；裸 View 不在允许的类型中，LinearLayout 则受支持。依据：[Android RemoteViews 官方文档](https://developer.android.com/reference/android/widget/RemoteViews)。

使用应用实际打包资源，在 Robolectric API 35 环境调用 `RemoteViews.apply`，修复前四项测试全部失败，异常为：

```text
Class not allowed to be inflated android.view.View
```

对应布局：预览、今日小卡、今日大卡、今日标尺。失败日志：`diagnostics/widget-remoteviews-red.log`；完整异常报告：`diagnostics/widget-remoteviews-red.xml`。这确认了现有布局的宿主加载缺陷；尚未以该手机桌面的运行日志验证其所有失败因素。

## 修改

- `app/src/main/res/layout/widget_preview.xml`
- `app/src/main/res/layout/widget_today_2x4.xml`
- `app/src/main/res/layout/widget_today_4x4.xml`
- `app/src/main/res/layout/widget_ruler_4x4.xml`

每份仅把占位 `<View>` 换为 `<LinearLayout>`，原宽度、高度、权重保持不变。保留课程数据来源、组件 ID、配置、背景及点击跳转。

新增 `app/src/test/java/com/gfs/iyango/WidgetRemoteViewsTest.kt`，并在 `app/build.gradle.kts` 增加 Android 资源单测开关及已有版本目录中的 JUnit / Robolectric 测试依赖。没有新增运行时依赖。

每种布局覆盖：初始加载、实色与透明背景的 setBackgroundResource、标题/页脚与课程字段更新、列表可见性更新、首次 apply 和已有布局 reapply。测试使用实际资源，避免仅检查 XML 文本或用普通 LayoutInflater 绕过宿主限制。

## 审查和验证

- 主代理独立确认四个 provider 的 initialLayout 指向上述资源；四处替换均未改变占位几何属性。
- Android CLI 版本：1.0.16625370；本地 SDK 位于 `G:/Android SDK/SDK`，编译平台 android-37.0 存在。未查询或操作设备。
- 定向修复后：4 项测试通过，日志 `diagnostics/widget-remoteviews-green.log`。
- 主代理最终执行：

```text
gradlew.bat :app:testDebugUnitTest :platform:android:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

结果：`BUILD SUCCESSFUL in 10s`。应用 4 项、平台 18 项，共 22 项报告均为 0 失败、0 错误、0 跳过；平台测试任务因输入未变为 UP-TO-DATE，应用测试本轮执行。未重复运行未受影响的成绩/课表功能模块测试，不将旧计数并入本轮。

最终日志：`diagnostics/widget-remoteviews-final-verification.log`。`git diff --check` 通过，四份 widget 布局已无裸 View。

## 产物及验收边界

- APK：[app-debug.apk](G:/code/Android/iYango/app/build/outputs/apk/debug/app-debug.apk)
- 大小：17,023,796 字节。
- SHA-256：`E772566BEE9FE1CEFEDFF7D55018864938961F9A245676DDCCA0CAA20C007C22`。
- 保留全部既有工作，未实施成绩页 UI 方案，未提交、未推送、未安装、未操作手机或真实学校接口。
- 已验证本地 RemoteViews 加载与更新；手机桌面重新添加、尺寸调整、透明外观及点击行为仍待设备验收。
- 后续用户安装本轮 Debug APK 后可重新添加组件检查；若旧错误占位未被桌面自动更新，可移除该占位再添加。不需要清除应用数据。
