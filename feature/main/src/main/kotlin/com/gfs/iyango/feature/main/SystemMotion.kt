package com.gfs.iyango.feature.main

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Compose applies nonzero system duration scales itself. Only observe whether motion is allowed. */
@Composable
internal fun rememberMotionEnabled(appEnabled: Boolean): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val setting = Settings.Global.ANIMATOR_DURATION_SCALE
    fun allowed() = Settings.Global.getFloat(resolver, setting, 1f) > 0f && ValueAnimator.areAnimatorsEnabled()
    var systemEnabled by remember(lifecycle, resolver) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && allowed())
    }
    LifecycleStartEffect(context, view) {
        var observing = true
        val handler = Handler(Looper.getMainLooper())
        val refresh = Runnable { if (observing) systemEnabled = allowed() }
        // On older releases the process can receive the effective scale after the global setting.
        fun refreshLegacyScale() {
            refresh.run()
            view.removeCallbacks(refresh)
            view.postOnAnimation(refresh)
        }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (Build.VERSION.SDK_INT >= 33) refresh.run() else refreshLegacyScale()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(setting), false, observer)
        val unsubscribe = if (Build.VERSION.SDK_INT >= 33) {
            observeEffectiveScale { handler.post(refresh) }
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = refreshLegacyScale()
            }
            context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            ({ context.unregisterReceiver(receiver) })
        }
        refresh.run()
        onStopOrDispose {
            observing = false
            systemEnabled = false
            resolver.unregisterContentObserver(observer)
            unsubscribe()
            handler.removeCallbacks(refresh)
            view.removeCallbacks(refresh)
        }
    }
    return appEnabled && systemEnabled
}

@RequiresApi(33)
private fun observeEffectiveScale(onChange: () -> Unit): () -> Unit {
    val listener = ValueAnimator.DurationScaleChangeListener { onChange() }
    ValueAnimator.registerDurationScaleChangeListener(listener)
    return { ValueAnimator.unregisterDurationScaleChangeListener(listener) }
}
