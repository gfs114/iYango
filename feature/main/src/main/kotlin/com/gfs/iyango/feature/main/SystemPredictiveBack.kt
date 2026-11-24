package com.gfs.iyango.feature.main

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 系统级预测性返回检测状态。 */
enum class SystemPredictiveBackStatus {
    /** Android 12 及以下（API < 33）不支持预测性返回。 */
    UNSUPPORTED_ANDROID_VERSION,

    /** 系统级「预测性返回动画」未开启（`enable_back_animation = 0`，或 Android 13–14 未显式开启）。 */
    SYSTEM_SETTING_DISABLED,

    /** 系统全局动画缩放为 0 或系统动画器已关闭，预测性返回无法播放。 */
    SYSTEM_ANIMATION_DISABLED,

    /** Android 13+ 且系统级预测性返回与系统动画均可用。 */
    ENABLED,
}

internal const val GLOBAL_ENABLE_BACK_ANIMATION = "enable_back_animation"
internal const val EXTRA_SETTINGS_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
internal const val EXTRA_SETTINGS_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
internal const val PREF_KEY_BACK_NAVIGATION_ANIMATION = "back_navigation_animation"

/** 系统预测性返回相关的快捷跳转目标。 */
internal enum class PredictiveBackSettingsShortcut {
    /** 直达系统「开发者选项」并高亮「预测性返回动画」。 */
    DEVELOPER_PREDICTIVE_BACK,

    /** 打开「关于手机」，供未解锁开发者选项的用户连点 7 次版本号开启。 */
    ABOUT_PHONE_UNLOCK_DEV,

    /** 打开「无障碍」检查或关闭「移除动画」。 */
    ACCESSIBILITY_ANIMATIONS,

    /** 打开系统设置首页，便于按机型搜索「预测性返回」。 */
    SYSTEM_SETTINGS_HOME,
}

/** 纯逻辑解析系统级预测性返回可用状态（供真机查询与单元测试共用）。 */
internal fun resolveSystemPredictiveBackStatus(
    sdkInt: Int,
    enableBackAnimationSetting: Int?,
    animatorDurationScale: Float,
    animatorsEnabled: Boolean,
): SystemPredictiveBackStatus {
    if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
        return SystemPredictiveBackStatus.UNSUPPORTED_ANDROID_VERSION
    }
    when (enableBackAnimationSetting) {
        0 -> return SystemPredictiveBackStatus.SYSTEM_SETTING_DISABLED
        null -> if (sdkInt < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return SystemPredictiveBackStatus.SYSTEM_SETTING_DISABLED
        }
        // 非 0（如 1）或 Android 15+（API 35+ AOSP 移除开发者开关后默认开启）继续检查动画总开关。
    }
    if (animatorDurationScale <= 0f || !animatorsEnabled) {
        return SystemPredictiveBackStatus.SYSTEM_ANIMATION_DISABLED
    }
    return SystemPredictiveBackStatus.ENABLED
}

/** 读取当前设备的系统级预测性返回状态。Android 12 及以下直接返回不支持，不查系统设置。 */
internal fun querySystemPredictiveBackStatus(context: Context): SystemPredictiveBackStatus {
    val sdkInt = Build.VERSION.SDK_INT
    if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
        return SystemPredictiveBackStatus.UNSUPPORTED_ANDROID_VERSION
    }
    val resolver = context.contentResolver
    val backAnimationSetting = runCatching {
        Settings.Global.getString(resolver, GLOBAL_ENABLE_BACK_ANIMATION)?.trim()?.toIntOrNull()
    }.getOrNull()
    val animatorScale = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    val animatorsEnabled = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    return resolveSystemPredictiveBackStatus(
        sdkInt = sdkInt,
        enableBackAnimationSetting = backAnimationSetting,
        animatorDurationScale = animatorScale,
        animatorsEnabled = animatorsEnabled,
    )
}

/** 构建各快捷跳转项对应的系统 Intent；对非 Activity Context 自动补齐 `FLAG_ACTIVITY_NEW_TASK`。 */
internal fun buildPredictiveBackShortcutIntent(
    context: Context,
    shortcut: PredictiveBackSettingsShortcut,
): Intent {
    val intent = when (shortcut) {
        PredictiveBackSettingsShortcut.DEVELOPER_PREDICTIVE_BACK -> Intent(
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        ).apply {
            val fragmentArgs = Bundle().apply {
                putString(EXTRA_SETTINGS_FRAGMENT_ARG_KEY, PREF_KEY_BACK_NAVIGATION_ANIMATION)
            }
            putExtra(EXTRA_SETTINGS_FRAGMENT_ARG_KEY, PREF_KEY_BACK_NAVIGATION_ANIMATION)
            putExtra(EXTRA_SETTINGS_SHOW_FRAGMENT_ARGS, fragmentArgs)
        }
        PredictiveBackSettingsShortcut.ABOUT_PHONE_UNLOCK_DEV -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        PredictiveBackSettingsShortcut.ACCESSIBILITY_ANIMATIONS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PredictiveBackSettingsShortcut.SYSTEM_SETTINGS_HOME -> Intent(Settings.ACTION_SETTINGS)
    }
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return intent
}

/** 跳转至指定的系统设置页，携带必要的兼容回退链。 */
internal fun openPredictiveBackSettingsShortcut(
    context: Context,
    shortcut: PredictiveBackSettingsShortcut,
): Boolean {
    val candidates = when (shortcut) {
        PredictiveBackSettingsShortcut.DEVELOPER_PREDICTIVE_BACK -> listOf(
            PredictiveBackSettingsShortcut.DEVELOPER_PREDICTIVE_BACK,
            PredictiveBackSettingsShortcut.ABOUT_PHONE_UNLOCK_DEV,
            PredictiveBackSettingsShortcut.SYSTEM_SETTINGS_HOME,
        )
        PredictiveBackSettingsShortcut.SYSTEM_SETTINGS_HOME -> listOf(
            PredictiveBackSettingsShortcut.SYSTEM_SETTINGS_HOME,
        )
        PredictiveBackSettingsShortcut.ABOUT_PHONE_UNLOCK_DEV,
        PredictiveBackSettingsShortcut.ACCESSIBILITY_ANIMATIONS,
        -> listOf(shortcut, PredictiveBackSettingsShortcut.SYSTEM_SETTINGS_HOME)
    }
    for (target in candidates) {
        val started = runCatching {
            context.startActivity(buildPredictiveBackShortcutIntent(context, target))
            true
        }.recoverCatching { err ->
            if (err is ActivityNotFoundException || err is SecurityException) false else throw err
        }.getOrDefault(false)
        if (started) return true
    }
    return false
}

/** 引导用户前往系统对应设置页开启预测性返回或恢复系统动画，找不到特定页时回退系统主设置。 */
internal fun openSystemPredictiveBackSettings(
    context: Context,
    status: SystemPredictiveBackStatus,
) {
    val shortcut = when (status) {
        SystemPredictiveBackStatus.SYSTEM_SETTING_DISABLED -> PredictiveBackSettingsShortcut.DEVELOPER_PREDICTIVE_BACK
        SystemPredictiveBackStatus.SYSTEM_ANIMATION_DISABLED -> PredictiveBackSettingsShortcut.ACCESSIBILITY_ANIMATIONS
        SystemPredictiveBackStatus.UNSUPPORTED_ANDROID_VERSION,
        SystemPredictiveBackStatus.ENABLED,
        -> PredictiveBackSettingsShortcut.SYSTEM_SETTINGS_HOME
    }
    openPredictiveBackSettingsShortcut(context, shortcut)
}

@Composable
private fun <T> rememberObservedGlobalSetting(
    uris: List<Uri?>,
    readerKey: Any?,
    fallback: T,
    bypassObservation: Boolean,
    reader: () -> T,
): T {
    if (bypassObservation) return fallback
    val context = LocalContext.current
    val resolver = context.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var value by remember(context, readerKey) { mutableStateOf(reader()) }

    DisposableEffect(lifecycle, context, readerKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                value = reader()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(resolver, context, readerKey) {
        val handler = Handler(Looper.getMainLooper())
        val contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                value = reader()
            }
        }
        val registered = runCatching {
            uris.filterNotNull().forEach { uri ->
                resolver.registerContentObserver(uri, false, contentObserver)
            }
            true
        }.getOrDefault(false)
        value = reader()
        onDispose {
            if (registered) {
                runCatching { resolver.unregisterContentObserver(contentObserver) }
            }
            handler.removeCallbacksAndMessages(null)
        }
    }

    return value
}

/**
 * 实时订阅系统级预测性返回状态：
 * - Android 12 及以下（API < 33）直接短路返回 [SystemPredictiveBackStatus.UNSUPPORTED_ANDROID_VERSION]；
 * - Android 13+ 在 `ON_RESUME`（从系统设置切回）及 `Settings.Global` 变化时自动刷新。
 */
@Composable
internal fun rememberSystemPredictiveBackStatus(
    checker: (() -> SystemPredictiveBackStatus)? = null,
): SystemPredictiveBackStatus {
    val context = LocalContext.current
    val uris = remember {
        listOf(
            Settings.Global.getUriFor(GLOBAL_ENABLE_BACK_ANIMATION),
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
        )
    }
    return rememberObservedGlobalSetting(
        uris = uris,
        readerKey = checker,
        fallback = SystemPredictiveBackStatus.UNSUPPORTED_ANDROID_VERSION,
        bypassObservation = checker == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
        reader = { checker?.invoke() ?: querySystemPredictiveBackStatus(context) },
    )
}
