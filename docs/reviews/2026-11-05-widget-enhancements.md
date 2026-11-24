# 2026-11-05 透明桌面组件增强审查与交付

本轮完成现有四种 RemoteViews 组件的增强。Sol 实现，主代理独立核对源码、需求、图像与运行结果；另外完成需求符合性及代码质量只读审查。当前无未修复的阻断问题。

## 功能结果

- 新组件默认轻玻璃，主体渐变保留约22%～28%不透明度。另有全透明与约50%不透明的柔雾；已有实色设置保留。透明度只影响背景，文字保持不透明。这些外观没有实时模糊或折射。
- 三种列表根据实际 TextView 行高、可用尺寸、字号、系统字体缩放计算容量，最多12行；超出时显示剩余数量和课表身份。
- 每个实例独立翻日前后最多7天，支持回到今天、今日剩余课程数，跨日恢复今天。标尺保留当前时间。
- 预览继续查询未来7天。跟随自动选表显示当天有效 owner 课表；临时整天引用其他表时，不把课程来源误认为 owner。显式绑定保持固定。
- 设置入口支持重新配置、字号、教师/教室与背景档位；配置读取/保存失败不会报告添加成功。
- Room 4→5 无损迁移，保留原组件id、绑定及实色选择；schema与测试资产同步。
- 数据刷新覆盖非当前固定绑定、自动选表及临时整天来源。时间/时区/开机/升级事件触发刷新；非精确次日闹钟及30分钟系统周期兜底，删除最后实例取消闹钟。

## 审查中发现并修复

1. 中文字体实际行高高于最初估算：改用真实 TextView 测量；导航、设置、课程与提示均检查边界。
2. 非整数密度dp取整累计误差：按资源规则取整，补充200/440dpi用例。
3. 预览设置占用独立行：并入标题行，小高度按偏好字号上限自适应。
4. 生命周期回调缺少类型和provider校验：明确action白名单，验证options/数组及已绑定实例，兼容框架首次ENABLE_AND_UPDATE。Receiver保持非导出，未据此主张普通第三方可直接利用。
5. 临时整天预览显示来源表名称：统一按下一节发生日期解析有效owner。
6. 旧翻日查询晚返回覆盖新日期：进程级按widgetId串行化配置写入、构建与发布；不同实例独立。删除废弃在途结果并等待写入后清理；取消和异常释放门控状态。

安全对齐说明及精确diff见 [安全报告](G:/code/Android/iYango/diagnostics/widget-security-alignment.md)。

## 主代理最终验证

命令：

```powershell
.\gradlew.bat :core:model:test :core:domain:test :data:testDebugUnitTest :platform:android:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

结果：退出码0，BUILD SUCCESSFUL。以下为受影响模块的验证范围，未将其他模块的历史测试结果计入：

| 模块 | 测试数 | 失败/错误/跳过 |
|---|---:|---:|
| core:model | 27 | 0/0/0 |
| core:domain | 128 | 0/0/0 |
| data | 145 | 0/0/0 |
| platform:android | 24 | 0/0/0 |
| app | 22 | 0/0/0 |
| 合计 | 346 | 0/0/0 |

验证包括：RemoteViews实际加载/重用、日夜玻璃透明度、三种列表36组尺寸/字号/字体组合、小预览及非整数密度、翻日隔离和跨日复位、参数校验和PendingIntent、午夜调度及删除取消、迁移和Room往返、有效owner/固定绑定/七天查询，以及可控挂起的更新顺序/取消/删除时序。

日志：[独立最终验证](G:/code/Android/iYango/diagnostics/widget-independent-verification.log)。
机器可读汇总：[widget-test-summary.json](G:/code/Android/iYango/diagnostics/widget-test-summary.json)。
本地合成预览：[日间透明度](G:/code/Android/iYango/diagnostics/widget-opacity-day.png)、[夜间透明度](G:/code/Android/iYango/diagnostics/widget-opacity-night.png)、[四种布局](G:/code/Android/iYango/diagnostics/widget-layout-day.png)。

## 本轮APK

- 路径：[app-debug.apk](G:/code/Android/iYango/app/build/outputs/apk/debug/app-debug.apk)
- 构建时间：2026-11-05 21:31:50，Asia/Shanghai。
- 字节数：17462492。
- SHA256：`B0C27BC540A9BAA1284ED3802AF2B6197ABD993AD24E5B8C100748E498003978`。
- 本包包含最后的并发修复；此前21:19的APK不作为本轮交付物。

## 验收边界与工作区

未安装、操作手机或调用真实学校接口。桌面宿主/真机视觉、配置点击和省电后台行为仍由用户验收。本地渲染图使用合成壁纸。

非精确闹钟可能受系统省电限制延迟，不承诺零点准时；依据 [Android闹钟文档](https://developer.android.com/develop/background-work/services/alarms/schedule)。周期更新间隔与事件刷新依据 [Android组件更新文档](https://developer.android.com/develop/ui/views/appwidgets/advanced)。

当前分支保持iYango_prelease。原有12个成绩页相关未提交文件逐个SHA256与本轮开始时一致；本轮未提交或推送。APK按当前工作区构建，保留这些既有成绩页改动。
