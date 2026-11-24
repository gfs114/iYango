package com.gfs.iyango

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.feature.main.IYangoMainApp
import com.gfs.iyango.platform.android.AndroidGradeNotificationPlatform
import com.gfs.iyango.platform.android.LaunchIntents
import com.gfs.iyango.platform.android.ReminderIntents
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 主 Activity 入口（多 Activity 架构 P2）：承载今日/课表/成绩/我的四个主 Tab。
 *
 * 二级/三级页改由 [SecondaryPageActivity] 经 [SecondaryPageLauncher.open] 拉起，
 * 压入系统 Activity 任务栈以启用原生跨 Activity 预测性返回；主壳常态不再
 * 注册页面级返回拦截（[IYangoMainApp] 已移除 MainTabBackHandler），四个主 Tab
 * 直接放行给系统 `TYPE_RETURN_TO_HOME`。
 *
 * 回前台刷新 / 提醒复核 / 检查更新已迁至 [IYangoApplication] 的进程级
 * 前台判定，本类不再在 onStart 重复触发。
 *
 * 外部入口（通知/小组件/快捷方式 `iyango://open/<target>`）解析后经
 * [SecondaryPageLauncher] 或页签切换消费，各参数均按白名单校验。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 通知点击待跳转课程；Compose 消费后清空。 */
    private val pendingCourseId = MutableStateFlow<Long?>(null)

    /** 组件/快捷入口待切换页签；Compose 消费后清空。 */
    private val pendingTab = MutableStateFlow<Int?>(null)

    /** 组件/快捷入口待打开的次级页路由；Compose 消费后清空。 */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNavigationIntent(intent)
    }

    /**
     * 统一解析外部入口：课程 > 次级页路由 > 页签。
     *
     * 组件与快捷入口走 `iyango://open/<target>`，各参数均按白名单校验，
     * 未知目标直接丢弃，不改变当前页面。
     */
    private fun consumeNavigationIntent(intent: Intent?) {
        val gradeNotifId = intent?.getIntExtra(AndroidGradeNotificationPlatform.EXTRA_NOTIFICATION_ID, 0) ?: 0
        if (gradeNotifId != 0) {
            AndroidGradeNotificationPlatform.dismissNotification(this, gradeNotifId)
            intent?.removeExtra(AndroidGradeNotificationPlatform.EXTRA_NOTIFICATION_ID)
        }
        val parsed = consumeExternalIntent(intent) ?: return
        parsed.courseId?.let { pendingCourseId.value = it }
        parsed.route?.let { pendingRoute.value = it }
        parsed.tab?.let { pendingTab.value = it }
    }

    internal data class ParsedExternalTarget(
        val courseId: Long? = null,
        val route: String? = null,
        val tab: Int? = null,
    )

    companion object {
        /** 解析并消费外部入口 Intent；白名单外 URI/Extra 均丢弃，并在消费后清除防止旋转重放。 */
        internal fun consumeExternalIntent(intent: Intent?): ParsedExternalTarget? {
            if (intent == null) return null
            var courseIdResult: Long? = null
            val courseId = intent.getLongExtra(ReminderIntents.EXTRA_COURSE_ID, 0L)
            if (courseId > 0) {
                courseIdResult = courseId
                intent.removeExtra(ReminderIntents.EXTRA_COURSE_ID)
            }

            val children = intent.data?.takeIf {
                it.scheme == LaunchIntents.URI_SCHEME && it.host == LaunchIntents.URI_HOST
            }?.pathSegments
            val uriRoute = children?.firstOrNull()?.takeIf { it in LaunchIntents.ROUTES }
            val uriTab = uriRoute?.takeIf { it in LaunchIntents.TAB_ROUTES }
                ?.let { LaunchIntents.TAB_ROUTES.indexOf(it) }

            val extraRoute = intent.getStringExtra(LaunchIntents.EXTRA_TARGET_ROUTE)
                ?.takeIf { it in LaunchIntents.ROUTES }
            val extraTab = intent.getIntExtra(LaunchIntents.EXTRA_TARGET_TAB, -1)
                .takeIf { it in LaunchIntents.TAB_ROUTES.indices }

            // 次级页（如 shortcut 里的课表管理）优先于页签；页签路由本身仍按页签处理。
            val route = extraRoute ?: uriRoute
            if (route != null && route !in LaunchIntents.TAB_ROUTES && courseId <= 0) {
                intent.removeExtra(LaunchIntents.EXTRA_TARGET_ROUTE)
                if (uriRoute != null) intent.data = null
                return ParsedExternalTarget(courseId = courseIdResult, route = route)
            }
            val tab = extraTab
                ?: uriTab
                ?: route?.takeIf { it in LaunchIntents.TAB_ROUTES }?.let { LaunchIntents.TAB_ROUTES.indexOf(it) }
            if (tab != null && courseId <= 0) {
                intent.removeExtra(LaunchIntents.EXTRA_TARGET_TAB)
                if (uriRoute != null) intent.data = null
                return ParsedExternalTarget(courseId = courseIdResult, tab = tab)
            }
            return if (courseIdResult != null) ParsedExternalTarget(courseId = courseIdResult) else null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) consumeNavigationIntent(intent)
        setContent {
            IYangoActivityHost { settings, onThemeSettingsChange ->
                val pendingCourse by pendingCourseId.collectAsStateWithLifecycle()
                val pendingTabState by pendingTab.collectAsStateWithLifecycle()
                val pendingRouteState by pendingRoute.collectAsStateWithLifecycle()
                IYangoMainApp(
                    themeSettings = settings,
                    onThemeSettingsChange = onThemeSettingsChange,
                    onOpenRoute = { route -> SecondaryPageLauncher.open(this@MainActivity, route) },
                    pendingCourseId = pendingCourse,
                    onPendingCourseConsumed = { pendingCourseId.value = null },
                    pendingTab = pendingTabState,
                    onPendingTabConsumed = { pendingTab.value = null },
                    pendingRoute = pendingRouteState,
                    onPendingRouteConsumed = { pendingRoute.value = null },
                )
            }
        }
    }
}
