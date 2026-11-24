# 关于页开发者模式

“关于与反馈”默认隐藏手机型号、近期状态、诊断日志、导出/清空操作及诊断说明。连续点击版本号七次后显示；相邻点击超过两秒、退出页面或页面进入后台时，未完成的计数重置。使用单调时钟计时。

开启状态通过现有设置事务保存在本机，重启仍保留。页面提供关闭入口，关闭后重新隐藏；写入失败保留原状态并提示重试。旧设置默认关闭；设置备份不导出此字段，恢复备份保留目标设备自己的状态。日志记录机制不受显示开关影响。

手机型号使用系统 Build.MANUFACTURER 与 Build.MODEL，重复品牌前缀不再追加，空值和 unknown 有回退。用户补充要求型号也仅在开发者模式下可见，已落实到显示条件与 UI 断言。

验证命令：`.\gradlew.bat :feature:main:testDebugUnitTest --tests 'com.gfs.iyango.feature.main.about.AboutDeveloperModeTest' :data:testDebugUnitTest --tests 'com.gfs.iyango.data.repository.DeveloperModePersistenceTest' --tests 'com.gfs.iyango.data.repository.HomePreferencesPersistenceTest' :app:assembleDebug --console=plain`。

最终构建通过，11 项测试零失败、零错误、零跳过。覆盖默认隐藏、六次不解锁、七次解锁、关闭后重新计数、超时/离开重计、保存失败重试、诊断操作保护、既有解锁状态、存储重开、旧数据兼容与首页设置回归。最初默认隐藏测试先失败，记录见 `diagnostics/ui-copy-20261113/developer-mode-red.log`；最终结果见同目录 `developer-mode-verification.log`。`git diff --check` 通过。

真机确认了六次点击仍隐藏、七次点击解锁，系统型号为 HONOR ELP-AN00。随后补充的“型号也需解锁”规则已通过 UI 自动化测试；最后 APK 已保留数据覆盖安装到手机 Debug 版，未重复最终真机视觉验收。

最终 APK SHA-256：`8FD7D75C3CDBDC3F5D9F0F2B3F5817C977BAC08D84E10837CD21C92C86C0C2F2`。未提交或推送，保留既有改动。
