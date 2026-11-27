package com.gfs.iyango.platform.android

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.domain.TodayCourseState
import com.gfs.iyango.core.domain.TodayCourseStateResolver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NextClassTileEntryPoint {
    fun timetableRepository(): TimetableRepositoryPort
    fun effectiveSchedule(): EffectiveSchedulePort
    fun clock(): Clock
}

/**
 * 下拉控制中心「下节课」Quick Settings Tile。
 */
class NextClassTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onStopListening() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val intent = LaunchIntents.launchIntent(this, tab = 0) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_CODE_OPEN_TODAY,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            qsTile ?: return@launch
            val model = runCatching { loadTileModel() }.getOrElse {
                NextClassTileModel(
                    state = Tile.STATE_INACTIVE,
                    label = "下节课",
                    subtitle = "点击打开课表",
                )
            }
            launch(Dispatchers.Main) {
                val activeTile = qsTile ?: return@launch
                NextClassTilePresentation.applyToTile(activeTile, model)
            }
        }
    }

    private suspend fun loadTileModel(): NextClassTileModel {
        val entry = EntryPointAccessors.fromApplication(applicationContext, NextClassTileEntryPoint::class.java)
        val hasTimetable = entry.timetableRepository().observeCurrent().first() != null ||
            entry.timetableRepository().observeAll().first().isNotEmpty()
        if (!hasTimetable) {
            return NextClassTilePresentation.format(
                todayState = TodayCourseState.NoClass,
                tomorrowFirst = null,
                hasTimetable = false,
            )
        }
        val now = LocalDateTime.now(entry.clock())
        val today = now.toLocalDate()
        val todaySchedule = entry.effectiveSchedule().resolveDay(today)
        val todayState = TodayCourseStateResolver.resolve(todaySchedule.occurrences, now)
        val tomorrowSchedule = entry.effectiveSchedule().resolveDay(today.plusDays(1))
        val tomorrowFirst = TodayCourseStateResolver.timedOccurrences(tomorrowSchedule.occurrences).firstOrNull()
            ?: tomorrowSchedule.occurrences.firstOrNull()
        return NextClassTilePresentation.format(
            todayState = todayState,
            tomorrowFirst = tomorrowFirst,
            hasTimetable = true,
        )
    }

    companion object {
        private const val REQUEST_CODE_OPEN_TODAY = 4102

        fun requestListeningState(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, NextClassTileService::class.java),
                )
            }
        }
    }
}
