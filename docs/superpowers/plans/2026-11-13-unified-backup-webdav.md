# 统一备份与 WebDAV 实施计划

> 仅计划，不包含生成的实现或测试代码。实施步骤、网络动作和验证命令均未执行。本期采用用户主动操作的备份恢复，不包含双向同步或定时任务。

**Goal:** 将课表、手动考试、个性化及本轮新增配置统一备份，并支持 WebDAV 历史版本和可预览的恢复。

**Architecture:** 以版本化白名单归档为唯一交换载体，本地与远端共用解析、预览和恢复服务。Room 事务负责关系数据；恢复协调器负责设置、资源和外部系统更新的阶段记录及中断恢复。WebDAV 仅负责保存和传输归档。

**Tech Stack:** Kotlin、Room、DataStore、Hilt、Java ZIP、Android Keystore、Compose。拟使用版本目录已有的 OkHttp 坐标作为独立 WebDAV 传输实现；现有 YguHttp 继续保持教务域名白名单，不拿来发送云盘凭据。

## 1. 现状与复用结论

- 当前 [ImportExportScreen](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/importexport/ImportExportScreen.kt:167) 导出单个课表；[DefaultSettingsBackup](G:/code/Android/iYango/data/src/main/kotlin/com/gfs/iyango/data/repository/DefaultSettingsBackup.kt:63) 单独导出设置及可选壁纸。
- 原单课表 JSON 保留 ClassSchedule 交换协议；它没有覆盖全部课表、独立临时调整等完整关系，不能简单循环导出后称为完整备份。
- 当前课表导入的 Room 提交和 DataStore 设置写入分开处理，不能原样宣称跨存储事务。见 [TransferPortImpl](G:/code/Android/iYango/data/src/main/kotlin/com/gfs/iyango/data/repository/TransferPortImpl.kt:78)。
- 系统备份规则已排除主数据库、凭据和设备相关偏好；本功能使用独立协议，不取消这些排除。
- 拾光 [WebDavClient](C:/Users/32773/Desktop/campus-ui-reference/4-shiguangschedule/shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/data/api/webdav/WebDavClient.kt) 包含 MKCOL、PUT、GET，可适配复用；当前没有远端历史列表。
- 拾光 [BackupViewModel](C:/Users/32773/Desktop/campus-ui-reference/4-shiguangschedule/shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/ui/settings/backup/BackupViewModel.kt:153) 先覆盖固定 meta，再逐个覆盖模块文件；下载缺失模块会被过滤。新实现必须避免混合不同版本或静默恢复残缺模块。
- 拾光 [BackupRepository](C:/Users/32773/Desktop/campus-ui-reference/4-shiguangschedule/shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/data/repository/BackupRepository.kt) 的模块划分可借鉴，但 DTO/DAO、CBOR、Koin 和应用设置全量映射不适配本项目，需改为白名单 DTO 和 Hilt。
- 优先移植 [ZipUtils.android.kt](C:/Users/32773/Desktop/campus-ui-reference/4-shiguangschedule/shared/src/androidMain/kotlin/com/xingheyuzhuan/shiguangschedule/tool/ZipUtils.android.kt) 的 ZIP 写入结构；改为有上限的流式操作，补完整解析、路径校验和临时文件管理。

## 2. 用户流程

入口为“我的 → 数据与同步 → 备份与恢复”。保留原单课表导出和旧设置备份能力，新入口提供本地备份、从文件恢复、WebDAV 三组。

本地备份流程：查看包含模块 → 选择是否包含壁纸 → 生成并自检 → 系统文件选择器保存。保存或自检失败不显示成功；取消不留下可见的损坏文件。

远端流程：填写服务地址、用户名、应用密码、专用目录 → 测试连接 → 保存配置 → 手动备份或打开历史版本列表。默认目录为 iYango/backups；列表显示时间、大小、来源设备别名和应用版本。

恢复流程：选择文件/远端版本 → 下载到临时文件 → 完整校验 → 显示数量、包含模块、影响和账号匹配情况 → 选择恢复方式及设置/壁纸选项 → 用户确认 → 应用 → 显示实际结果。

## 3. 备份白名单

| 数据 | 本期处理 |
| --- | --- |
| 全部课表、学期、作息、课程 | 包含；保留当前课表、分组、自动选表及轮换配置 |
| 单次移动/取消、临时整天及旧式课程覆盖关系 | 包含；记录包内逻辑标识，恢复时映射引用 |
| 手动考试 | 包含日期、跨日、时间、地点、座位、备注和状态 |
| 主题、全局/逐课表显示设置、提醒偏好 | 包含；跨设备标识转换为逻辑引用或在预览中明确失效项 |
| 首页排序、隐藏、快捷入口 | 包含，未知未来模块按兼容规则处理 |
| 学分总目标、分类目标、记录合并/分类人工确认 | 包含，按学校和账号主体标识隔离；恢复后需匹配身份 |
| 壁纸及自定义小组件背景 | 用户可选择包含；必须转成可携带文件，不导出原机私有路径/URI |
| 小组件外观 | 可作为可重新应用的样式预设保存；不恢复宿主实例及安装位置 |
| 成绩、历史学期浏览、教室与学校考试缓存、同步历史 | 本期不迁移，保留当前设备缓存并在同账号下重新查询；恢复预览明确列为可重新获取数据 |
| 登录信息、口令、Cookie、WebDAV 凭据 | 不进入备份，不因恢复替换或清除当前登录 |
| 日历事件/闹钟映射、系统日历 ID、widgetId、临时 URI 授权、ICS 本机命名空间 | 不跨设备恢复；当前设备已有映射在副作用清理结束前保留 |

产品文案称“统一备份”，并列出范围，不能称为整机或所有账号数据的完整镜像。缺失必需模块应拒绝恢复，不能把失败下载解释成用户选择跳过。

## 4. 归档与兼容契约

- 导出也需要一致性边界：业务写入进入同一协调门控后，在 Room 读事务内取得全部关系数据，并读取同一逻辑修订的设置和资源清单；快照完成后立即释放门控，再进行压缩和网络传输。不能跨多个独立 DAO 查询拼出不同时刻的数据，也不能持锁等待网络。
- 新协议使用独立应用标识和格式版本，不复用旧单课表 JSON 的版本号。
- 清单记录创建时间、应用版本、各模块格式版本、文件大小和摘要；业务数据使用可审查的 JSON，图片为独立资源。
- 建议初始上限：归档 128 MiB、解包总量 256 MiB、512 个条目；单张图片 24 MiB。超限在写入任何业务数据前拒绝，提示减少图片后重试。
- 拒绝绝对路径、父目录跳转、重复条目、引用不存在的资源、异常大小及非法关系；图片实际解码通过后才可恢复。
- 不将不可信 ZIP 直接解到业务目录。所有待恢复文件先进入本次会话专用临时目录。
- 新于客户端的必需模块版本拒绝恢复；可识别旧版本迁移后预览。旧单课表 JSON 和设置 JSON 仍路由到原有导入器，不能当成全量归档。
- 每次预览绑定归档摘要、恢复选项、当前数据修订和账号版本；预览后数据改变或切换账号需重新预览。
- 用稳定的备份标识和恢复操作标识识别重复恢复，不用文件名猜测身份。一次操作的提交与完成结果持久保存；重试/进程重启读取已完成结果，不重复插入。用户再次主动导入同一归档时默认提示已恢复，仅明确选择“再次创建副本”才建立新操作；替换同账号计划不自动叠加规则。

## 5. 恢复方式、身份和一致性

提供两种明确方式：默认“作为新课表导入”，保留当前数据，给课表/课程/考试分配本机新标识；另一种“替换可迁移数据”，先列明将替换的模块和数量并要求单独确认。主题和首页等全局设置由预览中的选项决定，不随新增课表强制覆盖。

新增模式在有现存课表时保留原当前课表；导入的分组使用独立标识，自动选表默认关闭，需用户在预览或后续管理页明确启用，避免导入副本改变现有提醒和日历。空库恢复及明确替换课表模块时可恢复源当前课表与自动分组规则，校验最多一个当前课表；源没有当前课表则保持无当前课表。临时整天的所有跨表引用随同一组映射转换。

本机既有 widgetId 不被导入数据覆盖。替换课表后，已有组件绑定通过本次逻辑映射更新；找不到对应课表时显示重新绑定状态，不随意指向另一个同名课表。

恢复的教务课表首先作为离线本地副本使用，保留来源说明但不伪造有效 connectionId。必须经当前账号匹配并重新走同步预览，才能成为自动更新的学校绑定课表。学分目标的账号主体不匹配时保存为待匹配计划，不能进入其他账号统计；原账号主体无法确认时要求用户选择归属。

归档中的账号匹配信息使用学校标识和已验证账号主体的稳定匹配指纹，不包含登录账号明文、姓名、会话或凭据。当前设备登录成功后计算相同指纹用于匹配；不能只用显示昵称或脱敏账号比较。

同账号已有学分计划时，预览默认保留本机计划；用户可以单独选择替换该账号的目标和人工规则，替换前展示两个版本的更新时间与差异。第一版不自动合并两套人工归组规则。“作为新课表导入”不等于覆盖学分计划，“替换可迁移数据”也仍需显示该模块是否选中。恢复的人工确认在重新取得成绩并通过来源内容校验前仅为待校验状态。

执行顺序：

1. 完成全部解析和关系校验，获得写入协调锁；协调课表编辑、教务刷新、目标/首页设置和其他恢复操作。获得锁后再次比较预览绑定的数据修订和账号版本，不在锁外检查后直接写入。
2. 生成本机恢复前快照和持久阶段记录；暂缓提醒、日历、小组件等消费者应用中间结果。准备阶段失败则不开始写入。
3. 将图片写入新资源目录；在 Room 事务中写入业务数据、引用映射和阶段状态，不覆盖旧资源。
4. 写入明确选中的主题、DataStore、新增配置，再持久标记完成；失败则按恢复前快照回滚。
5. 恢复屏障在进程启动时默认关闭，恢复协调器自身可执行检查/回滚；检查完成前，所有普通写入、演示播种和系统消费者均不得消费中间状态。覆盖 Application/Hilt 构造时启动的观察者、无界面广播接收器和小组件入口，不仅在主页面等待。崩溃后先完成回滚/恢复处理，再开放普通流程；空间不足导致回滚暂时失败时保留快照并展示可重试恢复状态。
6. 业务恢复成功后再重算提醒、日历和小组件。旧系统映射只能在对应清理/更新确认后处理；首次换机恢复不自动开启提醒或选择系统日历，由用户在当前设备启用。
7. 系统权限失败显示“数据已恢复，日历/提醒未启用”等实际状态，不能把外部失败伪装成业务恢复失败后反复导入。

业务提交完成与外部副作用待重建分开持久记录。若提交后、重建前进程终止，重启后按当前数据重新生成待执行的外部计划；不重复导入，不复用旧账号或旧修订的事件载荷。只有确认没有待恢复工作后才开放启动屏障，不能依赖某次页面打开来触发。

Room 与 DataStore 没有一个天然覆盖两者的事务，本计划采用有恢复日志的协调流程。其必要性来自项目现有跨存储实现；DataStore 的单次事务能力不等于跨库事务。[Android DataStore 文档](https://developer.android.com/topic/libraries/architecture/datastore)

## 6. WebDAV 版本和传输规则

- 独立客户端、独立凭据存储。可借用现有 AndroidSchoolCredentialStore 的 Keystore 模式，但采用新的 alias、存储文件和类型，不把两类凭据混在一起。
- 第一版支持 HTTPS + 用户名/应用密码；不关闭证书校验，不自动携带认证跳转到其他主机。密码不出现在日志、异常、持久 UI 状态、备份或诊断导出中。
- 路径按段编码；远端 href 必须规范化并验证仍位于所配置同源目录。禁止目录穿越、任意目标下载和删除。
- 使用 PROPFIND 深度 1 获取列表；MKCOL 创建专用目录；PUT/GET 上传下载；MOVE 用于发布；DELETE 只操作用户明确选中的自有备份。207 响应逐项检查，不能只看 HTTP 总状态。
- MKCOL 返回 405 只有在后续确认目标已存在且为集合时才能视为目录已就绪，不能把文件占位或协议不支持当成功。
- 每次备份采用时间加随机标识的独立名称，不覆盖历史版本、不维护单个易冲突的全局索引。
- 上传临时名，校验服务端返回后发布正式版本；若服务端不支持 MOVE，则上传唯一归档并在最后写入完成标记。列表只展示已完成且清单有效的版本，未完成文件不提供恢复入口。
- 所有发布方式最终写入完成标记，绑定备份 ID、精确同源归档路径、字节数、归档摘要和格式版本。只有归档上传/发布响应成功且标记可读回、绑定匹配时才报告备份完成；正式目标和标记均禁止覆盖已有不同内容。
- 发布成功但客户端超时或响应丢失时，按同一备份 ID 查询归档和标记；一致则返回已完成，不一致则停留在失败/待重试。取消前标记尚未发布则保持不可恢复的临时版本；重试沿用该操作 ID，不产生跨版本配对。
- 服务器返回的 ETag 不假定为文件摘要。恢复前下载完整文件并验证清单摘要；网络失败不进入业务写入。
- 连接测试在专用测试目录验证必要能力；若需要验证写入，只创建随机命名的小测试文件并清理该文件，不触碰用户原有内容。
- 无权限、认证失败、配额不足、协议不支持、超时与用户取消分别展示可操作结果。协程取消向上传递，流和连接及时关闭。
- 历史版本不自动清理；删除需在列表确认具体版本。本期不增加后台上传、远端文件编辑或多设备自动合并。

上述方法和响应处理以 [RFC 4918](https://www.rfc-editor.org/rfc/rfc4918.html) 为协议参考；完成标记和版本命名是本应用设计，不假定所有服务端提供同样的原子写入语义。

## 7. 文件职责与后续任务

所有以下新增文件均为拟定路径，尚未创建。

| 位置 | 计划修改/新增 |
| --- | --- |
| core:model/src/main/kotlin/com/gfs/iyango/core/model/ | 新增 BackupModels.kt，描述模块、预览、选项和可迁移的云端配置；凭据独立类型 |
| core/domain/src/main/kotlin/com/gfs/iyango/core/domain/ | 新增 UnifiedBackupPort.kt、WebDavPort.kt、BackupRestoreCoordinator.kt 相关契约，保留 SettingsBackupPort |
| data/src/main/kotlin/com/gfs/iyango/data/backup/ | 新增 BackupArchive、BackupDtos、BackupSnapshot、BackupRestore、RestoreJournal、WebDavClient 等职责文件 |
| data/src/main/kotlin/com/gfs/iyango/data/db/ | 为全量读取、引用映射和恢复阶段记录增加所需 DAO/迁移，数据库版本从当前 6 按实际合并状态递增 |
| data/src/main/kotlin/com/gfs/iyango/data/repository/ | 调整 DefaultSettingsBackup、DataStoreAppSettings 和刷新/提醒/日历协调器，接入写入及恢复门控 |
| platform/android/src/main/kotlin/com/gfs/iyango/platform/android/ | 新增 AndroidWebDavCredentialStore；复用文件选择和图片存储机制 |
| feature/main/src/main/kotlin/com/gfs/iyango/feature/main/settings/ | 新增 BackupScreen、BackupViewModel、WebDavSettingsScreen、BackupRestorePreview；旧备份入口兼容 |
| app/src/main/java/com/gfs/iyango/di/AppBindings.kt；feature/main/src/main/kotlin/com/gfs/iyango/feature/main/IYangoMainApp.kt 与 mine/MineSections.kt | 绑定服务、启动恢复检查和入口 |
| app/src/main/java/com/gfs/iyango/IYangoApplication.kt；platform/android/src/main/kotlin/com/gfs/iyango/platform/android/ScheduleWidgets.kt、AndroidReminderPlatform.kt 及组件入口 | 应用注入、演示播种、广播和组件更新均等待进程级恢复屏障；AndroidWidgetUpdater 位于 ScheduleWidgets.kt |
| app/src/main/res/xml/backup_rules.xml、data_extraction_rules.xml | 补充排除新的凭据和恢复临时目录 |
| docs/third-party/ | 实施时新增实际移植文件清单及所需许可文本；仅记录真正移植的文件 |

- [ ] B1 固定数据白名单、逻辑标识、模块版本、旧协议路由和恢复选项；评审后再编写 DTO。
- [ ] B2 先建立归档损坏、限额、引用完整性和往返测试，再实现本地归档。
- [ ] B3 建立新增/替换恢复及账号映射测试，再实现事务与持久阶段记录。
- [ ] B4 对每个阶段注入失败并模拟进程重启，证明不会将半恢复状态提供给系统消费者。
- [ ] B5 接入本地备份、预览和恢复页面，验证取消、重复点击和恢复后导航。
- [ ] B6 完成凭据隔离与独立 WebDAV 传输，使用模拟服务器验证方法、路径、认证和状态码。
- [ ] B7 完成远端版本、发布、下载、预览及删除，验证不完整上传不可恢复。
- [ ] B8 独立复核许可证、日志、跨存储一致性、并发和旧功能兼容，再执行自动化与真实服务器验收。

## 8. 验收矩阵

| 场景 | 必须观察到的结果 |
| --- | --- |
| 多课表、自动选表、跨周移动、临时整天、手动考试往返 | 课程实际发生结果一致，引用不串表，当前课表规则有效 |
| 新首页和学分计划往返 | 布局/入口一致，目标及人工确认归属正确；不匹配账号不应用 |
| 设置-only、课表-only 旧文件 | 走原有预览，不删除其他数据、不误判为损坏新归档 |
| 图片损坏、摘要错误、缺模块、未来版本、ZIP 越界或超限 | 写入前拒绝，现有数据保持不变 |
| 恢复中写库失败、设置失败、进程终止、磁盘不足 | 能恢复到一致状态；无法完成恢复时保留可恢复材料和明确状态 |
| 同步与恢复并发、预览后编辑、切换账号 | 串行协调或要求重预览，旧响应不能覆盖恢复结果 |
| 新增导入到已有自动分组、替换被组件绑定的课表 | 原当前/自动生效课表不被悄然改变，组件绑定有明确映射或重绑状态 |
| 提交后进程终止、无界面广播先启动、重复恢复 | 启动屏障先恢复一致性，副作用重建可重试，业务数据不重复插入 |
| WebDAV 401/403/404/405/409/413/507、207 局部失败 | 正确区分结果，失败不显示成功、不删除旧备份 |
| 非法 href、跨域跳转、目录穿越、特殊字符文件名 | 不发送凭据给非配置主机，不读取/删除根目录外内容 |
| 上传断网、取消、重复点击、多设备同时上传 | 历史版本不被覆盖，未完成版本不能恢复，重试不产生数据混合 |
| 标记残缺/错配、发布成功但客户端超时 | 错配版本不可恢复，同一操作能识别已完成发布并幂等返回 |
| 新设备无日历/通知权限、已有桌面组件 | 数据恢复与系统接入状态分开，既有组件不使用外来 widgetId |

后续自动化检查包括 core:model/core:domain、data、platform:android、feature:main 与 app 的相关测试及 :app:assembleDebug。本次未运行。真实 WebDAV 的服务器兼容性、账号测试和设备恢复演练必须单独记录，不由模拟服务器或 APK 构建结果代替。

## 9. 参考快照记录

- WebDavClient.kt SHA-256：509DCF4F20EEBF2BA7DADB5783C14834D9E756CBDC165A63EDC357CE5602D7AC。
- BackupRepository.kt SHA-256：EC5C93EA6503E6DA32780574B3111300B623904CAF046229E51E4BED5A658D62。
- 快照来自桌面拾光目录，不能等同于上游最新版本。Apache-2.0 文本见该目录 LICENSE；后续移植需保留声明并注明修改。
