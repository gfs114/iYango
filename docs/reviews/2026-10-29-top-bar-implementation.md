# 全软件顶部栏实施与验证

日期：2026-10-29。实现由 GPT 6.1 Sol 完成，主代理进行源码审查、联合验证和证据复核。

## 版本与范围

- 实施前已将当前源码、测试和设计文档同步为本地 `iYango_release` 快照：`fd200d974339997de42e43044ada843cb42c5065`，前一个 release 为 `c73b9b2187b59d6db2fa0936fba58669cd816f8e`。
- 同步采用独立临时索引，原工作分支 `iYango_prelease` 与用户暂存区均保留。诊断截图、设备数据和被忽略的构建/机器配置未加入快照。仓库没有远程地址，没有远程推送。
- 本轮顶部栏实现保留在 `iYango_prelease` 工作区；release 保存的是开始本任务之前的版本。
- 覆盖[设计矩阵](G:/code/Android/iYango/docs/reviews/2026-10-29-top-bar-design-research.md:151)中的 M01–M04、S01–S16、A01，共 21 类页面；另覆盖全屏壁纸裁剪和相关 Sheet/Dialog 标题。

## 实际实现

新增共享 [GlassTopBar](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/GlassTopBar.kt)：固定顶栏、可选副标题、返回/操作槽、可点击上下文标题、48dp 命中区域和 60dp 最小内容高度。大字号可以增高；玻璃长条保留模糊和蒙层，关闭长边透镜折射。局部浮层标题使用同一排版，并允许明确设置内容边距。

四个主页面均接入统一顶栏。今日的加载、错误和无课表状态也保留顶栏；我的标题固定，内容继续滚动；成绩保留账号脱敏和重新登录；课表直接点击周次选择，副标题跟随显示周，添加、更多和回到本周沿用原有业务行为。

根据用户后续确认，顶栏需要像底栏一样透出滚过的当前页内容。新增 [GlassScaffold](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/component/GlassScaffold.kt)：顶栏固定，首项初始避让实测标题高度，但该留白在滚动容器内部，可以随内容滚走。顶栏独立采样主题/壁纸 L0 与当前页正文 L1；正文卡片仍采样 L0，不把顶栏自身录进来源。

21 类页面均接入此布局。列表使用 contentPadding，普通滚动列先 verticalScroll 再 padding；成绩、课程管理等原固定筛选随正文滚动。课表扩大滚动视口，但行高仍按扣除标题后的原可用高度计算。窄屏显示设置的预览随设置一起滚动，宽屏保留左侧固定预览。非滚动的壁纸裁剪继续沿用原裁剪视口和坐标。

16 个二级页面与独立组件配置页完成接入。课程保存、新建课表、新建学期移动到顶部相应动作槽；课程撤销/重做、冲突确认、放弃修改仍保留。同步记录将清除放入更多菜单。即时保存的设置不新增保存按钮；导入、同步和重置仍在原有预览/说明处确认。组件配置的取消和成功结果协议保持不变。

壁纸裁剪使用独立背景宿主，顶部取消/使用沿用原裁剪像素区域、有效性判断和忙碌状态。普通系统选择器由系统管理，本轮不改系统 UI。

## 审查发现并修复

1. **背景显示与记录分离。** Backdrop 2.0.1 的记录回调不会自动绘制宿主背景。现在 [GlassBackdropHost](G:/code/Android/iYango/core/designsystem/src/main/kotlin/com/gfs/iyango/core/designsystem/liquidglass/GlassBackdrop.kt)明确铺主题底色，并在记录主题底色后调用 `drawContent()`；壁纸进入背景记录，前景仍作为兄弟层排除在外。课表顶栏使用已解析的专属/全局回退壁纸。
2. **返回预览遮挡。** 二级页保留原实体内容背景，并将该背景录入本页正文来源，避免返回预览时后方主页面文字穿过表单间隙或顶栏。根页面显式透明以保留壁纸；独立窗口仍拥有自己的主题背景。
3. **浮层边距。** 已有 16dp 外层留白的五处局部标题显式传零内部留白，未留白的容器继续使用默认值，避免标题与正文错位。
4. **标题测量。** 标题 Text 明确占满分配的标题槽；采用原生图形测试检查实际中文字形、无省略和无宽度溢出，不以 Robolectric LEGACY 的假字宽判断真实显示。
5. **透视根因。** 旧布局把正文放在标题下方，顶栏只录到 L0，因此调低蒙层也无法显示滚过的正文。本次修正布局与来源，未调整玻璃透明度、卡片或底栏材质参数。
6. **遮挡区交互。** 顶栏空白处拦截命中，避免点击背后不可见的列表项，同时保留标题、返回和动作按钮。课表拒绝在顶栏区域拖放；暂存放置读取最新标题高度与坐标，并使用用户确认的节数，保留第 11/12 节可达性。
7. **安全区与刷新。** 下拉指示器留在标题下方；根页面不重复加 Dock 已包含的底部安全区；显示设置页横向安全区由父层消费一次，840dp 分栏判断沿用可用宽度。

## 验证结果

JDK：`C:/Program Files/Java/jdk-17`。

```text
gradlew.bat :core:designsystem:testDebugUnitTest :feature:main:testDebugUnitTest :platform:android:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

滚动透视修正后的最终联合执行：**BUILD SUCCESSFUL，34 秒**。以下统计来自该轮结束后的 XML；未重复计入此前定向执行的测试。

| 模块 | 测试数 | 失败 / 错误 / 跳过 |
|---|---:|---|
| core:designsystem | 15 | 0 / 0 / 0 |
| feature:main | 213 | 0 / 0 / 0 |
| platform:android | 2 | 0 / 0 / 0 |
| app | 0，任务为 NO-SOURCE | 不作为测试通过数量 |
| 合计 | **230** | **0 / 0 / 0** |

本次在首次顶栏实现的 217 项基础上新增 13 项，覆盖滚动前后顶栏像素变化、大字号动态 inset、本页背景隔离、顶栏命中、四个主页面及真实二级页面滚动、课程拖放边界和标题高度动态变化。原有标题、保存/冲突/撤销、课表字形与行高、暂存调课至最后两节等回归一同通过。

背景采样测试直接读取记录图层的图像，区分“壁纸可见”和“壁纸已进入采样”。这些是 Robolectric 原生图形测试，不代表手机 GPU 上的模糊/折射视觉或帧率验收。

主代理已查看[滚动前](G:/code/Android/iYango/build/diagnostics/top-bar-transparency-20261029/scroll-before.png)与[滚动后](G:/code/Android/iYango/build/diagnostics/top-bar-transparency-20261029/scroll-after.png)的受控色块对照：红色正文滚入后，固定标题区域呈现红色采样。旧 Scaffold 的同一对照不变化。另有真实触摸回归先复现顶栏穿透误点两次，修正后为零。这些是测试渲染与输入结果，不是真机截图。

证据：[联合构建日志](G:/code/Android/iYango/diagnostics/topbar-transparency-20261029/verification-final.log)、[测试统计与 APK 摘要](G:/code/Android/iYango/diagnostics/topbar-transparency-20261029/verification-summary.json)、[绘制诊断](G:/code/Android/iYango/build/diagnostics/top-bar-transparency-20261029/README.md)、[release 同步记录](G:/code/Android/iYango/diagnostics/topbar-adaptation-20261029/release-sync.json)。Release 快照仍为 `fd200d9`；本次没有提交或推送。

## 产物与验证边界

- [Debug APK](G:/code/Android/iYango/app/build/outputs/apk/debug/app-debug.apk)，17,077,731 字节。
- SHA-256：`962e5ebe3a369fd5fae33ddd3123db2137d75bffa1bbc9e732ad0417540b0af1`。
- android-cli 已用于核查安装环境及官方 inset 文档；检测到手机正在其他应用后询问用户，用户明确要求先完成代码和构建。本轮未对手机输入、安装或启动应用。
- 待设备验收：真实壁纸下顶栏材质、不同系统版本的模糊/折射降级、TalkBack 播报、横屏/大字号显示以及返回和切页手势性能。
