# iYango 应用动效实施计划

> 第 1–9 节保留设计时的代码盘点、方案与验收条件。任务框已按实施情况更新。尚未勾选的条目含有待验的设备或交互组合。2026-11-21 的变更及验证结果见[第 10 节](#10-实施记录)。

**Goal:** 为排序、课程状态、弹窗及页面内容变化补充一致的动效，保持操作及时、信息准确和玻璃界面的流畅度。

**Architecture:** 在设计系统建立统一的动效策略和参数，由应用根节点传入现有动画偏好。业务状态先更新，动画只表现变化；课程数据、导航所有权、生命周期与保存操作继续由原有模块负责。

**Tech Stack:** 项目现有 Jetpack Compose、Navigation Compose、Flow、DataStore、GlassSurface 与液态玻璃组件。第一、二批不新增动画库，不升级 Compose 或导航依赖。

2026-11-21 复核时，本地 `iYango_prelease` 的 HEAD 为 `e3ef1d3`。本计划以当前工作区为实施基线，`git status --short` 当时列出 79 条记录（未跟踪目录合并显示）。学分进度改动已涉及 `TodayScreen`、`IYangoMainApp`、`GradesScreen`，另有未跟踪的 `creditprogress/` 目录。动效实施前重新核对这些入口和 `SettingsBackupScreen` 的差异，在当时的工作区上运行基线测试，记录 HEAD、相关未提交差异、用例数和失败项。

## 1. 当前基础与实施范围

| 现有能力 | 当前实现 | 本计划处理方式 |
| --- | --- | --- |
| 主页签淡入 | `RetainedMainTabs` 已读取传入的开关，显示页淡入约 180 毫秒 | M0 验证淡入中途关闭时立即显示目标页，保留页签状态和生命周期 |
| 底栏显隐与颜色 | `DockAutoHide` 根据滚动更新显隐状态。`IYangoMainApp` 的 `AnimatedVisibility` 和 `animateColorAsState` 已读取开关 | M0 在调用方处理中途关闭 |
| 底栏选中、拖动与顶栏按压 | `LiquidBottomTabs` 使用 feature 内的 `DampedDragAnimation` 和 `InteractiveHighlight`，`GlassTopBar` 使用设计系统内的同名高光。两处均未读取开关 | M0 接入选中指示器、回弹与按压高光，拖动位置继续跟手 |
| 刷新过渡 | `RefreshNoticeCapsule` 的提示进出场、`SchedulePullTransition` 的 300 毫秒过渡均未读取开关 | M0 接入开关，保留提示展示时长和刷新完成判定 |
| 返回预览 | `MainBackNavigation` 跟随手势进度，取消与完成使用 180 毫秒过渡，未读取开关 | M0 接入松手后的过渡，M8 再协调页面转场 |
| 账号展开 | `SchoolAccountCards` 使用默认动画规格旋转箭头、展开或收起内容，未读取开关 | M0 接入开关，箭头与内容同步到达目标状态 |
| 首页课程卡片 | `TodayScreen` 中的 `HomeTimingCard` 将 `timing.progress` 直接传给 `LinearProgressIndicator` | M2 新增状态与进度动效，M5 为可打开详情的区域增加按压反馈 |
| 首页排序 | 有稳定标识、排序柄和上移/下移按钮；排序柄长按后拖动，每累计 64dp 调用一次上移/下移，卡片本身不跟手 | 改为跟手拖动并增加列表换位和落位效果（见 M1，属于交互改写） |
| 课程详情 | 独立 Dialog，支持多课程分页与后续操作 | 第一批只做弹窗自身进出场 |
| 二级页面 | 主 NavHost 的进入、退出、返回转场均设为 None | 保持到第三批再改 |
| 开发者模式 | 七次连点后显示型号、状态和日志，关闭后隐藏 | 增加显隐衔接，严格保留可见性规则 |

`AppPreferences.animationsEnabled` 目前控制主页签淡入、底栏显隐及背景色。M0 将“我的”页的“切换动画”改为“界面动画”，说明改为“控制页面过渡、卡片变化和弹窗动效”，沿用原字段和默认值。

`CourseDynamicIslandCard` 的两个调用点都在 `CourseDynamicIslandCard.kt` 的 `@Preview` 中。它的进度动画使用 450 毫秒的 `tween`，未读取应用开关。`GradeTermRangePicker` 和 `GradeTermInterval` 均未定义学期选择过渡。

不纳入本次路线：常驻流动背景、大面积模糊半径动画、全屏循环呼吸、倒计时逐秒翻牌、分数从零滚动计数、装饰性粒子、额外启动等待。原生按压反馈、用户拖拽、滚动惯性和返回手势保持可用。后续新增界面文案继续不用信息分隔中点。

## 2. 分批安排

| 批次 | 任务 | 交付范围 | 相对成本 |
| --- | --- | --- | --- |
| 第一批 | M0–M4 | 统一动效策略：首页排序、课程状态、课程详情、开发者模式 | 中至高（M1 含拖动交互改写） |
| 第二批 | M5–M7 | 快捷入口按压、成绩结果变化、加载/空状态衔接 | 低至中 |
| 第三批 | M8 | 二级页面进入/退出与返回手势协调 | 中至高 |
| 后续独立评估 | M9 | 课程卡片连续展开为详情 | 高 |

依赖顺序：先完成 M0，再逐项完成 M1–M4；第一批验收后再进入第二批。M8 必须在主容器和返回手势回归通过后开展。M9 包含承载层调整的单独决策，不属于第一批弹窗动效的附带任务。

每批可以独立验收。出现性能或交互回退时，回退该场景新增动效，保留业务功能；不通过删除既有课表、详情或返回能力规避问题。

## 3. 统一动效规则

### 3.1 开关与系统设置

应用开关关闭，或系统动画时长比例为零时，新增的非必要过渡直接到达最终状态。开关在动画执行中关闭时也立即结束过渡，不能等下一次进入页面才生效。

系统比例的处理分两部分：

- 时长缩放：Compose 动画在帧时钟协程中读取 MotionDurationScale，比例为零时自动直达终点，非零比例自动乘到时长上。业务层不再次乘时长，避免重复缩放。MotionDurationScale 只存在于协程上下文，组合阶段无法直接读取，不将其误当成 CompositionLocal。[官方时长控制说明](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale)
- 策略层在应用根节点可见期间用 `ContentObserver` 监听 `Settings.Global.ANIMATOR_DURATION_SCALE`。开始监听时读取当前值，收到变更时重新读取，结合 `ValueAnimator.areAnimatorsEnabled()` 更新是否播放过渡。根节点停止可见或销毁时注销，恢复可见时重新注册并读取。分屏期间把比例改为零也应结束进行中的过渡。[Android 系统动画比例](https://developer.android.com/reference/android/provider/Settings.Global#ANIMATOR_DURATION_SCALE)

应用开关与系统禁用任一成立，即视为"动画关闭"。

过渡中关闭开关时，即使目标值没有变化，也要检查显示结果。对仍在播放的 `Animatable` 可调用 `snapTo` 对齐目标值；保留退出内容的组件可直接切到最终布局。M0 用进行中的动画验证各入口。

“关闭动画”只取消程序控制的装饰性过渡。手指拖动位置与返回进度仍直接跟随手势；松手后可立即落到目标。原生按钮的按下状态、焦点和可访问性提示继续存在。加载指示必须继续表达“正在处理”，不能因关闭动画而变成空白。

动画偏好沿用现有原子更新入口，保存时保留首页布局、壁纸和开发者模式等其他字段。设置备份继续沿用 animationsEnabled，不增加备份格式版本。

### 3.2 初始参数

下表是实施起点，不是已验证的最佳参数。第一批真机统一调试后记录最终值，业务页面不各自散落魔法数字。

| 场景 | 初始参数 | 结束状态 |
| --- | --- | --- |
| 按下/松开 | 按下 100 毫秒到 0.98 倍；松开 160 毫秒恢复 | 正常尺寸；取消手势也恢复 |
| 内容交替 | 160–200 毫秒淡入淡出，必要时位移不超过 8dp | 最新业务内容 |
| 排序换位 | 约 220 毫秒；拖动项最多放大到 1.02 倍 | 保存前的当前草稿顺序 |
| 详情弹窗 | 进入 200 毫秒，0.96 倍到 1 倍；退出 160 毫秒 | 完整显示或窗口释放 |
| 展开/收起 | 约 200 毫秒；大型日志区只做一次外层变化 | 完整布局或完全移除 |
| 二级页面 | 进入 240 毫秒，退出 200 毫秒；短距离位移 | 导航目标唯一可交互 |

颜色变化不叠加闪烁。已有底栏弹簧参数先保留，不为了数值统一改掉其手感。提示胶囊的展示时长和动画时长分别处理，关闭动画仍能正常读到提示。

### 3.3 状态、手势与生命周期

动画不触发网络、数据库写入或课程重新解析。保存、删除、导航与刷新结果不等待装饰性动画完成；只有纯视觉关闭允许延后释放窗口，且应可取消。

同一元素的新状态打断旧动画，从当前视觉位置转向新目标，不排队播放过期状态。离开页面、切换主 Tab、应用进入后台时停止非必要动效，返回后先显示最新状态，不续播离开前的半段动画。

主 Tab 的 ViewModel、保存状态、输入阻断、可访问性和生命周期继续由 RetainedMainTabs 管理。不能把整个主页容器重新包进会销毁/重建页面的 AnimatedContent，也不能延迟隐藏页停止订阅，以免重新引入课表切首页闪退及后台计时问题。

## 4. 文件职责与改动边界

下列现有文件在评估时已核对；链接相对本文所在目录，代码格式路径相对仓库根目录。表中“规划新增”的文件尚不存在，本轮不会创建。

| 文件 | 计划职责 |
| --- | --- |
| [IYangoMainApp.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/IYangoMainApp.kt) | 提供全局动效策略，监听系统动画比例，处理底栏显隐和背景色过渡，第三批协调导航 |
| [RetainedMainTabs.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/RetainedMainTabs.kt) | 保持页面保活，接入策略并处理中途关闭 |
| [MainBackNavigation.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/MainBackNavigation.kt) | 手势跟随、取消和完成的统一控制 |
| [MineScreen.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/mine/MineScreen.kt)；[WallpaperViewModel.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/WallpaperViewModel.kt) | 更新可见文案和读屏名称，沿用现有偏好保存入口 |
| [SettingsBackupScreen.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/SettingsBackupScreen.kt) | 备份预览显示“界面动画开”或“界面动画关” |
| [HomeSettingsScreen.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/HomeSettingsScreen.kt) | 模块和快捷入口的拖动、换位、取消反馈 |
| [TodayScreen.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/TodayScreen.kt) | 模块显隐，`HomeTimingCard` 的状态切换、进度与按压，`QuickTiles` 的入口按压 |
| [CourseDetailDialog.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/coursedetail/CourseDetailDialog.kt) | 弹窗生命周期与纯视觉进出场 |
| [AboutScreen.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/about/AboutScreen.kt) | 开发者区域展开及立即撤销可见性 |
| [GradesScreen.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/grades/GradesScreen.kt)；[GradeStatisticsContent.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/grades/GradeStatisticsContent.kt) | 筛选结果与统计区域的局部过渡 |
| [RefreshNoticeCapsule.kt](../../../core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/RefreshNoticeCapsule.kt)；[SchedulePullTransition.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/SchedulePullTransition.kt) | 既有刷新效果统一接入策略，保持数据到达判定 |
| [SchoolAccountCards.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/school/SchoolAccountCards.kt) | 账号展开和箭头旋转接入策略 |
| [DockAutoHide.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/liquidglass/DockAutoHide.kt) | 提供滚动触发的显隐状态。动画由 IYangoMainApp 执行 |
| [LiquidBottomTabs.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/liquidglass/LiquidBottomTabs.kt)；[DampedDragAnimation.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/liquidglass/DampedDragAnimation.kt)；[feature/main 的 InteractiveHighlight.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/liquidglass/InteractiveHighlight.kt) | 底栏选中指示器、回弹和按压高光接入策略，拖动保持实时 |
| [GlassTopBar.kt](../../../core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/GlassTopBar.kt)；[core/designsystem 的 InteractiveHighlight.kt](../../../core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/InteractiveHighlight.kt) | 顶栏按压高光接入策略 |
| [GlassSurface.kt](../../../core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/GlassSurface.kt) | 复用现有绘制层能力和无壁纸轻量分支，不修改模糊算法 |
| `core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/motion/AppMotion.kt`，规划新增 | 只定义动效策略、共享参数和提供入口，不读取仓库或执行导航 |
| `core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/motion/MotionModifiers.kt`，第二批规划新增 | 复用轻量按压表现，与业务点击回调分离 |

核心模型、DataStore DTO、备份格式和课程时间计算原则上无需改动。若实施中发现仅为动画而需要修改这些层，应先复核职责，避免把视觉状态持久化。

## 5. 第一批详细任务

### M0：统一开关与现有动效

根节点提供 `AppMotion`，设置页和备份预览共用“界面动画”的叫法。现有动效按以下两组处理，文件位置见第 4 节。

- [x] 在 `feature/main/src/main` 和 `core/designsystem/src/main` 按完整 API 名复核，例如用 `\b(tween|spring)\s*\(` 区分动画调用和 `between(...)`。同时检查 `Animatable`、`animate*AsState`、`AnimatedVisibility` 的调用方，区分实际页面、预览、手势跟随和必要加载反馈。
- [x] **已读取开关**。在 `RetainedMainTabs` 的页签淡入、`IYangoMainApp` 的底栏显隐和背景色过渡中关闭开关，验证过渡能立即切到最终状态。对仍会继续播放的入口补上主动结束处理。
- [x] 为统一策略定义应用开启/关闭与系统比例 0、1、2 的行为，设置关闭中断时不得残留中间尺寸或透明度。
- [x] 在应用根层提供策略；偏好变更即时传播。保持动画参数在设计系统，业务组件只声明交互场景。
- [ ] **需新接入开关**。覆盖 `RefreshNoticeCapsule`、`SchedulePullTransition`、`SchoolAccountCards`、`MainBackNavigation` 的取消与完成、`LiquidBottomTabs` 及 `DampedDragAnimation`、底栏和顶栏各自的 `InteractiveHighlight`。逐项验证关闭时直接到达目标状态，保留手势跟随、返回回调及刷新完成判定。
- [ ] 同步更新 `MineScreen` 的显示文字、开关 `contentDescription` 及相关注释，更新 `IYangoMainApp` 中引用旧设置名的注释。`SettingsBackupScreen` 预览显示“界面动画开”或“界面动画关”。调整 `MineNavigationLayoutVisibilityTest` 的可见文字断言和 `MineOrganizationTest` 的读屏名称断言，验证与壁纸、首页布局、开发者模式并发保存时互不覆盖。
- [x] 验证主 Tab 切换后隐藏页不接收输入、不暴露语义、不重复注册接收器，返回前台只恢复当前页所需工作。

验收：关闭开关后没有新增的程序过渡，操作结果仍完整；重新开启无需重启；系统比例零立即到达最终状态；系统比例二不被重复放大为四倍。

### M1：首页排序、模块显隐与快捷入口排列

涉及文件：HomeSettingsScreen、TodayScreen。继续使用 HomeSettingsViewModel 的草稿与保存规则，不改变模块 ID、快捷入口上限和点击目标。

现状说明：HomeOrderRow 的排序柄使用长按后拖动，每累计 64dp 调用一次 onMove(±1)，卡片不随手指移动。本任务把它改为"跟手拖动"，属于交互改写而非单纯加动效，需同时调整手势处理和列表状态。

- [x] 为现有带稳定 key 的 LazyColumn 项增加位置变化表现（animateItem），模块与快捷入口分别维持原有标识。
- [x] 拖动保持"长按排序柄后开始"，其他区域不触发拖动；拖动项轻微抬起，并以绘制层偏移跟随手指，跨过邻项中线时更新草稿顺序，邻项通过位置动画换位。
- [x] 正在拖动的项不参与 animateItem 位置动画（或在拖动期间关闭其放置动画），由手指偏移单独决定位置，避免重排时被位置动画拉离手指；换位后按新旧位置差修正偏移，保证视觉位置不跳变。
- [x] 按上移/下移按钮产生与拖动松手相同的落位效果；读屏和键盘操作继续使用这两个按钮。
- [x] 处理快速反向拖动、边界、拖到列表可视区边缘时的自动滚动、松手、取消、切页与保存中状态；结束后清除偏移和抬起状态，实际顺序以草稿为准。
- [x] 预览中的隐藏项平滑移除，剩余项补位；恢复显示时仅该项出现，不重播整页入场。
- [x] 已保存设置回到首页时使用最终布局，不出现旧顺序闪回。列表滚出后再滚入不重复播放首屏动画。

验收：上下移动后视觉顺序、语义顺序和保存顺序一致；隐藏全部模块和零快捷入口仍正确；课程卡片点击目标不会随旧位置错配；未保存离开仍遵循原有确认流程。

### M2：课程状态切换与进度

在 `TodayScreen.kt` 的 `HomeTimingCard` 中实现，继续消费 TodayCourseTiming，不改变 TodayViewModel 的秒级、分钟级和日期数据流职责。

- [x] 以实际课程实例标识、日期和状态阶段确定内容转换。只在以下情况播放切换：同一门课从"即将开始"进入"正在上课"；当前课程结束，焦点转到下一门或进入"今日已无课程"；焦点课程因数据刷新、修改或删除而改变。同一状态下的倒计时与进度变化不算切换。
- [x] 每秒倒计时只更新文字与必要的进度，不能每秒重建或重播整张卡片。
- [x] 为 `LinearProgressIndicator` 新增进度动画并接入开关。同一课程实例内的小幅进度更新可平滑推进。关闭动画或切到另一课程实例时，直接显示该课程的真实进度。
- [x] 时间、状态、倒计时与详情入口立即依据最新投影；转场中的旧内容不得继续响应点击或重复向读屏播报。
- [x] 首次打开、前台恢复、跨午夜及手动修改时间时先对齐真实结果，不从零重演已经过去的上课进度。
- [x] 今日全部课程列表继续显示，不因焦点卡动画过滤已结束课程。保留当前起止时间文案。

验收：覆盖上课前一秒、开始时刻、结束时刻、重叠课程、时间未知、下一门在其他日期、跨午夜；在 `HomeCustomizationUiTest` 直接渲染 `HomeTimingCard`，推进时钟验证同课插值、换课对齐和中途关闭。不增加数据库读取、网络请求或后台秒级任务。

### M3：课程详情弹窗进出场

涉及文件：CourseDetailDialog 及打开详情的首页、课表调用处。保持独立 Dialog，保留现有调课、编辑、复制、删除和只读限制。

- [x] 核对现有系统窗口动效，选择一层负责缩放与淡入，避免窗口和内容叠加成两次缩放。
- [x] 将“业务选择的课程”和“纯视觉关闭阶段”分开处理。进入仅播放一次，多课程分页不重复打开整窗。
- [x] 纯关闭时立即禁用内部操作，再完成短退出并释放窗口；关闭动画时直接释放。
- [x] 编辑、复制、调课、删除确认等跳转立即移交给下一操作，必要时跳过装饰性退出，避免旧弹窗挡住新页面。
- [ ] 连续点击、系统返回、点击外部、切换 Tab 与进入后台分别覆盖；Tab 失活时直接撤掉窗口，不等待退出动画。
- [x] 课程数据在退出中被刷新或移除时保持安全快照或直接结束关闭，不能访问失效索引，也不能重复执行回调。

验收：每次操作回调只执行一次；关闭后没有透明窗口拦截触摸；维持顶部无“关闭”按钮的现有布局，底部四个操作仍同一行；长名称、冲突分页、历史只读和临时安排均正常。

### M4：开发者模式区域展开

涉及文件：AboutScreen；AboutViewModel 的七次连点、两秒间隔、保存失败处理及持久化逻辑保持不变。

- [x] 只有开发者状态成功开启后，手机型号、近期状态与诊断日志区域才进入展示；第六次点击和写入失败不能提前出现。
- [x] 手机型号采用轻微淡入，下面两块使用一次整体展开。日志多时不逐条交错入场，也不为动画额外读取日志。
- [x] 成功关闭时立即移除型号、日志、诊断操作及其语义/焦点，取消相关显示订阅；如需收起高度，只收缩已经清空内容的容器。
- [x] 禁止直接依赖 AnimatedVisibility 的退出保留阶段继续显示已关闭的开发者内容。
- [x] 重开页面时已开启的模式直接恢复完整内容；不反复重播“解锁”效果，不改变正常版本信息的七次点击区域。

验收：关闭动作后的第一帧中，型号和日志已经不可见、不可点击、不可被读屏访问；连续开关和保存失败时界面与实际设置一致。

## 6. 第二批详细任务

### M5：快捷入口与课程卡片按压反馈

在 `TodayScreen.kt` 的 `QuickTiles` 和 `HomeTimingCard` 接入 `MotionModifiers`。反馈跟随快捷入口和已有课程详情点击区域，保留无课程目标时的禁用状态。

- [x] 将按压表现和点击行为分离，复用一个交互源，不叠加第二个 clickable 或手势拦截层。
- [x] 只对可操作的快捷入口和课程卡片启用；按下、松开、滑动取消、禁用和页面移除均恢复正确视觉状态。
- [x] 排序中的卡片不同时播放按压缩小；已经有液态按压反馈的底栏不再包此效果。
- [x] 保持原有触摸区域、长按功能、键盘点击和读屏动作。关闭动画仍有即时按下状态。

验收：轻点只执行一次导航；手指滑出改为滚动后不误点击；快速连点不留下缩小或放大状态；卡片缩放不改变列表布局尺寸。

### M6：成绩筛选结果与统计变化

涉及文件：GradesScreen、GradeStatisticsContent。动效仅加在筛选结果与统计内容上。学期选择沿用现有交互，统计计算和学校数据优先级保持原样。

- [x] 筛选确认、选课范围或统计口径改变后，对结果区域做短淡入；操作中的选择状态立即反馈。
- [x] 分数、绩点和学分始终使用真实结果，不从零滚动到目标，也不出现中间伪造数值。
- [x] 快速切换条件时仅显示当前请求/选择对应的结果，过期结果不能因退出动画重新盖回页面。
- [x] 保持原有选择集合、列表滚动位置与筛选弹层关闭语义；原生 BottomSheet 不再叠加第二套整页滑入。

验收：跨学期切换、空结果、筛选取消、成绩刷新及连续选课时，统计与当前选择一致；动画不会触发额外同步或清空选择。

### M7：加载、空状态、错误与内容衔接

涉及文件：TodayScreen、GradesScreen、现有状态容器及 RefreshNoticeCapsule。必要时复用现有 LoadingState、EmptyState、ErrorState 的组合，不新增统一业务状态模型。

- [x] 首次加载到内容采用一次淡入；已有可用内容刷新时保留原列表与滚动位置。
- [x] 无数据、失败和重试之间使用短过渡，不通过人为延迟显示加载动画。
- [x] 状态切换后只保留最新可操作区域和唯一读屏提示，旧“重试”按钮不能在淡出中继续触发请求。
- [x] 使用现有加载反馈，第一版不加入全屏骨架闪光或循环背景动画；必要加载反馈随页面生命周期停止。

验收：瞬时完成的请求不被强行延长；连续失败/重试不叠加按钮和提示；离开页面后没有装饰性动画持续刷新。

## 7. 第三批与后续评估

### M8：二级页面转场

涉及文件：IYangoMainApp、MainBackNavigation、RetainedMainTabs、编辑页的既有返回处理及导航回归测试。

- [x] 区分主 Tab 切换、进入普通二级页、返回和有未保存内容的编辑页，建立路由转场表。
- [x] 普通二级页先采用短距离位移配合淡入；主 Tab 继续使用保活容器，不套用同一套页面销毁/创建动画。
- [x] 交互式返回期间只由现有手势进度驱动；完成后不再额外播放第二段完整返回动画，取消则回到原页。
- [x] 编辑页需要确认时，先恢复页面视觉位置再展示确认；取消离开仍停留原编辑页，确认离开只出栈一次。
- [ ] 过渡期间只允许目标交互层接收输入和读屏焦点，验证底栏玻璃采样及主页面显隐不出现空白帧。
- [ ] 对每种入口覆盖快速双击、返回取消、后台恢复、旋转/窗口尺寸变化和系统关闭动画。

验收：导航栈变化与无动画时一致；返回取消不会丢状态；没有点击穿透、重复出栈、残留窗口或隐形主页面收集任务。

### M9：课程卡片连续展开为详情

Compose 的共享元素机制需要共同的 SharedTransitionScope。当前详情使用独立 Dialog，不能把跨窗口效果按普通卡片进出场估算。[官方共享元素说明](https://developer.android.com/develop/ui/compose/animation/shared-elements)

原计划把 M9 列为单独实验。用户随后将其纳入本次交付。实现沿用原生 Dialog 的返回、遮罩、焦点、外部点击关闭、只读与分页行为，在详情窗口内绘制来源卡片与目标面板之间的连续变化。

实验必须覆盖课表滚动后的位置、重复课程、冲突分页、源卡片离屏和删除后的返回。找不到可用来源时退回 M3 的普通弹窗效果。无法保持现有交互或性能时继续使用 M3，不以此为理由改写整个导航结构。

## 8. 性能与玻璃效果约束

现有 GlassSurface 在无壁纸的内容卡片上有轻量绘制分支，有壁纸时仍涉及模糊。新增动效复用这些分支，不为移动、缩放重新创建壁纸、Shader、画刷或整页截图。

透明度、位移和缩放优先在绘制层读取动画值；排序和展开确需布局变化时限定影响范围，避免每帧重新计算整页业务内容。该选择依据 Compose 官方对组合、布局和绘制阶段的性能建议。[官方性能建议](https://developer.android.com/develop/ui/compose/animation/quick-guide#optimize-animation-performance)

不得同时给整页、列表和每张玻璃卡片叠加同类缩放。首页秒级计时与动画帧分离；一秒数据更新不能变成每帧查询。首屏入场不做长队列交错，列表重入视口不重播。

性能验证使用现有 Benchmark 构建类型，不能把 Debug 帧率当作最终结论。固定设备、刷新率、壁纸、数据和构建配置，比较改动前后相同场景；记录帧时长 P50/P95、超时帧占比、内存峰值与热状态。60Hz 和 120Hz 的单帧预算分别约为 16.7 和 8.3 毫秒，不能在 120Hz 下仍用 16.7 毫秒判断流畅。

每个关键场景预热后重复五轮。若帧时长 P95 或超时帧占比在至少三轮持续变差，应检查新增的重组、布局和离屏绘制；未解释并处理的回退不作为该批验收通过。冷启动、正常交互、持续手势和后台静置分别记录，不用一次截图证明流畅或低耗电。

先验证当前 HONOR ELP-AN00；另验证一台较低性能设备或模拟环境的功能降级，后者只证明兼容，不能替代真实设备性能结论。覆盖无壁纸/有壁纸、浅色/深色、短列表/长列表及系统动画关闭。

## 9. 测试与验收清单

测试针对用户可见行为、状态边界与生命周期，不为每个插值公式、颜色或时长常量增加镜像测试。优先扩展现有测试；参数手感由真机比较确认。

现有单元测试运行在 Robolectric 上，验证分层如下：

- 自动化测试负责：应用开关开/关、开关中途关闭后到达最终状态、回调次数、语义树可见性、导航栈结果。需要逐帧判断的场景（如开发者模式"关闭后第一帧"、弹窗退出期间禁用操作）使用 `mainClock.autoAdvance = false` 并手动推进帧时钟。
- 系统动画比例：自动化测试只验证策略在"系统禁用动画"输入下的分支选择（将判断结果作为可注入参数）；比例 0/1/2 的实际表现及"二倍不被放大为四倍"由真机开发者选项验证，同时覆盖分屏可见时改为零及后台修改后恢复。
- 只能在真机确认：Dialog 窗口动画与内容动画是否叠加、关闭后有无透明窗口拦截触摸、玻璃采样空白帧、手势跟手程度。

| 范围 | 测试输入与必须成立的结果 | 测试落点 |
| --- | --- | --- |
| 动效策略 | 应用关/开；系统禁用动画（注入）；中途关闭；最终状态与操作结果一致 | 规划新增 `core/designsystem/src/test/kotlin/com/gfs/iyango/core/designsystem/motion/AppMotionTest.kt` |
| 设置文案 | 开关文字和读屏名称均为“界面动画”，备份预览按保存值显示“界面动画开”或“界面动画关”，切换后偏好保存，其他字段不被覆盖 | 修改 [MineNavigationLayoutVisibilityTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/mine/MineNavigationLayoutVisibilityTest.kt)、[MineOrganizationTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/mine/MineOrganizationTest.kt) 中的文案断言，并核对备份预览的开、关两种状态 |
| 排序和显示 | 拖动/按钮换位、隐藏全部、零入口、取消离开；顺序与草稿一致 | 扩展 [HomeCustomizationUiTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/today/HomeCustomizationUiTest.kt)、[HomeSettingsViewModelTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/today/HomeSettingsViewModelTest.kt) |
| 课程状态 | 开始/结束边界、跨日、重叠、未知时间；计时不驱动整卡重播 | 扩展 `HomeCustomizationUiTest` 中直接渲染 `HomeTimingCard` 的用例，覆盖同课进度、换课及中途关闭，结合 [HomeCountdownTextTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/today/HomeCountdownTextTest.kt)，复用 [TodayCourseTimingTest](../../../core/domain/src/test/kotlin/com/gfs/iyango/core/domain/TodayCourseTimingTest.kt) |
| 页签生命周期 | 首页与课表反复切换、二级页面遮盖、后台恢复；接收器注册/释放平衡 | 复用 [TodayTabLifecycleTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/today/TodayTabLifecycleTest.kt)、[RetainedMainTabsTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/RetainedMainTabsTest.kt) |
| 课程弹窗 | 外部点击/返回/多次关闭/跳编辑/切 Tab；回调一次，无残留输入层 | 扩展 [CourseDetailOccurrenceTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/schedule/CourseDetailOccurrenceTest.kt)、[TermBrowseHeaderTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/schedule/TermBrowseHeaderTest.kt) 中的真实 Dialog 场景 |
| 开发者区域 | 第六/第七次、写入失败、关闭第一帧、重新进入；型号与日志受同一门控 | 扩展 [AboutDeveloperModeTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/about/AboutDeveloperModeTest.kt) |
| 成绩区域 | 快速切学期/筛选/选课、失败重试；结果与选择不被动画覆盖 | 复用 [GradesFiltersUiTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/grades/GradesFiltersUiTest.kt)、[GradeSelectionUiTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/grades/GradeSelectionUiTest.kt)、[GradesRefreshStateTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/grades/GradesRefreshStateTest.kt) |
| 导航 | 返回完成/取消、未保存确认、连续返回；导航栈和交互层唯一 | 扩展 [PredictiveBackTest](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/PredictiveBackTest.kt) 和上述 RetainedMainTabsTest |

以下 Gradle 命令以仓库根目录为工作目录，执行结果见第 10 节。

1. 设计系统和首页第一批回归：`.\gradlew.bat :core:designsystem:testDebugUnitTest :feature:main:testDebugUnitTest :app:assembleDebug --console=plain`。
2. 动画偏好保存与备份路径被修改时，补充 `.\gradlew.bat :data:testDebugUnitTest --console=plain`；只有状态展示变化不要求无意义重跑数据层。
3. 首批正式验收前覆盖模型、领域与集成：`.\gradlew.bat :core:model:test :core:domain:test :core:designsystem:testDebugUnitTest :data:testDebugUnitTest :feature:main:testDebugUnitTest :platform:android:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --console=plain`。
4. 性能记录使用 `.\gradlew.bat :app:assembleBenchmark --console=plain` 产物。构建成功与真机安装、帧率测量分别记录，不将其混为一个“通过”。

测试预期为零新增失败。出现既有失败时记录具体用例、同配置基线与可复现证据，不能省略失败后宣称整套全绿。自动化测试证明行为，真机记录证明视觉和交互，性能数据证明流畅度，三类证据分别保存。

## 10. 实施记录

2026-11-21，按用户选定的 M0–M9 范围在本地 `iYango_prelease` 实施。实际开发基线为 `ac87694` 加工作区已有的登录页、账号及会话改动。开始前该工作区通过 882 项测试及 Debug、Benchmark 构建。

| 范围 | 实际行为与代码入口 |
| --- | --- |
| M0 统一开关 | “我的”和备份预览使用“界面动画”。应用前台监听系统动画比例，回到前台时重新读取。系统比例为零或应用开关关闭时，程序过渡直接对齐目标。非零比例交给 Compose 处理。[系统设置监听](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/SystemMotion.kt) · [统一参数](../../../core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/motion/AppMotion.kt) |
| M1 首页排序 | 长按排序柄后卡片随手指移动，越过相邻项中线时更新草稿，拖至边缘可滚动。上下移按钮、预览和保存使用同一个顺序。[排序手势](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/HomeOrderDrag.kt) |
| M2、M5 首页课程与按压 | 同一门课的进度平滑前进，课程切换和时钟跳变直接对齐真实值。快捷入口和课程详情入口共用原点击区域的交互源。[首页](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/TodayScreen.kt) |
| M3、M9 课程详情 | 首页和课表登记点击卡片及标题的屏幕位置。原生 Dialog 负责遮罩、返回和焦点，在窗口内绘制连续变化的边界、圆角、颜色和标题。正文单独淡入。来源被遮挡、移出可视区或与当前课程不匹配时使用普通弹窗过渡。[来源定位](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/coursedetail/CourseDetailSource.kt) · [详情绘制](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/coursedetail/CourseDetailMotionWindow.kt) |
| M4 开发者区域 | 第七次点击且保存成功后，手机型号淡入，近期状态和诊断区域一起展开。关闭时立即移除内容、操作及 UI 订阅。已开启的页面重新打开时直接展示。[关于页](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/about/AboutScreen.kt) |
| M6、M7 成绩与加载 | 筛选后只绘制当前结果和真实统计值，查询期间保留已有成绩列表。首页与成绩页的加载、空状态和内容切换使用当前数据，旧操作随状态替换而移除。[成绩页](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/grades/GradesScreen.kt) |
| M8 二级导航 | 普通二级页使用短距离位移与淡入，主 Tab 沿用保活容器。离场页面屏蔽输入、焦点和读屏操作。编辑页保留原有未保存确认。[二级页面](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/AppPage.kt) · [返回处理](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/MainBackNavigation.kt) |

常用时长为按下 100 ms、释放 160 ms、局部内容 180 ms、区域展开 200 ms、排序换位 220 ms。详情进入和退出分别为 200 和 160 ms。登录页的页头、历史账号展开和提交按钮也使用同一开关，保留历史账号操作和自动重登选项。

| 导航入口 | 转场与返回 |
| --- | --- |
| 首页、课表、成绩、我的 | 保活页签只对目标页做 180 ms 淡入。目标页接收输入和读屏焦点。 |
| 普通二级页 | 新页短距离水平移动并淡入，240 ms 内就位。离场用 200 ms。连续点击同一目的地只入栈一次。 |
| 系统预测返回 | 页面的移动随系统手势进度。提交或取消沿同一个导航过渡收尾。 |
| 有未保存内容的编辑页 | 返回先恢复页面位置，再打开舍弃确认。继续编辑保留草稿，确认离开出栈一次。 |

### 验证记录

七个模块的最终自动化回归共 903 项，失败、错误和跳过均为 0。覆盖开关中途关闭、系统设置与有效动画比例异步更新、详情来源回收、翻页、重复操作、课程计时、隐藏页生命周期及导航返回。系统策略同时在 API 28 和 35 上验证。Debug、Benchmark 均构建成功，产物分别为 `app/build/outputs/apk/debug/app-debug.apk` 和 `app/build/outputs/apk/benchmark/app-benchmark.apk`。

Android 36 模拟器使用虚构课程验证了从首页焦点卡和滚动后的课程行打开对应详情、四个底部动作的显示、返回键和遮罩点击关闭。系统动画比例分别设为 0、2 和 1 后，三种情况下均可打开及关闭。竖屏和横屏暗色模式均验证了打开与返回。缩短屏幕后把首页第一项拖至第六项，随后分别验证了继续编辑与放弃更改。保存后返回首页，新的首项及快捷入口可以直接操作。

HONOR ELP-AN00 的改动前 Benchmark 样本使用系统动画比例 1。预热后，每轮从首页打开课表管理并返回三次，共五轮。五轮 P50 为 12、12、13、13、13 ms，P95 为 38、42、48、46、48 ms，系统报告的超时帧分别占 6.97%、7.54%、7.65%、7.11%、8.63%。热状态均为 1，三次采样中的最大进程 PSS 从首轮 188796 KiB 到末轮 272456 KiB。这组数字只描述改动前的同一路径。

模拟器另做了排序与详情的 Benchmark 对照。使用同一个 API 36 模拟器、60 Hz 软件渲染和同一批本地测试课程。基线来自单独编译的 `ac87694`。三个版本都预热一轮，每个场景再测五轮。每轮详情打开并关闭三次，排序上下拖回两次。P50、P95 取五轮中位数，超时占比按五轮总帧数计算。

| 场景与版本 | 总帧数 | P50 | P95 | 超时帧占比 | 最高进程 RSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| 详情，基线 | 403 | 46 ms | 89 ms | 94.54% | 182.3 MiB |
| 详情，首次实现 | 552 | 19 ms | 93 ms | 36.41% | 173.4 MiB |
| 详情，最终实现 | 577 | 18 ms | 89 ms | 28.77% | 169.4 MiB |
| 排序，基线 | 39 | 21 ms | 32 ms | 66.67% | 185.6 MiB |
| 排序，首次实现 | 383 | 25 ms | 38 ms | 17.49% | 175.7 MiB |
| 排序，最终实现 | 420 | 25 ms | 34 ms | 11.43% | 173.9 MiB |

首轮对比后，详情改为在来源布局、可见性或进退场发生变化时定位卡片。排序只为拖动行创建抬升动画，复用同一次列表测量的行位置。三版所有轮次的模拟器热状态均为 0。旧版排序每轮仅绘制 7–8 帧，新版逐帧跟手。最终排序 P95 有四轮高于对应基线，真实设备上的慢帧仍需复测。

荣耀手机在采完基线后断开 ADB。最终 Debug 和 Benchmark 已在模拟器安装。荣耀实机的新版安装、前后性能对照及壁纸玻璃效果仍待验证。
