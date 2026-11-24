# 多页面刷新状态与结果提示修复

日期：2026-11-12，Asia/Shanghai。分支：`iYango_prelease`。

用户反馈多个页面刷新后持续转圈，无法知道是否成功。本次先检查协调器、各页面 ViewModel 与顶部结果提示，再通过隔离测试复现、修复；没有使用真实学校账号或操作手机。

## 确认的问题与修复

1. **取消后仍保留 RUNNING**：处理器取消时原实现直接抛出，前台代次变化又会丢弃旧完成事件，部分资源因此无法清理旧状态。现在取消、前台切换和会话变化均明确结束旧状态。
2. **旧查询覆盖新查询状态**：同资源不同查询共用一个状态槽，原实现只校验会话和前台代次。现在每次实际请求分配独立编号，只有该资源最新请求能够发布完成状态；同条件仍共享在途请求。
3. **超时无法及时结束界面等待**：仅在执行器内部使用 `withTimeout`，遇到阻塞的网络操作时取消不能及时完成。现在调用方等待也有独立的 30 秒上限，到期结束界面等待并取消任务。教室查询及历史学期请求同样增加上限。底层阻塞 I/O 不保证立即退出，但迟到任务不能覆盖新的刷新状态。
4. **后台自动同步延长手动转圈**：今日、课表和考试页原先把全局后台状态合并进手动刷新圆圈。现在圆圈由本页手动操作控制，后台自动同步继续执行。
5. **反馈缺失或误报成功**：今日页现在区分部分失败、全部失败与会话失效；未登录时明确只更新本地数据。考试页读取真实结果；成绩与教室失败也展示顶部提示，同时保留原有错误区域及缓存。课表后台同步成功和历史学期手动刷新均给出结果。
6. **同步冲突误报成功**：学校同步因本地修改冲突而未提交时，现在提示重新同步，不再当作成功。
7. **提示呈现**：顶部胶囊停留 3 秒；非活动成绩标签页消费迟到提示。课表刷新反馈后续按用户要求调整为同步完成后提示，见下文“课表同步反馈语义修正”。

超时处理依据 Kotlin 官方说明：[`withTimeout` 的取消需要任务配合](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout.html)。本次用真正阻塞的测试处理器验证调用方能够按时返回，不以可取消的 `delay` 代替该场景。

## 验证

新增 19 项测试，其中 15 项在对应修复前复现失败。覆盖取消、前台切换、查询竞争、相同请求合并、阻塞请求超时、各页面反馈、缓存保留、历史学期成功提示、冲突、顶部提示可见性及再次显示。

真实线程解析测试保留线程隔离断言，改用真实时钟，避免虚拟超时抢在真实工作线程完成之前触发。Compose 动画测试显式同步帧与界面队列后检查提示显示和消失。

最终命令：

```powershell
.\gradlew.bat :core:model:test :core:domain:test :core:designsystem:testDebugUnitTest :data:testDebugUnitTest :feature:main:testDebugUnitTest :platform:android:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --console=plain
```

结果：退出码 0，`BUILD SUCCESSFUL`。按本次 Gradle 测试任务的 XML 报告核对；未变化任务允许 Gradle 增量复用结果。

| 模块 | 测试数 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| core:model | 27 | 0 / 0 / 0 |
| core:domain | 134 | 0 / 0 / 0 |
| core:designsystem | 16 | 0 / 0 / 0 |
| data | 157 | 0 / 0 / 0 |
| feature:main | 328 | 0 / 0 / 0 |
| platform:android | 36 | 0 / 0 / 0 |
| app | 32 | 0 / 0 / 0 |
| 合计 | 730 | 0 / 0 / 0 |

Debug 与 Benchmark 均构建成功，Benchmark 包含 R8 与 lintVital。`git diff --check` 通过。首次离线完整构建因缺少 `lint-gradle:32.4.0` 缓存失败，随后联网构建成功；未因此修改依赖版本或项目配置。

日志：`diagnostics/refresh-20261112-red.log`、`refresh-20261112-classroom-red.log`、`refresh-20261112-background-red.log`、`refresh-20261112-conflict-red.log`、`refresh-20261112-notice-ui.log`、`refresh-20261112-full-online.log`。

## 产物与验收边界

| 构建 | 路径 | SHA-256 |
| --- | --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | `6E85AC1AEBCCB36B16D07D6035A9F0F85C0697667A08315B9637F3512D819864` |
| Benchmark | `app/build/outputs/apk/benchmark/app-benchmark.apk` | `6E09317AA48D3953884C881C42357EFAC591C1D2F919D20637B12826980907FA` |

未安装、未进行真实学校网络验收。手机上的下拉手势、真实弱网和学校登录过期场景仍需设备验收。测试使用合成数据，没有输出密码、Cookie 或认证页面内容。

开始时记录了 228 个已有改动文件的哈希。本轮只在其中 11 个与刷新有关的文件上继续修改，其余 217 个文件内容保持不变。没有提交或推送。

## 后续提示样式调整

按用户提供的截图调整统一胶囊：青绿色 `#419588` 底色、白色 15 sp 粗体、40 dp 单行高度、24 dp 圆角及轻阴影，居中置于顶栏下方 16 dp。左右各留 24 dp，最大宽度 360 dp，长文案自动换行。沿用 3 秒停留及淡入淡出、上下滑动动画。

只修改 `RefreshNoticeCapsule.kt` 的外观；今日、课表、成绩、教室、考试和账号页的统一提示同步生效。

本次重新执行 `:core:designsystem:testDebugUnitTest :feature:main:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain`，344 项测试通过（16 + 328），0 失败、0 错误、0 跳过；两个构建均成功。日志：`diagnostics/refresh-notice-style-20261112.log`。`git diff --check` 通过；未安装或真机检查。

此样式版本替代上表 APK，最终 SHA-256：

- Debug：`7A74CD691AF9DAA87F67EECB77A039E7D86DE73B03DFC63696F38CA4C159FB7C`。
- Benchmark：`8738971E12A2C2295BDC48C2D915EE0C7446249B091136CA069DDDC6EE235E5B`。

### 主题色跟随修正

按用户后续要求，胶囊底色改用当前主题的 `primary`，文字使用配套的 `onPrimary`。预设、自定义主题色及深浅模式均走现有主题派生规则；固定青绿色已取消。

验证：设计系统 16 项测试、刷新提示界面 2 项测试通过；Debug、Benchmark 均构建成功。日志：`diagnostics/refresh-notice-theme-20261112.log`。未安装到手机。

## 课表同步反馈语义修正（2026-11-12）

用户指出下拉时先提示“本地课表刷新成功”，之后才提示教务成功。根因是 `completePull` 将回到本周的动画/分页完成当成数据刷新成功；该回调没有读取教务，也没有保存数据。

- 动画完成只清理导航状态，不再产生任何“本地刷新”结果，也不覆盖同步结果。
- 下拉等待教务同步完成；`SchoolTimetableRefreshHandler` 只有在 `quickSync` 返回提交成功后才正常结束，提交包含 Room 事务写入。此时统一提示一次“教务课表同步成功，已保存到本地”。选择器中的当前课表刷新使用相同文案。
- 刷新指示持续到本次同步结束；请求进行中阻止重复下拉。失败、未登录、会话过期保留失败或登录提示；离开页面后抑制迟到结果。
- 空课表、缺少学期或不在教学周只影响页面显示，不再伪造“本地刷新成功”，也不以本周定位结果替代教务同步结果。
- 沿用现有主题色 Liquid Glass 胶囊；未修改数据库同步/合并规则。

修复前，更新后的交互测试 19 项中 8 项复现失败；修复后全部通过。覆盖慢同步等待、快速同步不被动画重复提示、同步失败、写入失败、未登录、首次同步、重复下拉、页面取消及菜单刷新。

验证命令：

```powershell
.\gradlew.bat :core:designsystem:testDebugUnitTest :data:testDebugUnitTest :feature:main:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline --console=plain
```

539 项测试通过（设计系统 20、数据 157、主功能 330、应用 32），0 失败、0 错误、0 跳过；Debug、Benchmark 构建成功，`git diff --check` 通过。日志：`diagnostics/schedule-sync-feedback-20261112-full.log`。

本次安装包 SHA-256：

- Debug：`4B2E85E804109BAC6A5B53159D64609730C2840B5AFE7AEBCE2266B10B4769CC`
- Benchmark：`7DD7D09A8E45E2EA259D1362F56B75D5E9B2F2A6CA35F77046B257D8E26C08A9`

未安装或操作手机，未调用真实教务账号。测试和源码核查不替代真实学校同步及设备视觉验收。

本次 APK 替代前述样式版本，SHA-256：

- Debug：`7B965597234DCBC4660092F7B5E353BB4A842CCE5BE73A66C107B6A416A1FC63`。
- Benchmark：`D8A7600F3ADDEA9A05104FEC201CD805A7F1B73CDB29983363AC3973B802860E`。
