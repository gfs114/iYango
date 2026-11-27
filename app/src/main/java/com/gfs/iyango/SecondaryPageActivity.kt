package com.gfs.iyango

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import com.gfs.iyango.feature.main.IYangoSecondaryPage
import com.gfs.iyango.feature.main.SecondaryPageRoute
import dagger.hilt.android.AndroidEntryPoint

/**
 * 通用二级/三级页宿主 Activity（多 Activity 架构 P2）。
 *
 * 每个二级/三级页通过 `startActivity` 压入系统 Activity 任务栈，页面 clean 状态下
 * 不注册返回拦截，由 Android 14–16 系统窗口合成器直接渲染原生
 * `TYPE_CROSS_ACTIVITY` 预测性返回动画（真实露出底层 Activity）。
 *
 * 反模式红线：保持不透明 `@style/Theme.IYango`（禁 `windowIsTranslucent`）、
 * 不调用 `overridePendingTransition`、默认 `launchMode="standard"`
 * （启动 Intent 不加 `FLAG_ACTIVITY_SINGLE_TOP`，保证二级页跳三级页不被吞）。
 */
@AndroidEntryPoint
class SecondaryPageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val route = SecondaryPageRoute.parse(intent?.getStringExtra(EXTRA_ROUTE))
        if (route == null) {
            // 白名单外或被系统重建后丢失参数：直接退出，不渲染空页面。
            finish()
            return
        }
        setContent {
            IYangoActivityHost { settings, onThemeSettingsChange ->
                IYangoSecondaryPage(
                    route = route,
                    themeSettings = settings,
                    onThemeSettingsChange = onThemeSettingsChange,
                    onBack = { finish() },
                    onOpenRoute = { next -> SecondaryPageLauncher.open(this@SecondaryPageActivity, next) },
                )
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "com.gfs.iyango.extra.SECONDARY_ROUTE"
    }
}

/**
 * 二级页启动器：路由白名单校验 + 400ms 同路由防抖（快速双击不会重复开两个同页面；
 * 不同路由连续跳转不受影响）。按时间戳判定，无生命周期耦合。
 */
object SecondaryPageLauncher {

    /** 同路由防抖窗口（ms）。 */
    internal const val DEBOUNCE_MS = 400L

    /** 测试可重置的防抖实例（时间戳判定，无生命周期状态）。 */
    @VisibleForTesting
    internal var throttle: RouteOpenThrottle = RouteOpenThrottle(DEBOUNCE_MS)

    /**
     * 构建目标 Intent；未知/非法路由返回 null。
     *
     * 参数 Extra 键与各 `SavedStateHandle` ViewModel 的键完全一致
     * （`courseId` / `copyFromId` / `timetableId` / `perTimetable`），
     * 经 [ComponentActivity.defaultViewModelCreationExtras] 自动注入 Hilt ViewModel。
     */
    fun createIntent(context: Context, route: String): Intent? {
        val parsed = SecondaryPageRoute.parse(route) ?: return null
        return createIntent(context, parsed)
    }

    private fun createIntent(context: Context, parsed: SecondaryPageRoute): Intent {
        val intent = Intent(context, SecondaryPageActivity::class.java)
        intent.putExtra(SecondaryPageActivity.EXTRA_ROUTE, parsed.route)
        when (parsed) {
            is SecondaryPageRoute.Editor -> {
                intent.putExtra("courseId", parsed.courseId)
                intent.putExtra("copyFromId", parsed.copyFromId)
            }
            is SecondaryPageRoute.CourseRaw -> intent.putExtra("timetableId", parsed.timetableId)
            is SecondaryPageRoute.Display -> intent.putExtra("perTimetable", parsed.perTimetable)
            is SecondaryPageRoute.Classroom -> {
                intent.putExtra("prefillWeek", parsed.week)
                intent.putExtra("prefillDay", parsed.dayOfWeek)
                intent.putExtra("prefillStartSection", parsed.startSection)
                intent.putExtra("prefillEndSection", parsed.endSection)
            }
            is SecondaryPageRoute.AiProviderDetail -> intent.putExtra("profileId", parsed.profileId)
            is SecondaryPageRoute.Plain -> Unit
        }
        return intent
    }

    /** 打开二级页；同路由 [SecondaryPageLauncher.DEBOUNCE_MS] 内重复调用直接忽略。 */
    fun open(context: Context, route: String) {
        val parsed = SecondaryPageRoute.parse(route) ?: return
        if (!throttle.shouldOpen(parsed.route, SystemClock.elapsedRealtime())) return
        val intent = createIntent(context, parsed)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 绝不添加 FLAG_ACTIVITY_SINGLE_TOP / FLAG_ACTIVITY_CLEAR_TOP，
        // 也绝不调用 overridePendingTransition —— 保住系统跨 Activity 预测返回。
        context.startActivity(intent)
    }
}

/** 同路由时间节流（纯逻辑，可单测）：窗口内重复路由拒绝，不同路由放行。 */
internal class RouteOpenThrottle(private val windowMs: Long) {

    private val lastOpenAt = HashMap<String, Long>()

    fun shouldOpen(route: String, nowMs: Long): Boolean {
        synchronized(this) {
            val last = lastOpenAt[route]
            if (last != null && nowMs - last < windowMs) return false
            lastOpenAt[route] = nowMs
            return true
        }
    }
}
