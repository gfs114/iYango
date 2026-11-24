# P2 UI 基准（视觉与交互契约）

依据：ClassSchedule-exp（**主基准**，下称"参考"）+ campus-ui-reference 十仓（辅）。写码与审查都以此文为准。
标签约定：〔参〕=来自参考实现；〔辅〕=来自参考仓吸收的模式；〔定〕=本项目裁决。

## 1. 设计令牌与主题系统

### 1.1 令牌（对齐参考 DesignToken.ets）
间距 XS4/SM8/MD12/LG16/XL24/XXL32；圆角 SM8/MD12/LG16/胶囊 999；字号 CAPTION12/BODY_SM14/BODY16/TITLE20/HEADLINE28；控件高 48；图标盒 40；页面水平 padding 16；列表项距 12；列表底部预留 84（浮动底栏）。
头部：通栏液态玻璃（`RoundedRectangle(0.dp)`，内容槽高 ≥ 60dp + 状态栏避让，含 `colorControls` + `blur` + `lens` 折射，子控件采样 `exportedBackdrop`），标题左对齐 20sp SemiBold；右侧最多 2 个 40×40 液态玻璃图标（48×48 命中槽）+ 溢出菜单。下拉刷新触发距离 96dp。
底栏：高 52dp、悬浮（底部边距 24、左右 16、宽 ≤420）、图标 22sp + 文案 12sp 间距 2；选中 = accent + Bold，未选中 = TAB_UNSELECTED。

### 1.2 语义色（对齐参考 color.json，Android 侧定义在 designsystem）
| 语义 | 浅色 | 深色 |
|---|---|---|
| appBackground | #F2F1F6 | #0D0D0D |
| surface | #FFFFFF | #1C1C1E |
| surfaceAlt | #F3F6FB | #26262A |
| textPrimary/Secondary/Tertiary | #182431/#5E6670/#6D7781 | #E6E6E6/#9BA3AD/#7C8591 |
| success/warning/error | #21A675/#C38933/#E84026 | #4CC38A/#E8B339/#FF6B5B |
| border/divider | #DCE3EC/#E6EBF2 | #33333A/#2A2A2F |
| gridSurface | #FFFFFF | #161618 |
| gridTodayColumn | #F0F5FF | #191F2B |

### 1.3 主题色系统（AccentColors）〔参〕
6 预设（light/dark 成对）：classic #C2410C/#F08C4A、azure #2563EB/#5B9BFF、forest #0F8A5F/#3DD68C、violet #6D4AE0/#A78BFA、sakura #DB2777/#F472B6、lake #0E8FA8/#2AC9DB；支持自定义 #RRGGBB（默认按 classic 浅色）。
派生规则〔参〕：textOnBrand 用 YIQ≥150 定黑/白墨；brandSoft = accent @ alpha 0x26（≈15%）；页面背景 = accent 与基底 8% 混合（开关）；cardTint = accent 与 surface 10% 混合（开关）。P2 先落地：预设切换 + brandSoft/onBrand/染背景三派生；自定义色输入与染页开关放设置页。

### 1.4 课程色板 CourseSwatch〔参〕12 色池 + 明暗配对
accent：BLUE #3E7BFA、GREEN #2BB673、AMBER #F2A03D、RED #F2634E、PURPLE #8B6BF0、TEAL #2BB2B2、PINK #EE5A96、GREY #7A8794、INDIGO #5E6AD2、CYAN #2E9FD9、OLIVE #7C9B3F、BROWN #B08050。
每色提供 fill/tint/ink/inkSoft 四层 × 明暗（例 BLUE：tint #C9DCFE / tintDark #1B3358 / ink #12325F / inkDark #D6E4FF；fill #2F63D9 / fillDark #254A9E）。**网格卡用 tint 底 + ink 字**（柔和洗色深墨字，非饱和底白字）；列表圆点/标签用 ink。分配：按 (课名+教师) 分组保色，新组按课程 id % 12 从池取；已有 color 字段（#RRGGBB）的课走 resolve 派生（感知亮度选墨）。〔辅·XJAU〕底/字成对预调保证对比度。

## 2. 今日页（today 包，对齐参考 TodayView）

自上而下（Refresh + 惰性列表，项距 12，水平 padding 16）：
1. **资料卡**〔参〕：Surface R16 padding16；左列姓名+问候 20sp Bold、学号·班级 12sp Secondary（无绑定显示"未绑定教务账号，点击去同步"）；右 chevron；点击→登录（P2 无登录，点击提示"建设中"）。
2. **区块标题**〔参〕：3×16dp 圆角 2 accent 竖条 + 16sp Bold + 右侧 trailing 12sp。顺序：今日课程 / 快捷入口 / 日历 / 明日安排。
3. **今日课程**〔参〕：错误态/加载态/空态用 StateView（空态主按钮"查看课表"次按钮"管理课程"）；全部结束=🎉+“今日课程已全部结束”卡；正常=focusCard（状态标签 12sp Medium accent："正在上课/下一门/今日最后一门/时间待确认"）+ courseRow 列表。
4. **courseRow**〔参〕：Surface 卡；Row[时间列宽 48（开始 14sp Medium/结束 12sp Tertiary）| 8dp 圆点(ink 色) | 课程名 16sp Medium(≤2行)+状态胶囊+教室·教师 12sp | chevron]。点击→课程详情弹层（见 §4）。
5. **快捷入口**〔参〕：frosted 容器（surfaceAlt 底 R16）内栅格 2 列（宽屏自适应 2-4）；quickTile 高 48、brandSoft 底、图标 22sp accent + 14sp Medium 文案；P2 四项=成绩/导入导出/课程管理/学期管理（成绩点击提示建设中）。
6. **月历卡**〔参〕：学期名 14sp Bold + 周次胶囊（12sp accent，brandSoft 底 R8）+ 开学/结束日期胶囊；星期表头 一~日 12sp Tertiary；7 列月格，格 28dp 圆，今天=accent 底+onBrand 字 Bold；当日有课月格下加 3dp 圆点（ink 色）〔定，取自月历卡课程密度点约定〕。
7. **明日安排**〔参〕：无课=图标+“明天 · X月X日”+“明天没有课，好好休息”；有课=同 courseRow（48 时间列+圆点）。

## 3. 课表页（schedule 包，对齐参考 SchedulePage/ScheduleGrid）

结构：Column[顶部标题区（标题槽 60+避让：标题"第 N 周"20sp Bold + 副标题"学期名 · 2026/9/26 周五" 12sp Secondary；右侧图标行=周选择、添加课程、显示设置(→提示建设中)、回到本周）→ 网格面板（R16 容器 padding 8）]。学期未设置=StateView("下拉同步教务课表，或在「我的」中创建学期")。

**网格几何**〔参·ScheduleLayoutMetrics〕：
- 时间轴列宽 40（随字号放大）、表头高 45；日列宽 =(容器宽-40)/天数，最小 40；行高默认 77，范围 44-80，段数 12（MAX 20）。
- 课程块定位：x=40+(day-1)*colW+0.5，y=(startSection-1)*rowH+0.5；块宽=colW-1、高=rowH*rowSpan-1；块内 padding 4、圆角 4。
- **冲突组**〔参〕：同 day+startSection 分组，组内只画第一门 + 数量徽章（accent 底 pill）；点击弹详情可翻页。〔辅·shiguang 的贪心分列防遮挡〕列为 P3 后增强，P2 用参考的"徽章+详情翻页"方案。
- 课程块内容〔参〕：名称 Bold 13sp（窄列 11）→ 可选开始时间 → "正在上课"(accent Medium) → @教室 → 教师 → 周标记（非本周/单周/双周）；行数按块高预算。**tint 底 + ink 字**；运行中块=1.5dp accent 描边+底部 2dp 进度条；非本周块 fill 用 38% 透明度降权〔参 offWeekOpacity〕。
- **当前时间指示器**〔参〕：accent 2dp 横线 + 左端 HH:mm 药丸（9sp onBrand），按分钟线性插值，30s 刷新；混合校区 09:45-12:00 时不画。
- **今日列 wash**：gridTodayColumn 底色整列。
- **切周**：左右箭头 + 水平滑动（翻周动效 translate ±28dp + 淡入 260ms；picker 跳周纯淡入）〔参〕；到学期边界停止。

**周选择半模态**〔参·WeekPickerSheet〕：底部 ModalBottomSheet，标题"选择周次"+学期名副标题；周芯片 56×68 R12 网格（滚动区 maxHeight 480）：选中=accent 底 onBrand 字 Bold；本周未选=brandSoft 底+1dp accent 描边；其他=surfaceAlt 底；芯片内=周号 14sp+"本周"标记行(10sp 占位)+7×4 密度点阵（3dp 点，取自 weekDayCounts）。

## 4. 课程详情弹层 + 单次调整（对齐参考 CourseDetailDialog/Flow）

- 居中 Dialog：标题=课名、副标题=教师；内容滚动（maxHeight 320）："调整课程"文字按钮（id>0 且非 override 实例）→ 分隔线 → 信息行×N（18sp 图标 accent + 12sp Secondary 标签 + 14sp 值）：周次（"1-16 周 单周 · 每 3 周第 1 周"格式化）、节次与时间（自定义钟点/指定日期如实展示）、教师、教室、备注、资料（简称/学分/户外）；override 实例显示"单次调整"标记。按钮行：编辑 / 复制 / 删除（error 字）/ 关闭。
- 删除→ActionSheet 三选：仅取消本周这次 / 删除这条安排的所有周次 / 删除整门课的所有安排 → AlertDialog 确认〔参〕。
- **ScheduleAdjustmentDialog**〔参〕：目标周/星期/起止节选择 + "仅本周生效" Switch（P2 固定开）+ 冲突提示（warning 12sp）→ 确认调课（写入 override 记录，受唯一约束）。

## 5. 课程编辑页（新建 editor 包，对齐参考 CourseEditPage）

结构：Scroll → Column(space 16, padding 16)：
1. **基本信息卡**（Surface R16 padding16）：标题 16sp Medium；"复用已有科目"选择（accent 字 brandSoft 底 高 48，下拉=同课表现有课程名去重）；LabeledInput×3（课程名称必填 max64 / 简称 max32 / 学分）；户外/启用 Switch 行；备注 TextArea（高 100 max300，surfaceAlt 底 R12）+ 字数 N/300。
2. **上课安排卡 ×N**：标题"上课安排 N"+移除（error）；教师/地点输入；时间模式三选（按节次/每周自定义时间/指定日期）；按节次=星期 Select + 起/止节 Select（高 48 surfaceAlt R12）；自定义=起止时间 TimePicker；指定日期=DatePicker（可多个日期→每日期一条安排〔定：简化为单日期，多日期后续〕）；周次摘要行+"选择周次"按钮；轮换两个数字输入（每几周/轮换第几周）；颜色区=12 个 40dp 圆点（选中 ✓+2dp 描边）。
3. **操作条**（底部固定）：错误消息（error 14sp）+ Row[撤销 76 | 重做 76 | 保存("保存 N 条安排") flex，高 48 R12]。
4. 行为〔参〕：撤销/重做栈（连续文本编辑合并）；离开守卫（未保存 Dialog）；保存流程=validate→冲突预检（Dialog 警告可继续，文案"发现课程冲突：X，是否继续添加？"）→保存组→toast→返回；冲突检测用 ConflictDetector。
5. 单次调整入口：详情弹层"调整课程"→ScheduleAdjustmentDialog；编辑页不承载单次调整。

## 6. 多课表管理（timetable 包，对齐参考 TimetablePage）

页面大标题"我的课表"20sp Bold + 说明 + "新建课表"主按钮；课表卡列表（Surface R16 padding16；当前卡=2dp accent 描边）：图标+课名 16sp Medium+当前 ✓；来源/学期/规则说明行；"设为当前"按钮（高 44 surfaceAlt）；操作胶囊"名称与规则/复制/删除"。删除需确认 Dialog（列明影响：课程 N 条/学期/节次一并删除）；LOCAL 才可删。

## 7. 学期与节次（semester 包，对齐参考 SemesterPage）

"已有学期 · N"；学期列表卡（行=名称 16sp+“当前学期”胶囊+“开学 X · 共 N 周 · 第 M 周”12sp Tertiary+设为当前按钮+编辑/删除）；新建/编辑=底部 Sheet（名称/开学日期 DatePicker/总周数 1-30）；节次时间表管理：默认 11 节可编辑起止（TimePicker），支持恢复默认〔参 TimeSlot 语义〕。

## 8. JSON 导入导出页（importexport 包，对齐参考 ImportPage + W5 服务）

两分区卡：**备份当前课表**（说明+“导出到文件”→SAF CreateDocument("application/json")，文件名来自 ExportScheduleService）；**从文件导入**（"选择文件并预览"→OpenDocument→ImportScheduleService.preview→预览区：课程数量/解析问题可折叠"解析提示（N）"列表→写入模式单选：新建课表 / 替换某本地课表（列出本地课表）→"确认导入"主按钮；重复文件=警示行+“仍要导入”二次确认；页脚"文件读写全部在本机完成，不会上传服务器"）。

## 9. 我的页改造（mine 包，对齐参考 MineView）

头部卡（账号占位）+ 分组菜单：课表管理（课程管理→编辑器列表入口/我的课表/学期管理）、数据（导入导出）、外观（主题色 6 预设横排选择器〔参 SettingPage accentCard〕+ 自定义 hex 输入 + 染背景开关）、关于（建设中提示）。

## 10. 跨页面共性（全部页面遵守）

1. 顶部栏：标题槽 60+避让、标题 Bottom 对齐、≤2 图标+溢出；次级页=返回+标题+动作。
2. 状态呈现：StateView（图标+20sp 标题+14sp hint+按钮）；错误消息 error 14sp 置操作条上方；Toast 用系统。
3. 弹层：居中详情/确认=Dialog；选择器/编辑器=ModalBottomSheet（拖动条+关闭）；范围选择=ActionSheet。
4. 底部导航维持四页；隐藏底栏的次级页用全屏 route 覆盖。
5. 数据刷新：所有页面数据经 Room Flow 自动更新，不自行过滤课程（必须走 ResolveScheduleOccurrences）。
6. 文案全部中文；新 Compose 文件放对应业务包，不跨包改他人文件。
