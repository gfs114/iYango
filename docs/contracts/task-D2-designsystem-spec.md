# D2 任务规格：designsystem 扩展（主题色系统 + 课程色板 + 区块头）

任务：依据 G:\code\Android\iYango\docs\contracts\ui-baseline.md 第 1 节，扩展 core:designsystem。**只改 core/designsystem/src/**，不改其他任何文件。

## 交付 1：theme/AccentTheme.kt（API 逐字如下，调用方依赖此签名）

```kotlin
package com.gfs.iyango.core.designsystem.theme

enum class AccentPreset(val label: String, val light: String, val dark: String) {
    CLASSIC("经典橙", "#C2410C", "#F08C4A"),
    AZURE("远峰蓝", "#2563EB", "#5B9BFF"),
    FOREST("松石绿", "#0F8A5F", "#3DD68C"),
    VIOLET("暮山紫", "#6D4AE0", "#A78BFA"),
    SAKURA("樱语粉", "#DB2777", "#F472B6"),
    LAKE("湖光青", "#0E8FA8", "#2AC9DB");
}

data class BrandColors(val accent: Color, val onBrand: Color, val brandSoft: Color)

val LocalBrand: ProvidableCompositionLocal<BrandColors>
```

- `IYangoTheme` 修改签名：`IYangoTheme(darkTheme: Boolean = isSystemInDarkTheme(), accentHex: String? = null, tintBackground: Boolean = false, content: @Composable () -> Unit)`。
  - accentHex 非 null（#RRGGBB）时用它；否则按 darkTheme 取 CLASSIC 的 light/dark。
  - BrandColors 派生：onBrand 用 YIQ（0.2126R+0.7152G+0.0722B，0-255 尺度）≥150 → 黑墨（浅色模式 #182431 / 深色 #0D0D0D）否则白 #FFFFFF；brandSoft = accent.copy(alpha = 0.15f)。
  - tintBackground=true 时 MaterialTheme background = accent 与基底（浅 #F2F1F6 / 深 #0D0D0D）按 8% 混合；ColorScheme.surface/surfaceAlt/text*/error 等语义槽位按 baseline §1.2 的浅深表显式赋值。
  - `LocalBrand` 由 CompositionLocalProvider 提供；另加 `@Composable rememberBrand(): BrandColors = LocalBrand.current` 便捷函数。
- 原 `IYangoThemePreview` 保留并适配新签名（默认参数即可）。现有预览函数不改名不删除。

## 交付 2：theme/CourseSwatch.kt

```kotlin
data class CourseSwatchColors(val fill: Color, val tint: Color, val ink: Color, val inkSoft: Color)
object CourseSwatch {
    val accents: List<String>  // baseline §1.4 的 12 个 #RRGGBB，顺序一致
    fun resolve(accentHex: String, dark: Boolean): CourseSwatchColors
    fun forCourse(color: String?, courseId: Long, dark: Boolean): CourseSwatchColors
}
```
- 12 色 BLUE #3E7BFA、GREEN #2BB673、AMBER #F2A03D、RED #F2634E、PURPLE #8B6BF0、TEAL #2BB2B2、PINK #EE5A96、GREY #7A8794、INDIGO #5E6AD2、CYAN #2E9FD9、OLIVE #7C9B3F、BROWN #B08050。
- 每色四层明暗自拟（要求：tint=柔和洗色底、ink=同色系深墨字、浅色对白底可读、深色对深底可读；BLUE 参考值 tint #C9DCFE/tintDark #1B3358/ink #12325F/inkDark #D6E4FF，其余按同明度关系推导）。
- `forCourse`：color 非 null 且匹配 #RRGGBB → resolve(color)；否则 accents[(courseId.floorMod(12))]。

## 交付 3：component/SectionHeader.kt

```kotlin
@Composable fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null)
```
- 3dp×16dp 圆角 2dp accent 竖条（LocalBrand.accent）+ 标题 16sp Bold + 右侧 trailing 槽；高度对齐 24dp。

## 通过标准
- `./gradlew :core:designsystem:compileDebugKotlin` 通过；明暗 @Preview 各 ≥2（accent 预设切换预览 + CourseSwatch 色板预览）。
- 最终回复：文件清单、API 与规格一致性自检结果。
