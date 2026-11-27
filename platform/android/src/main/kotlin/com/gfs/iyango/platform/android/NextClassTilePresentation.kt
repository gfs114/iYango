package com.gfs.iyango.platform.android

import android.os.Build
import android.service.quicksettings.Tile
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.TodayCourseState
import java.util.Locale

data class NextClassTileModel(
    val state: Int,
    val label: String,
    val subtitle: String,
)

/**
 * 下拉控制中心「下节课」磁贴的纯展示格式化规则（严禁使用 · / • 分隔符）。
 */
object NextClassTilePresentation {

    fun format(
        todayState: TodayCourseState,
        tomorrowFirst: CourseOccurrence?,
        hasTimetable: Boolean,
    ): NextClassTileModel {
        if (!hasTimetable) {
            return NextClassTileModel(
                state = Tile.STATE_INACTIVE,
                label = "下节课",
                subtitle = "未设置课表",
            )
        }
        return when (todayState) {
            is TodayCourseState.InClass -> {
                val course = todayState.current.course
                val endClock = todayState.current.endMinute?.let(::formatClock)
                val subtitle = listOfNotNull(
                    course.classroom.trim().takeIf { it.isNotEmpty() },
                    endClock?.let { "${it}下课" } ?: "上课中",
                ).joinToString(" ")
                NextClassTileModel(
                    state = Tile.STATE_ACTIVE,
                    label = course.name.ifBlank { "上课中" },
                    subtitle = subtitle,
                )
            }
            is TodayCourseState.Upcoming -> {
                val course = todayState.next.course
                val startClock = todayState.next.startMinute?.let(::formatClock)
                val subtitle = listOfNotNull(
                    startClock,
                    course.classroom.trim().takeIf { it.isNotEmpty() },
                ).joinToString(" ")
                NextClassTileModel(
                    state = Tile.STATE_ACTIVE,
                    label = course.name.ifBlank { "即将上课" },
                    subtitle = subtitle,
                )
            }
            is TodayCourseState.Finished,
            TodayCourseState.NoClass,
            -> {
                if (tomorrowFirst != null) {
                    val startClock = tomorrowFirst.startMinute?.let(::formatClock)
                    val subtitle = listOfNotNull(
                        startClock,
                        tomorrowFirst.course.classroom.trim().takeIf { it.isNotEmpty() },
                    ).joinToString(" ")
                    NextClassTileModel(
                        state = Tile.STATE_INACTIVE,
                        label = "明日 ${tomorrowFirst.course.name}".trim(),
                        subtitle = subtitle,
                    )
                } else {
                    NextClassTileModel(
                        state = Tile.STATE_INACTIVE,
                        label = "今日无课",
                        subtitle = if (todayState is TodayCourseState.Finished) "今日已结课" else "明日无课",
                    )
                }
            }
        }
    }

    internal fun applyToTile(tile: Tile, model: NextClassTileModel) {
        tile.state = model.state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = model.label
            tile.subtitle = model.subtitle
        } else {
            tile.label = listOf(model.label, model.subtitle)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
        tile.updateTile()
    }

    private fun formatClock(totalMinutes: Int): String {
        val safe = totalMinutes.coerceIn(0, 23 * 60 + 59)
        return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
    }
}
