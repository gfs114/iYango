# 桌面玻璃卡片通透度修正

日期：2026-11-01。用户反馈“透在哪？”。GPT 6.1 Sol 实现，主代理审查和验证。

## 原因与修正

上一版为追求任意纯色背景上的文字对比，把底色设为约 85%–90% 不透明，再叠加暗部，壁纸只贡献约一成颜色；纯色预览也无法证明真实图案透出。该视觉结果不符合用户要求。

本轮将渐变不透明度改为 `0x47 / 0x38 / 0x40`，分别约 27.8% / 22.0% / 25.1%，删除增厚暗层和对应色值，只保留半透明渐变与 1dp 高光边缘。正文区域约有 72%–78% 背景贡献，边缘高光不适用此数值。

在四份布局的 TextView 上使用统一 `WidgetTextHalo` 样式，只设置 shadowColor/dx/dy/radius：浅色模式浅色光晕，深色模式黑色阴影，半径 1.5px，偏移 0。用于改善与壁纸反差较低时的文字边缘；该轻微阴影也作用于实色文字，但实色背景保持原样。字号、文字内容和布局尺寸不变。

高通透背景不能保证文字在任意壁纸上都满足 4.5:1；不再通过加厚整个底色实现这一错误验收目标。阴影改善边缘辨认，不等于对所有壁纸作可读性保证。

## 验证方法变化

- 新的正文透明度测试先在上一版运行，日夜都因 alpha 超过 35% 失败，记录在 `diagnostics/widget-glass-transparency-red.log`。
- 实际 drawable 渲染后逐行检查正文 alpha 在 10%–35%，并检查同一像素叠加黑白背景后的红通道差保留至少 60%。这项是合成背景差异保留测试，不是文字对比或真实光学折射测试。
- 继续保留四种 RemoteViews 布局、实色/玻璃、日夜 apply/reapply 测试。
- 预览改为四列：明亮图案原图/组件覆盖、深色图案原图/组件覆盖。使用真实 XML 资源与合成课程，四布局均展示；主代理已查看日夜两张图片，圆形、渐变和色带能明显透出。
- 明暗模式与壁纸明暗相反时，文字仍可能偏弱；轻微文字阴影已在实际资源预览中检查，没有再提高整体底色不透明度。

## 最终检查与构建

主代理执行：

```text
gradlew.bat :app:testDebugUnitTest :platform:android:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

`BUILD SUCCESSFUL in 12s`。应用 7 项、平台 18 项测试报告均为 0 失败、0 错误、0 跳过；平台输入未变，任务复用 UP-TO-DATE 结果。应用测试本轮执行。`git diff --check` 通过。

日志：`diagnostics/widget-glass-shadow-tests.log`、`diagnostics/widget-glass-transparency-final-verification.log`。

- [APK](G:/code/Android/iYango/app/build/outputs/apk/debug/app-debug.apk)：17,024,564 字节。
- SHA-256：`92951ECFD4B3B1CFF4EF7B21DDED8E432186BD2D4858035C9A2574124027F3C4`。
- [浅色图案合成预览](G:/code/Android/iYango/app/build/reports/widget-glass-preview/day.png)。
- [深色图案合成预览](G:/code/Android/iYango/app/build/reports/widget-glass-preview/night.png)。

预览不是手机截图。本轮没有操作设备、请求学校接口、提交或推送。实际桌面效果仍待设备验收；未改变组件配置存储、课程查询或点击跳转。
