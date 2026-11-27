package com.gfs.iyango

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.gfs.iyango.core.domain.AppUpdatePort
import com.gfs.iyango.core.domain.AutoSilentCoordinatorPort
import com.gfs.iyango.core.domain.CalendarCoordinatorPort
import com.gfs.iyango.core.domain.GradeCheckTrigger
import com.gfs.iyango.core.domain.GradeMonitorCoordinatorPort
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.WebDavSyncPort
import com.gfs.iyango.data.repository.GradeRefreshHandler
import com.gfs.iyango.data.repository.ProfileRefreshHandler
import com.gfs.iyango.data.repository.RefreshHandlerRegistry
import com.gfs.iyango.data.repository.SchoolTimetableRefreshHandler
import com.gfs.iyango.data.seed.DemoSeeder
import com.gfs.iyango.platform.android.AndroidWidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：触发 Hilt 依赖图生成；启动期用应用级作用域播种演示数据（幂等）。
 *
 * 多 Activity 架构（P1）：回前台刷新 / 提醒复核 / 自动检查更新从 MainActivity.onStart
 * 迁到进程级生命周期——[AppForegroundTracker] 统计非配置变更的 startedActivityCount，
 * 仅当应用真正进入前台（计数 0→1）时触发；从二级页退回 MainActivity 不再误触发。
 */
@HiltAndroidApp
class IYangoApplication : Application() {

    /** 应用级协程作用域：生命周期与进程一致，用于启动期后台任务。 */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 演示数据播种器（由 Hilt 提供，data 层构造）。 */
    @Inject
    lateinit var demoSeeder: DemoSeeder

    /** 课表变化后触发桌面组件重绘（实例化即开始观察）。 */
    @Inject
    lateinit var widgetUpdater: AndroidWidgetUpdater

    /** 学校资源刷新执行器注册表（F05）。 */
    @Inject
    lateinit var refreshHandlers: RefreshHandlerRegistry

    /** 课表自动刷新：仅刷新已绑定的教务学期。 */
    @Inject
    lateinit var timetableRefreshHandler: SchoolTimetableRefreshHandler

    /** 成绩刷新：只重查用户已确认的学期范围。 */
    @Inject
    lateinit var gradeRefreshHandler: GradeRefreshHandler

    /** 个人资料刷新：重拉当前账号的姓名/学号/班级。 */
    @Inject
    lateinit var profileRefreshHandler: ProfileRefreshHandler

    /** 回前台开启一轮刷新（协调器内部抑制旋转带来的重复信号）。 */
    @Inject
    lateinit var refreshCoordinator: RefreshCoordinatorPort

    /** 授权可能在前台期间被系统撤销：回前台复核提醒与日历状态。 */
    @Inject
    lateinit var reminderCoordinator: ReminderCoordinatorPort

    @Inject
    lateinit var calendarCoordinator: CalendarCoordinatorPort

    @Inject
    lateinit var gradeMonitorCoordinator: GradeMonitorCoordinatorPort

    /** 每次进入应用自动检测更新（未配置地址时静默跳过）。 */
    @Inject
    lateinit var appUpdate: AppUpdatePort

    @Inject
    lateinit var autoSilentCoordinator: AutoSilentCoordinatorPort

    @Inject
    lateinit var webDavSync: WebDavSyncPort

    private lateinit var foregroundTracker: AppForegroundTracker

    override fun onCreate() {
        super.onCreate()
        // 仅持有引用：观察者在构造时启动，进程存活期间持续生效。
        widgetUpdater
        refreshHandlers.register(timetableRefreshHandler)
        refreshHandlers.register(gradeRefreshHandler)
        refreshHandlers.register(profileRefreshHandler)
        applicationScope.launch {
            try {
                demoSeeder.seedDemoIfEmpty()
                demoSeeder.ensureTestShowcaseCourses()
                widgetUpdater.updateAll()
            } catch (e: Exception) {
                // 播种失败不阻塞应用启动，仅记录日志。
                Log.e(TAG, "演示数据播种失败", e)
            }
            runCatching { gradeMonitorCoordinator.syncSchedule() }
        }
        foregroundTracker = AppForegroundTracker { onAppEnteredForeground() }
        registerActivityLifecycleCallbacks(ForegroundLifecycleBridge())
    }

    /** 应用真正进入前台（冷启动或后台回前台）：刷新、复核与检查更新统一入口。 */
    private fun onAppEnteredForeground() {
        refreshCoordinator.onForeground()
        applicationScope.launch(Dispatchers.IO) {
            runCatching { reminderCoordinator.refresh() }
            runCatching { autoSilentCoordinator.refresh() }
            runCatching { calendarCoordinator.refresh() }
            runCatching { gradeMonitorCoordinator.checkOnTrigger(GradeCheckTrigger.FOREGROUND_SILENT) }
            runCatching { webDavSync.syncIfDue() }
        }
        // 独立协程不阻塞本地刷新。
        applicationScope.launch(Dispatchers.IO) {
            runCatching { appUpdate.checkOnLaunch() }
        }
    }

    /** 只桥接 start/stop 计数；其余生命周期回调不涉及前后台判定。 */
    private inner class ForegroundLifecycleBridge : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            // 0→1 且非配置变更重建时 tracker 内部回调 [onAppEnteredForeground]。
            foregroundTracker.onActivityStarted()
        }

        override fun onActivityStopped(activity: Activity) {
            foregroundTracker.onActivityStopped(activity.isChangingConfigurations)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        private const val TAG = "IYangoApplication"
    }
}
