# iYango 全软件顶部栏统一设计与参考调研

实施状态（2026-10-29）：用户随后授权实施；21 类页面及相关浮层已经接入，代码与构建结果见[实施与验证记录](G:/code/Android/iYango/docs/reviews/2026-10-29-top-bar-implementation.md)。以下保留原调研与设计内容，文中“本轮只写文档”描述的是前期调研阶段。

日期：2026-10-29。范围：整个 iYango 的顶部栏。依据本地 `campus-ui-reference` 十仓筛查、重点顶栏源码与截图核对、GitHub 搜索，以及 iYango 全部页面入口与当前实现对照。

**整个软件采用同一套“紧凑通栏玻璃底板 + 左对齐标题 + 语义明确的导航与操作槽”。覆盖 4 个主页面、16 个二级页面、1 个独立桌面组件配置页，以及应用自有全屏裁剪和各类浮层标题。首版全屏页面的顶部栏均固定；各页只按用途改变标题、副标题和动作。**

全量页面依据 [主页面声明](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/IYangoMainApp.kt:179)、[导航路由](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/IYangoMainApp.kt:505)与 [WidgetConfigActivity](G:/code/Android/iYango/app/src/main/java/com/gfs/iyango/WidgetConfigActivity.kt:167)核对。页面矩阵见第 6 节。显示设置的全局/单课表范围、课程新建/编辑/复制作为同类页面的不同状态列入，不重复计算页面数量。

本文是设计建议，没有修改应用代码。下文的尺寸和交互方案属于提案；源码事实、仓库截图和待实施方案分别标明。没有运行参考项目，没有构建、安装或操作手机，也没有验证滚动帧率。

## 1. 当前项目的约束

| 当前实现事实 | 设计上的影响 |
|---|---|
| [UI 基准](G:/code/Android/iYango/docs/contracts/ui-baseline.md:11)规定标题槽 60dp，加状态栏避让；标题 20sp；图标盒 40dp | 常规字号下继续控制顶栏高度；不能为了玻璃效果额外增加一整行装饰 |
| [ScheduleHeader](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/ScheduleScreen.kt:414)显示“第 N 周”、学期和今日日期，右侧为周选择、添加、更多 | 周次本身可以承担选择入口，释放日历按钮占用的宽度 |
| [TodayScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/TodayScreen.kt:266)以滚动资料卡开始；[MineScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/mine/MineScreen.kt:111)以账号卡和功能分组开始 | 两页当前并没有与课表完全相同的固定标题栏；统一时需要处理资料卡与顶栏的信息重复 |
| [GradesHeader](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/grades/GradesScreen.kt:114)承载成绩标题、账号连接状态和重新登录 | 连接异常必须继续明确可见，不能只剩一个不明含义的图标 |
| 多数二级页已经使用 Material 3 `TopAppBar`，例如 [WallpaperScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/WallpaperScreen.kt:117) | 统一材质与动作槽即可，避免每个页面重写导航行为 |
| [GlassTokens](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/GlassTokens.kt:14)已有导航、内容、控件、浮层四类材质 | 顶栏沿用导航材质；按钮沿用控件材质，不引入第二套玻璃引擎 |

现有基准文档部分功能描述已经落后于当前源码，例如显示设置与成绩功能。本文按当前源码判断现状；不把旧文档里的“建设中”当成仍然缺失的功能。

## 2. 本地参考仓库：哪些值得借鉴

筛查了十个目录，重点核对前四仓的顶部结构。其余仓库主要用于判断技术栈和参考边界，没有逐页运行验收。

| 仓库 | 核对到的事实 | 对 iYango 的借鉴 |
|---|---|---|
| [XJAU-Helper](https://github.com/FengHDSL/XJAU-Helper) | ArkUI；[PageHeader](C:/Users/32773/Desktop/campus-ui-reference/1-XJAU-Helper/entry/src/main/ets/view/common/UiKit.ets:398)集中组织状态栏高度、返回、标题/副标题和右侧操作。背景包含主题色渐变，模糊受样式条件控制 | 借鉴统一头部组件、状态栏连续背景、标题层级和独立返回按钮。它不是一份可直接移植的 Compose 液态玻璃顶栏 |
| [HiXD](https://github.com/PollenWang6/HiXD) | ArkUI；课表使用自定义导航标题，另有[周导航行](C:/Users/32773/Desktop/campus-ui-reference/2-HiXD/entry/src/main/ets/pages/classtable/ClassTablePage.ets:1852)，包含左右箭头和日期范围；低频操作在菜单中 | 借鉴“浏览周次”和“管理操作”分组。手机端不照搬额外的 40vp 导航行，大屏才考虑把箭头常驻展示 |
| [sachet](https://github.com/wyvern1723/sachet) | Flutter；[ClassPageAppBar](C:/Users/32773/Desktop/campus-ui-reference/3-sachet/lib/widgets/classpage_widgets/classpage_appbar.dart:222)根据周/月/学期视图切换标题控件，周标题本身是下拉选择器；翻页箭头可以按设置显示 | 借鉴上下文标题直接可操作，以及按空间和使用场景显示动作。不据此给 iYango 增加未提出的月/学期视图 |
| [拾光课表](https://github.com/ShiGuangSchedule/shiguangschedule) | Compose Multiplatform；[WeeklyScheduleScreen](C:/Users/32773/Desktop/campus-ui-reference/4-shiguangschedule/shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/ui/schedule/WeeklyScheduleScreen.kt:213)使用居中顶栏，点击周次打开选择，右侧切换课表；容器透明 | 最适合借鉴周次标题的点击语义和精简操作。iYango 有学期副标题及更多操作，因此建议保留左对齐，不机械照搬居中 |
| [HutHelper](https://github.com/isnine/HutHelper-Open) | [README](C:/Users/32773/Desktop/campus-ui-reference/5-HutHelper/README.md:3)说明本地仓库为 Objective-C iOS 客户端 | 可作校园产品信息组织参考，本轮不作为 Android 顶栏组件来源 |
| [云小智](https://github.com/danbaixi/yunxiaozhi) | 微信小程序，[app.json](C:/Users/32773/Desktop/campus-ui-reference/6-yunxiaozhi/app.json:100)启用自定义导航 | 可参考标题与业务筛选的分工；小程序导航和安全区计算不能直接迁入 Android |
| [scuplus-wechat](https://github.com/mohuishou/scuplus-wechat) | Vue 页面配置小程序导航标题，例如[课表页](C:/Users/32773/Desktop/campus-ui-reference/7-scuplus-wechat/src/pages/schedule.vue:190) | 可参考页面命名和操作入口；本轮没有发现足以优先采用的 Compose 顶栏实现 |
| [ShellBox](https://github.com/Airmole/ShellBox) | 小程序，[app.json](C:/Users/32773/Desktop/campus-ui-reference/8-ShellBox/app.json:50)配置自定义导航 | 仅作信息布局参考，不复用其导航适配代码 |
| [TimetableView](https://github.com/zfman/TimetableView) | Android View 课表控件；[示例首页](C:/Users/32773/Desktop/campus-ui-reference/9-TimetableView/AndroidTimetableView/app/src/main/res/layout/activity_main.xml:2)是功能案例入口 | 更适合参考课表网格能力；本轮不把示例首页当作成品顶栏设计 |
| [ClassIsland](https://github.com/ClassIsland/ClassIsland) | 本地[工程配置](C:/Users/32773/Desktop/campus-ui-reference/10-ClassIsland/ClassIsland/ClassIsland.csproj:4)关闭 WPF，包含 Avalonia 相关配置 | 桌面工具的窗口操作与手机触控不同，本轮不采用其窗口栏布局 |

### 截图与源码的关系

实际查看了以下本地仓库截图：

- [XJAU 考试查询截图](C:/Users/32773/Desktop/campus-ui-reference/1-XJAU-Helper/docs/screenshots/phone/01.jpg)：左侧圆形返回、左对齐标题和副标题、右侧文字操作；截图处于详情弹层打开状态，页面被遮罩压暗。不能据此把灰暗背景判定为顶栏正常颜色。
- [拾光周课表截图](C:/Users/32773/Desktop/campus-ui-reference/4-shiguangschedule/picture/Screenshot_1.png)：居中周次、下拉提示、右侧切表，下面紧接星期表头。支持“减少常驻按钮”的布局判断；截图并不能证明运行时玻璃或滚动性能。

### 源码复用边界

本轮没有复制参考仓库代码。根目录许可证检查结果：XJAU、HiXD、sachet、TimetableView 为 MIT；拾光为 Apache-2.0；云小智、ClassIsland 为 GPL-3.0；ShellBox 为 AGPL-3.0。scuplus-wechat 根目录未找到许可证文件。HutHelper 的 README 标注 Apache，但 `LICENSE.txt` 是 GPL v2 文本，两者不一致，后续若复用源码需先核实。

上述许可记录只用于区分“借鉴布局”和“复用代码”，不是对整个仓库所有资源的许可保证。

## 3. GitHub 搜索补充

通过 GitHub 连接器检索了 AndroidLiquidGlass、Haze、Now in Android，以及 Compose 顶栏相关关键词，并读取了以下一手源码或文档。筛选重点是与当前 Compose 项目兼容、组件职责清楚，而不是堆叠视觉效果。

| 来源 | 核实内容 | 采用方式 |
|---|---|---|
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | [README](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/README.md)明确库本身不提供高层组件，目录中提供按钮、滑块、底栏等示例；[ProgressiveBlurContent](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/destinations/ProgressiveBlurContent.kt)演示了带透明渐变遮罩的背景模糊 | 用现有 Backdrop 构建项目自己的顶栏组件。渐变示例可用于研究顶栏底边过渡，不能把演示中的 128dp 高度和参数直接当作产品规格 |
| [chrisbanes/haze](https://github.com/chrisbanes/haze) | [Recipes](https://github.com/chrisbanes/haze/blob/bac0b12493eb5da154c483b591a8c018bce29adf/docs/recipes.md)展示透明 `TopAppBar` 与背景模糊的组合 | 借鉴“标准导航结构 + 背景效果”的分层；当前项目继续使用 Backdrop，不新增 Haze 依赖 |
| [android/nowinandroid](https://github.com/android/nowinandroid) | [NiaTopAppBar](https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/core/designsystem/src/main/kotlin/com/google/samples/apps/nowinandroid/core/designsystem/component/TopAppBar.kt)在设计系统中封装标题、导航图标、动作图标及无障碍描述 | 借鉴统一组件和动作语义；是否居中由 iYango 页面信息密度决定 |

这三个仓库读取到的根许可证均为 Apache-2.0。GitHub 分支内容可能继续变化；上表中 Haze 和 Nia 使用本次检索返回的固定版本链接，Backdrop 使用本次读取的 `kmp` 分支链接。

Android 官方提供小型、居中、中型、大型顶栏及固定/随滚动变化的行为选择。本项目首版统一采用固定紧凑顶栏；后续如引入大标题折叠，应作为共享组件的统一变体单独验证，不能由各页随意决定。这是项目设计选择。[官方 App bars 文档](https://developer.android.com/develop/ui/compose/components/app-bars)

## 4. 三种方案比较

| 方案 | 结构 | 适用性 |
|---|---|---|
| A：标准 Material 顶栏 | 标准标题/返回/操作布局，实体或轻透明底色 | 实现成本低，适合作为降级与结构基线；与已有玻璃底栏的材质联系较弱 |
| **B：通栏轻玻璃 + 精简操作（推荐）** | 状态栏背景与顶栏连续，左标题、右操作；材质轻，边缘过渡柔和 | 能保留课表空间，又能与现有底栏、壁纸统一；适合全项目建立公共规范 |
| C：整条悬浮胶囊 | 顶栏左右及上方留白，标题和操作全部放进大胶囊 | 适合极少内容的工具页；学期副标题、多操作和大字号会挤在一起，不作为 iYango 默认顶栏 |

推荐 B 的关键是让标题和操作清楚，同时让背景参与材质呈现。顶栏不复制底部导航的“液滴滑动选择器”：顶栏动作没有相同的互斥选中关系。

## 5. 全软件共用的顶部栏规范

### 5.1 统一外观与尺寸

| 部位 | 全软件统一规则 |
|---|---|
| 背景 | 轻玻璃通栏，延伸到系统状态栏背景；左右贴合窗口，不把整条顶栏包成悬浮大胶囊 |
| 高度 | 常规字号时内容槽 60dp，系统状态栏 inset 另计。单行标题垂直居中；双行标题作为一组居中。大字号允许增高，不裁切文字 |
| 水平排列 | 主页面标题从页面左边距 16dp 开始；二级页面先放返回命中槽，再放标题。可选操作固定靠右 |
| 标题 | 左对齐，建议 20sp、适度加粗。全项目使用同一字体令牌，不在各页单独指定不同 titleLarge/titleMedium |
| 副标题 | 可选的 12–14sp 辅助文字，只承载日期、当前范围或必要状态。不为了对齐强行补一行空白 |
| 图标 | 20–24dp；玻璃视觉底座约 40dp，独立命中槽至少 48×48dp，布局中为它分配空间 |
| 操作数量 | 通常 0–2 个。必须显示的提交文字优先，其余操作收入更多；不为了左右对称增加无业务意义的按钮 |
| 按钮材质 | 返回、添加、更多等图标使用同一圆形控件底座；保存等文字动作使用统一圆角文字按钮。破坏性操作不用普通主操作样式 |
| 滚动 | 按后续用户确认，顶栏固定且正文可滚入其背后；共享 GlassScaffold 负责本页正文采样与实测标题留白。非滚动裁剪保留原视口 |
| 反馈 | 点击立即调用动作；禁用、加载、保存中使用明确状态，不用液滴移动表示执行完成，也不等动画结束再跳转 |

这是对旧 [UI 基准](G:/code/Android/iYango/docs/contracts/ui-baseline.md:11)的设计修订提案：保留 60dp 紧凑方向，提出统一标题对齐及明确命中槽；当前代码和旧基准文件均未在本轮修改。

### 5.2 四种全屏变体，加一种浮层标题

共享同一个外壳与材质，只提供以下用途明确的变体：

| 变体 | 左侧 | 中间 | 右侧 | 使用范围 |
|---|---|---|---|---|
| 主页面 | 无返回 | 页面名或可操作的当前上下文 | 页面主要操作，可留空 | 今日、课表、成绩、我的 |
| 普通二级页 | 返回 | 页面名，可带范围说明 | 新增、刷新或更多，可留空 | 管理、查询、设置、诊断等 |
| 编辑/配置页 | 返回或取消，沿用原有离开逻辑 | 新建/编辑对象 | 保存、保存并添加等明确提交动作 | 课程编辑、桌面组件配置 |
| 全屏临时任务 | 取消或关闭 | 当前任务名称 | 当前任务确认动作 | 应用内壁纸裁剪；未来同类任务沿用 |
| 浮层内部标题 | 仅在有子层级时显示返回 | 选择/编辑/详情名称 | 按浮层需要显示关闭或确认 | Sheet、Dialog，不额外叠加页面顶栏 |

导入、同步、备份这类有预览、范围和确认步骤的流程，使用普通二级页标题。最终“确认导入/写入/重置”与预览或警示内容保持相邻，不为了顶栏统一把这些提交按钮搬成一个笼统的“保存”。

### 5.3 全软件的结构示意

以下均为设计示意，不是运行截图。空白操作槽表示该页无需动作按钮。

```text
主页面：   今日                              [账号]
           10 月 29 日 · 第 N 教学周

管理页面： [返回]  课程管理                    [＋]

普通设置： [返回]  壁纸与背景

编辑页面： [返回]  编辑课程                  [保存]

临时任务： [取消]  调整取景                  [使用]
```

账号入口可复用今日现有资料入口，但不新增账号能力；保存或使用按钮是否可点由原有校验与忙碌状态决定。普通设置页没有保存按钮是因为该页本身采用即时生效，不能把所有页面强行改为同一种提交方式。

### 5.4 课表页在统一规范下的具体安排

以下文字布局是提案，不是运行截图。方括号表示可点击区域；状态栏高度由系统提供。

```text
系统状态栏                 与顶栏连续的背景

[ 第 8 周  ▾ ]                         [＋] [⋮]
2026 秋季 · 10/26—11/01
─────────────────────────────────────────────
      一    二    三    四    五    六    日
                    课程网格
```

1. **周次标题直接可点。** 点击标题及下拉箭头所在的完整区域打开现有周选择器；移除与它重复的独立日历按钮。不要求长按才能发现入口。
2. **右侧保留添加与更多。** 添加沿用现有业务入口；更多继续包含显示设置、上一周、下一周、回到本周、恢复周和临时日程等现有操作，不因精简顶栏而删除功能。
3. **副标题优先解释正在看的内容。** 建议从“学期 + 今日日期”改为“学期 + 显示周日期范围”。例如切到下一周后，副标题也跟着变化；没有学期日期时显示明确的未设置状态。这是信息语义调整，尚未实施。
4. **回到本周容易找到。** 周选择面板顶部保留“回到本周”，同时保留更多菜单中的入口。常规手机宽度下不额外占用常驻标题宽度；平板或横屏可展开文字按钮与上一周/下一周。
5. **普通字号下保留 60dp 标题槽。** 不额外叠加一行周导航；星期表头仍属于课表网格，不算进顶栏。字体放大后允许内容区增高，不能用固定高度裁掉副标题。
6. **顶栏保持固定。** 切周、垂直滚动、课程拖动时都能立即找到周选择和添加；不通过滚动隐藏这些操作。周切换沿用现有手势，标题只增加直接选择入口。

## 6. 全量页面适配矩阵

下列“现状”来自当前源码；“目标”均为待实施设计。全部采用第 5 节的固定顶栏和统一材质。右侧留空是明确设计，不代表遗漏。

### 6.1 四个主页面

| ID / 页面 | 当前入口与现状 | 目标标题 / 副标题 | 右侧与交互 |
|---|---|---|---|
| M01 今日 | [TodayScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/today/TodayScreen.kt:266)，列表以资料卡开始 | 今日 / 日期与教学周；新增统一顶栏，资料卡继续承载身份与问候 | 可复用资料入口作为账号按钮；加载、无课表、错误和正常状态都保留相同顶栏 |
| M02 课表 | [ScheduleHeader](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/ScheduleScreen.kt:414)，自定义双行标题 | 第 N 周下拉 / 学期及显示周范围；未设置时显示课表与对应状态 | 添加、更多；标题打开周选择。详见 5.4 |
| M03 成绩 | [GradesHeader](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/grades/GradesScreen.kt:114)，自定义标题和连接状态 | 成绩 / 账号连接状态，保持账号脱敏 | 登录过期保留明确的重新登录；学期、排序、查询仍属于筛选区，不用图标替代异常说明 |
| M04 我的 | [MineScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/mine/MineScreen.kt:111)，账号卡与功能分组 | 我的；账号卡保留完整身份信息，顶栏无需重复副标题 | 留空；已有外观、布局等入口继续在功能分组中 |

### 6.2 十六个二级页面与一个独立页面

二级页默认左侧返回。涉及位置调整的动作要复用现有回调、可用条件和错误处理，不产生第二套业务逻辑。

| ID / 页面 | 源码中的顶栏与提交现状 | 目标右侧操作及必须保留的行为 |
|---|---|---|
| S01 新建/编辑课程 | [CourseEditorScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/editor/CourseEditorScreen.kt:136)，返回会检查未保存修改；保存、撤销、重做位于底部操作区 | 建议将主提交入口统一到顶栏“保存”，安排数量放在表单摘要；撤销/重做保留原操作区。新建、编辑及复制后的新建共用该变体；保留冲突确认、未保存离开提示与现有写入语义。不能增加一个独立、重复提交的保存实现 |
| S02 课程管理 | [CourseManageScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/coursemanage/CourseManageScreen.kt:88)，返回 + 新增 | 保留新增；搜索和筛选留在内容区，删除确认不改变 |
| S03 教务账号 | [SchoolAccountScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/school/SchoolAccountScreen.kt:94)，返回，登录相关动作在正文 | 留空；登录、切换账号、退出等动作与账号说明保持相邻，不加通用保存按钮 |
| S04 同步教务课表 | [SchoolTimetableSyncScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/school/SchoolTimetableSyncScreen.kt:92)，返回，选择学期与确认写入在正文 | 留空；刷新学期、预览、勾选和确认写入保持各自位置；网络加载与数据库写入不能由返回或普通标题点击触发 |
| S05 同步记录 | [SyncHistoryScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/synchistory/SyncHistoryScreen.kt:90)，全部已读 + 清除记录 | “全部已读”作为文字动作；清除收进更多菜单，保留确认。窄屏时两项都可进更多，未读数与禁用状态保留 |
| S06 考试安排 | [ExamListScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/exam/ExamListScreen.kt:104)，返回 + 新增考试 | 保留新增，打开现有编辑 Sheet；考试过滤和内容统计不挤入标题行 |
| S07 教室查询 | [ClassroomQueryScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/classroom/ClassroomQueryScreen.kt:94)，返回 + 刷新查询 | 保留刷新及账号、登录、忙碌等可用条件；楼栋、日期、节次等查询条件在内容区 |
| S08 我的课表 | [TimetableManageScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/timetable/TimetableManageScreen.kt:81)，顶栏只有返回，新建在正文 | 建议新建入口移至顶栏“＋”；课表切换、重命名、删除、作息规则保留在对应课表项及浮层，不在顶栏重复显示整组操作 |
| S09 学期管理 | [SemesterManageScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/semester/SemesterManageScreen.kt:79)，顶栏返回，分组有新建学期 | 建议将新建入口统一到顶栏“＋”；学期编辑和节次时间编辑继续使用原浮层 |
| S10 课表显示 | [DisplaySettingsScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/DisplaySettingsScreen.kt:69)，修改即时保存 | 右侧留空；可用副标题说明全局默认/当前课表覆盖。保留当前范围切换、预览和恢复默认，不新增保存或退出丢弃逻辑 |
| S11 壁纸与背景 | [WallpaperScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/WallpaperScreen.kt:117)，返回；分别处理全局与课表、明暗壁纸 | 右侧留空；选择图片进入现有裁剪任务，页面其他设置沿用原保存方式。清除和明暗目标仍在对应区域明确显示 |
| S12 提醒与日历 | [ReminderCalendarScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/ReminderCalendarScreen.kt:90)，返回，设置动作位于各组 | 留空；保留即时设置与权限入口，顶栏返回不能申请权限或隐式写入系统日历 |
| S13 导入与导出 | [ImportExportScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/importexport/ImportExportScreen.kt:132)，返回，文件操作与导入确认在正文 | 留空；确认导入与预览保持相邻；保留重复导入、追加/替换等已有确认语义，不增加顶栏“一键导入” |
| S14 导出课表 | [ScheduleExportScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/export/ScheduleExportScreen.kt:150)，返回 + 刷新数据 | 保留刷新数据；导出动作继续与输出设置及预览对应。顶栏玻璃不能画进导出的图片 |
| S15 设置备份与重置 | [SettingsBackupScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/SettingsBackupScreen.kt:108)，顶栏返回，正文有导出/恢复/重置 | 留空；危险操作继续与作用范围、说明及确认放在一起，不在右上角增加容易误触的重置图标 |
| S16 关于与诊断 | [AboutScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/about/AboutScreen.kt:107)，返回，诊断和日志动作在正文 | 留空；各项诊断、导出、清理操作仍属于对应内容区 |
| A01 组件设置 | [WidgetConfigActivity](G:/code/Android/iYango/app/src/main/java/com/gfs/iyango/WidgetConfigActivity.kt:167)，独立 Activity；返回语义为取消，正文有保存并添加 | 左侧沿用取消；建议将“保存并添加”移入顶栏提交槽，保持原成功/取消结果协议。建立独立背景与材质来源，不依赖主 Activity 的玻璃层 |

### 6.3 全屏裁剪和浮层标题也纳入统一范围

这些界面没有独立导航路由，但同样有用户可见的顶部区域。统一排版、关闭入口和材质层次；系统提供的界面由系统负责。

| 界面组 | 已核对源码入口 | 目标规则 |
|---|---|---|
| 壁纸全屏裁剪 | [WallpaperCropDialog](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/WallpaperScreen.kt:413)：当前标题调整取景、右侧取消、底部使用裁剪结果 | 使用全屏临时任务变体：左取消、中标题、右使用；“使用”沿用裁剪校验和保存忙碌状态，无障碍名称保留“使用裁剪结果”。原底部重复提交入口可移除，保留必要说明。采样与预览必须位于本 Dialog 的作用域内 |
| 周次选择、临时日程 | [WeekPickerSheet](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/WeekPickerSheet.kt:48)、[TemporaryDaySheet](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/ScheduleScreen.kt:606) | Sheet 标题描述当前任务，选择与回到本周等动作明确；局部头部不再次添加系统状态栏 inset，不叠一条全屏顶栏 |
| 课程详情、调课、删除范围 | [CourseDetailDialog](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/coursedetail/CourseDetailDialog.kt:96)、[ScheduleAdjustmentDialog](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/coursedetail/ScheduleAdjustmentDialog.kt) | 详情顶部保留课程名称和关闭入口；调课提交、删除范围与警示保持各自语义，不能用关闭代替取消或提交 |
| 考试新建/编辑及详情 | [ExamEditorSheet](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/exam/ExamListScreen.kt:296)、[ExamDayDialog](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/schedule/ScheduleWeekGrid.kt:1003) | 标题区分新建/编辑/详情；确认和关闭保持明确，不要求浮层撑满屏幕来套用全屏顶栏 |
| 课表命名、规则编辑，学期和节次编辑 | [课表管理浮层](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/timetable/TimetableManageScreen.kt:289)、[SemesterSheet](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/semester/SemesterManageScreen.kt:255) | 同类表单共用标题和确认/取消位置；保留保存规则、保存学期等具体含义 |
| 日期、时间、周次、星期、节次选择 | [EditorSheetHost](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/editor/CourseEditorScreen.kt:432)及使用同类选择器的页面 | 选择器标题与当前字段对应；采用浮层标题规范，保留原有确认/取消行为，不对所有选择器额外加返回图标 |
| 登录异常、删除、放弃修改、重复导入、恢复和重置确认 | 各页面现有 AlertDialog；例如[未保存离开提示](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/editor/CourseEditorScreen.kt:259) | 统一标题字体和留白；警示内容与确认、取消按钮保持清楚。确认对话框不承载应用导航顶栏 |
| 系统照片选择、文件选择、分享、权限弹窗 | 例如壁纸页使用的系统照片选择器 | 不修改系统标题栏，不覆盖系统控件；返回应用后恢复应用自己的顶栏 |

页面中的“今日课程”“外观”等 SectionHeader 是内容分组标题，星期/日期行是网格表头，桌面小组件标题属于小组件内容。这些不升级为应用顶部栏；它们与顶栏沿用同一字体体系，但各自保留布局职责。

## 7. 液态玻璃与 Android 布局约束

### 材质

- 顶栏用导航材质，保持文字与图标清晰。全宽背景以模糊、主题蒙层和轻微底边过渡为主；明显的折射轮廓主要留给小型按钮，避免整条长边持续扭曲内容。
- 图标和文字绘制在效果层前方，不参与折射。用前景语义色与底板遮罩保证繁杂壁纸上的可读性，不能仅凭壁纸整体“偏亮/偏暗”决定一切。
- 顶栏覆盖状态栏的**背景**，可点击内容位于系统 inset 下方。由顶栏或外层布局中的一个明确负责人处理 inset，避免 `Scaffold` 内边距与 `statusBarsPadding` 重复叠加。
- 顶栏初始内容避让与列表滚入背景是两件事：首项不能被遮住；顶部留白放在滚动内容内部，滚走后顶栏采样进入该区域的本页正文。

### 采样范围

沿用[现有全项目玻璃适配文档](G:/code/Android/iYango/docs/plans/2026-10-27-liquid-glass-full-adaptation.md:111)的分层原则：主题/壁纸是 L0，页面内容是 L1，导航绘制在它们之上。

[GlassBackdropHost](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/GlassBackdrop.kt)只记录背景。后续实现已增加 [GlassScaffold](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/GlassScaffold.kt)，仅在顶栏子树提供 L0 与本页正文 L1 的组合来源，排除顶栏自身；正文卡片继续使用 L0。课表沿用已解析的专用壁纸或全局回退壁纸。

课表的滚动视口扩展到顶栏背后，顶部占位随内容滚走；自适应行高仍按扣除标题后的原可用高度计算，保留初始网格布局。具体实现与测试边界见[实施记录](G:/code/Android/iYango/docs/reviews/2026-10-29-top-bar-implementation.md)。

### 触控、适配与动画

- 可见图标建议 20–24dp，视觉底座可沿用约 40dp；操作命中区域至少 48×48dp。当前源码的 `.size(40.dp)` 不能单独证明实际触控不足，Compose 可能扩展命中范围；实施时仍需明确布局间距，避免相邻命中区域重叠。[Android 默认无障碍行为](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- 标题建议沿用 20sp，副标题 12–14sp；这些是本项目提案值。长学期名可省略并在选择面板显示完整内容，按钮与返回不能被挤出。
- 小屏与大字号优先保留标题及必要操作，其余进菜单；平板才展开箭头和文字动作。
- 点击立即执行动作，按压动画只提供反馈，不等待动画结束再打开页面或弹层。顶栏不加入跨按钮液滴拖动。
- 减少动态效果或设备能力较低时，采用现有能力档对应的实体/半透明底板；操作、对比度、状态反馈保持一致。

## 8. 后续实施范围与验收

实施范围以第 6 节完整矩阵为准。顺序建议为：先建立共享顶栏的材质、标题、导航、操作和 inset 契约；再替换 16 个二级页和独立配置页；随后统一 4 个主页面及其加载/错误/空状态；最后处理全屏裁剪与浮层标题。可以分批验证，但只有完成全部范围才算全软件适配完成。组件名和 API 在实施时确定，本文不新增实现代码。

后续需要单独验收：

1. 明暗主题、纯色/高亮/复杂壁纸、课表独立壁纸及回退背景下，标题均可辨认。
2. 周次点击直接打开选择器；切周后副标题对应显示周；添加、更多、恢复和临时日程等入口完整。
3. 首行星期、课程内容、下拉刷新指示器不被顶栏挡住，也没有双重状态栏留白。
4. 窄屏、横屏、平板、大字号和 TalkBack 下，必要操作可达；点击范围不会相互抢占。
5. 成绩账号过期、无课表、未设置学期、编辑未保存等状态有明确文字反馈。
6. 滚动、切周和切底栏期间，玻璃层不递归采样、不出现残影；性能用设备数据与手势验证，不能由静态源码推断通过。
7. 按 M01–M04、S01–S16、A01 逐页检查：没有遗留第二套字号、返回样式或安全区留白；主页面无多余返回，二级页面返回路径正确。
8. 课程保存、即时显示设置、裁剪使用、桌面组件保存并添加、导入/同步确认分别验证，顶栏统一没有改变提交时机或产生重复写入。
9. 全屏裁剪、普通 Sheet、Dialog 与系统选择器之间切换时，没有双重顶栏；关闭后回到原页面、原选择状态和正确背景。

本轮交付仅为本调研文档。应用源码、构建产物、设备安装和设备验证均未在此次调研中更改或执行。
