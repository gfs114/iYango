# iYango Android 全项目液态玻璃适配需求与实施方案

- 日期：2026-10-27。
- 项目：G:/code/Android/iYango。
- 状态：**全量代码适配已实施（2026-11-21：核心设计系统控件与弹层套件 + P00–P21 页面 + WidgetConfigActivity 全部代码迁移完成，全模块单测 / Debug APK / Release Kotlin 编译通过；真机视觉与帧率性能抽样仍待人工实机验收）**。详见文末第 13–14 节执行记录。本文最初版本为纯方案文档，不包含实现代码。
- 目标：将项目中适合使用 Android Liquid Glass（Backdrop）的应用内组件全部纳入适配，形成一致的导航、容器、控件和弹层材质。
- 完成口径：不能只修改底部栏；必须逐项覆盖页面、共享组件、各类弹层和独立 Activity，并对不支持实时玻璃的场景登记明确原因。
- 本次范围：新增本文件；不修改 Kotlin、Gradle、Manifest、资源或数据，不构建、不安装、不操作设备。

## 1. 需求与边界

### 1.1 必须达到的效果

1. 导航栏、标题栏、侧边导航、分组卡片、交互按钮、选择控件、表单容器、浮层和反馈容器使用统一的玻璃材质规范。
2. 在支持的设备上，玻璃能够采样真实的下层内容，表现模糊、边缘折射、明暗层次和按压反馈；不得仅把白色背景透明度降低后就宣称完成实时液态玻璃适配。
3. 适配覆盖本文第 5、6 节的所有条目。优先级只决定实施顺序，不表示低优先级可以不做。
4. 小面积状态标记、密集课程格、输入区采用较克制的材质，文字、数值、课程色和状态含义保持稳定。
5. 低版本、不可采样窗口和性能不足场景具备完整回退；回退仍保持一致的圆角、颜色、边界和交互语义。

### 1.2 保留的产品行为

材质改造不得改变课程与学期数据、自动选表规则、刷新策略、账号会话、导入写入方式、考试计算、提醒排程和日历所有权。业务 ViewModel、Repository、领域算法与数据库不应因材质适配而重构。

保留现有点击、长按、拖拽、分页、确认、取消、焦点、无障碍和状态恢复行为。应用内返回流程沿用当前实现，不在本文扩展新的导航功能。

新增内容仅限实现这些材质所必需的 UI 抽象和视觉偏好。若新增材质偏好，需要同时定义旧值默认行为、持久化、备份与重置语义；不能顺带改变其他偏好。

### 1.3 何谓“可以适配”

| 类别 | 要求 | 完成方式 |
|---|---|---|
| 应用持有的 Compose 导航与容器 | 全部纳入 | 完整或轻量实时玻璃 |
| 应用持有的 Compose 控件 | 全部纳入 | 玻璃底座、轨道、滑块或选中态；内容层清晰 |
| 密集信息与精确操作区域 | 纳入，但限制效果 | 容器统一材质；文字、网格线、光标、拖拽坐标等不做光学变形 |
| 应用 Dialog、Sheet、Popup | 全部登记并适配 | 先验证实际窗口和采样能力；具备条件时实时玻璃，否则使用有记录的材质回退 |
| 桌面 RemoteViews、系统 UI、导出文件 | 不承诺实时 Backdrop | 保持平台能力与内容正确性；具体边界见第 7 节 |

“需要回退”必须有技术或验收依据，不能作为遗漏普通 Compose 页面适配的理由。

## 2. 当前源码基线

以下为 2026-10-27 本次只读检查所得，不是对早期对话或旧规划的转述。

| 项目 | 当前事实 | 对本方案的影响 |
|---|---|---|
| 依赖 | Backdrop 2.0.1、Shapes 1.2.1，已由 feature:main 引入 | 复用现有版本起步，不以升级框架作为前置任务 |
| Android 范围 | minSdk 26、targetSdk 36、compileSdk 37 | 必须保留 Android 8 起的回退体验 |
| 底部导航 | 已有 LiquidBottomTabs、LiquidBottomTab、拖拽动画和高亮处理 | 属于已有能力，需要抽取统一规范并回归，不应整体推倒重写 |
| 桌面布局 | 根布局已有底栏／侧栏切换；自动侧栏阈值为窗口宽 600dp | 侧栏也在适配范围，不能只做手机竖屏 |
| 侧边导航 | SideNavigation 当前使用普通 Surface 与选中底色 | 是明确待适配项 |
| 页面覆盖 | feature:main 中有 20 个 *Screen.kt；另有 WidgetConfigActivity 中的配置页面 | 这些页面均进入本文清单，附属弹层另列 |
| 通用卡片 | SectionCard 当前基于 Material 3 Card；设计系统尚未提供统一玻璃组件族 | 优先建立通用材质能力，减少逐页复制 |
| 背景 | 已有全局与课表壁纸、可读性遮罩和背景模糊 | 玻璃采样必须与现有壁纸层协作 |
| 课表近期能力 | 已有实际课表预览、周分页、拖拽调课、宽屏周选择、考试叠加等实现 | 需要专项回归，不能依赖旧 UI 基准中的早期描述 |
| 桌面组件 | ScheduleWidgets 使用 RemoteViews | 组件配置 Activity 可适配；桌面实体不能直接套用 Compose Modifier |

源码依据：[版本目录](G:/code/Android/iYango/gradle/libs.versions.toml)、[应用构建配置](G:/code/Android/iYango/app/build.gradle.kts)、[功能模块依赖](G:/code/Android/iYango/feature/main/build.gradle.kts)、[根布局](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/IYangoMainApp.kt)、[通用卡片](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/SectionCard.kt)、[壁纸层](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/WallpaperLayer.kt)。

### 2.1 历史说明与当前源码的差异

- 前面对话曾说明底栏长按约 300ms 激活；**当前 LiquidBottomTabs.kt 第 74 行的 LONG_PRESS_JUMP_MS 为 0L**。本方案如实记录当前值，不把改回 300ms 列为材质任务。后续实施前应重新核对该值，保留届时有效的交互基线。
- 当前 DockGeometry 的栏高为 56dp、内容区高为 48dp；旧 UI 基准中的 52dp 不能直接覆盖当前实现。
- 当前公共尺寸令牌的卡片圆角为 16dp、课程格圆角为 8dp。新增材质复用实际令牌，不机械照搬历史文档中的尺寸。
- 过去完成的测试、构建和手机验证只说明当时的版本；本文不把这些结果视为全项目玻璃适配已通过。

依据：[底栏实现](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/liquidglass/LiquidBottomTabs.kt)、[尺寸令牌](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/tokens/Dimens.kt)、[近期课表审查记录](G:/code/Android/iYango/docs/reviews/2026-10-27-schedule-enhancements.md)。历史 [UI 基准](G:/code/Android/iYango/docs/contracts/ui-baseline.md) 用于理解设计意图，冲突处以当前源码和明确的产品要求复核。

## 3. 全项目统一材质规范

### 3.1 四类呈现

以下是本项目拟定的适配规范，不是 Backdrop 官方提供的组件或固定参数。

| 材质类别 | 适用组件 | 光学要求 | 内容要求 |
|---|---|---|---|
| 导航玻璃 | 底栏、侧栏、顶部栏、浮动操作栏 | 明确的背景采样、克制折射和细高光；保持悬浮层次 | 图标、文字和选中状态稳定清晰 |
| 内容玻璃 | 分组卡、资料卡、列表卡、统计面板、网格外框 | 更强的可读性底色，较弱边缘折射；长列表优先轻量档 | 不扭曲正文，不改变课程色／状态色含义 |
| 控件玻璃 | 按钮、选择胶囊、开关、滑块、输入框底座 | 小范围高光与按压；按组件使用底座、轨道或滑块采样 | 禁用、错误、加载、选中、焦点明确可辨 |
| 浮层玻璃 | Dialog、Sheet、菜单、Snackbar、提示条 | 比页面更高的层级和更强遮罩；采样必须避开自身 | 内容密集区、危险操作和确认按钮优先保证清晰度 |

透明度、模糊、折射、高光、阴影、圆角、按压比例和动画时长必须集中管理，并具有明暗主题取值。不得在每个 Screen 复制一套难以统一调整的常量。

### 3.2 视觉约束

- 文字和图标绘制在玻璃效果之上，不把前景文字一起模糊或折射。
- 主题强调色、六个主题预设、自定义颜色、课程色板与成功／警告／错误语义继续生效。
- 白底、黑底、纯色、照片、细密文字壁纸都要可读；关闭底栏遮罩的既有偏好不得被扩大为“所有输入框和卡片都完全透明”。
- 现有底栏参数可作为导航材质参考，不能直接应用到所有卡片、表单和弹窗。
- 只有本来可交互的组件产生按压反馈；不把静态卡片伪装成按钮。
- 当玻璃背后只有纯色时，效果可能不明显，应通过边界、高光和层级表达材质，不人为增加无业务意义的动态图案。
- 用更强底色解决可读性问题，不依赖增大文字阴影遮盖问题。
- 本文将普通正文对比度不低于 4.5:1、关键控件边界与大字不低于 3:1 作为验收目标；对会变化的背景按最不利截图检查。
- 触控目标原则上不小于 48dp；不以缩小命中区域换取玻璃外观，邻接目标不能依靠重叠的扩展热区。

### 3.3 动态反馈约束

点击处理与视觉动画解耦：正常点击不能等弹簧动画结束后才调用业务动作。开关与滑块保留原来的提交语义，动画不重复触发保存、网络请求或导航。

按压缩放／旋转应使用 drawBackdrop 的 layerBlock 处理背景与组件的变换关系。现有动画偏好关闭时，非必要弹性和缩放应停止，但点击、拖动、选中与取消仍工作。[官方交互教程](https://kyant.gitbook.io/backdrop/tutorials/interactive-glass-bottom-bar)

## 4. 技术适配要求

### 4.1 按依赖方向组织绘制层

Backdrop 需要显式的背景来源：rememberLayerBackdrop 与 layerBackdrop 配合记录内容，drawBackdrop 负责前景绘制；多个来源可用 rememberCombinedBackdrop 合并。[官方 Backdrop API](https://kyant.gitbook.io/backdrop/api/backdrops)

后续实现应明确每个效果的来源和使用者，建议采用以下单向关系：

| 层级 | 内容 | 可以被谁采样 |
|---|---|---|
| L0 基底 | 主题背景、当前作用域壁纸及既有可读性遮罩 | 内容卡、网格底座、背景透明区域 |
| L1 页面内容 | 使用 L0 的内容容器，加上列表、课程网格等页面内容 | 顶栏、底栏、侧栏、同窗口上层浮层 |
| L2 导航与操作层 | 导航面板、工具栏、选择指示器 | 明确需要叠加导航的上层效果 |
| L3 弹出层 | 独立弹层表面及其控件 | 仅向更上层导出不含消费者自身的背景 |

L0 的记录范围不包含采样它的卡片；L1 的记录范围不包含采样它的导航。禁止“父布局记录全部子内容，子内容又采样同一记录”的闭环。

背景绑定按页面／窗口作用域创建和记忆，不在每次重组、每个列表项或每个动画帧重新建立整页背景。页面销毁后释放相应资源，隐藏页面不成为当前页的错误采样来源。

### 4.2 背景与壁纸

当前根布局把 WallpaperLayer 与页面内容分层绘制；“视觉上透出壁纸”不能证明“壁纸已经进入折射采样”。需要用移动的内容和带纹理壁纸分别验证真实采样，必要时组合 L0 与页面来源。

全局壁纸、课表独立壁纸、明暗壁纸回退、壁纸遮罩与模糊必须继续按原偏好工作。解码失败、尚未加载和无壁纸时使用完整主题基底，避免玻璃出现透明洞或错误底色。

课程网格、裁剪预览与显示设置预览各自使用正确作用域；不能把正式页面的坐标直接套到缩小的预览中。

### 4.3 效果与嵌套

颜色处理、模糊、透镜按该顺序组合；传入效果的距离采用像素并在密度作用域转换。透镜使用符合要求的 CornerBasedShape，折射高度受实际圆角约束。[官方效果 API](https://kyant.gitbook.io/backdrop/api/backdrop-effects)

玻璃面板内还有玻璃按钮时，通过 exportedBackdrop 导出面板表面，再按需要供子控件采样。它避免把消费者内容重新录入自己的背景；不得使用会形成循环的父级 layerBackdrop 方案。[官方嵌套教程](https://kyant.gitbook.io/backdrop/tutorials/glass-bottom-sheet)

滑块、页签液滴需要同时看到页面与轨道时，可组合两个独立背景。是否叠加前景内容必须明确，不默认把整棵控件树再次作为背景。[官方滑块教程](https://kyant.gitbook.io/backdrop/tutorials/glass-slider)

### 4.4 弹窗、菜单与窗口边界

官网 Bottom Sheet 教程展示的绘制容器不等于项目中的所有 Material 3 ModalBottomSheet、Dialog、DropdownMenu 都能直接跨窗口采样。以下是实施验证要求，不是已确认的库能力：

1. 确认具体组件的窗口、Compose owner、坐标系和图层生命周期。
2. 同窗口且采样闭环已排除时，使用真实下层背景。
3. 独立窗口优先建立该窗口可安全持有的背景；没有经过验证的跨窗口方案时，采用较高不透明度的统一浮层材质。
4. 不为“做出玻璃”随意把 Dialog 改成普通 Box，丢失焦点约束、外部点击关闭、键盘避让、无障碍和窗口语义。
5. 任一回退必须在覆盖清单记录具体组件、原因、设备证据和呈现方式；可用的同窗口页面仍必须完成实时效果。

### 4.5 公共组件的归属

建议将通用材质令牌、能力判断、无业务背景作用域与基础表面放入 core:designsystem；页面内容、导航路由和业务动作留在 feature:main。配置 Activity 可直接消费公共组件。

拟建立的能力包括：玻璃 Surface／分组卡、顶部操作栏、按钮／图标按钮、选择胶囊、开关／滑块、输入框底座、弹窗／Sheet 表面、反馈容器。以上是待设计的项目能力，不是声称 Backdrop 已导出了这些高级组件。[官方仓库说明](https://github.com/Kyant0/AndroidLiquidGlass)

共享组件必须支持无 Backdrop 的主题回退，供 Preview、独立窗口和测试使用；不能因为缺少 Activity 或背景对象而崩溃。抽取时保留既有第三方来源、许可和修改说明。

## 5. 页面与组件覆盖清单

状态约定：本表“待适配”是后续工作；“已有”仅表示源码已有局部能力，不代表已满足本文全部要求。所有行均需完成适配或提交明确的边界证明。

| 编号／优先级 | 页面与源码入口 | 必须覆盖的组件 | 保护要求／当前状态 |
|---|---|---|---|
| P00／P0–P1 | [IYangoMainApp](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/IYangoMainApp.kt) | 根背景、底部导航、SideNavigation、选中指示器、导航项 | 底栏已有；侧栏待适配。保留自动／底部／侧边设置、选中状态、安全区及页面保留 |
| P01／P1 | [TodayScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/TodayScreen.kt) | ProfileCard、近期考试卡、焦点课程／CourseRow、QuickTiles、MonthCalendarCard、明日安排、空态动作 | 待适配。月历日期／课程点和时间文字清晰；快捷入口不产生额外延迟 |
| P02／P1 | [ScheduleScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/ScheduleScreen.kt)、[ScheduleWeekGrid](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/ScheduleWeekGrid.kt) | ScheduleHeader、HeaderIconButton、溢出菜单、周导航、网格外框、课程与考试块、冲突徽章、时间标签 | 待适配。密集块采用轻量内容材质；保留课程色、重叠分列、午休、时间轴、拖拽命中坐标 |
| P03／P1 | [GradesScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/grades/GradesScreen.kt) | GradesHeader、GradesFilters、统计卡、GradeRecordCard、GradeBadge、通知／未读提示、详情框、登录与重试动作 | 待适配。成绩、绩点、排序筛选、及格与未读语义不受背景影响 |
| P04／P1 | [MineScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/mine/MineScreen.kt) | HeaderCard、MenuRow、主题色选择、外观开关、自定义色输入、OptionPill、布局设置 | 待适配。保留主题与底栏遮罩已有值，主题切换不闪白／闪黑 |
| P05／P2 | [CourseManageScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/coursemanage/CourseManageScreen.kt) | 顶栏、搜索框、筛选 Chip、CourseRow、Checkbox、多选 SelectionBar、删除确认、Snackbar | 待适配。保持搜索／多选／批量操作和列表滚动位置 |
| P06／P2 | [CourseEditorScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/editor/CourseEditorScreen.kt) | 顶栏、BasicInfoCard、ArrangementCard、输入框、开关、ColorDots、PickerRow、时间／周类型／周期选择、撤销重做与保存栏 | 待适配。所有输入正文与光标清晰；保留撤销历史、校验、脏数据确认、键盘与保存语义 |
| P07／P2 | [TimetableManageScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/timetable/TimetableManageScreen.kt) | 顶栏、TimetableCard、ActionPill、新建按钮、名称输入／删除弹窗、TimetableRulesSheet | 待适配。当前表选择、复制、删除和自动规则保持原逻辑 |
| P08／P2 | [SemesterManageScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/semester/SemesterManageScreen.kt) | 顶栏、SemesterRow、SlotRow、创建按钮、SemesterSheet、日期／节次时间编辑 | 待适配。原始节次、钟点、日期及输入校验不变 |
| P09／P2 | [SchoolAccountScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/school/SchoolAccountScreen.kt) | ActiveConnectionCard、LoginCard、账号／密码框、密码可见按钮、记住密码开关、HistoryRow、StatusChip、退出与删除确认 | 待适配。保留密码遮蔽、焦点、IME 和登录中禁用状态，不改变凭据处理 |
| P10／P2 | [SchoolTimetableSyncScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/school/SchoolTimetableSyncScreen.kt) | 顶栏、学期筛选、SemesterDraftCard、课程预览与 ArrangementSelectionCard、Checkbox、日期输入、进度与提交按钮 | 待适配。查询／预览／写入分阶段状态明确，不重复提交 |
| P11／P2 | [ClassroomQueryScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/classroom/ClassroomQueryScreen.kt) | 顶栏、FilterCard、OptionChips、查询按钮、ResultHeader、RoomCard、ClassroomCellChip、DetailSheet／DetailCard | 待适配。筛选区和占用状态清晰；可用／占用不能只靠透明程度区分 |
| P12／P2 | [ExamListScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/exam/ExamListScreen.kt) | 顶栏、筛选、ExamCard、新建操作、ExamEditorSheet、输入框、日期时间选择、删除确认、Snackbar | 待适配。倒计时、过去／未来与缺失时间含义保持准确 |
| P13／P3 | [SyncHistoryScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/synchistory/SyncHistoryScreen.kt) | 顶栏、BatchCard、UnreadDot、ResultBadge、SyncBatchSheet、ChangeRow、清理确认与反馈 | 待适配。新增／变更／删除和已读状态保持明显 |
| P14／P2 | [DisplaySettingsScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/DisplaySettingsScreen.kt) | 顶栏、作用域 Chip、真实课表预览容器、SettingsGroup、SwitchRow、SettingSlider、PaletteGroup、HexColorField、信息排序控件 | 待适配。全局／单课表范围不混淆；预览与正式课表使用同一材质规则，参数即时反馈 |
| P15／P2 | [WallpaperScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/WallpaperScreen.kt) | 顶栏、WallpaperSlotCard、WallpaperRow、明暗预览、ScrimSection、BlurSection、操作按钮与裁剪外框 | 待适配。玻璃不改变原图、裁剪坐标或实际保存结果，照片本体不折射 |
| P16／P3 | [ReminderCalendarScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/ReminderCalendarScreen.kt) | 顶栏、SettingsCard、总开关／CourseSwitchRow、提前时间 Chip、日历 RadioButton、StatusRow、IssueList | 待适配。权限不足、排程失败和日历目标可辨；不新增重复系统调用 |
| P17／P3 | [ImportExportScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/importexport/ImportExportScreen.kt) | 顶栏、导入／导出分组卡、预览列表、搜索、多选、ModeRow、CheckRow、重复导入确认和提交按钮 | 待适配。新增／追加／替换区别明确，危险操作确认不能弱化 |
| P18／P3 | [ScheduleExportScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/export/ScheduleExportScreen.kt) | 顶栏、ExportRangeCard、课表菜单、周次／清晰度 Chip、ImageOptionsCard、开关、预览外框、ExportActionCard、Snackbar | 待适配。仅屏幕 UI 使用玻璃；预览图与实际导出内容一致 |
| P19／P3 | [SettingsBackupScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/SettingsBackupScreen.kt) | 顶栏、备份卡、预览卡、选择开关、恢复按钮、重置确认与结果提示 | 待适配。保留旧文件兼容和危险操作语义；如增加材质设置，明确是否纳入备份 |
| P20／P3 | [AboutScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/about/AboutScreen.kt) | 顶栏、信息卡、RefreshStatusRow、提醒／日历状态、DiagnosticRow、导出预览／清空确认 | 待适配。日志正文保持可读；不改变脱敏、导出和清理行为 |
| P21／P3 | [WidgetConfigActivity](G:/code/Android/iYango/app/src/main/java/com/gfs/iyango/WidgetConfigActivity.kt) | 独立配置页顶栏、课表选择卡、TimetableOption、RadioButton、外观 Chip、保存按钮 | 待适配。建立自己的主题与背景作用域，不能依赖主 Activity 的 Backdrop；保存／取消结果契约不变 |

P00 是导航外壳；P01–P20 对应当前 20 个业务 Screen；P21 是独立组件配置页。页面中没有单独命名的按钮、输入框和反馈项同样受第 6 节约束。

## 6. 共享组件、弹层与容易遗漏的状态

### 6.1 公共组件族

| 编号 | 组件族与现有入口 | 适配要求 | 验收重点 |
|---|---|---|---|
| C01 | [SectionCard](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/SectionCard.kt) 及页面内普通 Surface／Card | 提供统一内容玻璃表面；逐个核对未使用 SectionCard 的自建卡片 | 不能仅修改一个公共 Card 就宣称全覆盖 |
| C02 | [SectionHeader](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/SectionHeader.kt)、标题／操作槽 | 需要承载面的标题使用统一材质；纯文字标题保持清晰前景 | 行高、标题对齐、长标题与尾部动作不受影响 |
| C03 | Button、OutlinedButton、TextButton、IconButton、ActionPill、QuickTile | 按主次／危险操作定义底座、高光和反馈；纯文字动作不强制增加厚胶囊 | 点击、禁用、加载、键盘激活和动作层级 |
| C04 | FilterChip、OptionPill、周次 Chip、分段选项 | 统一选中、未选中、禁用和多选视觉 | 不能只有玻璃反光表示选中 |
| C05 | Switch、Checkbox、RadioButton | 统一底座／轨道或选择标记材质，保留原有角色与可访问状态 | 整行点击不触发两次，TalkBack 能读出状态 |
| C06 | Slider、SettingSlider、壁纸模糊／遮罩滑块 | 轨道和液滴按需组合背景；保留范围、步长和提交时机 | 拖动值与实际设置一致，支持无障碍增减 |
| C07 | TextField、OutlinedTextField、搜索框、密码框、数字／日期输入 | 玻璃仅作用于底座；正文、光标、选区、标签、提示、错误不变形 | IME、复制粘贴、密码遮蔽、焦点、校验 |
| C08 | [StatusBadge](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/StatusBadge.kt)、GradeBadge、ResultBadge、课程冲突数量 | 轻量材质或统一底色；保持状态含义 | 警告／错误与未读标记不被背景淹没 |
| C09 | [EmptyState](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/EmptyState.kt)、[ErrorState](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/ErrorState.kt)、[LoadingState](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/LoadingState.kt) | 统一容器与动作；图标、说明及进度保持清晰 | 初次加载、空列表、失败重试都纳入截图 |
| C10 | Snackbar、NoticeBanner、UnreadBanner、表单错误／结果条 | 使用高可读性的反馈材质 | 消息完整、按钮可用、不盖住关键提交操作 |
| C11 | PullToRefreshBox、加载指示器、进度条 | 指示器容器与材质统一，进度本体保持清晰 | 在顶栏安全区内完整出现，触发手势和完成时机不变 |
| C12 | 内联菜单、DropdownMenu、溢出菜单 | 统一菜单表面、选中行与分隔层级；跨窗口按第 4.4 节处理 | 不遮挡关键上下文，关闭／焦点／滚动行为正确 |
| C13 | 底部固定操作栏、多选栏、拖拽提示与操作条 | 统一导航玻璃，透明区域不能产生额外点击穿透 | IME、底部安全区及内容预留；调课取消／确认始终可触达 |

### 6.2 弹层逐项清单

| 编号 | 当前源码与弹层 | 适配范围与关键约束 |
|---|---|---|
| O01 | [CourseDetailDialog](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/coursedetail/CourseDetailDialog.kt) | 详情表面、信息行、左右分页与指示器、编辑／复制／删除／关闭、DeleteScopeSheet、最终删除确认。不能改变分页对象与删除作用域 |
| O02 | [ScheduleAdjustmentDialog](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/coursedetail/ScheduleAdjustmentDialog.kt) | 调课面板、SelectField 菜单、开关、保存／取消；保留来源课程、目标周次／节次与冲突校验 |
| O03 | [WeekPickerSheet](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/WeekPickerSheet.kt) | Sheet 外壳、WeekPicker、WeekChip、宽屏同源周选择；密度点清晰，不给每个小点建立玻璃层 |
| O04 | [ScheduleScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/ScheduleScreen.kt)、[ScheduleWeeks](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/ScheduleWeeks.kt) | TemporaryDaySheet、拖拽暂存操作条、课程实例／目标长度选择、临时调课确认；取消与落点不能被材质动画干扰 |
| O05 | [ScheduleWeekGrid](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/ScheduleWeekGrid.kt) | ExamDayDialog 与考试入口；考试卡和课程卡不能因玻璃样式混淆 |
| O06 | [CourseEditorScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/editor/CourseEditorScreen.kt) | EditorSheetHost、WeekPickerDialog、OptionPickerDialog、日期／时间选择与未保存确认。保留键盘操作、数值范围、撤销和草稿 |
| O07 | 课表／学期管理页 P07、P08 | NameInputDialog、TimetableRulesSheet、SemesterSheet、SlotTimeDialog、日期选择和删除确认 |
| O08 | 成绩／教室／考试／同步记录页 P03、P11、P12、P13 | GradeDetailDialog、DetailSheet、ExamEditorSheet、SyncBatchSheet，以及各自确认框和日期时间选择 |
| O09 | 账号／同步／导入／备份／诊断页 P09、P10、P17、P19、P20 | 账号退出与清理、学期日期、重复导入、备份恢复／重置、日志预览／清理；危险操作仍需清晰确认 |
| O10 | [WallpaperCropDialog](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/WallpaperScreen.kt) | 仅适配外壳、工具栏、确认／取消；图片、裁剪框位置、缩放手柄和选区几何保持真实 |

应用自己绘制的日期／时间选择器仍在适配范围，可先统一弹窗表面和操作区；不能为追求玻璃外观破坏日历、时钟、范围选择及辅助功能。

## 7. 不直接接入实时 Backdrop 的区域

| 区域 | 源码／原因 | 本轮后续实施的要求 |
|---|---|---|
| 桌面小组件实体 | [ScheduleWidgets](G:/code/Android/iYango/platform/android/src/main/kotlin/com/gfs/iyango/platform/android/ScheduleWidgets.kt) 使用 RemoteViews，运行在 Launcher 承载环境 | 保留实色／透明模式，统一可由资源表达的颜色与圆角；不声称能实时折射桌面壁纸。配置页按 P21 完整适配 |
| 系统通知 | [AndroidReminderPlatform](G:/code/Android/iYango/platform/android/src/main/kotlin/com/gfs/iyango/platform/android/AndroidReminderPlatform.kt) 使用 NotificationCompat | 保持平台通知模板与动作，不用应用玻璃替代系统通知 UI |
| 系统文件选择器、权限弹窗、输入法、系统日历与设置页 | UI 由其他应用或系统持有 | 仅适配发起入口与返回后的应用界面，不改造系统窗口 |
| 导出的 PNG／ICS／JSON 与备份文件 | [ScheduleImageRenderer](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/export/ScheduleImageRenderer.kt) 及数据导出流程 | 保留稳定、清晰、可复现的文件内容；不把实时屏幕背景、菜单或导航玻璃烘焙进导出结果 |
| 照片、图像裁剪内容、密集文字、网格线、光标与状态图形 | 属于要准确展示的内容层 | 其承载容器适配，内容本体不做光学变形 |
| 无 UI 的 data／domain／model 逻辑 | 不属于材质绘制 | 不改业务逻辑；如必须新增视觉偏好，只做必要且兼容的数据契约扩展 |

以上属于明确边界，不是待办遗漏。普通业务卡片、表单、管理页不能归入本节来规避适配。

## 8. 兼容、性能与无障碍

### 8.1 能力分档

| 能力档 | 平台范围 | 呈现要求 |
|---|---|---|
| 完整档 | Android 13+ 且运行环境支持所需效果 | 可使用模糊、透镜及必要高光；色散和深度只在合适组件启用 |
| 模糊档 | Android 12／12L，有 RenderEffect、没有所需 RuntimeShader 能力 | 保留模糊、主题底色与边界，不调用不可用的透镜效果 |
| 基础档 | Android 8–11 或绘制环境缺少效果支持 | 使用清晰的主题表面、边界、圆角和选中态，功能完全可用 |

平台门槛来自[官方效果文档](https://kyant.gitbook.io/backdrop/api/backdrop-effects)。能力分档与失败回退应集中处理，参考已有底栏的能力检测；仅判断 API 数字不能替代实际环境验证。

低性能降级属于本项目拟定策略：先减少色散／深度和多层阴影，再降低折射与模糊成本，最后回退主题表面。降级不能造成按钮消失或后台持续重试。

### 8.2 性能约束

- 继续保留 RetainedMainTabs 对页面状态与隐藏页面活动的隔离，不因新背景宿主让所有页同时布局、绘制或处理输入。
- 大列表、密集课表共享必要的背景来源；不为每个格子重复记录整屏，不在滚动过程中截图或做 CPU 模糊。
- 禁止每帧图片解码、创建 Shader／大位图、数据库查询或更新持久化偏好。
- 滑块的视觉预览可逐帧更新，真实持久化沿用现有提交节奏；不能每帧触发整页重组。
- 嵌套玻璃控制层数；透明导航覆盖滚动内容时测量 GPU 成本，不能只看 Compose 重组次数。
- 普通点击必须及时反馈；导航动作不等待材质过渡完成。底栏直接指示器拖动与任意位置激活不能互相抢占。
- 建立同一设备、同一数据、同一操作脚本的改造前基线；Debug 结果用于排错，性能验收使用 Release 或等价可分析构建。
- 每个场景先完整预热一轮，再进行至少 3 轮、每轮至少 60 秒的重复操作采样；前后版本使用相同脚本与操作次数。固定刷新率、亮度、供电与电量区间，记录设备热状态，避开热降频；无法固定刷新率时按实际刷新档位分组，不混算。应用主窗口与独立弹窗分别标注，首次绘制与冷启动另列结果，不能用预热掩盖首次打开问题。
- 帧耗时统一取 FrameMetrics.TOTAL_DURATION，换算为毫秒，P95 使用有效样本排序后的第 ceil(0.95 × N) 项。本文的“卡顿帧率”具体限定为帧耗时超过该帧 DEADLINE 的比例；DEADLINE 从 API 31 起可用，旧系统以固定刷新周期估算并明确标注“估算超限率”。缺失字段、首绘帧与采集丢帧须单列，不能当作零耗时；不同工具的 CPU 帧时长、系统 jank 标签与这个口径不能混用。[FrameMetrics 定义](https://developer.android.com/reference/android/view/FrameMetrics)
- 拟定回归门槛：至少 3 轮稳定采样，P95 帧耗时相对基线增加不超过 10%，卡顿帧率增加不超过 2 个百分点；超过则降低材质成本并复测。若基线本身明显卡顿，仍需单独列为未达标，不以“没有更差”证明流畅。
- 按目标刷新率观察帧预算：60Hz 约 16.7ms、90Hz 约 11.1ms、120Hz 约 8.3ms；历史底栏 Debug 数据不能直接充当此次全量验收结论。

以上数字是本方案的工程验收目标，不是库对性能的保证。

### 8.3 可访问性与输入

检查 TalkBack 的角色、名称、选中／禁用状态、错误说明与焦点顺序；装饰高光不增加可访问节点。玻璃背景不可截获本应由控件处理的点击。

支持键盘 Tab／方向键／Enter、鼠标与触控笔。字体缩放到 1.3 倍和 2 倍时，关键动作不被裁切，必要时允许换行或滚动。动画关闭时仍应能识别当前选中项与拖动目标。

独立窗口、弹层关闭、旋转、分屏和主题切换后不能残留点击屏蔽层、错误焦点或旧页面背景。

## 9. 实施阶段与交付物

本节仅安排后续工作，没有在本次生成代码。

| 阶段 | 工作 | 退出条件 |
|---|---|---|
| 阶段 0：冻结基线 | 重新核对源码、页面入口、当前交互参数；拍摄支持／回退档基准图，记录性能 | 清单与代码一致，0ms／历史 300ms 差异有记录；不擅自回滚现有交互 |
| 阶段 1：公共基础 | 背景作用域、令牌、能力分档、基础表面、通用按钮／控件和窗口策略 | 至少覆盖无壁纸／照片背景、明暗主题、独立窗口；无绘制循环 |
| 阶段 2：导航与四主页面 | P00–P04、共享状态组件及课表主要弹层 | 底栏与侧栏都完成，主页面滚动、切换、周分页和拖拽回归通过 |
| 阶段 3：编辑和业务管理 | P05–P12、P14、P15，相关 O 项 | 搜索、多选、表单、日期时间、账号、查询与嵌套浮层可用 |
| 阶段 4：工具与独立入口 | P13、P16–P21，剩余弹层和反馈 | 导入导出、备份、诊断、组件配置无普通材质遗漏；系统边界登记完整 |
| 阶段 5：全量验收 | 全清单回查、低版本、宽屏、大字、性能和业务回归 | 满足第 11 节，保留完整证据与回退记录 |

后续实现采用“实现者提交具体改动，独立审查者复核范围、交互和证据”的方式。每个阶段交付：覆盖项编号、修改文件、明暗截图、交互检查、受影响测试结果、构建结果和已知限制。

## 10. 验收矩阵

### 10.1 视觉与设备组合

- 平台：至少一台 Android 13+ 真机验证完整效果；Android 12 与 Android 8–11 验证回退，缺少设备时明确记录未覆盖，不把模拟器视为全部真机证据。
- 布局：窄屏竖屏、横屏、600dp 临界两侧、宽屏侧导航、分屏和独立组件配置 Activity。
- 主题：浅色、深色、跟随系统、自定义强调色；无壁纸、纯色、深浅照片、高对比细纹理；课表独立壁纸。
- 状态：加载、空、错误、正常、选中、禁用、提交中、未读、危险操作、键盘展开、弹层展开、长列表。
- 设置：动画开／关，已有底栏遮罩开／关，字体放大，课表全局／单表显示设置。

### 10.2 必须执行的交互旅程

| 验收项 | 操作 | 预期 |
|---|---|---|
| V01 底栏 | 连续点击四页；按当前阈值激活未选中项；拖动、松手、取消 | 正常点击及时切换；预览与真实选中按原契约处理；取消复位，没有重复导航 |
| V02 宽屏 | 在底部／侧边／自动布局间切换，改变窗口宽度 | 玻璃导航样式一致，页面状态不丢失，背景坐标正确 |
| V03 课表 | 滚动、切周、周选择、拖拽调课、跨周暂存、取消和确认 | 课程落点、冲突和考试入口准确；玻璃层不抢手势 |
| V04 详情 | 多课程详情翻页；详情→删除范围 Sheet→最终确认，按实际弹层切换顺序分别取消／关闭；检查编辑与复制 | 分页内容与动作对象一致；各层采样来源、焦点、返回键、外部点击和遮罩释放正确，不能只验证单个弹层 |
| V05 编辑 | 输入、撤销重做、切换时间模式、选日期／周次、触发错误、取消未保存退出 | 光标和错误可读，草稿、校验、键盘及确认行为保留 |
| V06 查询 | 成绩筛选排序、教室筛选查询、下拉刷新、打开详情并关闭 | 当前条件不变，列表不卡顿，刷新指示器不被顶栏盖住 |
| V07 管理 | 课表／学期／考试新增、选择、修改、删除确认 | 操作状态和危险按钮清晰，无重复提交 |
| V08 背景 | 切换明暗壁纸、拖动模糊／遮罩、裁剪图片、移除壁纸 | 实际采样跟随背景，裁剪结果准确，失败可回退 |
| V09 显示 | 修改行高、信息顺序、配色与作用域；比较预览和正式课表 | 材质规则一致，内容几何与数据不变 |
| V10 文件与设置 | 导入预览并取消；导出预览；备份预览并取消重置 | 所有应用面板完成适配，系统选择器正常，未确认不写业务数据 |
| V11 独立入口 | 从 Launcher 打开组件配置、取消、再配置保存 | 无主页面也可渲染，配置结果正确，桌面透明模式不被误称为折射 |
| V12 生命周期 | 后台恢复、旋转、分屏、切换页面／弹层、恢复输入焦点 | 无背景错位、资源泄漏、旧页面透出或隐藏页交互 |

验收涉及创建／修改／删除时使用专门测试数据；既有账号和课表不作为可随意删除的数据。真实登录、同步、提醒和日历写入应在其授权与测试范围内执行，不因材质验收自动扩大外部操作范围。

### 10.3 验证与证据要求

1. 静态核查：所有 P、C、O 编号都能追到源码和处理结果；新增加的页面或弹层补入清单。
2. 自动化：只补与背景作用域、能力回退、交互冲突、可访问状态及业务回归有关的有效测试，不用大量截图存在性断言代替行为验证。
3. 构建：后续实现必须通过受影响模块检查与 Debug／Release 构建；编译成功不代表视觉通过。
4. 真机：沿用 android-cli 检查布局与截图；每次截图后实际查看图像。至少记录应用、设备／系统、主题、步骤、预期、实际、截图和结论。
5. 性能：记录采样条件、次数、P50／P95、卡顿帧率及最慢帧；发生回退时记录回退档位。
6. 对系统拥有的 UI、无法跨窗口采样的部分和未覆盖设备分别记录，不合并写成“全项目已通过”。

## 11. 完成定义与清单维护

只有同时满足以下条件，后续实现才能标记为完成：

- [ ] P00–P21 全部有适配结论；已有底栏经过回归，侧栏和独立配置页没有遗漏。
- [ ] C01–C13 与 O01–O10 全部有源码落点、主题／状态检查和结果。
- [ ] 应用内普通 Surface、Card、按钮、输入框、选择控件与弹层调用点经过逐个核对，未绕过公共材质规范。
- [ ] 所有保留普通呈现或采用回退的项均有原因，符合第 7 节或有真实能力／性能证据。
- [ ] 无绘制闭环、自采样、跨窗口错位、前景文字模糊、不可见控件或透明点击穿透。
- [ ] 明暗主题、壁纸、低版本、宽屏、大字体与动画关闭组合通过约定验收。
- [ ] 原有业务行为、交互参数和数据保持正确；没有为材质改造顺带变更导航与手势契约。
- [ ] 测试、构建、性能和设备验收分别记录；未完成项不被成功项掩盖。
- [ ] 视觉上完成完整／轻量实时玻璃的项目，与仅做统一回退外观的项目分开统计。

后续执行记录应采用以下字段，不能只写“已改”：

| 编号 | 实际组件／文件 | 采用材质／能力档 | 改造前后截图 | 行为检查 | 性能／回退原因 | 状态／审查人 |
|---|---|---|---|---|---|---|
| 填写 P／C／O 编号 | 填写当前源码位置 | 完整、轻量或回退及参数来源 | 填写证据路径 | 填写旅程编号与结果 | 填写测量或能力依据 | 待实施／待验收／通过／有条件回退 |

## 12. 文档依据与当前交付状态

官方资料：

- [Backdrop 入门](https://kyant.gitbook.io/backdrop)：背景绘制与依赖接入。
- [Backdrop API](https://kyant.gitbook.io/backdrop/api/backdrops)：图层、组合背景及绘制来源。
- [效果 API](https://kyant.gitbook.io/backdrop/api/backdrop-effects)：效果顺序、形状限制和系统版本。
- [玻璃底栏](https://kyant.gitbook.io/backdrop/tutorials/glass-bottom-bar)：背景完整性与可读性表面。
- [交互底栏](https://kyant.gitbook.io/backdrop/tutorials/interactive-glass-bottom-bar)：变换与 layerBlock。
- [玻璃面板](https://kyant.gitbook.io/backdrop/tutorials/glass-bottom-sheet)：exportedBackdrop 和自采样循环。
- [玻璃滑块](https://kyant.gitbook.io/backdrop/tutorials/glass-slider)：组合背景。
- [平滑圆角](https://kyant.gitbook.io/backdrop/tutorials/smoother-rounded-corners)：Shapes 配套能力。
- [AndroidLiquidGlass 仓库](https://github.com/Kyant0/AndroidLiquidGlass)：库定位和高级组件示例。

文中版本、页面、组件和交互参数来自当前项目源码；材质分类、执行阶段、公共组件规划与验收门槛属于本方案要求，尚未实现。跨窗口实际表现、全项目性能与真机视觉结果均需后续验证。

文档自检：当前 20 个业务 Screen 均已列入，独立读者审查未发现页面或组件类别遗漏；本地源码链接已检查有效。检查期间工作区有并行课表修改，已复核其页面／弹层仍被 P02、O04、O05 和 C13 覆盖；本文不声称整个共享工作区哈希未变化，后续实施仍需重新冻结基线。

**本次交付只有本 Markdown 文档。本文复核了源码覆盖和资料依据，没有生成或修改应用代码，也没有执行新的测试、构建、安装或真机验收。**

## 13. 执行记录（2026-10-28：阶段 0 冻结基线 + 阶段 1 公共基础）

按第 9 节阶段划分执行。阶段 0 完成；阶段 1 的代码落地完成，其退出条件中“无壁纸／照片背景、明暗主题、独立窗口”的真机核验**未执行**（当日无设备连接），不得视为阶段 1 已通过。

阶段 0 核对结论（与本文档原记载一致，无需修订）：

- [LiquidBottomTabs.kt](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/liquidglass/LiquidBottomTabs.kt) `LONG_PRESS_JUMP_MS = 0L`、`DockGeometry` 56dp／48dp、能力分档以 `isRenderEffectSupported()`／`isRuntimeShaderSupported()` 探测。
- [Dimens.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/tokens/Dimens.kt) 卡片圆角 16dp、课程格圆角 8dp。
- [IYangoMainApp.kt](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/IYangoMainApp.kt) 侧边导航（`SideNavigation`）原为普通 `Surface`（surface 0.92 透明度）；根布局原把背景与整层页面内容记成单一 `rememberLayerBackdrop`，已按 §4.1 拆分。
- 依赖 backdrop 2.0.1、shapes 1.2.1（[libs.versions.toml](G:/code/Android/iYango/gradle/libs.versions.toml)），未升级。

执行项（字段格式见第 11 节）：

| 编号 | 实际组件／文件 | 采用材质／能力档 | 改造前后截图 | 行为检查 | 性能／回退原因 | 状态／审查人 |
|---|---|---|---|---|---|---|
| 阶段1-令牌 | [GlassTokens.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/GlassTokens.kt)（新增） | 导航／内容／控件／浮层四类集中参数 + 明暗取值 + 模糊档增益 | 未覆盖（无设备） | 构建通过 | 令牌为唯一定义点，调参不改调用方 | 已实施，待真机验收 |
| 阶段1-能力档 | [GlassTier.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/GlassTier.kt)（新增） | BASIC／BLUR／FULL，按运行时能力探测 | 未覆盖 | 构建通过 | 与底栏既有分层一致，供迁移复用 | 已实施，待真机验收 |
| 阶段1-L0宿主 | [GlassBackdrop.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/GlassBackdrop.kt)（新增：`LocalGlassBackdrop` + `GlassBackdropHost`） | L0 背景作用域，先铺主题基底再录壁纸，结构性排除自采样 | 未覆盖 | 构建通过 | 无宿主时 Local 为 null，组件回退不崩溃 | 已实施，待真机验收 |
| 阶段1-基础表面 | [GlassSurface.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/GlassSurface.kt)（新增） | 按类别令牌绘制；FULL=模糊+折射，BLUR=无透镜加蒙层，BASIC／无宿主=主题实体表面 | 未覆盖 | 构建通过 | 回退保持同圆角／颜色／内容语义 | 已实施，待真机验收 |
| C01 | [SectionCard.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/SectionCard.kt) | 有宿主=内容玻璃；无宿主=原 Card（Preview 不变） | 未覆盖 | 构建通过；Preview 路径与改造前一致 | 未逐页核对自建卡片（属阶段 2–4 页面工作） | 已实施，待真机验收 |
| P00（部分） | [IYangoMainApp.kt](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/IYangoMainApp.kt) | 根布局改用 `GlassBackdropHost`；L0=背景+壁纸，L1=页面内容；Dock 采样 combined(L0,L1)；`SideNavigation` 换用导航玻璃档 | 未覆盖 | 构建通过；交互契约（选中、安全区、页面保留）未改动 | 侧栏真机可读性未测；Dock 视觉与改造前采样内容等价（背景+页面内容） | 已实施，待真机验收 |
| P01 | [TodayScreen.kt](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/TodayScreen.kt) | ProfileCard、今日课程卡、CourseRow、QuickTiles 外框、MonthCalendarCard、明日空态卡、近期考试卡/行 → 内容玻璃（`GlassMaterial.CONTENT`） | 未覆盖 | 构建通过；点击/分页/详情弹层契约未改动 | 倒计时与周数小徽章保留实色底色（C08 统一底色选项）；CourseRow 在焦点卡内为嵌套玻璃（采样 L0），待真机评估是否需 exportedBackdrop | 已实施，待真机验收 |
| P02 | [ScheduleScreen.kt](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/ScheduleScreen.kt)、[ScheduleWeekGrid.kt](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/ScheduleWeekGrid.kt) | 本轮不改视觉 | 未覆盖 | 构建通过；无行为改动 | 网格外框保留现状：用户可自定义网格底色 + 密集课程格可读性优先（§3.1 克制材质），当前 0.86 半透明已采样壁纸，属有记录的材质决策；课程块/时间标签/冲突徽章按保护要求保持课程色与坐标；HeaderIconButton 与标题槽保持透明呈现；WeekPickerSheet/TemporaryDaySheet/DropdownMenu 为跨窗口弹层，按 §4.4 待验证 | 已登记决策，待真机评估 |
| P03 | [GradesScreen.kt](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/grades/GradesScreen.kt) | GradesFilters、GradeStatisticsCard、GradeRecordCard → 内容玻璃 | 未覆盖 | 构建通过；筛选/查询/未读语义未改动 | 错误横幅（errorContainer）与未读横幅（brandSoft）保留实色反馈材质（C10）；GradeBadge 实色保留；长列表轻量档调参留待阶段 5 性能验收；GradeDetailDialog 为跨窗口弹层待验证 | 已实施，待真机验收 |
| P04 | [MineScreen.kt](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/mine/MineScreen.kt) | HeaderCard、MenuRow → 内容玻璃 | 未覆盖 | 构建通过；外观开关/主题切换/布局设置语义未改动 | 主题预设圆点、深色模式胶囊、OptionPill 保留实色选中语义（C04）；染背景/玻璃遮罩既有偏好不受影响 | 已实施，待真机验收 |

验证边界（按 §10.3.6 分别记录，不合并）：

1. 构建：`:core:designsystem`、`:feature:main`、`:app` 的 `compileDebugKotlin` 与 `:app:assembleDebug` 全部通过（2026-10-28）。
2. 真机：`adb devices` 无设备连接，明暗主题、壁纸、宽屏侧栏、低版本回档（Android 8–12）全部**未验证**。
3. 性能：未采样（§8.2 要求的基线与三轮采样留待阶段 5 全量验收）。
4. 后续阶段入口：阶段 2（P00 侧栏可读性调参 + P01–P04 页面与共享状态组件、课表主要弹层）之前，需在真机上先确认侧栏玻璃与 Dock combined 采样的视觉正确性。
5. 阶段 2 代码进度（2026-10-28 续）：P01／P03／P04 页面容器已接入内容玻璃，P02 完成决策登记；弹层（O01–O10）一律未动——按 §4.4，ModalBottomSheet／Dialog／DropdownMenu 的跨窗口采样能力未经验证，在真机确认前保持原有窗口材质，此为边界而非遗漏。

## 14. 执行记录（2026-11-21：全量组件／弹层／页面／独立配置页补齐）

围绕“将应用内所有适合接入 Android Liquid Glass（Backdrop）的组件与页面全部补齐”的目标，完成第 1–4 阶段代码改造与第 5 阶段自动化静态审计与构建/单测验证：

### 14.1 公共基础与交互控件套件（`core:designsystem` & 根宿主）

1. **玻璃核心增强**：
   - [DampedDragAnimation.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/DampedDragAnimation.kt) 下沉至 `core:designsystem`（在 `feature:main` 保留 `typealias` 兼容旧引用），并迁移/公开 [InteractiveHighlight.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/InteractiveHighlight.kt) 中的 `rememberLiquidHighlight()`、`rememberTopBarHighlight()`、`GraphicsLayerScope.applyLiquidPressLayer(...)`。
   - [GlassTokens.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/GlassTokens.kt) 强化 `Content` / `Control` / `Overlay` 光学令牌；`Navigation` 令牌维持 `GlassBackdropHostTest` 既有契约不变。
   - [GlassSurface.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/GlassSurface.kt) 重新启用壁纸层之上的 `GlassMaterial.CONTENT` `lens()` 折射，并为 `!hasWallpaper && CONTENT` 分支补齐明暗自适应玻璃边框高光。
   - [GlassBackdrop.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/GlassBackdrop.kt) 新增 `rememberGlassFallbackBackdrop(fallbackColor)`；[GlassScaffold.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/GlassScaffold.kt) 新增 `LocalGlassPageBackground`，在不透明次级页底层绘制全局壁纸，并在 [IYangoMainApp.kt](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/IYangoMainApp.kt) 完成注入与 `SideNavigation` 选中胶囊 `GlassSurface(exportedBackdrop)` 升级。
   - [ErrorState.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/ErrorState.kt) 重试按钮接入 `LiquidButton`。
2. **新增完整交互控件与弹层体系（`core:designsystem`）**：
   - [LiquidToggle.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/LiquidToggle.kt)（`LiquidToggle`）
   - [LiquidSlider.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/LiquidSlider.kt)（`LiquidSlider`，含 `progressSemantics` / `SemanticsActions.SetProgress`、`steps`、`onValueChangeFinished`、深度效果与色散）
   - [LiquidButton.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/LiquidButton.kt)（`LiquidButton`、`LiquidOutlinedButton`、`LiquidTonalButton`）
   - [LiquidChip.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/LiquidChip.kt)（`LiquidFilterChip`）
   - [LiquidSelectionControls.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/LiquidSelectionControls.kt)（`LiquidCheckbox`、`LiquidRadioButton`；当回调为 `null` 时省略点击/切换语义以避免整行切换重复触发）
   - [LiquidTextField.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/LiquidTextField.kt)（`LiquidOutlinedTextField`、`LiquidTextField`，直接将调用方 `modifier` / `testTag` 透传至 `BasicTextField` 以保留 `SemanticsActions.SetText`）
   - [LiquidOverlays.kt](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/LiquidOverlays.kt)（`applyWindowBlur`、`LiquidDialogSurface`、`LiquidAlertDialog`、`LiquidModalBottomSheet`、`LiquidDropdownMenu`、`LiquidFloatingBar`，边框统一使用 `Modifier.border` 避免指针穿透拦截）
   - [LiquidControlsTest.kt](G:/code/Android/iYango/core/designsystem/src/test/kotlin/com/gfs/iyango/core/designsystem/liquidglass/LiquidControlsTest.kt) 覆盖交互控件与弹层单测（`core:designsystem` 共 36 项测试通过）。

### 14.2 页面、弹层与桌面组件配置页迁移（P00–P21、O01–O10）

1. **主标签页与核心弹层**：`TodayScreen`、`HomeSettingsScreen`、`MineScreen`、`ScheduleScreen`、`ScheduleQuickSettingsSheet`、`ScheduleWeeks`（长按调课暂存条 `LiquidFloatingBar`）、`ScheduleWeekGrid`（`ExamDayDialog`）、`WeekPickerSheet`、`TermBrowseHeader`、`CourseRawScreen`、`CourseDetailMotionWindow`（静置态 `LiquidDialogSurface` + `applyWindowBlur`）、`CourseDetailDialog`、`ScheduleAdjustmentDialog`、`GradesScreen`、`GradeRecordCard`、`GradesFilters`、`GradeStatisticsContent`、`GradeAttributePickerSheet`、`GradeTermRangePicker`、`GradeSelectionBar`（升级为 `GlassMaterial.OVERLAY`）。
2. **业务管理页**：`CourseColorPalette`、`CourseManageScreen`、`CourseEditorScreen`、`TimetableManageScreen`、`SemesterManageScreen`、`SchoolAccountScreen`、`SchoolAccountCards`、`SchoolLoginForm`、`SchoolTimetableSyncScreen`、`SchoolLoginPage`（外包 `GlassBackdropHost`）、`ClassroomQueryScreen`、`ExamListScreen`、`CreditProgressScreen`、`CreditProgressDialogs`。
3. **设置页、工具页与独立 Activity**：`DisplaySettingsScreen`、`DockGlassSettingsScreen`、`FontAppearanceScreen`、`ReminderCalendarScreen`、`SettingsBackupScreen`、`WallpaperScreen`、`ImportExportScreen`、`ScheduleExportScreen`、`SyncHistoryScreen`、`AboutScreen`、`RuntimeLogScreen` 以及桌面组件配置页 [WidgetConfigActivity.kt](G:/code/Android/iYango/app/src/main/java/com/gfs/iyango/WidgetConfigActivity.kt)（移除私有 `SectionCard` 改为复用共享玻璃 `SectionCard`）。

### 14.3 保留边界与未迁移项说明（静态审计已确认）

经 `app/src/main` 与 `feature/main/src/main` 全量静态扫描，剩余原生 Material 3 组件仅限于以下明确边界：
- `@Preview` 预览包裹壳体（`Surface { ... }`，供 Android Studio 预览渲染，不影响运行态页面）。
- 极小语义状态徽章（`GradeRecordCard` 成绩徽章、`ExamListScreen` 倒计时徽章、`SemesterManageScreen` 状态标签、`SyncHistoryScreen` `ResultBadge`、`TodayScreen` 周数/节次小胶囊、`ClassroomQueryScreen` 教室格小标签），保留实色以保障状态识别。
- 数据展示/图像保真画布：`ScheduleWeekGrid` 密集课程格、`WallpaperCropDialog` 裁剪画布、`ScheduleImageRenderer` 离屏导出位图、`WidgetConfigActivity` 中 `StylePreviewCard` 对 RemoteViews 桌面微件的保真预览、以及 `CourseDetailMotionWindow` 形态过渡期间的动画框。
- 平台/复合语义组件：`CourseEditorScreen` 系统 `DatePickerDialog` 与 `CreditProgress` 内的三态 `TriStateCheckbox`。

### 14.4 自动化验证结果（2026-11-21）

1. **全模块单测与 Robolectric UI 测试**：`.\gradlew.bat :core:model:test :core:domain:test :core:designsystem:testDebugUnitTest :data:testDebugUnitTest :feature:main:testDebugUnitTest :app:testDebugUnitTest :platform:android:testDebugUnitTest --console=plain` 全部通过（退出码 0）。
2. **安装包与 Release Kotlin 编译**：`.\gradlew.bat :app:assembleDebug :app:compileReleaseKotlin --console=plain` 构建通过（退出码 0）。
3. **待实机人工检查项**：不同 Android 版本（RenderEffect / RuntimeShader 分档）、明暗壁纸折射视觉观感与长列表滑动帧率抽样需在实机上视觉复核。

