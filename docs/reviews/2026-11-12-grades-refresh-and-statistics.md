# 成绩刷新与统计区域核查（2026-11-12）

用户反馈刷新成绩后出现两条登录过期提示，并要求将“统计说明”移到“统计包含公选课”文字旁。核查覆盖学校成绩客户端、查询仓储、账号/查询状态、离线缓存、统计计算、自选算分及界面布局。

## 已复现并修复

1. **全部学期查询误报登录过期**：`YguGradesClient.fetchInternal` 在返回前将当前 Cookie 放进 `YguGradesOutcome.cookieHeader`，随后在 `finally` 中清空临时 CookieJar。仓储却在下一次请求时调用 `client.cookieHeader()`，取得空 Cookie；即使学校上一响应正常，也会在本地被判为登录失效，进而清空已存凭据的 Cookie 并将账号标记为 EXPIRED。现在每个请求均接续上一结果里的 Cookie；学期列表、单学期和全部学期查询也将返回的新 Cookie 正确保存。保留客户端结束时清理临时凭据的行为。
2. **刷新失败提示重复、重新登录后旧提示残留**：登录失效统一由带“去登录”入口的缓存提示承载，不再同时展示通用错误条或重复胶囊；真实 401 仍标记失效，403/普通网络错误不会被改成登录过期。账号状态恢复后清理旧提示，缓存仍保留。
3. **取消及过期请求影响当前界面**：查询在 `finally` 中释放加载状态，并用请求代次、账号、范围保护结果；账号会话版本变化时取消旧查询，迟到的旧会话错误不能覆盖新登录状态。
4. **统计设置位置**：按用户后续澄清，普通统计卡与自选计算栏共用设置行，开关紧邻“统计包含公选课”文字，“统计说明”（自选栏为“说明”）靠最右侧。窄屏大字号下左侧标签/开关可以换行，按钮和开关分别响应操作。

已经被旧逻辑标记过期并清空 Cookie 的账号，需要在新版本中重新登录一次；本次没有伪造或直接恢复账号有效状态。

## 其他核查范围

- 加权均分、学校返回的绩点、公选课排除、等级成绩换算、自选记录统计保持现有计算规则。
- 成绩仓储仍仅保存完整成功结果；账号、具体学期与全部学期快照沿用现有独立分区规则，本次未改写数据库或合并缓存。
- 保留主题色和现有 Liquid Glass 提示组件。

## 验证

新增 16 项自动化检查：学校会话链路 7 项、页面查询状态 6 项、界面 3 项。修复前首先运行的会话测试 6 项中 3 项失败，界面/状态测试 8 项中 7 项失败，均对应上述根因；新增边界用例也已通过。

模拟 HTTP 注入仅用于测试，实际执行真实 CookieJar、表单/表格解析与仓储流程，覆盖全部学期直查、逐学期回退、直查缺少学期字段后的回退、Cookie 续期、真实 401 与 403。没有访问学校服务器或真实账号。

```powershell
.\gradlew.bat :core:domain:test :core:designsystem:testDebugUnitTest :data:testDebugUnitTest :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain
```

共 **689 项测试通过**：领域 134、设计系统 20、数据 164、主功能 339、应用 32；0 失败、0 错误、0 跳过。Debug、Benchmark 构建成功，包含 Benchmark R8 与 lintVital。日志：`diagnostics/grades-review-20261112-full.log`。

本地 Native Graphics 渲染已检查：`build/diagnostics/grades-review-20261112/normal.png` 与 `large-text.png`。这不是手机截图，也不代替真实设备验收。

保留客户端原有 CRLF 行尾，避免整文件格式变更；`git -c core.whitespace=cr-at-eol diff --check` 通过。改动前记录 257 个既有未提交文件的哈希，其中 5 个为本次授权修改，另外 252 个内容未变；客户端为原先干净的新增修改路径。未提交或推送。

安装包 SHA-256：

- Debug：`65DEF71357061BA9612390FA33C5AEDA1E133D45FC64AFF6AE0D1AF544AAA088`
- Benchmark：`7FEEAE85B8252897DE64E7A0C794BA04407E38EED27B2ABDD369D5F2DF587261`

未安装到手机，真实教务查询及手机视觉/触控验收尚未进行。

## 控件位置澄清后的验证

用户澄清“按钮”指开关，所需顺序是“统计包含公选课 → 开关 → 右侧统计说明”。本次只调整共享设置行与对应几何断言，查询和统计行为不变。

`GradesNoticeLayoutTest`、`GradesCompactWindowUiTest`、`GradesOverlayUiTest` 共 7 项界面测试通过；已查看普通字号与 2 倍字号的本地渲染图。Debug、Benchmark 重新构建成功。日志：`diagnostics/grades-controls-position-20261112.log`。这是本次布局调整后的定向验证，前文 689 项为上一轮完整核查结果。

最新安装包 SHA-256：

- Debug：`82E06B8B2EFF9C280962B1EB165CB365D1875ADD4E5C91B9BBD573DE10BAE4BD`
- Benchmark：`734265C9AF87AEDA005ADCA50A23085C41FA7463B3CA8FA5A0AE2F138AA96DB9`

仍未安装到手机。

## 字号与开关尺寸统一

按用户“字和按钮同样大小”的要求，设置行两侧文字统一使用 `bodySmall`。开关的可见高度跟随该文字行高缩放（默认约 16dp），保留至少 48dp 的点击区域和唯一的开关无障碍语义；仍是左侧文字紧邻开关、右侧说明。

上述 7 项界面测试再次通过，补充验证小开关外围点击区域仍能切换；已检查关闭、开启及 2 倍字号渲染。Debug、Benchmark 构建成功，未安装。日志：`diagnostics/grades-control-size-20261112-targeted.log`、`diagnostics/grades-control-size-20261112-build.log`。

本轮安装包 SHA-256：

- Debug：`9F3F7BB89354155D5FAA272479FAFB8DCAF3EA683307D170D618879970FA6584`
- Benchmark：`0C68BFB230125574BFC28882D55147EDE37B0C71FB0DA6165A4E46927133E920`
