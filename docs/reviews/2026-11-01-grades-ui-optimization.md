# 成绩页 UI 优化（P1–P4）：实施与验证

日期：2026-11-01（Asia/Shanghai）。按 [方案](G:/code/Android/iYango/docs/plans/2026-11-01-grades-ui-optimization.md) 实施：本轮由主代理直接编码并自查（原方案的“GPT 6.1 Sol 编码 + 主代理审查”分工在当前会话中合并执行）。本轮不提交、不推送、不安装（方案 §1.4），真机视觉/手势验收未做。

## 变更清单

新增（`feature/main/src/main/kotlin/com/gfs/iyango/feature/main/grades/`）：

| 文件 | 职责 |
|---|---|
| `GradeSelectionBar.kt` | P1 固定计算栏：操作行（已选 N 条/全选/清空/退出，FlowRow 换行）、三项指标、纳入排除、包含选修 + 说明；内容高度封顶屏幕高 40% 并允许栏内滚动；`pointerInput{}` 吸收实体栏空白点击 |
| `GradesFilters.kt` | P2 紧凑查询行（学期 + 草稿标签 2 行 + 查询按钮）、`TermPickerSheet`（全部学期/学校列表/本机缓存回退，radio 语义、48dp 行）、列表标题行 + 排序 `DropdownMenu`（短标签/完整文案/勾选标识） |
| `GradeStatisticsContent.kt` | P3 共享指标行（headlineSmall、缺失朗读“暂无数据”）、普通统计卡（自选算分入口/统计说明入口）、自选提示条（统计卡同 key 占位）、统计说明面板（§7.2 全部 8 条口径）、集中格式化函数 |
| `GradeRecordCard.kt` | P4 课程卡：名称最多两行、元信息可换行、「新/更新」移入辅助区、学分/属性/考试性质分层；选中态 = 复选框 + accent 1dp 边框 + 内部 10% 淡色背景（未用 `tintColor`）；宽度 < 360dp / 字体 ≥ 1.5× / 分数原文 > 8 字符时转上下布局 |

修改：

- `GradesScreen.kt`：页面装配重写。`GradesPageLayout` 签名保持原顺序（8 个页面级回调补空默认值便于测试驱动），内部新增：共享 `LazyListState`（普通/自选共用）、公共统计派生值（单一 `remember` 计算）、`GradesOverlay` 枚举浮层互斥、`GlassScaffold.bottomBar` 插槽挂计算栏（仅活动标签 + 有账号 + 有记录 + 自选模式显示）、`BackHandler` 改为“先浮层后退出自选”、账号/范围/快照变化或切走主标签时关闭浮层（`LaunchedEffect`）。
- 测试：`GradeSelectionUiTest` 适配新文案/固定栏；`MainTopBarsTest` 学期路径改为“打开面板→选学期→查询”，保留正文滚入顶栏断言。

新增测试（`feature/main/src/test/kotlin/com/gfs/iyango/feature/main/grades/`）：`GradesLayoutUiTest`（U02/U03 几何断言、方案样本统计期望值、U24 模式切换保位）、`GradesFiltersUiTest`（U10–U13、加载/离线/空列表）、`GradesOverlayUiTest`（U15/U16/U18）、`GradesCompactWindowUiTest`（320×568/2 倍字号：高度封顶、首行操作可达、栏内滚动）。

## 与方案的偏差

1. **测试文件多一个**：方案 §11 列 3 个新测试文件，实际 4 个——极矮窗口矩阵需要独立 `@Config` qualifiers（w320dp-h568dp + 2 倍字号），故拆出 `GradesCompactWindowUiTest`。
2. **页面级回调补默认值**：`GradesPageLayout` 的 `onSelectRange` 等 8 个无副作用回调增加 `= {}` 默认值（方案未约定；原签名要求全量传参，补默认值后新测试可直接驱动，原调用点不受影响）。
3. **任务 0 基线截图未产出**：未做像素级首屏对比图。以方案 §12 任务 0 样本数据的统计期望值测试（选 1、2 → 86.67/3.00/3；全选 → 85.83/3.30/6、纳入 3 排除 2）+ 滚动几何断言替代；“393×852 首屏位置变化”未实测，不声称任何紧凑百分比。
4. **长分数判定用确定性代理**：方案 §8.2“分数原文超过两行可用长度”无法低成本测得文本溢出，改用 `scoreText.length > 8` 代理，叠加方案给定的宽度/字体缩放判定。
5. **面板加「关闭」按钮**：学期面板与统计说明面板头部 actions 槽增加「关闭」文字按钮（方案未明说；便于确定性关闭路径与辅助功能，下滑/返回/scrim 仍可关闭）。
6. **U07 纯“全部排除”与 U23 栏体点击穿透未自动化**：全部排除的“学分 0”分支未单独断言；`pointerInput{}` 防穿透已实现但无自动化断言（Robolectric 无法验证兄弟节点命中穿透）。

## 验证结果

- `:feature:main:testDebugUnitTest`（全量）：**264 tests, 0 failures, 0 errors, 0 skipped**（XML 报告核对，含 6 个成绩测试类与 MainTopBarsTest）。
- `:core:domain:test`：**128 tests, 0 failures**。
- `:app:assembleDebug --offline`：`BUILD SUCCESSFUL in 44s`（149 任务）。
- `git diff --check`：无空白错误。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
  SHA-256 `4885215D873E993DA59336A8C8AF10D8AB928FFFA775537136AD0A9A1440E9B4`。
- ViewModel 未修改；`GradesViewModel` 契约与既有 `GradeSelectionViewModelTest` 全部保持通过。

## 未验收项

- 真机视觉：玻璃折射观感、选中态在深/浅色与壁纸下的对比度、计算栏遮挡与三键/手势导航避让（U23 实测）。
- 真机交互：横竖屏与极矮窗口的实机表现、TalkBack 朗读顺序、帧率（未测量，不作声明）。
- 下方 P5（提示整合）/ P6（详情底部面板）未实施，属方案 §15 独立后续项。
