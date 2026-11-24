# 课程详情与调整弹窗布局

按用户连续反馈完成四项调整：

- 去掉课程详情右上角“关闭”，保留弹窗外点击和系统返回关闭。
- 课程标题按文字高度布局，下方间距 4dp。
- “调整课程”移到底部，与编辑、复制、删除同排；原有可调整条件和回调保持一致。
- 调整弹窗删除“原安排保留，可恢复本周调整”，课程名与“当前：……”换行。

修改 `CourseDetailDialog.kt`、`ScheduleAdjustmentDialog.kt`，并更新已有只读详情测试的关闭按钮断言。

验证：课程实例详情、历史课表详情、课表网格、首页切换四组共 26 项测试通过；日志 `diagnostics/home-customization-20261113/course-detail-final-verification.log`。最后一处纯文案换行后再次 Debug 构建通过，未重复运行测试；日志 `course-adjustment-linebreak-build.log`。`git diff --check` 通过。

真机观察：底部四项按钮完整同排，关闭按钮已消失；此前已检查外部点击和系统返回可关闭详情。最后的文字删除与换行已构建并保留数据覆盖安装 Debug 版，留待用户继续查看。没有更改课程数据、提交或推送。

最终 APK SHA-256：`328F4DED6FA8386CAE578A15992BCE54ECE272564353533129D9C0A407ABA619`。
