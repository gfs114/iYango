package com.gfs.iyango.platform.android

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import com.gfs.iyango.core.domain.CalendarDeleteSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidCalendarPlatformTest {

    private lateinit var app: Application
    private lateinit var platform: AndroidCalendarPlatform
    private lateinit var provider: FakeCalendarContentProvider

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
        provider = FakeCalendarContentProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        platform = AndroidCalendarPlatform(app)
    }

    @Test
    fun `deleteAllOwned deletes only non-deleted events matching ownerId marker across past and future`() =
        runBlocking {
            provider.rows[1L] = FakeRow(1L, "老师 张\n\n[iYango:owner-a]", dtStart = 1_000L, deleted = 0)
            provider.rows[2L] = FakeRow(2L, "考试\n\n[iYango:owner-a]", dtStart = 9_999_999_999L, deleted = 0)
            provider.rows[3L] = FakeRow(3L, "老师\n\n[iYango:owner-b]", dtStart = 5_000L, deleted = 0)
            provider.rows[4L] = FakeRow(4L, "用户自建日程", dtStart = 6_000L, deleted = 0)
            provider.rows[5L] = FakeRow(5L, "已删\n\n[iYango:owner-a]", dtStart = 7_000L, deleted = 1)

            val summary = platform.deleteAllOwned("owner-a")

            assertEquals(CalendarDeleteSummary(deleted = 2, failedEventIds = emptySet()), summary)
            assertEquals(setOf(3L, 4L, 5L), provider.rows.keys)
        }

    @Test
    fun `deleteAllOwned records failed event ids without aborting remaining deletions`() = runBlocking {
        provider.rows[1L] = FakeRow(1L, "课1\n\n[iYango:owner-a]", dtStart = 1_000L, deleted = 0)
        provider.rows[2L] = FakeRow(2L, "课2\n\n[iYango:owner-a]", dtStart = 2_000L, deleted = 0)
        provider.failingDeleteIds += 1L

        val summary = platform.deleteAllOwned("owner-a")

        assertEquals(CalendarDeleteSummary(deleted = 1, failedEventIds = setOf(1L)), summary)
        assertEquals(setOf(1L), provider.rows.keys)
    }

    @Test
    fun `deleteAllOwned returns empty summary when permission denied or ownerId blank`() = runBlocking {
        provider.rows[1L] = FakeRow(1L, "课1\n\n[iYango:owner-a]", dtStart = 1_000L, deleted = 0)

        assertEquals(CalendarDeleteSummary(), platform.deleteAllOwned(""))
        assertEquals(setOf(1L), provider.rows.keys)

        Shadows.shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        assertEquals(CalendarDeleteSummary(), platform.deleteAllOwned("owner-a"))
        assertEquals(setOf(1L), provider.rows.keys)
    }

    private data class FakeRow(
        val id: Long,
        val description: String,
        val dtStart: Long,
        val deleted: Int,
    )

    private class FakeCalendarContentProvider : ContentProvider() {
        val rows = linkedMapOf<Long, FakeRow>()
        val failingDeleteIds = mutableSetOf<Long>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = projection ?: arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.DESCRIPTION,
            )
            val cursor = MatrixCursor(cols)
            val likeNeedle = selectionArgs?.firstOrNull()?.trim('%').orEmpty()
            val filterDeleted = selection?.contains(CalendarContract.Events.DELETED) == true
            val filtered = rows.values.filter { row ->
                (!filterDeleted || row.deleted == 0) &&
                    (likeNeedle.isEmpty() || row.description.contains(likeNeedle))
            }
            filtered.forEach { row ->
                cursor.addRow(
                    cols.map { col ->
                        when (col) {
                            CalendarContract.Events._ID -> row.id
                            CalendarContract.Events.DESCRIPTION -> row.description
                            CalendarContract.Events.DTSTART -> row.dtStart
                            else -> null
                        }
                    },
                )
            }
            return cursor
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return 0
            if (id in failingDeleteIds) return 0
            return if (rows.remove(id) != null) 1 else 0
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
