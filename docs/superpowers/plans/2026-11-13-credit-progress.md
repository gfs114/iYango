# 学分完成进度实施计划

> 仅计划。本文档阶段尚未编写实现代码、测试代码、数据库迁移或网络接口调用；实施时需要扩展成绩模型并新增数据库迁移（见 §3.1、§6）。本期定义为基于真实成绩的本地参考进度，不等同于学校毕业资格审核。

**Goal:** 展示累计已通过学分、用户设置的总目标和分类目标、剩余学分，并让每一项计入结果可追溯。

**Architecture:** 新建独立学分规则与按学校/账号隔离的目标存储，从明确覆盖范围的成绩快照派生进度。沿用成绩查询与账号生命周期，新增独立页面；不修改已有均分、GPA、自选算分或重修记录保留规则。

**Tech Stack:** Kotlin 纯领域规则、Room、Flow、Hilt、Compose、现有 GradeRepositoryPort 与 GradeQueryPort。

## 1. 已核对的能力和缺口

- [GradeRecord](G:/code/Android/iYango/core/model/src/main/kotlin/com/gfs/iyango/core/model/Grades.kt:16) 有课程号、名称、属性、学期、考试性质、学分和成绩；没有培养方案、毕业要求、学分认定和替代课程关系。
- [GradeStatistics](G:/code/Android/iYango/core/domain/src/main/kotlin/com/gfs/iyango/core/domain/GradeStatistics.kt:10) 的学分是算分分母，含未通过记录、重修不去重，默认排除公选课，不能作为本功能结果。
- 成绩行 stableKey 用来区分学校成绩记录，包含学期/考试性质等；不能当作跨学期同课程身份。见 [YguGradeQueryRepository](G:/code/Android/iYango/data/src/main/kotlin/com/gfs/iyango/data/repository/YguGradeQueryRepository.kt:203)。
- [全部学期查询](G:/code/Android/iYango/data/src/main/kotlin/com/gfs/iyango/data/repository/YguGradeQueryRepository.kt:169) 已有，但 `complete = true` 不等于覆盖全学程：
  - 逐学期补查使用 `.take(MAX_TERMS)`（当前 40），超出部分被静默截断，仍返回 `complete = true`；
  - 单学期 `EMPTY` 视为成功；“全部学期”直查返回 `EMPTY` 时零条记录也标记完成；
  - 返回的 `terms` 只含有成绩的学期，无法区分“学校提供的学期”与“实际查询成功的学期”。
- [RoomGradeRepository](G:/code/Android/iYango/data/src/main/kotlin/com/gfs/iyango/data/repository/RoomGradeRepository.kt:30) 在缺少全量快照时：快照回落为 `firstOrNull` 得到的**任意一个**单学期快照，记录则回落为**全部已缓存学期的并集**。两者元信息与记录集合可能不一致，新投影不得复用该回落路径，也不能仅看 complete 字段判断覆盖全学程。
- [学分解析](G:/code/Android/iYango/data/src/main/kotlin/com/gfs/iyango/data/repository/YguGradeQueryRepository.kt:221) 为 `toDoubleOrNull() ?: 0.0`：缺失/无法解析学分被写成 0，与真实零学分不可区分；且 `toDoubleOrNull` 会接受 `"NaN"`、`"Infinity"`。
- [resolveAttribute](G:/code/Android/iYango/data/src/main/kotlin/com/gfs/iyango/data/repository/YguGradeQueryRepository.kt:233) 把 `courseAttribute`、`courseType`、`generalElectiveType` 合成为单一 `attribute`，并会生成“xx·公选”“公选（…）”等本地拼接值；原始三字段未持久化。`GradeRecord.attribute` 不是学校原文。
- [GradeStatisticsCalculator](G:/code/Android/iYango/core/domain/src/main/kotlin/com/gfs/iyango/core/domain/GradeStatistics.kt:21) 的等级表只有优秀/良好/中等/及格/不及格，没有“合格/不合格”，也没有可复用的“是否通过”判定。
- [GradesViewModel](G:/code/Android/iYango/feature/main/src/main/kotlin/com/gfs/iyango/feature/main/grades/GradesViewModel.kt:316) 已有账号切换和旧请求隔离，需要沿用同等约束。

## 2. 首期功能范围

入口：成绩页增加“学分进度”，进入独立页面；提供给首页自选快捷入口，不增加底部导航项。

页面包含：

1. 顶部数据状态：所属账号、最近成功更新时间、完整全部学期/部分缓存/未查询状态；没有账号时显示绑定入口。
2. 总进度卡：已确认通过学分、目标、剩余、进度条及“设置目标”。
3. 分类进度：按学校原始课程属性分组（以 §3.1 新增持久化的原始字段为准，不使用本地拼接的 `attribute`），支持给类别设置目标、由用户明确映射类别别名；无属性进入“未分类”。
4. 可追溯明细：“已计入”“未通过或不计入”“待确认”，展示来源成绩、原始学分、归组和计入原因。
5. 目标编辑：总目标、各分类目标和用户备注；输入非负小数，目标未设置与零目标分开保存。
6. 待确认处理：确认课程是否同一门、选择可信学分、调整归类或明确不计入；只改变进度计划中的人工规则，不改学校成绩原文。

无目标时只展示已确认通过学分及“未设置目标”，不预填 160 等默认毕业学分。总目标和分类目标允许独立设置；两者不相等时标明“分类目标尚未覆盖总目标”，不擅自补齐。

分类目标、别名及人工归类使用稳定分类标识，名称仅为展示；改名不改变目标归属。每个原始属性只映射到一个分类，冲突映射需在编辑时解决，不能用显示文字充当恢复关联键。

总进度不跟随成绩页学期区间、自选勾选、排序和公选课算分开关变化；本功能默认考虑全部课程属性。公选是否满足个人目标由分类目标表达。

## 3. 通过与计入规则

### 3.1 通过状态

- 仅使用已明确的成绩状态和学分，不凭学校 GPA 或本地换算 GPA 反推是否获得学分。
- 可信百分制成绩按已验证的及格线规则处理；原始数据的有效范围、等级/字母映射与现有成绩解析和已核验学校口径一致，不新增猜测映射。
- 及格线必须在 C1 明确写定并附学校口径来源，当前代码中不存在该规则。拟定口径（待核验）：百分制 ≥ 60 为通过、< 60 为未通过；超出 0–100 的数值列为待确认。未核验前，相关验收用例（如“55 后 80”）以该拟定口径编写，核验结果不同则同步修改用例。
- “优秀、良好、中等、及格、合格”等明确通过文本可计入；“不及格、不合格”等明确失败不计入。匹配采用规范化后的完整值，不用包含“合格”的子串判断。
- 缺考、缓考、未出分、取消、免修、免考、未知文本等分别标记，未获确认的学分认定不自动计入。不得从备注中的数字抽取为成绩。
- 学分必须是可信的有限非负值（显式拒绝 `NaN`、`Infinity` 和负数）；零学分课程可显示为通过但贡献为零，缺失/无效学分列为待确认。当前模型把部分缺失值归为零时需在解析/投影层保留“缺失”和“真实零”的差别。
- 为上述区分扩展 GradeRecord、GradeRecordEntity、解析映射及数据库迁移的来源状态；同时持久化原始 `courseAttribute`、`courseType`、`generalElectiveType` 三个字段，供分类映射和来源指纹使用，现有 `attribute` 合成规则保持不变。旧缓存中的零值无法还原来源时标为“需刷新确认”，不能通过迁移猜测为真实零学分；旧缓存缺少原始属性字段时同样需刷新后才能参与分类。现有算分页面的历史行为保持不变。
- 升级影响：迁移后首次打开学分进度，旧缓存中零学分及缺少原始属性的记录会集中进入“需刷新确认”。页面应在顶部合并提示“缓存来自旧版本，需更新全部学期”，并提供一键更新，不能把这些记录逐条摊成大量待确认项。
- 分类未设置目标不影响总通过学分；“未分类”保持可见，不丢弃课程。

### 3.2 重修、补考与身份

- 同学校、同账号下，课程号是首选候选身份；同时检查课程名称、学分和记录语义是否一致。课程号不同的同名课程不自动合并。
- 可确认的同课程组只有一次通过学分贡献。先不及格后通过，按通过记录计入一次；两次通过也不累计两份。
- 不把“最新一条”或“第一条”直接当作学分事实。相同课程号的学分冲突、名称明显不一致、疑似重复编号需进入待确认，不取最大值猜测。
- 无课程号的记录保留独立来源；若不能排除同课重考/重复记录，进入待确认，由用户明确归组。不得仅凭名称自动去重。
- 人工确认记录使用可迁移的来源锚点和组标识，不依赖本机 Room 自增行号，也不以随学校排序变化的重复序号作为唯一锚点。保存相关原始课程、成绩、学分、属性及考试身份的内容指纹；刷新后锚点不唯一、无法匹配或相关来源字段变化，重新提示确认，不能套到另一条记录。
- 每个已确认课程组只贡献一次总学分，并只能归入一个明确分类。组内不同成绩的课程属性不一致时先进入分类待确认；不得在两个分类中各算一次，也不能直接采用最新记录覆盖用户选择。
- 替代、抵免、跨专业认定关系没有学校数据时不推断。用户手工处理必须在明细中标注“人工确认”。

### 3.3 数值展示

- 采用一致的小数精度做学分累计和比较，避免二进制误差导致目标达成错误：学分从原始字符串直接解析为 `BigDecimal`，累计与比较保留 2 位小数（`HALF_UP`），全程不经过 `Double`；目标输入同样按 2 位小数保存。UI 去除无意义尾零。
- 目标大于零才计算百分比。目标为零显示“无需学分目标”，目标缺失显示“未设置”。
- 剩余学分最低为零，进度条最多填满；实际已获学分和超额量保留显示，不把超额学分改写成目标值。
- 存在待确认项时清楚区分“已确认通过学分”与“待确认记录数”，不把待确认部分当作零学分结论。

## 4. 数据覆盖与账号生命周期

为新页面提供专用成绩投影，返回快照的查询范围、覆盖学期、获取时间、全量完成标记及记录。可以扩展仓储返回元信息，但不能改变现有成绩页单学期回落行为；新投影不复用 `observeSnapshot`/`observeRecords` 的 ALL 回落路径，元信息与记录必须来自同一快照。

覆盖契约：

- “目标学期”定义为本次学校 metadata 返回的全部非空学期。投影须记录“学校提供的学期集合”与“查询成功的学期集合”，两者相等才可标为全学程覆盖。
- 逐学期补查被 `MAX_TERMS` 截断时，结果只能标记为部分覆盖并说明被截断的学期数，不能沿用现有 `complete = true`。
- 单学期 `EMPTY` 视为“该学期查询成功且无成绩”并计入已查询集合；“全部学期”直查返回 `EMPTY` 且学校学期列表非空时，不直接判为全覆盖，需逐学期确认或标为待核实。
- 上述判定在新增的投影/元信息层完成，不修改现有成绩页对 `complete` 的使用。

- 首次打开先读取缓存；只有部分缓存时显示“当前仅覆盖已缓存学期”，提供“更新全部学期”，不能显示完整毕业完成率。
- 全量查询只有所有目标学期成功解析后才替换对应快照。任一学期失败保留旧快照，失败时间不冒充成功更新时间。
- 一次全量快照完成后，后续某个学期单独更新要明确合并/失效覆盖标记，避免对旧全量结果误报新鲜。
- 页面切换账号取消旧请求并校验账号与查询代次；任何旧结果不得更新新账号进度。
- 目标和人工规则以学校标识及账号主体隔离。普通退出保留本机目标；删除账号明确删除其目标和人工规则；会话过期仍可显示带时间标记的缓存参考结果。
- 待核实：普通退出时现有逻辑是否清除成绩缓存。若会清除，退出后页面只保留目标，显示“未查询”状态和绑定入口，不显示 0 学分进度。
- 身份由旧连接 ID 回落到真实账号主体时执行明确迁移，不把不同账号的目标混合。
- 统一备份保存目标和人工规则，不保存凭据。恢复到另一设备后，身份不匹配的计划保持待匹配；成绩按当前学校账号重新获取。
- 同账号恢复冲突默认保留本机目标和人工规则，用户可在预览中显式替换整套账号计划；首期不自动合并规则。恢复后的人工确认在来源指纹重新核对前不参与计算，详见备份子计划。

## 5. 源码复用

[XJAU AcademicPage](C:/Users/32773/Desktop/campus-ui-reference/1-XJAU-Helper/entry/src/main/ets/view/AcademicPage.ets:322) 的“分类名 → 已获/要求 → 进度条 → 明细”可以按本项目 Compose/GlassSurface 重现。其 MIT 许可允许按许可条件移植实质内容，但 ArkTS 页面不能直接编入 Android 工程。

明确不移植：

- [Models.ets](C:/Users/32773/Desktop/campus-ui-reference/1-XJAU-Helper/entry/src/main/ets/model/Models.ets:98) 中 totalRequired/totalEarned 的课程门数语义，不能改个标题当学分。
- [AcademicPage 的空数据示例回落](C:/Users/32773/Desktop/campus-ui-reference/1-XJAU-Helper/entry/src/main/ets/view/AcademicPage.ets:683) 和 [JwApi 的 USE_MOCK](C:/Users/32773/Desktop/campus-ui-reference/1-XJAU-Helper/entry/src/main/ets/api/JwApi.ets:54)。真实空态不得填充模拟进度。
- 参考 API 的学校专属 HTML/字段、解析失败时默认课程总数、按名称保留首条记录、未判断通过就累加学分的逻辑。
- 参考数据只查有限历史学期的补查策略，不能代替本功能的数据覆盖契约。

参考 AcademicPage.ets 的本地 SHA-256：17B6E5976C90B29686E4DC1E6031D9AA9EA648E2EE696A8226BF34CE1BED0658。上述参考路径为本机路径，不在仓库内；实施时须将上游 MIT 许可证原文、上游版本/提交号与该 SHA-256 一并登记到仓库（如 `docs/third-party/`），并登记实际移植文件与声明；不把这些发现扩展为对上游所有版本的结论。

## 6. 文件与实施任务

所有新增名称为拟定职责，尚未创建。

| 模块/文件 | 职责 |
| --- | --- |
| core/model/.../CreditProgress.kt | 目标、分类、来源锚点、确认记录、结果和覆盖状态模型 |
| core/domain/.../CreditProgressCalculator.kt、CreditProgressPort.kt | 独立判定/归组/累计规则，以及目标存储接口 |
| core/domain/.../GradePorts.kt；data/.../repository/RoomGradeRepository.kt | 新增明确全量覆盖的读取能力，保留旧页面语义 |
| data/.../school/ygu/YguGradesModels.kt、解析与映射文件 | 需要时保留未知/真实零学分及状态来源，不改变成绩原文 |
| core/model/.../Grades.kt；data/.../db/GradeEntities.kt 及成绩映射/迁移 | 将学分来源有效性贯穿缓存，旧缓存未知零值等待重新查询 |
| data/.../db/CreditProgressEntities.kt、CreditProgressDao.kt；IYangoDatabase 与 IYangoDatabaseMigrations | 按账号持久化目标/人工规则及无损迁移 |
| data/.../repository/RoomCreditProgressRepository.kt、RoomSchoolAuthRepository.kt | 数据存储、身份迁移与删除账号清理 |
| feature/main/.../creditprogress/CreditProgressScreen.kt、ViewModel 与编辑/明细组件 | 统计、目标编辑、待确认、空态和刷新 |
| feature/main/.../grades/GradesScreen.kt、IYangoMainApp.kt；app/.../di/AppBindings.kt | 成绩入口、路由、依赖注入 |
| core/model/.../HomePreferences.kt（HomeShortcut）；feature/main/.../today/HomeSettingsScreen.kt、HomeSettingsViewModel.kt、HomePresentation.kt | 首页快捷入口登记；对应首页计划 H6，须在本模块完成后注册 |
| 对应 core/domain、data、feature/main 测试目录 | 行为、持久化、账号/缓存并发和 UI 回归 |

上述省略路径的根分别为 core/model/src/main/kotlin/com/gfs/iyango/core/model、core/domain/src/main/kotlin/com/gfs/iyango/core/domain、data/src/main/kotlin/com/gfs/iyango/data、feature/main/src/main/kotlin/com/gfs/iyango/feature/main、app/src/main/java/com/gfs/iyango；均位于 G:/code/Android/iYango。

数据库版本：当前 `IYangoDatabase` 为 version 6。成绩实体扩展与学分进度新表建议合并为同一次版本升级（6 → 7），以减少中间态；若统一备份计划同期也需升级 schema，须事先约定版本号顺序，避免两条迁移争用同一版本。

- [ ] C1 固定通过状态（含及格线及来源）、课程身份、数据覆盖契约（§4）和人工确认契约；保留当前 GPA 统计所有口径。
- [ ] C1.5 扩展成绩模型与缓存：学分缺失/真实零区分、原始属性三字段、覆盖元信息；编写迁移及迁移测试，验证现有成绩页行为不变。
- [ ] C2 为 §3 规则与 §7 验收案例建立先失败的领域测试，再实现纯规则。
- [ ] C3 建立目标持久化、迁移、删除账号及旧请求隔离测试，再实现仓储与全量覆盖投影。
- [ ] C4 接入页面、目标编辑和待确认流程；将入口登记为可选首页快捷项（首页计划 H6）。
- [ ] C5 与备份子计划联调目标和确认记录的身份映射及往返。
- [ ] C6 独立审查数据真实性、重修规则、未知值、账号切换和无障碍，再运行相关测试与 Debug 构建。

## 7. 验收案例

| 输入/操作 | 预期 |
| --- | --- |
| 同一门 4 学分课程先 55 后 80 | 通过学分为 4，保留两条原始成绩和一次计入说明 |
| 同一门 4 学分课程两次通过 | 仍计 4，不变为 8 |
| 名称相同但课程号不同，分别 2、3 学分且通过 | 分别计入，共 5，不按名称合并 |
| 同号学分冲突、无法确认的重复记录 | 进入待确认，说明缺少的判断依据 |
| 重修前后课程属性不同、分类改名后备份往返 | 每个课程组只贡献一次且分类不串位，冲突进入待确认 |
| 合格/不合格、零/缺失学分、免修/缓考/未出分 | 不被相似文字或数值默认值误计入 |
| 总目标未设置、为零、累计超过目标 | 无除零/虚构目标，超额与剩余显示正确 |
| 只有单学期缓存、全部查询中途失败 | 清楚标记覆盖范围，旧结果保留且不伪装全量更新成功 |
| 学校提供超过 40 个学期并走逐学期补查；“全部学期”直查返回 EMPTY | 标为部分覆盖/待核实并说明原因，不显示全学程完成率 |
| 学分字段为空、`NaN`、`Infinity`、负数 | 进入待确认，不按 0 计入，不报错崩溃 |
| 从 version 6 升级后首次打开 | 旧零学分及缺少原始属性的记录合并提示“需更新全部学期”，成绩页行为不变 |
| 学分累计如 0.1 + 0.2 与目标 0.3 比较 | 判定为恰好达成，无浮点误差 |
| 修改成绩页学期/自选课程/公选算分开关 | 累计进度不改变口径 |
| A 查询未结束切到 B、退出或删除账号 | 不串数据；目标保留/删除行为符合生命周期约定 |
| 刷新后学校重复行换序，成绩/学分/属性改变或锚点失效 | 重新待确认，不能静默附着到另一条记录 |
| 备份恢复到同账号/不同账号 | 同身份可匹配，异身份不能直接应用 |
| 窄屏、大字、深色、空成绩、离线 | 能读明细、编辑目标、识别当前数据状态 |

本期不承诺培养方案自动抓取、缺少的必修课程列表、在读课程学分、课程替代认定和毕业资格。若后续获得学校可信接口，另增数据适配和学校认定视图，不能把个人目标更名为学校要求。
