package com.gfs.iyango.data.repository

import androidx.room.Room
import com.gfs.iyango.core.domain.SchoolSyncCommitResult
import com.gfs.iyango.core.domain.SchoolSyncCommitSummary
import com.gfs.iyango.core.domain.SchoolSyncPrepareResult
import com.gfs.iyango.core.domain.SchoolSyncPreview
import com.gfs.iyango.core.domain.SchoolTimetablePort
import com.gfs.iyango.core.domain.SchoolTimetableSyncPort
import com.gfs.iyango.core.domain.SessionExpiredException
import com.gfs.iyango.core.model.SchoolCampusOption
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.SchoolTermOption
import com.gfs.iyango.core.model.SchoolTimetableResult
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SchoolConnectionEntity
import com.gfs.iyango.data.db.TimetableEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [SchoolTimetableRefreshHandler] 学期选择测试：
 * 已绑定学期只刷新该学期；首次登录无绑定时取学校当前学期并自动新建课表。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchoolTimetableRefreshHandlerTest {

    private lateinit var db: IYangoDatabase
    private lateinit var timetablePort: FakeTimetablePort
    private lateinit var sync: RecordingSync
    private lateinit var handler: SchoolTimetableRefreshHandler

    private val options = listOf(
        SchoolTermOption("2026-2027-1", "当前学期"),
        SchoolTermOption("2025-2026-2", "上一学期"),
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, IYangoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timetablePort = FakeTimetablePort(options)
        sync = RecordingSync()
        handler = SchoolTimetableRefreshHandler(db, timetablePort, sync)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `无绑定学期时取学校当前学期并自动同步`() = runTest {
        seedConnection()

        handler.refresh("")

        assertEquals(listOf("ygu-a"), timetablePort.listed)
        assertEquals(listOf("ygu-a" to "2026-2027-1"), sync.quickSynced)
    }

    @Test
    fun `已有绑定学期时只刷新绑定学期`() = runTest {
        seedConnection()
        db.timetableDao().upsert(
            TimetableEntity(
                name = "阳光学院 2025-2026-2",
                source = "YGU",
                schoolTerm = "2025-2026-2",
                connectionId = "ygu-a",
                isCurrent = true,
                lastSyncAt = "2026-03-01T00:00:00",
                groupName = "",
                autoEnabled = false,
                enabledDays = listOf(1, 2, 3, 4, 5, 6, 7),
                cycleLength = 1,
                cycleWeek = 0,
            ),
        )

        handler.refresh("")

        assertTrue(timetablePort.listed.isEmpty())
        assertEquals(listOf("ygu-a" to "2025-2026-2"), sync.quickSynced)
    }

    @Test
    fun `未登录时不读取学期也不同步`() = runTest {
        val failure = runCatching { handler.refresh("") }.exceptionOrNull()

        assertTrue(failure is SessionExpiredException)
        assertTrue(timetablePort.listed.isEmpty())
        assertTrue(sync.quickSynced.isEmpty())
    }

    @Test fun `本地修改阻止同步时不能报告刷新成功`() = runTest {
        seedConnection()
        sync.result = SchoolSyncCommitResult.Conflict("课表已在本地修改，请重新同步")
        val failure = runCatching { handler.refresh("") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("课表已在本地修改，请重新同步", failure?.message)
    }

    private suspend fun seedConnection() {
        db.schoolConnectionDao().upsert(
            SchoolConnectionEntity(
                id = "ygu-a",
                schoolId = "YGU",
                accountSubject = "2024001",
                maskedAccount = "202****001",
                sessionRevision = 1,
                status = SchoolConnectionStatus.ACTIVE,
                credentialRef = "ygu-a",
                rememberPassword = false,
                createdAt = 0,
                lastLoginAt = 0,
                lastError = "",
                displayName = "",
                studentNo = "",
                college = "",
                major = "",
                className = "",
                gender = "",
                avatarUrl = "",
            ),
        )
    }
}

private class FakeTimetablePort(
    private val options: List<SchoolTermOption>,
) : SchoolTimetablePort {

    val listed = mutableListOf<String>()

    override suspend fun listTerms(connectionId: String): Result<List<SchoolTermOption>> {
        listed += connectionId
        return Result.success(options)
    }

    override suspend fun listCampuses(connectionId: String): Result<List<SchoolCampusOption>> =
        Result.success(emptyList())

    override suspend fun fetchTimetable(
        connectionId: String,
        termValue: String,
        campusCode: String,
    ): SchoolTimetableResult = error("不应读取整表")
}

private class RecordingSync : SchoolTimetableSyncPort {

    val quickSynced = mutableListOf<Pair<String, String>>()
    var result: SchoolSyncCommitResult = SchoolSyncCommitResult.Success(SchoolSyncCommitSummary())

    override suspend fun quickSync(connectionId: String, termValue: String): SchoolSyncCommitResult {
        quickSynced += connectionId to termValue
        return result
    }

    override suspend fun prepare(
        connectionId: String,
        termValue: String,
    ): SchoolSyncPrepareResult = error("不应生成预览")

    override suspend fun commit(
        preview: SchoolSyncPreview,
        startDate: String,
        totalWeeks: Int,
        selectedRowKeys: Set<String>?,
    ): SchoolSyncCommitResult = error("不应提交预览")
}
