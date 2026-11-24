# W4 任务规格：feature:main 四页骨架 + app 装配（纵向原型）

任务编号：P1-06 / P1-07 / P1-04 ｜写码：GLM-5.3-Flash ｜审查：GLM-5.3

## 0. 边界

- 允许修改：`feature/main/src/`、`app/src/`（MainActivity、Application、绑定模块）。**不要修改任何 build.gradle.kts / settings / gradle.properties / data / core 模块**。
- 删除占位 `FeatureMainContract.kt`。
- 已有依赖：compose-bom、material3、material-icons-core、lifecycle-*-compose、navigation-compose、hilt-navigation-compose、hilt。全部可用，不要新增依赖。
- 数据端口：`core/domain/.../RepositoryPorts.kt`（TimetableRepositoryPort、ScheduleDataPort）与 `ResolveScheduleOccurrences`、`CourseOccurrence`、`CourseStatusCalculator`。**先读这些文件**。实现类在 data（W3 并行编写中），你只面向接口编程。
- 注释中文；文案中文；硬编码字符串可接受（P1 原型）。

## 1. feature:main（包 com.gfs.iyango.feature.main）

### 1.1 状态结构（P1-07）
每页 `sealed interface` UiState，至少区分：`FirstLoading`（首次无数据加载）、`Ready(data, refreshing: Boolean)`（有数据+可选刷新中）、`Empty`、`Error(message, canRetry)`。禁止一个 isLoading 表达所有状态。

### 1.2 TodayViewModel + TodayScreen（包 today）
- 注入 TimetableRepositoryPort、ScheduleDataPort、`Clock`。
- combine(currentTimetable, semester, slots, courses) → 用 `ResolveScheduleOccurrences.invoke(table, semester, slots, courses, today..today)` 与 `(tomorrow..tomorrow)` 生成实例；当前/下一课用 CourseStatusCalculator 对今日实例判定（startMinute/endMinute + now）。
- UiState 内容：weekNumber（0=假期/未设置）、todayOccurrences（含状态徽标所需分钟）、currentOccurrence、nextOccurrence、tomorrowOccurrences、timetableName。
- Screen：顶部资料卡（课表名+第 N 周 / "假期中"）；"正在上课/即将开始" StatusBadge；今日课程列表（时间、名称、教室、教师、时间待定显式提示）；明日安排 SectionCard；空态/错误态用 designsystem 组件；内容加载中显示旧数据+顶部小刷新提示（Ready(refreshing)）。
- 下拉刷新：P1 阶段仅触发本地重算（PullToRefreshBox 或省略——若实现，只刷新内存快照，不发网络）。

### 1.3 ScheduleViewModel + ScheduleScreen（包 schedule）
- 状态：currentWeek（从学期推算初始）、displayedWeek、按周缓存的 occurrences。
- 切周：上一周/下一周按钮 + 顶部"第 N 周"标题；重算用 ResolveScheduleOccurrences（该周一..周日）。
- 网格：列头一~日；行按当周出现的节次范围；课程块背景 paletteColor(courseId 循环取色)，显示名称+教室；时间待定（startMinute=null）块显示"时间待定"文字；单次调整实例加"调"角标；同一天多节次块按顺序堆叠。滚动竖向即可（横向滚动 P2 再做）。
- 点击课程块：底部弹层或详情 Dialog 显示全部字段（名称/教师/教室/周次/节次/备注，调整实例标注"单次调整"）。

### 1.4 GradesScreen（包 grades）
占位：EmptyState("成绩查询将在后续版本提供")。ViewModel 可省，直接静态占位 Composable。

### 1.5 MineScreen（包 mine）
- MineViewModel：观察课表列表与当前课表；显示：当前课表名、课表数量、"设置"与"关于"占位入口（点击提示"建设中"）。
- 提供切换当前课表的最小操作：若有多个课表，弹出选择列表调 setCurrent。

### 1.6 导航（包 IYangoMainApp.kt）
- `@Composable IYangoMainApp()`：Scaffold + NavigationBar（今日/课表/成绩/我的，material icons：Today/CalendarMonth/School/Person）+ NavHost 四条类型化 route（"today"、"schedule"、"grades"、"mine"）。各 Screen 用 hiltViewModel()。
- 保持单 Activity 单栈导航意图；不引入双栏。

## 2. app 装配（包 com.gfs.iyango）

- `MainActivity`：`setContent { IYangoTheme { IYangoMainApp() } }` + enableEdgeToEdge（保持现状）。
- `IYangoApplication`：持有 `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`，onCreate 中 launch 调 `DemoSeeder.seedDemoIfEmpty()`（try-catch 记录 Log）。
- `di/AppBindings.kt`：@Module @InstallIn(SingletonComponent)：
  - @Binds TimetableRepositoryPort → RoomTimetableRepository；ScheduleDataPort → RoomScheduleDataPort。
  - @Provides fun clock(): Clock = Clock.systemDefaultZone()。
  - @Provides IYangoDatabase（allowDestructive = BuildConfig.DEBUG）与 DemoSeeder。（如 provider 放在 data 更合适也可，绑定必须在 app。）
- res/values/strings.xml：app_name 保持 iYango。

## 3. 通过标准

- `./gradlew :feature:main:compileDebugKotlin :app:assembleDebug` 通过。
- 四页可切换；Today/Schedule 不允许自行推导"某天有没有课"（必须走 ResolveScheduleOccurrences，code review 会查）。
- grep feature:main 无 `androidx.room`、无 data 包 import。
- 最终回复：文件清单、构建结果、UiState 覆盖情况、遗留问题。
