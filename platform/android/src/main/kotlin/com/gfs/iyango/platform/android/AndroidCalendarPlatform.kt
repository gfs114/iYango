package com.gfs.iyango.platform.android

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.gfs.iyango.core.domain.CalendarDeleteSummary
import com.gfs.iyango.core.domain.CalendarEventPlan
import com.gfs.iyango.core.domain.CalendarPlatformPort
import com.gfs.iyango.core.domain.CalendarWriteResult
import com.gfs.iyango.core.domain.SystemCalendar
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CalendarProvider 平台实现（F13）。
 *
 * 所有权：事件 DESCRIPTION 末尾写入 `[iYango:<ownerId>]`；更新/删除前先校验该标记，
 * 用户自建同名事件或标记被清除的事件一律不碰。
 */
@Singleton
class AndroidCalendarPlatform @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarPlatformPort {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun calendars(): List<SystemCalendar> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                args,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC, " +
                    "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { cursor ->
                buildList<SystemCalendar> {
                    while (cursor.moveToNext()) {
                        add(
                            SystemCalendar(
                                id = cursor.getLong(0),
                                name = cursor.getString(1).orEmpty(),
                                accountName = cursor.getString(2).orEmpty(),
                                isPrimary = cursor.getInt(3) == 1,
                            ),
                        )
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    override suspend fun upsert(
        plan: CalendarEventPlan,
        existingEventId: Long?,
        calendarId: Long,
        ownerId: String,
    ): CalendarWriteResult = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext CalendarWriteResult.Failure("缺少日历读写权限")
        }
        if (existingEventId != null && !isOwned(existingEventId, ownerId)) {
            return@withContext CalendarWriteResult.NotOwned
        }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, plan.title)
            put(CalendarContract.Events.DESCRIPTION, withMarker(plan.description, ownerId))
            put(CalendarContract.Events.EVENT_LOCATION, plan.location)
            put(CalendarContract.Events.DTSTART, plan.startAtMillis)
            put(CalendarContract.Events.DTEND, plan.endAtMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, plan.timeZone)
            // 提醒由应用通知负责，系统日历事件不重复挂闹钟。
            put(CalendarContract.Events.HAS_ALARM, 0)
        }
        runCatching {
            if (existingEventId != null) {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEventId)
                val updated = context.contentResolver.update(uri, values, null, null)
                if (updated > 0) {
                    CalendarWriteResult.Success(existingEventId)
                } else {
                    CalendarWriteResult.Failure("日历事件更新失败（可能已被删除）")
                }
            } else {
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    ?: return@runCatching CalendarWriteResult.Failure("日历事件写入失败")
                CalendarWriteResult.Success(ContentUris.parseId(uri))
            }
        }.getOrElse { CalendarWriteResult.Failure(it.message ?: "日历写入异常") }
    }

    override suspend fun deleteOwned(eventId: Long, ownerId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!hasPermission() || !isOwned(eventId, ownerId)) return@withContext false
            runCatching {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                context.contentResolver.delete(uri, null, null) > 0
            }.getOrDefault(false)
        }

    override suspend fun deleteAllOwned(ownerId: String): CalendarDeleteSummary =
        withContext(Dispatchers.IO) {
            if (!hasPermission() || ownerId.isBlank()) {
                return@withContext CalendarDeleteSummary()
            }
            val token = marker(ownerId)
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.DESCRIPTION,
            )
            val selection = "(${CalendarContract.Events.DELETED} = 0 OR " +
                "${CalendarContract.Events.DELETED} IS NULL) AND " +
                "${CalendarContract.Events.DESCRIPTION} LIKE ?"
            val args = arrayOf("%$token%")
            val ownedIds = runCatching {
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    projection,
                    selection,
                    args,
                    null,
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val description = cursor.getString(1).orEmpty()
                            if (description.contains(token)) {
                                add(cursor.getLong(0))
                            }
                        }
                    }
                }
            }.getOrNull().orEmpty()
            var deleted = 0
            val failedIds = mutableSetOf<Long>()
            ownedIds.forEach { eventId ->
                val ok = runCatching {
                    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                    context.contentResolver.delete(uri, null, null) > 0
                }.getOrDefault(false)
                if (ok) deleted++ else failedIds += eventId
            }
            CalendarDeleteSummary(deleted = deleted, failedEventIds = failedIds)
        }

    override suspend fun eventStartMillis(eventId: Long, ownerId: String): Long? =
        withContext(Dispatchers.IO) {
            if (!hasPermission() || !isOwned(eventId, ownerId)) return@withContext null
            runCatching {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                context.contentResolver.query(
                    uri,
                    arrayOf(CalendarContract.Events.DTSTART),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
            }.getOrNull()
        }

    private fun isOwned(eventId: Long, ownerId: String): Boolean {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Events.DESCRIPTION),
                null,
                null,
                null,
            )?.use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).orEmpty().contains(marker(ownerId))
            } ?: false
        }.getOrDefault(false)
    }

    private fun withMarker(description: String, ownerId: String): String {
        val cleaned = description.replace(Regex("\\n*\\[iYango:[^\\]]+]\$"), "")
        return "$cleaned\n\n${marker(ownerId)}"
    }

    private fun marker(ownerId: String) = "[iYango:$ownerId]"
}
