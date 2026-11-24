# 自选算分内直接切换学期（2026-11-13）

## 完成行为

- “课程属性”面板增加学期入口，可直接选择单学期或全部学期；选择后面板保持打开，列表勾选和统计随之更新。
- 入口中的“学期范围…”打开原有范围面板，选好或关闭后直接返回课程属性，无需重新进入自选算分。
- 主页面的学期切换同样保留自选模式。按属性选中的条件会用于新范围，从该范围的成绩重新匹配课程。
- 某学期没有匹配属性或没有缓存课程时，显示空选择并保留自选操作；切回有匹配课程的学期后恢复匹配。
- 同范围查询更新成绩时，按保留的属性重新选择新记录；旧课程 ID 和旧上下文回调不会影响新记录。
- 逐门调整仍可使用。部分取消不取消该属性的跨学期选择条件；取消某属性的最后一门课会移除该条件。“清空”“退出”和切换账号会清理条件；全选采用当前可用的全部属性。
- 单独手动勾选的课程不作为跨学期条件迁移。纯手动选择在快照替换时继续使用原有清空与重新选择提示。

## 实现

`GradesUiState.selectionAttributes` 保存本次自选操作需要沿用的属性。课程复选框和统计继续使用 `selectedRecordIds`。范围改变先清理旧记录和 ID、推进选择版本，再在新缓存返回时匹配属性；账号和范围校验继续拦截过期回调。

属性面板复用现有学期选择行，提供单学期下拉和范围入口。学期/缓存变化期间保持面板，切换账号、离开成绩标签或退出自选时关闭。空学期也显示底部计算栏，保留继续选择和退出操作。

## 验证

新增 8 项测试：6 项范围/状态测试及 2 项界面测试。覆盖单学期与范围切换、属性暂时缺失后切回、清空/退出、快速连续切换、空学期、逐门取消、缓存替换后重新匹配、过期回调，以及范围面板直接返回属性面板。原有大字号界面测试同时检查新增入口。

最先执行的 4 项新行为测试在实现前全部失败，实现后通过。已检查普通字号与 320dp/实际 2 倍系统字号的 Native Graphics 渲染：`build/diagnostics/grade-selection-terms-20261113/attribute-term-switch.png`、`attribute-term-large.png`。

本次未操作手机、真实教务账号，未提交或推送。

最终验证命令：

```powershell
.\gradlew.bat :core:domain:test :core:designsystem:testDebugUnitTest :data:testDebugUnitTest :platform:android:testDebugUnitTest :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain
```

754 项测试通过：领域 134、设计系统 20、数据 164、Android 平台 36、主功能 368、应用 32；0 失败、0 错误、0 跳过。Debug、Benchmark 构建成功。日志和计数：`build/diagnostics/grade-selection-terms-20261113-full.log`、`grade-selection-terms-20261113-results.json`。

本次改动 4 个既有生产文件和 3 个既有测试文件，并新增本记录。改动前 285 个未提交文件中，其余 278 个哈希保持一致；差异空白检查通过。

安装包 SHA-256：

- Debug：`D9090AD5E03F981FDCFC5846B1BE1971B11D056227C955D5684E69D23D833AC8`
- Benchmark：`8CAC6C0EBD6C915508CE5EFEA639B1E7F6576B19754C432C810BCFF4DDC148B0`
