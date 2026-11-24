# iYango Android 教务登录页设计方案

2026-11-21。适用于阳光学院教务账号的首次登录、历史账号切换和会话失效后的重新登录。方案核对时本地 `iYango_prelease` 的 HEAD 为 `e3ef1d3`，新版基于 `ac87694` 及工作区修改。

## 1. 页面结构

首次打开时让学生一眼认出 iYango 和阳光学院教务系统；再次登录时，让学生尽快回到原账号。两种入口共用一张表单。**历史账号保留可见入口，自动重登沿用现有七天规则，并在表单上说明。**

| 从哪里进入 | 页头与卡片标题 | 主要操作 |
| --- | --- | --- |
| 首次登录 | 橙色渐变页头居中显示 iYango 图标与应用名，卡片标题为「教务登录」 | 填写阳光学院教务系统的学号和密码 |
| 未登录，设备上已有历史账号 | 同上 | 卡内「历史账号（N）」就地展开，可填写、切换或删除账号 |
| 从账号管理点「使用其他账号登录」或「填写账号」 | 相同的品牌页头，卡片标题分别为「登录其他账号」或「重新登录」 | 前者显示空表单，后者只回填所选账号。返回可回到刚才的管理位置 |
| 已登录，想切换账号 | 「教务账号」概览保留当前身份与可展开的「账号管理」 | 在对应历史条目上点「切换账号」或「填写账号」 |

所有登录入口共用以下布局。

```text
          [应用图标]         橙色日出页头
            iYango

  ╭────────────────────────────╮
  │ 教务登录                   │
  │ 使用阳光学院教务系统的学号与密码登录 │
  │ ╭ 学号                   ╮ │
  │ ╰ 密码          清空  显隐 ╯ │
  │ ☑ 记住密码      忘记密码？ │
  │ [          登录          ] │
  │        历史账号（N）       │  有记录时显示
  ╰────────────────────────────╯
        重登方式   自动重登 ›
          教务网站    登录帮助
```

竖屏常规布局的图标容器为 120 dp，卡片从橙色页头下沿向上叠入 26 dp，两侧各留 24 dp。页头右下角的日出圆弧呼应应用图标。卡片使用 20 dp 圆角，浅色模式下采用白底和暖灰输入区，深色模式下采用炭灰底和细描边。学号与密码共用一个输入区，两行标签始终可见。登录按钮占满卡片内容宽度，退出表单使用 Android 系统返回。横屏、短窗口或键盘打开时图标收至 60 dp，整页可滚动。

## 2. 表单与反馈

- **输入。** 学号支持文本输入，键盘「下一项」进入密码框，「完成」与登录按钮执行同一次提交。密码默认遮蔽，显隐按钮固定在框内最右侧；输入后，清空按钮出现在其左侧。清空后焦点和键盘留在原处，登录按钮的纵向位置保持稳定。读屏分别读出「清空密码」「显示密码」或「隐藏密码」。
- **记住密码与帮助。** 勾选「记住密码」时，卡片下方显示「重登方式　自动重登」，取消时显示「手动登录」。点此项可以查看七天规则和选择方式，结果同步到复选项。底部并排放置「教务网站」和「登录帮助」两个文字入口，前者进入外部浏览器。「忘记密码？」打开帮助页。
- **提交。** 学号或密码为空时按钮禁用。手动提交后收起键盘、遮蔽密码，按钮在原位显示「正在登录…」，屏蔽重复提交。这一状态只在按钮内提示一次。历史切换的「正在恢复教务会话…」属于所选历史账号，仍在管理区反馈。
- **失败与成功。** 校方明确拒绝凭据时，将原因放在密码框下；连接失败或原因不明确时，将实际可展示的提示放在按钮上方，读屏播报一次。现有提交完成后会清空密码，失败时保留学号，便于重新填写。成功后直接回到已登录账号概览，沿用现有后续同步。

## 3. 历史账号与自动重登

这三个动作的结果不同，页面文案和验收应分别对应。

| 动作 | 触发与结果 |
| --- | --- |
| 填写账号 | 用户主动从非当前账号条目进入。只回填账号，不回填密码、不发起登录。异步读到保存的账号时，若用户已编辑，保留用户输入 |
| 切换账号 | 非当前账号且记录标记为已保存密码时显示。点击后先尝试恢复该账号会话，需要且有可用凭据时再尝试登录。成功后显示新的当前账号，失败时保留可见的「填写账号」入口 |
| 自动重登 | 已有业务请求遇到会话失效时，沿用现有静默恢复。条件为该账号勾选了记住密码、有可用的已保存账号密码、未主动退出，并在最近一次主动登录或成功恢复起算的七天窗口内。成功后继续原请求，窗口不会因静默重登而顺延；条件不满足或恢复失败时，按原流程提示手动登录 |

历史数量与管理列表使用同一份按账号 ID 去重的集合。「当前使用」「已保存密码」「需输入密码」保留在条目上；删除单个记录仍需确认，清空历史时保留当前登录账号。退出登录继续提供「同时清除已保存密码」选择，保留本地课表。即使选择保留密码，主动退出的账号也不能被后台自动重新激活。

打开登录页或查看历史列表本身只读取状态。七天是**会话失效后可尝试静默重登的窗口**，并非一个定时退出点，也不限制用户主动点击「切换账号」。本次界面沿用「记住密码」作为现有控制项，不另设与实际策略脱节的「自动重登」开关。

## 4. 参考取舍

| 参考 | 在 iYango 中的用法 |
| --- | --- |
| [XJAU-Helper 登录页实图](https://github.com/FengHDSL/XJAU-Helper/blob/main/docs/screenshots/phone/07.jpg)及[页面源码](https://github.com/FengHDSL/XJAU-Helper/blob/main/entry/src/main/ets/view/LoginView.ets) | 参考大图标、跨页头的登录卡和卡内历史入口 |
| [橙色曲线登录页](https://github.com/TheAlphamerc/flutter_login_signup/blob/master/screenshots/screenshot_2.jpg)、[成组输入区](https://github.com/afgprogrammer/Flutter-Login-Page-UI/blob/master/assets/screenshot/one.png)、[深色表单](https://github.com/tonydavidx/signin-register-page-ui-flutter/blob/main/screenshots/signin_page.png) | 橙色日出、成组输入区、深色描边和通栏主按钮的视觉参考 |
| [sachet 本地登录页截图](<C:/Users/32773/Desktop/campus-ui-reference/3-sachet/docs/app-screenshots/login_page.png>) | 在卡片标题下写明学校和系统名称 |
| [XDYou 密码输入区](https://github.com/BenderBlog/traintime_pda/blob/a2d8af7fbcc3c97dfe43ddb97e811443a75abd05/lib/page/login/login_window.dart#L72-L104) | 将显隐操作固定在密码框末端 |
| [HutHelper 本地登录页设计稿](<C:/Users/32773/Desktop/campus-ui-reference/5-HutHelper/ui设计稿/第一版/B登录界面/1登录界面.jpg>) | 用于比较表单在首屏的位置 |

## 5. 实施位置与验收

品牌页头、历史展开与重登方式选择在 [SchoolLoginPage.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/school/SchoolLoginPage.kt)。输入与提交在 [SchoolLoginForm.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/school/SchoolLoginForm.kt)。[SchoolAccountScreen.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/school/SchoolAccountScreen.kt) 按登录入口确定卡片标题和返回位置。历史条目与帮助文案在 [SchoolAccountCards.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/school/SchoolAccountCards.kt)。回填、手动切换与提交行为对照 [SchoolAccountViewModel.kt](../../../feature/main/src/main/kotlin/com/gfs/iyango/feature/main/school/SchoolAccountViewModel.kt)，七天条件对照 [RoomSchoolAuthRepository.kt](../../../data/src/main/kotlin/com/gfs/iyango/data/repository/RoomSchoolAuthRepository.kt)。

| 验收场景 | 预期结果 |
| --- | --- |
| 首次、已有历史、已登录后添加账号 | 页头与入口相符；有历史时入口可见，数量与列表一致；返回帮助页或管理页后能回到发起操作的位置 |
| 连续输入、显隐、清空、键盘提交 | 切换显隐不丢输入，输入首字符时按钮不下移；清空后可继续输入；连续点击和键盘提交只发起一次请求 |
| 历史填写、切换及失败 | 填写不发请求。已保存密码的非当前账号可主动切换；失败可回填重试；当前账号标记唯一，删除和清空沿用确认流程 |
| 自动重登与手动恢复 | 用模拟时钟和模拟会话覆盖七天整、刚超过七天、未记住密码、无保存凭据、主动退出及续期失败。符合条件时原请求继续，静默恢复不延长窗口；查看页面不发起登录 |
| 深浅主题、壁纸、小屏、大字号和读屏 | 字段、错误和登录按钮在键盘打开时可滚动到达；按钮不重叠，错误只播报一次，历史账号和登录帮助均可独立聚焦 |

实施后优先扩展 [账号 UI 测试](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/school/SchoolAccountUiTest.kt) 和 [账号 ViewModel 测试](../../../feature/main/src/test/kotlin/com/gfs/iyango/feature/main/school/SchoolAccountViewModelTest.kt)，认证规则对照 [认证仓库测试](../../../data/src/test/kotlin/com/gfs/iyango/data/repository/RoomSchoolAuthRepositoryTest.kt)。视觉验收重点看键盘弹出后的可用高度、有壁纸时的文字对比，以及密码输入前后的按钮位置。

## 6. 实施与验证记录

首次登录、已有历史、添加账号和回填账号已接入共用表单。恢复会话的进度与失败原因显示在所选历史条目。只有学校明确拒绝凭据才标记密码框。进程重建后若只恢复了页面栈而表单已经释放，页面回到原来的账号管理位置。

业务请求在写回会话前核对账号和会话版本，每个请求最多尝试一次自动重登。模拟测试覆盖了退出后才返回的续期结果、删除账号后才返回的资料，以及切换账号后旧请求才报告过期。手动登录或恢复时，若随后的资料请求已确认会话失效，保留原当前账号。

初版验证时七个模块共 882 项单元测试通过，Debug APK 构建成功。真实教务账号的登录与会话续期仍需使用者验收。

橙色版在同一组七个模块重新验证，882 项通过。Benchmark 和 Debug 均已覆盖安装到 HONOR ELP-AN00。Android 16 模拟器使用空白账号环境核对了浅色与深色首屏、密码键盘弹出时的布局，以及「手动登录」与「记住密码」的联动。

通栏登录按钮版再次通过七个模块的 882 项测试，并覆盖安装了两个构建版本。Android 16 模拟器验证了空表单的浅杏色按钮、填写测试值后可点击的登录按钮、密码遮蔽、键盘打开时的紧凑布局，以及系统返回到首页。账号 UI 测试另行覆盖了从历史账号重登表单返回原列表的路径。
