# 安卓课表应用背景图（壁纸）功能仓库调研

> 2026-11-21 搜索整理。目的：为 iYango 的壁纸/玻璃背景方向收集开源参考实现。搜索方式：GitHub Search API，12 组中英文关键词（课程表/课表 × 壁纸/背景/wallpaper/android 等），62 个去重仓库，其中 57 个安卓项目逐个检查了 Releases 是否含 .apk，12 个重点仓库进一步核对了 README 与源码文件树。仓库描述随时间变化，以仓库当前 README 为准。

## 1. 明确带背景图功能的安卓课表仓库

| 仓库 | ★ | 技术栈 | APK | 背景功能 |
| --- | --- | --- | --- | --- |
| [xiaomanjun233/SleepDown-Schedule](https://github.com/xiaomanjun233/SleepDown-Schedule) | 114 | Kotlin + Compose + Miuix（底层 `kyant0/backdrop` + 自研纹理金字塔） | 有 | 首页壁纸横竖屏独立取景，卡片透明度/模糊/玻璃可调，壁纸取色 |
| [Mutx163/mikcb](https://github.com/Mutx163/mikcb)（轻屿课表） | 62 | Kotlin + Compose + miuix-blur | 有 | 自定义壁纸位置拖拽微调、历史壁纸缓存；Android 13+ RuntimeShader 背景捕获+模糊+染色+边缘高光，不支持时降级实体卡片 |
| [HF-CYGG/Dawn-Course](https://github.com/HF-CYGG/Dawn-Course) | 49 | Kotlin 多模块 + Glance | 有 | 动态壁纸背景：高斯模糊 + 亮度调节 |
| [IMSX3D/Classy](https://github.com/IMSX3D/Classy) | 24 | Kotlin + Compose（glasense-ui 玻璃库） | 有 | 相册背景图 + 浓淡/模糊/色彩浓度/裁切位置；主题可跟随系统壁纸取色（Android 12+） |
| [MrZhencn/ntu-schedule](https://github.com/MrZhencn/ntu-schedule)（通大课表） | 1 | Kotlin + Compose | 有 | 全局背景：默认/8 纯色/6 渐变/相册选图，拖拽取景，0–80% 遮罩；顶栏底栏半透明 |
| [JS-CAUTION/csust-course-schedule](https://github.com/JS-CAUTION/csust-course-schedule)（千纸课） | 5 | Flutter | 有 | 「弥散圆形」与「背景图片」双模式多存档；9:19.5 取景窗、双指缩放平移 |
| [tomypeng2007/hybrid-schedule-app](https://github.com/tomypeng2007/hybrid-schedule-app) | 0 | Kotlin 2.1 | 无发布 | 四种背景模式：动态壁纸模糊 / 毛玻璃 / 自定义图片 / 纯色 |
| [hwanghj09/SchoolLauncher](https://github.com/hwanghj09/SchoolLauncher) | 0 | Java | 无发布 | 反向思路：把课表渲染成**系统壁纸**（WallpaperApplier + 每日定时） |
| [zfman/TimetableView](https://github.com/zfman/TimetableView) | 719 | Java 控件库 | — | 控件属性级「可设置背景以及透明度」，非完整应用 |

## 2. 其他高星安卓课表仓库（仅传统 View 背景、无自定义壁纸或未核源码）

[BenderBlog/traintime_pda](https://github.com/BenderBlog/traintime_pda)（XDYou，Flutter，★350，APK 有，未逐一核源码）、[xxyangyoulin/ClassSchedule](https://github.com/xxyangyoulin/ClassSchedule)（Java，★163，APK 有，传统 View 体系含 `BackgroundView`/`ColorImageView` 静态背景图）、[HITSchedule/HITSchedule](https://github.com/HITSchedule/HITSchedule)（★104）、[lingion/sleepy](https://github.com/lingion/sleepy)（★91，液态玻璃风格但无自定义壁纸背景）、[cakeni/CourseSchedule](https://github.com/cakeni/CourseSchedule)（晴课表，★82，已确认无自定义壁纸）、[itning/YunShuClassSchedule](https://github.com/itning/YunShuClassSchedule)（★51）、[yuchenii/HHUClassSchedule](https://github.com/yuchenii/HHUClassSchedule)（★48）、[qiqqqqq517/shangkeschedule](https://github.com/qiqqqqq517/shangkeschedule)（★39）、[Potato-DiGua/Timetable](https://github.com/Potato-DiGua/Timetable)（★21）。

桌面/网页端课表壁纸（非 APK，思路参考）：[clansty/ClassTools](https://github.com/clansty/ClassTools)（★156）、[Candlest/ClassBoard](https://github.com/Candlest/ClassBoard)（★24）、[inori728/courseflow](https://github.com/inori728/courseflow)、[charon153/dayline-wallpaper](https://github.com/charon153/dayline-wallpaper)、[564988749615/classroom-screen](https://github.com/564988749615/classroom-screen)、[wky81222/Classboard](https://github.com/wky81222/Classboard)、[ZZ0YY/Classroom-wallpaper](https://github.com/ZZ0YY/Classroom-wallpaper)。

## 3. 重点仓库壁纸实现要点（README + 源码树核对）

### SleepDown-Schedule（与 iYango 方向最接近，且同用 `kyant0/backdrop`）
- 底层玻璃库与 iYango 同样基于 `io.github.kyant0:backdrop`，壁纸模块集中在 `app/src/main/java/.../core/wallpaper/`：
  - `WallpaperImageStore.kt` 存储、`WallpaperImageOrientation.kt` 方向处理
  - `WallpaperCropMath.kt` + `FocusCroppedWallpaper.kt`：横竖屏独立取景（焦点裁剪）
  - `WallpaperReadability.kt`：壁纸前景可读性适配（与 iYango 液态玻璃规范的可读性目标一致）
  - `WallpaperTexturePyramid.kt`：多级纹理金字塔，避免每帧重采样原图（性能思路）
- `feature/home/`：`WallpaperEditorOverlay.kt` 编辑层、`WallpaperColorSamplerScreen.kt` 与 `WallpaperPaletteSampler.kt` 壁纸取色生成配色
- `core/glass/` 独立液态玻璃框架目录；README 明确“针对壁纸背景下的可读性、动画连续性专门适配”
- 壁纸保留原图，横竖屏取景参数以无损坐标持久化在 Room（对比：iYango 当前在 `AndroidWallpaperStore.commit` 中按选框破坏性裁剪落盘为静态 JPEG，DataStore `AppPreferences` 只存文件路径与遮罩/模糊参数）

### mikcb 轻屿课表
- `pages/shell/HomeWallpaperBackdrop.kt`、`HomeWallpaperColorStore.kt`：壁纸底衬与取色缓存
- `pages/settings/WallpaperPositionPickerPage.kt`、`WallpaperSheetBlocks/Body.kt`、`RecentWallpaperStrip.kt`：壁纸位置微调与历史壁纸管理 UI
- 渲染走 miuix-blur 的 RuntimeShader 效果链（背景捕获 → 模糊 → 染色 → 边缘高光），明确“不是截图缩放的假玻璃”；不支持时自动降级实体卡片

### IMSX3D/Classy
- `data/BackgroundAdjustments.kt`（浓淡/模糊/色彩浓度/裁切参数模型）、`BackgroundSoftening.kt`（柔化）、`ScheduleBackgroundStore.kt`（持久化）
- `ui/component/ScheduleBackground.kt` 渲染；有 `docs/design/CUSTOM_BACKGROUND.md` 设计文档与 androidTest 覆盖
- 主题可跟随系统壁纸取色（Android 12+ Material You）

### MrZhencn/ntu-schedule
- `ui/AppBackground.kt`（全局背景渲染）、`ui/BackgroundSettingsDialog.kt`、`ui/ImageCropDialog.kt` + `core/ImageCrop.kt`（拖拽取景，有单测）
- 纯色/渐变/相册三类来源 + 0–80% 黑遮罩防压暗课程内容；界面元素半透明让背景透出

### JS-CAUTION/csust-course-schedule（Flutter）
- `lib/models/background_preset.dart` + `lib/providers/background_provider.dart`：背景存档（多预设可切换）
- `lib/widgets/diffuse_background.dart`：弥散光斑背景（非图片方案）
- `lib/screens/image_edit_screen.dart`：9:19.5 取景窗、双指缩放平移、空图防呆

### tomypeng2007/hybrid-schedule-app
- `background/manager/BackgroundManager.kt`、`background/effect/BackgroundEffect.kt`、`background/processor/ImageProcessor.kt`、`domain/model/BackgroundMode.kt`
- 四模式抽象（动态壁纸模糊/毛玻璃/自定义图片/纯色）值得借鉴为枚举化背景策略

### Dawn-Course
- `feature/timetable/TimetableBackground.kt`：动态壁纸 + 高斯模糊 + 亮度调节，参数简单直接

### hwanghj09/SchoolLauncher（反向思路）
- `WallpaperApplier.java`、`WallpaperImageRenderer.java`、`DailyWallpaperWorker.java`、`WallpaperScheduler.java`：把课表渲染为系统级壁纸并每日更新，适合“锁屏/桌面看课表”场景

## 4. 对 iYango 的参考要点

1. **取景与视觉调节**：iYango 现有 `WallpaperCropDialog`（窗口比例取景）以及 `WallpaperScreen` 的 `ScrimSection`（0–80% 可读性遮罩，导入时经 `WallpaperTone.recommendedScrim` 自动给初值）和 `BlurSection`（0–100% 映射 0–24dp `Modifier.blur`，Android 12+ 生效）。对照 Classy 的「浓淡/模糊/色彩浓度」与 SleepDown 的无损取景，iYango 可评估补齐**色彩浓度/亮度调节**、**取景/调参时的课程卡实时预览**，以及**保留原图的横竖屏无损取景**。
2. **性能与课表页玻璃化**：
   - iYango 的 `GlassSurface` 在无壁纸时已有 `CONTENT` 轻量 `Surface` 分支，但**有壁纸时**每张可见 `CONTENT` 卡片仍会走 `drawBackdrop`（每帧 `RenderEffect` 模糊 L0 壁纸层）；
   - iYango 课表网格 `ScheduleWeekGrid` 目前在有壁纸时使用 `alpha = 0.86f` 的整块 `Surface` 底板 + 半透明纯色 `CourseBlockView`（尚未接入逐卡玻璃采样）。SleepDown 的 `WallpaperTexturePyramid` + `CourseTextureRenderer` 证明：针对仅采样静态壁纸的卡片，可用预构建多级模糊纹理 + 查表插值替代每帧 `RenderEffect`，既避免列表/网格逐卡离屏模糊开销，也能去掉遮挡壁纸的 86% 整块底板。
3. **可读性**：SleepDown 的 `WallpaperReadability`（按取景矩形对原图做 7×5 网格采样）与 ntu 的遮罩都针对“背景别压过内容”，与 iYango 液态玻璃规范（`docs/plans/2026-10-27-liquid-glass-full-adaptation.md` §3.2 及 `GlassTokens.kt`：正文对比度 ≥ 4.5:1、关键控件 ≥ 3:1，用底色而非增大文字阴影保证可读）目标一致。iYango 当前 `WallpaperTone.averageLuminance` 是在 `prepare` 阶段对**裁剪前整图**算全局均值，后续可改为对**裁剪后取景区域**（或分块网格）采样。
4. **多预设存档**：千纸课的背景存档（弥散/图片多套切换）与 mikcb 的历史壁纸条（`RecentWallpaperStrip`）是 iYango 目前没有的形态，可评估是否引入历史壁纸快速切换。
5. **反向场景**：SchoolLauncher 的“课表即系统壁纸”与 iYango 小组件方向互补，仅作思路记录。

## 5. 源码深挖（2026-11-21 第二轮，已核对实际源码）

### 5.1 SleepDown-Schedule 的壁纸玻璃管线（kyant0/backdrop + 自研金字塔）

核心思路：**静态壁纸模糊不走每帧 RenderEffect，而是"位图加载期内存建金字塔、绘制时查表插值"**。

1. **`WallpaperTexturePyramid`**（`core/wallpaper/WallpaperTexturePyramid.kt`，58 行）
   - 壁纸 bitmap 加载/更新时在内存中一次性构建 ≤7 级金字塔（非导入落盘，约占原图 ~1.33× 内存）：每级 = 双线性半尺寸缩小 + 3 次半径 1 的 box blur（`blurWallpaperBitmapInPlace`），协程中 `ensureActive()` 可取消，全部在 `Dispatchers.Default` 完成（`CourseMaterialSampling.kt` 的 `snapshotFlow { pixels.value?.bitmap }.collectLatest { … }`）。
   - 方差累加：`variance += 0.25f * priorScale² + 2f * scale²`，每级记录 `sigma = sqrt(variance)`。
   - 任意目标模糊度 = 相邻两级按**方差空间**线性插值：`mix = (σ_req² − σ_low²) / (σ_high² − σ_low²)`。裁剪、亮度、卡片移动都不重建像素；原图保持全分辨率供零模糊/折射使用。
2. **`CourseTextureRenderer`**（`glass/CourseTextureRenderer.kt`）
   - 每张课程卡记录"不可变 shader 绘制"，**不是** RenderEffect 或 GraphicsLayer 采样——注释明确 "Every card records an immutable shader draw… never creates a temporary per-card blur"。
   - 卡片→壁纸源的坐标关系用 `LayoutCoordinates.transformFrom` 矩阵 + 裁剪偏移折算成 AGSL 仿射行向量（`rowX`/`rowY`）。
   - HWUI 模糊半径→高斯 σ 换算：HWUI `Blur::convertRadiusToSigma` 本身为 `radiusPx > 0 ? radiusPx * 0.57735f + 0.5f : 0f`（`0.57735 ≈ 1/√3`），乘以屏幕像素到壁纸位图像素的仿射缩放系数 `sqrt(kx * ky)`（其中 `kx`、`ky` 分别为 `rowX`、`rowY` 的模长）后得到位图空间下的采样 `sigma`，与系统 `RenderEffect` 语义对齐。
   - AGSL `TextureSamplingShader`：`textureA`/`textureB` 两个 `BitmapShader`（CLAMP + LINEAR）按 `blend` 混合，再套 dim（压暗+alpha 收敛）、brightness、saturation（`dot(rgb, half3(0.213, 0.715, 0.072))` 亮度混合，vibrancy 时 1.5）。
   - 边缘折射复用 kyant0/backdrop 的 `textureLensShaderSource`：传圆角四角半径、refractionHeight/Amount、色散（chromaticAberration 用第二份 shader 实例缓存）。
   - 金字塔未就绪或 API < 33 时回退到共享 `MaterialBackdrop`（`RenderEffect` 路径，需 API 31+；API < 31 无 `RenderEffect` 则进一步降级），卡片侧 `drawPreparedCourseBackdrop` 直接采样已过滤源。
3. **`WallpaperReadability`**：通过 CompositionLocal 下发 `HomeReadabilityContext`，需要时可对**原图**按取景矩形映射做 7×5 网格取色（乘亮度钳制 0.35–1），供内容配色决策。
4. 另有 `glass/ui/ProgressiveBlur.kt`（渐进模糊）与 COUI 扩展（`com.itos.coui`，ColorOS 风格等高线/焦散程序）。

### 5.2 mikcb 轻屿课表：miuix-blur 的玻璃配方与工程教训

**渲染契约（`QingyuGlassSurface.kt`，276 行）**——"全站玻璃面的唯一渲染实现"：
- 除该文件外任何组件不得直接调用 `drawBackdrop`/`blur`/`lens`/`vibrancy`；调用方只给三样：`spec`（`GlassSpecResolver.resolve` 产物，blurSigmaDp/refractionBandDp/refractionAmountDp/dispersion/vibrancy）、`backdrop`（**壁纸 + 页面内容的合成层**——"只录内容不含壁纸会在静止时糊空气，只录壁纸则折射看不出"）、形状几何。
- 效果链顺序：先抬采样 padding（lens 会朝形状外取像素，玻璃坞固定 40dp）→ `blur()` 或 `progressiveTextureBlurEffect()` → `lens(refractionHeight, refractionAmount, chromaticAberration)` → 可选 `runtimeShaderEffect`。
- 自研 `QingyuVerticalFade` AGSL：给**模糊本身**按 y 加权（顶 1→底 0）。注释记录了两个真实坑：① AGSL `coord` 原点在图层左上角，权重写反会出现"渐变模糊长得奇怪"；② 模糊层比玻璃面大一圈 kernel reach padding，且降采样图层中的**像素坐标/长度类 uniform** 必须除以 `downscaleFactor`，否则渐变位置整体偏移。
- P0 硬保护：NaN/±Inf/非法负值进底层 shader/RenderEffect 可能触发 **RenderThread 原生崩溃（SIGSEGV/SIGABRT）** 而非普通 Java 异常，统一 `safe()` 钳制，采样 padding 上限 64dp；`runtimeShaderEffect` 全部 `runCatching` 包裹失败静默回退均匀模糊。
- 降级路径：设备不支持 RuntimeShader 时调用方不传 backdrop，直接画压色 + 描边。

**miuix-blur 库本身**（mikcb 仓库内 vendor 文档 `docs/reference/miuix-demo/docs/zh_CN/guide/blur.md`）：
- 三步法：`rememberLayerBackdrop()` → 内容容器 `Modifier.layerBackdrop(backdrop)` → 玻璃面 `textureBlur(...)`；透明区域会"糊出色块"，需在 backdrop 里先 `drawRect(背景色)`。
- effects 链原语：`blur(radius, edgeTreatment)`、`colorControls(brightness/contrast/saturation)`、40+ 混合模式（含 Lab 系自定义）、`runtimeShaderEffect(key, shaderString, …)`、`noiseDither`（抗条纹抖动，默认 0.0045，渐进模糊默认关）。
- `ProgressiveBlur`：两点线性渐变描述满强度→零区间（angle/startFraction/endFraction/curve 幂次）；性能注记——清晰端每帧要额外一个全分辨率 pass，只适合栏/边缘带，不适合大面积。
- 边缘高光 `Highlight`（BloomStroke）：两路半球定向光（参考原点 UV (0.5, 0.7)），`rememberTiltLight` 用旋转传感器做视差；暗色模式光晕更薄（innerBlurRadius 1.7–3.5dp 六档预设）。
- `BgEffectPainter.kt`（miuix demo 的动态背景）：HyperOS 风格网格渐变——4 个 `sin/cos` 漂移光点、4 色循环插值（16 float uniform）、纵横比自适应 `uBound` 区域，OS2/OS3 两套 AGSL。

**工程教训（mikcb 仓库内 `docs/reference/miuix-demo/我们的差距与修法.md`，对 miuix 官方 demo 的对照复盘）**：
- "官方每个组件都给了正确配方，我们只抄了壳没抄配方"——典型问题包括：字阶脱离刻度、可点卡片漏开按压反馈、计划文档的 API 断言未核源码、半迁移死代码误导后人。
- 可借鉴的工程纪律：任何"官方没有 X"的断言必须贴源码出处；结构性改动不许用正则批量替换嵌套层级；材质参数真相源只能有一份。

### 5.3 对 iYango 的可迁移结论

| 结论 | 依据 | 对 iYango 的动作建议 |
| --- | --- | --- |
| 静态壁纸模糊可"查表"替代"每帧算" | SleepDown 金字塔：位图加载期一次构建、绘制时两级方差空间插值 | **仅适用于只采样 L0 静态壁纸的组件**（有壁纸时的 `GlassMaterial.CONTENT` 卡片、`ScheduleWeekGrid` 课程块或 `WallpaperLayer` 自身，且可顺带补齐 Android 8–11 的壁纸模糊）；采样 `L0 + L1` 动态滚动内容的顶栏/底栏/浮层仍需实时 `RenderEffect` |
| σ 与模糊半径有固定换算 | HWUI `radiusPx > 0 ? radiusPx * 0.57735f + 0.5f : 0f`；映射到壁纸位图坐标时再乘 `sqrt(kx * ky)` | 若做自绘模糊或金字塔查表，沿用该公式并保留 `radiusPx <= 0 -> 0` 边界，与系统 `RenderEffect` 视觉密度对齐 |
| 采样源分层：导航/浮层采 `L0+L1`，内容卡只采 `L0` | mikcb 强调导航需"壁纸+内容"合成；iYango 统一规范 §4.1 要求杜绝自采样闭环 | iYango 已在 `IYangoMainApp`（Dock）和 `GlassScaffold`（顶栏/overlay）合成 `L0 + L1`，而 `CONTENT` 卡片只采 `L0` 并关闭折射；应保持该单向分层，不把正文内容反向混入 `L0` |
| shader uniform 防 NaN/Inf，降采样层像素坐标需除以 `downscaleFactor` | mikcb 两个真实事故（RenderThread 崩溃、降采样层渐变错位） | 若引入自定义 AGSL：所有 float uniform 统一做 `isFinite` 与范围钳制；仅对运行在降采样纹理上的**像素坐标/长度类 uniform** 除以 `downscaleFactor` |
| API 分级降级保持三档（33+ / 31–32 / <31） | SleepDown `SDK_INT >= 33` 门控 + `RenderEffect`（31+）回退；mikcb `runCatching` 兜底 | iYango 已有 `GlassTier` 三档（`FULL` 33+ / `BLUR` 31–32 / `BASIC` <31）及无壁纸轻量分支；若新增自定义 AGSL，应挂在 `FULL` 档并用 `runCatching` 失败回退 `BLUR` 档，保留 Android 12 模糊而非直接退化为纯压色 |
| 可读性采样应基于裁剪后区域/网格而非裁剪前整图 | SleepDown `WallpaperReadability` 按取景矩形 7×5 网格采样 + 亮度钳制 | iYango 液态玻璃规范明确 App 内靠底色而非增大文字阴影保障可读（文字阴影仅用于桌面小组件）；可将现有 `WallpaperTone` 从 `prepare` 阶段的裁剪前整图均值，升级为对**裁剪后取景区域**（或局部网格）采样 |
| 保持 spec 单一真相源，收敛裸 `drawBackdrop` 调用 | mikcb "唯一渲染实现 + spec 驱动" | iYango 已有 `GlassTokens.spec(material)`（`GlassMaterialSpec`）集中参数层，但 `LiquidBottomTabs.kt` 仍有 3 处直接调用 `Modifier.drawBackdrop`；后续可评估收敛或明确登记为导航特例 |

## 6. 检索局限

- GitHub 搜索只覆盖描述/名称/README 命中的仓库；纯代码内实现而 README 不提背景的仓库会漏（如晴课表确认无此功能，XDYou 未逐一核源码）。
- “有 APK” 指最近 3 个 Release 含 .apk 资产；CI 构建产物或 tag 附件未统计。
- 星数与更新时间为 2026-11-21 快照。
