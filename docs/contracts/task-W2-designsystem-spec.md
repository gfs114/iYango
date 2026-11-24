# W2 任务规格：core:designsystem iYango 主题与基础控件

任务编号：P1-06（设计系统部分）｜写码：GLM-5.3-Flash ｜审查：GLM-5.3

## 0. 硬性边界

- 只改 `core/designsystem/` 目录；build 文件已就绪，**不要修改**。
- 删除占位 `DesignSystemContract.kt`。
- 视觉基准：iYango 品牌主色 `#3E7BFA`（参考项目默认课程色），明暗两套；不承诺复刻鸿蒙系统材质（实施方案 11.4）。
- 注释中文。

## 1. 交付内容（包 com.gfs.iyango.core.designsystem）

### theme/Color.kt
Material3 `lightColorScheme` / `darkColorScheme`：primary=#3E7BFA（暗色用亮一档 #6B9BFF），secondary/tertiary、error、background/surface/onSurface 全套定义明暗两组。课程色板 CoursePalette：≥10 个颜色（含 #3E7BFA、#0A59F7、绿/橙/紫/青/粉等），`paletteColor(index)` 循环取色。

### theme/Type.kt
Material3 Typography 基于默认值微调：标题 20sp/600，正文 15sp，辅助 12sp。不做字体文件定制。

### theme/Theme.kt
`IYangoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content)` 组装 ColorScheme+Typography；`IYangoThemePreview` 供预览。

### tokens/Dimens.kt
间距 token（xs=4, s=8, m=12, l=16, xl=24）、卡片圆角 16dp、课程块圆角 8dp。

### component/
- `EmptyState(icon, title, hint, modifier)`：居中空态（图标+主文案+浅色提示），用于"没有课程/没有数据"。
- `ErrorState(title, detail, onRetry: (() -> Unit)?)`：错误态 + 可选重试按钮；**必须同时提供文字含义，不能只有颜色**（可访问性）。
- `LoadingState()`：居中 CircularProgressIndicator。
- `SectionCard(title, action: (@Composable () -> Unit)?, content)`：带标题与右侧动作槽的卡片容器。
- `StatusBadge(text)`：小圆角标签（用于"正在上课/即将开始"）。

## 2. 通过标准

- `./gradlew :core:designsystem:compileDebugKotlin` 通过（不需要跑设备）。
- 每个 Composable 提供 `@Preview`（明/暗至少各一个预览函数）。
- grep 无 `com.gfs.iyango.feature`、`androidx.room` 依赖。
