<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="96" alt="iYango" />

# iYango

面向阳光学院的本地优先 Android 校园课表与教务助手

当前版本 `0.1.2`（versionCode 3），分支 `iYango_prelease`，设计与变更记录见 [docs/plans](docs/plans) 与 `git log`

[![Android](https://img.shields.io/badge/Android-8.0%2B%20%28minSdk%2026%20%2F%20targetSdk%2036%29-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202026.09.00-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Gradle](https://img.shields.io/badge/AGP%209.4.0-Gradle%209.8.2-02303A?logo=gradle&logoColor=white)](https://gradle.org/)
[![Room](https://img.shields.io/badge/Room-2.8.5-4CAF50)](https://developer.android.com/training/data-storage/room)

</div>

---

## 核心功能

### 课表与日程
- **今日与周课表**：今日日程卡含上课进度与跨楼转场提示，周视图支持手势切周，课程详情弹窗呈现时间、教师、教室与调课状态
- **灵活排课语义**：支持多课表管理与分层自动切换、单双周 / 离散周次 / 自定义钟点 / 单次日期课、单次调课（MOVE / CANCEL）与临时整天课表替换（TEMPORARY_DAY）
- **学期数据海报**：统计总课时、早八节数、教学楼分布与 GPA，并生成液态玻璃海报导出

### 阳光学院教务集成
- **账号与课表同步**：支持教务登录、会话恢复、课表一键同步、多学期浏览与同步变更记录、原始课表 HTML 核对
- **成绩与 GPA 统计**：全学期与单学期分区离线缓存，支持加权平均分与平均绩点统计、公选课开关、自定义学分进度追踪
- **实时空教室查询**：支持当前时段空闲筛选、连续空闲 >= 2 节筛选、常用教学楼置顶与教室占用详情查看
- **考试与倒计时**：支持考试安排管理、时间/地点/座位号展示与近期考试倒计时

### 提醒、Live Update 与桌面小组件
- **课程提醒与实时进度**：支持课前闹钟提醒、上课实时进度常驻通知与系统日历同步（写入 Android `CalendarProvider`）
- **新出分 Live Update 与冲刺同步**：
  - 适配 Android 16+（API 36）Live Update（部分厂商称灵动胶囊）：折叠态仅显示课程名，展开态两行展示
  - 真实锁屏态检测（结合解锁状态与屏幕广播），锁屏期间自动隐藏具体分数与 GPA，解锁后静默刷新恢复
  - 支持平时周期检测与期末出分冲刺模式（白天 15 / 30 分钟窗口自动轮询当前学期，到期自动降频），支持 Wi-Fi / 充电与前台冷却触发
- **桌面小组件**：7 种布局（课程预览、今日课程 2x4 / 4x4、今日标尺、周课表、下一节胶囊、考试倒计时），支持自定义圆角、透明度与文本配色

### BYOK AI 助理与 AI 拍图导入
- **三协议兼容接口（自带 Key）**：在「AI 接口」页面统一配置，兼容 **OpenAI Chat Completions**、**OpenAI Responses API** 与 **Anthropic Messages API**，内置多种常见服务商预设，支持自定义中转地址、模型拉取与连接测试
- **本地 Tool-Calling AI 助理**（「今日」顶部栏机器人图标与「我的」页直达）：
  - 只读工具自动执行：查询生效课表、检索课程、读取本机成绩汇总、确定性 GPA 模拟与目标均绩反推、查询近期考试、实时查询空教室
  - 写入操作二次确认：加课、单次调课 / 停课、添加考试均生成交互式确认卡片，绝不在后台静默修改本地数据库
- **AI 拍图 / 文本识表**：支持拍照、相册多图，以及粘贴群通知文本，经本地标准化后直接进入课表导入预览流程

### 导入导出与口令分享
- 支持标准 JSON 课表导出与导入（新建 / 追加 / 替换与重复导入校验）
- 支持紧凑课表分享口令（`IYANGO-TT1:` 前缀，支持直接粘贴带聊天文字的消息体）与离线二维码编码生成
- 支持课表高清图片导出、应用设置备份与重置

### 外部入口与维护
- 深链 `iyango://open/<tab|route>` 按白名单解析，通知、桌面组件与快捷方式共用同一入口
- 应用自更新：启动时节流检测更新清单，未配置地址时静默跳过，关于页可手动检查
- 运行时日志：按类别与关键字筛选、一键复制脱敏日志、清空本地日志

### 液态玻璃与外观定制
- 基于 `AndroidLiquidGlass` 实现全局材质（顶栏、卡片、弹窗、底部玻璃 Dock 与侧边导航布局自适应）
- 多 Activity 页面结构配合系统原生跨 Activity 预测性返回动画
- 支持全局壁纸、日出日落 / 轮播壁纸调度、Bing 每日壁纸、自定义强调色与自定义 TTF / OTF 字体

---

## 模块架构

项目采用多模块清晰分层，依赖严格单向收敛：

```text
app
 ├─► feature:main ─────► core:designsystem
 │        └────────────► core:domain ──► core:model
 ├─► data ─────────────► core:domain / core:model
 └─► platform:android ─► core:domain / core:model
```

| 模块 | 职责 |
| :--- | :--- |
| `:app` | 应用入口、`MainActivity` / `SecondaryPageActivity`、进程级前台判定、Hilt 装配（`AppBindings`）、Manifest 权限与组件声明 |
| `:core:model` | 纯 Kotlin 不可变领域模型与校验规则（`Course`、`Timetable`、`GradeRecord`、`Exam`、`Classroom`、`Settings` 等） |
| `:core:domain` | 纯 Kotlin 用例、调度/统计/变更检测算法及仓储/平台端口定义（无 Android 依赖） |
| `:core:designsystem` | 主题、色板、动效规范与液态玻璃基础组件库 |
| `:data` | Room 数据库持久化、DataStore 偏好存储、阳光学院教务抓取与解析、多协议 AI 客户端与本地 Tool-Calling 助理实现 |
| `:platform:android` | `AndroidKeyStore` 加密存储、Live Update 与通知适配、`AlarmManager` 调度、系统日历同步、桌面小组件、壁纸与自定义字体存储 |
| `:feature:main` | 全部主页面、二级页面与 ViewModel（今日、课表、成绩、我的、AI 助理、AI 接口、AI 导入等） |

---

## 技术栈

| 类别 | 技术选型与版本 |
| :--- | :--- |
| 构建工具 | AGP `9.4.0`（内置 Kotlin `2.4.21`）、Gradle `9.8.2`、KSP `2.3.12` |
| UI 框架 | Jetpack Compose BOM `2026.09.00`、Material 3、`AndroidLiquidGlass` (`io.github.kyant0:backdrop:2.0.1`) |
| 异步与序列化 | Kotlin Coroutines `1.11.0`、kotlinx.serialization JSON `1.12.0` |
| 依赖注入 | Hilt `2.60.1` |
| 本地持久化 | Room `2.8.5`、DataStore Preferences `1.3.0` |
| 网络与解析 | OkHttp `5.5.1`、Jsoup `1.23.2` |
| 测试框架 | JUnit `4.13.2`、Robolectric `4.17`、Turbine `1.2.1`、`room-testing`、Compose `ui-test-junit4` |

---

## 本地构建与测试

### 环境要求
- **JDK**：Java 17
- **Android SDK**：`compileSdk = 37`、`minSdk = 26`、`targetSdk = 36`
- **SDK 路径**：在 `local.properties` 写入 `sdk.dir`，或设置 `ANDROID_HOME`
- **Robolectric**：默认按 SDK 35 运行，少量兼容性用例单独指定（预测性返回测试用 SDK 28）
- **Gradle**：使用项目自带 Wrapper（`gradlew.bat` / `./gradlew`）

### 构建变体说明

| 构建类型 | Application ID | 说明 |
| :--- | :--- | :--- |
| `debug` | `com.gfs.iyango.debug` | 开发调试包，可与稳定包共存 |
| `benchmark` | `com.gfs.iyango.benchmark` | 继承自 `release` 并开启 R8 优化，使用 debug 签名便于实机性能评测 |
| `release` | `com.gfs.iyango` | 正式发布包（开启 R8 代码优化；未配置签名，产出未签名 APK） |

`optimization { enable = true }` 控制 R8 代码优化；仓库未提供 release 签名配置，正式分发前需自行补充。

### 常用命令（Windows PowerShell）

```powershell
# 运行全部模块单元测试
.\gradlew.bat :core:model:test :core:domain:test :core:designsystem:testDebugUnitTest :data:testDebugUnitTest :platform:android:testDebugUnitTest :feature:main:testDebugUnitTest :app:testDebugUnitTest --console=plain

# 构建并安装 Debug 包到已连接的 ADB 设备
.\gradlew.bat :app:assembleDebug :app:installDebug --console=plain

# 构建并安装 R8 优化后的 Benchmark 包
.\gradlew.bat :app:installBenchmark --console=plain

# 构建 Release 包
.\gradlew.bat :app:assembleRelease --console=plain
```

---

## 已知限制

- 教务同步依赖阳光学院强智教务页面结构，学校改版后需同步更新解析器
- Android 16（API 36）的 Live Update 与提升通知在低版本降级为普通常驻通知
- 二维码分享为离线生成，应用内不提供相机扫码，导入依赖粘贴口令或外部扫码工具
- `debug` 与 `benchmark` 使用 debug 签名，`release` 未配置签名

---

## 隐私与安全

- **本地优先存储**：课表、成绩、考试、设置全部存储在用户设备本地 Room 与 DataStore 中
- **硬件级凭据加密**：教务密码与用户自带的 AI API Key 分别通过独立的 `AndroidKeyStore` AES-256-GCM 密钥加密保存，并从系统备份与设置导出中排除
- **最小化网络传输**：AI 请求由手机端直连用户指定的接口地址，底层独立 `OkHttpClient` 禁用重定向以防止请求头跨主机泄露，日志与错误提示均对 API Key 脱敏
- **HTTPS 约束**：仅接受 `https`；`http` 只放行 `localhost`、`127.0.0.1`、`::1`、`10.0.2.2`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16` 私网地址
- **备份排除**：凭据、AI 配置、`iyango.db`、壁纸与系统映射不进入系统备份与设备迁移，设置通过应用内导出迁移

## 许可与第三方

- 液态玻璃材质基于 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，Apache-2.0
- 学分进度的分类与展示思路参考 [XJAU-Helper](https://github.com/FengHDSL/XJAU-Helper)，MIT，署名见 [XJAU-Helper-MIT.md](docs/third-party/XJAU-Helper-MIT.md)
- 仓库未附带 `LICENSE`，对外分发前需补充项目自身许可

## 致谢与免责声明

- 本项目为非官方校园辅助工具，教务相关功能仅适配特定高校教务页面结构；使用过程中请遵守所在学校信息安全与网络使用规范，使用自带 AI 接口产生的调用费用由用户自行承担
