# 桌面组件玻璃风格：实施与验证

> 历史阶段记录：用户随后反馈本版不够透明。底色及测试标准已由 [通透度修正记录](G:/code/Android/iYango/docs/reviews/2026-11-01-widget-glass-transparency.md) 更新。本页的旧对比数值与 APK 哈希仅对应当时版本；下方同路径预览和 APK 已被新版替换。

日期：2026-11-01。用户已批准半透明玻璃风格方案；GPT 6.1 Sol 编码，主代理独立审查、查看实际资源渲染图并完成最终构建。

## 效果与使用

在组件配置的“外观”中选择“玻璃风格”。原“透明”对应的枚举及保存值 `TRANSPARENT` 保持不变，既有透明组件在更新渲染后使用新资源。实色选项继续保留，无需数据库迁移或重新选择课表。

玻璃背景由同一边界内的三层 XML drawable 构成：柔和暗部渐变、半透明日夜底色、1dp 高光描边。保留 20dp 圆角及原内容空间，没有增加 inset、压缩课程区域或引入不受 RemoteViews 支持的控件。

暗部属于卡片内部的柔和层次，不是桌面外投影。本次实现不包含实时模糊、桌面背景采样或透镜折射。日夜资源跟随宿主的 Android 资源配置，不承诺与应用内单独指定的主题同步。

## 修改范围

- `app/src/main/res/drawable/widget_background_transparent.xml`：纯色底改为三层渐变与高光。
- `app/src/main/res/values/colors.xml`、`values-night/colors.xml`：新增玻璃色值；调整组件专用辅助文字和时间强调色，以保证可读性。实色卡片也使用这些文字色，应用主界面主题色不受影响。
- `app/src/main/java/com/gfs/iyango/WidgetConfigActivity.kt`：将“透明”标签改为“玻璃风格”，说明改为“半透明底色与高光描边，可透出壁纸”。
- `WidgetRemoteViewsTest.kt`：扩展夜间四布局加载与更新覆盖。
- `WidgetGlassDrawableTest.kt`：使用 Robolectric native graphics 对实际 drawable 渲染、合成检查并导出预览。

保留上轮四个占位 View 改为 LinearLayout 的修复；本轮没有更改组件课程数据来源、配置结构、跳转与更新逻辑，没有新增运行时库、权限或服务。

## 验证

7 项定向测试通过：5 项 RemoteViews 用例覆盖四布局的初始 apply、实色与玻璃背景切换及 reapply，并覆盖日夜；2 项原生绘图用例检查透明度、渐变及文字对比。

对比测试对 340×180 渲染结果的正文内部区域逐行采样，分别合成黑、白、洋红、红、绿、蓝、青、黄八种纯色背景，检查主文字、辅助文字及时间强调色。采样结果：

| 模式 | 主文字最低对比 | 辅助文字最低对比 | 时间强调色最低对比 |
|---|---:|---:|---:|
| 浅色 | 11.24:1 | 5.14:1 | 5.07:1 |
| 深色 | 8.44:1 | 4.86:1 | 5.56:1 |

这是合成背景上的局部采样验证，不代表已经对所有真实桌面、字体设置或复杂壁纸进行了验收。

主代理最终执行：

```text
gradlew.bat :app:testDebugUnitTest :platform:android:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

结果：`BUILD SUCCESSFUL in 20s`。应用 7 项、平台 18 项报告均为 0 失败、0 错误、0 跳过；平台任务因输入未变为 UP-TO-DATE，应用测试在最终命令中执行。`git diff --check` 通过。

日志：`diagnostics/widget-glass-tests.log`、`diagnostics/widget-glass-final-verification.log`。

## 产物

- [Debug APK](G:/code/Android/iYango/app/build/outputs/apk/debug/app-debug.apk)，17,024,480 字节。
- SHA-256：`FFF70478C019DF28603AC12F8229331D18311CE0E6F23422F0F1D123641B0AEB`。
- [浅色合成预览](G:/code/Android/iYango/app/build/reports/widget-glass-preview/day.png)。
- [深色合成预览](G:/code/Android/iYango/app/build/reports/widget-glass-preview/night.png)。

预览使用真实布局资源及合成课程，三列为黑、白、洋红背景，四行为四种组件布局；不是手机截图。主代理已查看日夜两图，课程文字、边缘与日夜区别可见。

未安装或操作手机、未请求学校接口、未提交或推送；实际桌面更新后的观感待设备验收。既有成绩页 UI 修改方案仍仅为文档，未在本轮实施。
