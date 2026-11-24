# 首页自定义、课程进度与倒计时实施计划

> 仅计划，不生成 UI、业务代码或测试代码。本期覆盖用户选中的首页模块设置和内置快捷入口，不增加任意网址或后台常驻倒计时。

> 执行状态（2026-11-13）：用户随后授权实施本首页功能，已完成独立审查、801 项测试验证和 Debug 构建；原计划阶段的上述说明保留作历史范围记录。详见[实施与最终验证记录](../../reviews/2026-11-13-home-customization.md)。学分进度、统一备份及其恢复门控仍由各自计划负责，本轮仅注册已有功能并实现当前设置备份的原子偏好兼容。

**Goal:** 让用户决定首页卡片与快捷入口的顺序和可见性，并准确显示当前课程进度和下一门课的等待时间。

**Architecture:** 首页布局配置存入应用偏好，通过仓库原子更新。有效课表观察流负责课程实例变化，前台轻量时钟只对已缓存实例作时间投影；排序不改变课表、提醒和系统日历数据。

**Tech Stack:** Compose、DataStore、Flow、现有 EffectiveSchedulePort、Clock、GlassScaffold/GlassSurface 和主导航。

## 1. 现状

- [TodayScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/TodayScreen.kt:308) 顺序固定为资料、今日课程、快捷入口、日历、明日安排、近期考试；标题与内容分开作为列表项。
- [快捷入口](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/TodayScreen.kt:598) 是成绩、导入导出、课程管理、学期管理四项，跳转目前按中文文案分派。
- 已有今日/明日有效课程与考试倒计时；尚无课程进度条、课程倒计时或首页配置模型。
- [TodayViewModel](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/TodayViewModel.kt:101) 的 30 秒 tick 参与数据流订阅和整月密度推导，不能直接替换为每秒 tick。
- [TodayScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/TodayScreen.kt:103) 在 CREATED 收集；[RetainedMainTabs](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/RetainedMainTabs.kt:86) 隐藏页也在 CREATED。高频时钟必须增加“当前首页可见且前台”的条件。

## 2. 首页编辑

入口放在首页顶栏，同时在“我的 → 外观与布局 → 首页设置”提供入口。即使所有卡片隐藏，仍能重新编辑。没有课表时也允许设置布局。

编辑页由即时预览和配置列表组成，修改先存草稿；“保存”一次提交，取消丢弃草稿。返回未保存时提示保存或放弃。恢复默认也只修改草稿，保存后生效。

可编辑的六个模块：资料、今日课程、快捷入口、月历、明日安排、近期考试。每个模块用稳定 ID 表示，标题和内容按一整组排序/隐藏。

- 拖动只在编辑页的拖动柄上开始，提供上移/下移按钮供无障碍和精确操作。
- 默认顺序、显示状态与现有首页一致，避免升级后重新排列用户首页。
- 允许全部隐藏，此时首页显示精简的“编辑首页”入口；该状态与读取配置失败区分。
- 隐藏模块不删除课程、考试或账号，不停止教务同步和系统提醒。
- 新增模块默认追加到列表末尾；保留用户已有隐藏设置。未知 ID 忽略、重复 ID 去重，损坏配置回退默认并允许重新保存。

## 3. 快捷入口自选

第一版支持内置目标：课表、成绩、学分进度、考试、教室查询、课程管理、课表管理、学期管理、教务账号、导入导出、备份与恢复。

最多选 8 项，允许 0 项；顺序可调，默认保持原四项。零入口时显示添加入口提示，用户也可隐藏整个模块。手机保持两列，宽屏依现有布局空间增加列数，奇数项不撑满整行。

持久化入口 ID，不保存中文名称或任意路由字符串。课表和成绩跳主 Tab，其余走现有二级路由；学分进度和统一备份在对应模块完成后注册。登录状态不满足时，进入目标页自己的绑定提示，不静默替换入口。

## 4. 课程时间投影

首页所有课程状态、区标题、进度、倒计时和问候使用同一个当前时间。依据课程实例的实际日期、开始和结束时刻计算，不靠递减计数保持时间。

| 状态 | 展示 |
| --- | --- |
| 有进行中的课程 | 科目、时间/地点、已进行比例、距离结束的时间；保留进入课程详情 |
| 尚未开始下一门 | 下一门课程与开始时间、距离开始的时间 |
| 课间 | 保留下一门等待信息，不把上一门进度延长至课间 |
| 当天课程结束，查询窗口内还有课 | 显示下一门的日期和时间，不让用户误解为今天还有课 |
| 七天窗口内没有已知后续课程 | 明确“未来 7 天暂无已知课程”，不宣称整个学期没有课 |
| 开始/结束时刻缺失或非法 | 显示时间待确认；不补造默认时间或进度，也不让一条未知记录遮盖所有有效课程 |
| 同时存在多门进行中的课 | 稳定选择开始较晚的一门作为主展示；同起点按稳定实例 ID 排序，同时标注其他重叠课程数量并允许查看 |

课程观察范围为今天到今天后第 6 天，共七个自然日；每天切换范围，保留今日/明日原有模块。下一门只能从真正尚未开始的实例中选择，不能简单取当前课程的列表后继。

展示精度：当前课程剩余时间和不足一小时的等待时间显示分秒；不足一天显示小时/分钟；更远的课程显示日期、开始时间和天数提示。时间文字采用稳定宽度，避免每秒使布局跳动。屏幕阅读器不每秒播报，仅在课程状态切换时提供有意义的通知。

### 4.1 起止边界的明确选择

首页新投影采用实际日期时刻的左闭右开区间：开始瞬间进入课程，结束瞬间退出；相邻课程在交界时刻切换。进度限定 0～1，不出现负剩余量或超过 100%。

当前 [CourseStatusCalculator](G:/code/Android/iYango/core/domain/src/main/kotlin/com/gfs/iyango/core/domain/CourseStatusCalculator.kt:14) 包含结束分钟；[TodayCourseStateResolver](G:/code/Android/iYango/core/domain/src/main/kotlin/com/gfs/iyango/core/domain/TodayCourseState.kt:61) 虽用半开区间，但没有验证实例日期且后继可能已开始。因此新功能增加首页专用纯投影，不直接复用这两者的全部逻辑。

本期只替换首页的状态派生，原课表网格的共享计算器行为不变，修正其“全应用唯一入口”注释的适用范围。已知边界是：首页到达下课时刻立即结束，旧课表网格可能在结束分钟仍呈上课态；本期不悄悄扩大到全应用时间契约迁移。后续若统一全应用，需单独迁移课表网格和提醒相关契约并回归。

## 5. 生命周期和数据流

- 采用 [EffectiveSchedulePort.observeSelectedRange](G:/code/Android/iYango/core/domain/src/main/kotlin/com/gfs/iyango/core/domain/EffectiveSchedule.kt:206)，利用 [RoomEffectiveSchedule](G:/code/Android/iYango/data/src/main/kotlin/com/gfs/iyango/data/repository/RoomEffectiveSchedule.kt:51) 对课表、学期、课程、作息、单次调整、临时整天的观察。
- 课程或临时调课变化立即更新实例；不等待下一次计时轮询才发现变化。
- 将课程查询、月历密度与秒级时间流分离。每秒只更新小范围时间文字与进度，不重新查询数据库、不重新解析整月、不发起网络请求。
- 仅首页 Tab 活跃、未被二级页面覆盖、应用处于前台且相应时间卡片可见时启动秒级更新；隐藏今日课程模块时无需保留该秒级流。
- 返回前台、切回首页、日期/时区或系统时间变化时按真实 Clock 立即重算。跨午夜重新建立观察日期范围，不让“今日”停留在昨天。
- 现有月历密度继续响应业务数据与月份变化，不能以加速时钟为理由扩大计算频率。
- 进度动画遵循已有动画开关；关闭动画仍更新数值，不引入持续后台服务或高频系统小组件刷新。

## 6. 持久化和备份

在 AppPreferences 中增加带版本的首页偏好：卡片顺序、隐藏集合、快捷项顺序。读取、写入、校验、默认值及备份均遵守同一模型。

现有 [AppSettingsPort](G:/code/Android/iYango/core/domain/src/main/kotlin/com/gfs/iyango/core/domain/AppSettingsPort.kt:40) 接受整份偏好替换；[WallpaperViewModel](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/WallpaperViewModel.kt:179) 的锁仅在自身内部。新增首页页面不能使用“先读取旧偏好，再整份覆盖”并发保存。

计划增加仓库级原子专项更新；首页仅更新首页字段，壁纸写入也接入同一原子通道，避免保存首页时回滚壁纸。显式整份恢复只在备份协调流程持有写入权限时进行。

[DataStoreAppSettings](G:/code/Android/iYango/data/src/main/kotlin/com/gfs/iyango/data/repository/DataStoreAppSettings.kt:295) 和 [DefaultSettingsBackup 的 PreferencesDto](G:/code/Android/iYango/data/src/main/kotlin/com/gfs/iyango/data/repository/DefaultSettingsBackup.kt:374) 同步扩展。新备份保留首页设置；旧设置备份缺少首页字段时保留当前首页配置，全新安装则使用默认值，不能默默重置用户后来设置的首页。

备份解析阶段必须保留首页字段是否存在，不能先把缺失字段补成默认模型再判断恢复行为。旧文件缺失、新文件显式默认、新文件全隐藏/零快捷项是三个不同状态。旧 JSON 恢复入口同样经过新的写入协调，不能绕开统一恢复门控。

## 7. 参考源码用法

- 借鉴 [ClassIsland 课程控件](C:/Users/32773/Desktop/campus-ui-reference/10-ClassIsland/ClassIsland.Core/Controls/LessonsControls/LessonControlExpanded.axaml:227) 的贴底进度和次级时间信息，以及挂载/卸载时订阅时钟的原则。
- ClassIsland.Core 使用 LGPL-3.0，根应用及通用倒计时组件使用 GPL-3.0，且技术栈是 C#/Avalonia。本轮不复制这些实现，使用本项目 Compose 组件完成相同行为；许可区分见 [README](C:/Users/32773/Desktop/campus-ui-reference/10-ClassIsland/README.md:323)。
- 拾光 [TodayScheduleScreen](C:/Users/32773/Desktop/campus-ui-reference/4-shiguangschedule/shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/ui/today/TodayScheduleScreen.kt:220) 的时间轴和结束课程淡化可参考。其 remember 一次取时不能直接用于动态倒计时，不能整页套用后宣称已完成实时更新。
- 优先直接复用 iYango 现有玻璃卡片、颜色、课程详情、月历和导航能力，降低行为差异。

## 8. 文件与任务

以下新增文件均未创建，位于 G:/code/Android/iYango 对应模块的现有 Kotlin 包中。

| 文件/目录 | 计划职责 |
| --- | --- |
| core/model/.../HomePreferences.kt；Settings.kt | 稳定模块/快捷入口 ID、首页偏好及默认/兼容规则 |
| core/domain/.../TodayCourseTiming.kt | 输入有效实例与当前日期时刻，输出首页统一时间投影 |
| core/domain/.../AppSettingsPort.kt；data/.../repository/DataStoreAppSettings.kt | 原子偏好更新及首页持久化 |
| data/.../repository/DefaultSettingsBackup.kt；settings/WallpaperViewModel.kt | 备份字段和并发写入兼容 |
| feature/main/.../today/TodayScreen.kt、TodayViewModel.kt、TodayUiState.kt、TodayModels.kt | 模块化渲染、配置、有效实例观察和轻量时间更新 |
| feature/main/.../settings/HomeSettingsScreen.kt、HomeSettingsViewModel.kt | 编辑、预览、拖动/按键排序、保存取消 |
| feature/main/.../IYangoMainApp.kt、mine/MineSections.kt、MineScreen.kt | 编辑入口、稳定 ID 分派和主 Tab/二级路由区分 |
| core/domain 与 feature/main 的 today 测试目录；data 的 repository 测试目录 | 时间规则、生命周期、偏好并发和兼容回归 |

完整包根沿用总计划和学分子计划所列路径；既有文件引用链接可直接定位。

- [x] H1 固定稳定 ID、默认顺序、全隐藏/零入口和兼容行为。
- [x] H2 建立偏好规范化、并发保存、旧备份兼容的测试，再修改存储与写入入口。
- [x] H3 建立日期感知的起止边界、重叠、未知时间和下一门选择测试，再实现纯投影。
- [x] H4 拆分实例观察与秒级时钟；验证隐藏、后台和页面覆盖时停止高频工作。
- [x] H5 接入模块渲染、编辑预览、拖动/按钮排序及稳定导航。
- [ ] H6 待学分进度和统一备份各自实现后，再注册对应入口并联调；当前首页设置已能通过现有设置备份往返。
- [x] H7 完成独立需求/质量审查、自动化回归和 Debug 构建；设备验收按下节单独跟踪。

## 9. 验收矩阵

| 操作/情境 | 必须得到的结果 |
| --- | --- |
| 首次升级、重启、重建页面 | 默认布局不变，已保存配置可恢复 |
| 任意排序/隐藏/全隐藏、零/奇数/八个入口 | 标题内容整体移动，编辑始终可达，路由正确 |
| 草稿取消、恢复默认后取消 | 已保存配置不改变 |
| 重复/未知 ID、字段缺失、配置损坏 | 按兼容规则处理，不崩溃、不把全隐藏误判损坏 |
| 首页保存与壁纸保存并发 | 两者都保留最新字段，无整份覆盖丢失 |
| 开始前一秒、开始瞬间、结束前一秒、结束瞬间 | 时间、标题、进度来自同一状态，无负数和停留在已结束课 |
| 相邻课、重叠课、未知时间、今日无课但未来有课 | 当前和下一门选择正确，信息不被未知项整体遮盖 |
| 移课、停课、临时整天、自动选表或作息改变 | 立即响应有效实例，不使用失效课程安排 |
| 跨午夜、跨周/月、后台跨日、改系统时间/时区 | 返回立即纠正日期和时间，不靠累计 tick 补偿 |
| 首页被隐藏/二级页覆盖/切后台 | 秒级更新停止；重新出现立即更新 |
| 持续显示一分钟 | 数据库查询和整月解析次数不随秒数增长，无整页闪动 |
| 大字、TalkBack、横竖屏、暗色、动画关闭 | 排序与保存可操作，不每秒播报，视觉层次稳定 |

设备验收重点是拖动与滚动/下拉刷新的手势关系、文字宽度稳定和计时功耗；自动化通过不能替代这些观察。计划编写阶段未运行验证；本轮实施后的测试和构建已完成，设备验收仍未执行。
