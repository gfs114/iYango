package com.gfs.iyango.data.repository

import androidx.room.Room
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.RefreshState
import com.gfs.iyango.core.domain.RefreshTaskState
import com.gfs.iyango.core.domain.SchoolCredential
import com.gfs.iyango.core.domain.SchoolCredentialStore
import com.gfs.iyango.core.domain.SessionExpiredException
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSnapshot
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.SchoolLoginResult
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SchoolConnectionEntity
import com.gfs.iyango.data.school.ygu.YguLoginOutcome
import com.gfs.iyango.data.school.ygu.YguProfileRead
import com.gfs.iyango.data.school.ygu.YguSessionState
import com.gfs.iyango.data.school.ygu.YguStudentProfile
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
 * [RoomSchoolAuthRepository] 账号状态不变式测试：
 * 登录与恢复会话激活目标账号时，其余账号必须同事务下线，任何时刻至多一个 ACTIVE。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSchoolAuthRepositoryTest {

    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val sevenDaysAgo = clock.millis() - Duration.ofDays(7).toMillis()
    private val savedCredential = SchoolCredential("test-account", "test-password", "stale-cookie")
    private lateinit var db: IYangoDatabase
    private lateinit var credentialStore: FakeCredentialStore
    private lateinit var repository: RoomSchoolAuthRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, IYangoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        credentialStore = FakeCredentialStore()
        repository = RoomSchoolAuthRepository(
            db = db,
            credentialStore = credentialStore,
            gradeRepository = NoopGradeRepository(),
            refreshCoordinator = NoopRefreshCoordinator(),
            clock = clock,
        )
        repository.directLogin = { _, _ -> error("Unexpected login request") }
        repository.verifySession = { error("Unexpected session verification") }
        repository.fetchProfileRead = { error("Unexpected profile request") }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `恢复会话激活时同事务下线其余账号`() = runTest {
        seedConnection("ygu-a", SchoolConnectionStatus.EXPIRED)
        seedConnection("ygu-b", SchoolConnectionStatus.ACTIVE)
        seedConnection("ygu-c", SchoolConnectionStatus.EXPIRED)
        credentialStore.store["ygu-a"] =
            SchoolCredential(account = "a", password = "", cookieHeader = "cookie-a")
        repository.verifySession = { YguSessionState.Active("cookie-a-new") }
        repository.fetchProfileRead = { cookie ->
            assertEquals("cookie-a-new", cookie)
            YguProfileRead(
                profile = YguStudentProfile(name = "张三", studentId = "2024001"),
                authenticated = true,
                cookieHeader = "profile-cookie",
            )
        }

        val result = repository.restoreSession("ygu-a")

        assertTrue(result is SchoolLoginResult.Success)
        assertEquals("profile-cookie", credentialStore.read("ygu-a")?.cookieHeader)
        val rows = db.schoolConnectionDao().observeAll().first()
        assertEquals(
            listOf("ygu-a"),
            rows.filter { it.status == SchoolConnectionStatus.ACTIVE }.map { it.id },
        )
        assertEquals(SchoolConnectionStatus.LOGGED_OUT, rows.first { it.id == "ygu-b" }.status)
        assertEquals(SchoolConnectionStatus.LOGGED_OUT, rows.first { it.id == "ygu-c" }.status)
    }

    @Test
    fun `登录成功时同事务下线其余账号`() = runTest {
        seedConnection("ygu-a", SchoolConnectionStatus.ACTIVE)
        repository.directLogin = { _, _ -> YguLoginOutcome.Success("cookie-new") }
        repository.fetchProfileRead = { cookie ->
            assertEquals("cookie-new", cookie)
            YguProfileRead(
                profile = YguStudentProfile(name = "李四", studentId = "2024002"),
                authenticated = true,
                cookieHeader = "profile-cookie",
            )
        }

        val result = repository.login(account = "account-a", password = "pw", rememberPassword = false)

        assertTrue(result is SchoolLoginResult.Success)
        val rows = db.schoolConnectionDao().observeAll().first()
        val active = rows.filter { it.status == SchoolConnectionStatus.ACTIVE }
        assertEquals(1, active.size)
        assertEquals("profile-cookie", credentialStore.read(active.single().id)?.cookieHeader)
        // 新登录账号的 id 由账号哈希派生，不与旧账号相同。
        assertTrue(active.single().id != "ygu-a")
        assertEquals(SchoolConnectionStatus.LOGGED_OUT, rows.first { it.id == "ygu-a" }.status)
    }

    @Test
    fun `新账号登录后立即读到会话失效时保留当前账号且不保存新账号`() = runTest {
        seedRememberedConnection()
        repository.directLogin = { _, _ -> YguLoginOutcome.Success("login-cookie") }
        repository.fetchProfileRead = { cookie ->
            assertEquals("login-cookie", cookie)
            YguProfileRead(expired = true, cookieHeader = "expired-profile-cookie")
        }

        val result = repository.login("other-account", "other-password", rememberPassword = true)

        assertTrue(result is SchoolLoginResult.Failed)
        assertEquals(listOf("ygu-a"), activeConnectionIds())
        assertEquals(listOf("ygu-a"), repository.observeConnections().first().map { it.id })
        assertEquals(mapOf("ygu-a" to savedCredential), credentialStore.store)
    }

    @Test
    fun `恢复历史账号后立即读到会话失效时保留当前账号且不保存失效Cookie`() = runTest {
        seedRememberedConnection()
        seedConnection("ygu-b", SchoolConnectionStatus.LOGGED_OUT, rememberPassword = true)
        val historical = SchoolCredential("other-account", "other-password", "history-cookie")
        credentialStore.write("ygu-b", historical)
        repository.verifySession = { cookie ->
            assertEquals(historical.cookieHeader, cookie)
            YguSessionState.Active("verified-cookie")
        }
        repository.fetchProfileRead = { cookie ->
            assertEquals("verified-cookie", cookie)
            YguProfileRead(expired = true, cookieHeader = "expired-profile-cookie")
        }

        val result = repository.restoreSession("ygu-b")

        assertTrue(result is SchoolLoginResult.Failed)
        assertEquals(listOf("ygu-a"), activeConnectionIds())
        assertEquals(savedCredential, credentialStore.read("ygu-a"))
        val retained = credentialStore.read("ygu-b")!!
        assertTrue(retained.cookieHeader.isEmpty() || retained.cookieHeader == historical.cookieHeader)
        assertEquals(historical.copy(cookieHeader = retained.cookieHeader), retained)
        assertEquals(0L, repository.getConnection("ygu-b")!!.lastLoginAt)
    }

    @Test
    fun `历史账号回填的是登录账号而不是学号`() = runTest {
        seedConnection("ygu-a", SchoolConnectionStatus.LOGGED_OUT)
        credentialStore.store["ygu-a"] = SchoolCredential(account = "login-user", cookieHeader = "")

        assertEquals("login-user", repository.historyAccount("ygu-a"))
    }

    @Test
    fun `凭据已清除时历史账号回退到学号`() = runTest {
        seedConnection("ygu-a", SchoolConnectionStatus.LOGGED_OUT)

        assertEquals("ygu-a", repository.historyAccount("ygu-a"))
        assertEquals(null, repository.historyAccount("missing"))
    }

    @Test
    fun `距主动登录恰好7天仍可续期并继续读取资料且不会延长窗口`() = runTest {
        seedRememberedConnection(lastLoginAt = sevenDaysAgo)
        var loginCount = 0
        val requestedCookies = mutableListOf<String>()
        repository.directLogin = { account, password ->
            assertEquals(savedCredential.account, account)
            assertEquals(savedCredential.password, password)
            assertEquals(1, ++loginCount)
            YguLoginOutcome.Success("fresh-cookie")
        }
        repository.fetchProfileRead = { cookie ->
            requestedCookies += cookie
            if (cookie == savedCredential.cookieHeader) {
                YguProfileRead(expired = true)
            } else {
                YguProfileRead(
                    authenticated = true,
                    profile = YguStudentProfile(name = "测试学生", studentId = "test-student"),
                    cookieHeader = cookie,
                )
            }
        }

        val profileResult = repository.fetchProfile("ygu-a")

        assertEquals("测试学生", profileResult.getOrThrow().displayName)
        assertEquals(1, loginCount)
        assertEquals(listOf("stale-cookie", "fresh-cookie"), requestedCookies)
        val updated = db.schoolConnectionDao().getById("ygu-a")!!
        assertEquals(SchoolConnectionStatus.ACTIVE, updated.status)
        assertEquals(sevenDaysAgo, updated.lastLoginAt)
        assertEquals("fresh-cookie", credentialStore.store["ygu-a"]?.cookieHeader)
    }

    @Test
    fun `超过7天一毫秒且Cookie已失效时直接提示手动登录`() = runTest {
        seedRememberedConnection(lastLoginAt = sevenDaysAgo - 1)
        repository.fetchProfileRead = { YguProfileRead(expired = true) }

        assertProfileExpired()

        assertEquals(SchoolConnectionStatus.EXPIRED, db.schoolConnectionDao().getById("ygu-a")!!.status)
    }

    @Test
    fun `未勾选记住密码时即使凭据仍有密码也不自动登录`() = runTest {
        seedConnection("ygu-a", SchoolConnectionStatus.ACTIVE, rememberPassword = false, lastLoginAt = clock.millis())
        credentialStore.store["ygu-a"] = savedCredential
        repository.fetchProfileRead = { YguProfileRead(expired = true) }

        assertProfileExpired()

        assertEquals(SchoolConnectionStatus.EXPIRED, db.schoolConnectionDao().getById("ygu-a")!!.status)
    }

    @Test
    fun `缺少凭据或凭据中缺少账号密码时不发起登录`() = runTest {
        seedRememberedConnection()
        credentialStore.clear("ygu-a")
        assertProfileExpired()
        assertEquals(SchoolConnectionStatus.EXPIRED, db.schoolConnectionDao().getById("ygu-a")!!.status)

        for (incomplete in listOf(savedCredential.copy(account = " "), savedCredential.copy(password = ""))) {
            seedRememberedConnection(credential = incomplete.copy(cookieHeader = ""))
            assertProfileExpired()
        }
    }

    @Test
    fun `退出时保留密码随后多次读取资料都不会重新激活旧账号`() = runTest {
        seedRememberedConnection()
        repository.logout("ygu-a", clearSavedPassword = false)
        seedConnection("ygu-current", SchoolConnectionStatus.ACTIVE)

        repeat(2) {
            assertProfileExpired()
            assertEquals(SchoolConnectionStatus.LOGGED_OUT, db.schoolConnectionDao().getById("ygu-a")!!.status)
            assertEquals(SchoolConnectionStatus.ACTIVE, db.schoolConnectionDao().getById("ygu-current")!!.status)
            assertEquals(savedCredential.copy(cookieHeader = ""), credentialStore.read("ygu-a"))
        }
    }

    @Test
    fun `静默登录响应晚于退出时不会恢复已清除的密码或会话`() = runTest {
        seedRememberedConnection(credential = savedCredential.copy(cookieHeader = ""))
        val login = PendingResponse<YguLoginOutcome>()
        repository.directLogin = { _, _ -> login.awaitResponse() }

        val profileRequest = async { repository.fetchProfile("ygu-a") }
        login.started.await()
        repository.logout("ygu-a", clearSavedPassword = true)
        login.response.complete(YguLoginOutcome.Success("late-cookie"))

        assertTrue(profileRequest.await().isFailure)
        assertEquals(emptyList<String>(), activeConnectionIds())
        val connection = repository.getConnection("ygu-a")!!
        assertEquals(SchoolConnectionStatus.LOGGED_OUT, connection.status)
        assertEquals(false, connection.rememberPassword)
        assertEquals(savedCredential.copy(password = "", cookieHeader = ""), credentialStore.read("ygu-a"))
    }

    @Test
    fun `已删除的账号不会因较晚返回的资料和Cookie重新出现`() = runTest {
        seedRememberedConnection()
        val profile = PendingResponse<YguProfileRead>()
        repository.fetchProfileRead = { profile.awaitResponse() }

        val profileRequest = async { repository.fetchProfile("ygu-a") }
        profile.started.await()
        repository.deleteConnection("ygu-a")
        profile.response.complete(
            YguProfileRead(
                authenticated = true,
                profile = YguStudentProfile(name = "旧账号", studentId = "test-student-a"),
                cookieHeader = "late-cookie",
            ),
        )

        assertTrue(profileRequest.await().isFailure)
        assertEquals(emptyList<String>(), activeConnectionIds())
        assertEquals(null, repository.getConnection("ygu-a"))
        assertEquals(null, credentialStore.read("ygu-a"))
    }

    @Test
    fun `切换到其他账号后旧请求的过期响应不会修改任一账号`() = runTest {
        seedRememberedConnection()
        seedConnection("ygu-b", SchoolConnectionStatus.LOGGED_OUT, rememberPassword = true)
        val otherCredential = SchoolCredential("other-account", "other-password", "other-cookie")
        credentialStore.write("ygu-b", otherCredential)
        val oldProfile = PendingResponse<YguProfileRead>()
        repository.verifySession = { cookie ->
            assertEquals(otherCredential.cookieHeader, cookie)
            YguSessionState.Active("restored-cookie")
        }
        repository.fetchProfileRead = { cookie ->
            when (cookie) {
                savedCredential.cookieHeader -> oldProfile.awaitResponse()
                "restored-cookie" -> YguProfileRead(
                    authenticated = true,
                    profile = YguStudentProfile(name = "当前账号", studentId = "test-student-b"),
                )
                else -> error("Unexpected profile request")
            }
        }

        val profileRequest = async { repository.fetchProfile("ygu-a") }
        oldProfile.started.await()
        assertTrue(repository.restoreSession("ygu-b") is SchoolLoginResult.Success)
        val previousCredential = credentialStore.read("ygu-a")
        oldProfile.response.complete(YguProfileRead(expired = true))

        assertTrue(profileRequest.await().isFailure)
        assertEquals(listOf("ygu-b"), activeConnectionIds())
        assertEquals(SchoolConnectionStatus.LOGGED_OUT, repository.getConnection("ygu-a")!!.status)
        assertEquals(previousCredential, credentialStore.read("ygu-a"))
        assertEquals(otherCredential.copy(cookieHeader = "restored-cookie"), credentialStore.read("ygu-b"))
        assertEquals("test-student-b", repository.getConnection("ygu-b")!!.profile.studentNo)
    }

    @Test
    fun `自动续期被拒绝或网络失败时只尝试一次并结束原请求`() = runTest {
        for (outcome in listOf(YguLoginOutcome.Rejected("凭据无效"), YguLoginOutcome.Failed("连接失败"))) {
            seedRememberedConnection()
            var loginCount = 0
            var profileReadCount = 0
            repository.directLogin = { _, _ ->
                assertEquals(1, ++loginCount)
                outcome
            }
            repository.fetchProfileRead = {
                assertEquals(1, ++profileReadCount)
                YguProfileRead(expired = true)
            }

            assertProfileExpired()

            assertEquals(1, loginCount)
            assertEquals(1, profileReadCount)
            val updated = db.schoolConnectionDao().getById("ygu-a")!!
            assertEquals(SchoolConnectionStatus.EXPIRED, updated.status)
            assertEquals(clock.millis(), updated.lastLoginAt)
            assertEquals("", credentialStore.read("ygu-a")?.cookieHeader)
        }
    }

    @Test
    fun `缺少Cookie时自动续期但新Cookie也失效则不再次登录`() = runTest {
        seedRememberedConnection(credential = savedCredential.copy(cookieHeader = ""))
        var loginCount = 0
        repository.directLogin = { _, _ ->
            assertEquals(1, ++loginCount)
            YguLoginOutcome.Success("fresh-cookie")
        }
        val requestedCookies = mutableListOf<String>()
        repository.fetchProfileRead = { cookie ->
            requestedCookies += cookie
            YguProfileRead(expired = true)
        }

        assertProfileExpired()

        assertEquals(1, loginCount)
        assertEquals(listOf("fresh-cookie"), requestedCookies)
        assertEquals(SchoolConnectionStatus.EXPIRED, db.schoolConnectionDao().getById("ygu-a")!!.status)
    }

    private suspend fun assertProfileExpired() {
        val result = repository.fetchProfile("ygu-a")
        assertTrue(result.exceptionOrNull() is SessionExpiredException)
    }

    private suspend fun activeConnectionIds(): List<String> =
        repository.observeConnections().first().filter { it.status == SchoolConnectionStatus.ACTIVE }.map { it.id }

    private suspend fun seedRememberedConnection(
        lastLoginAt: Long = clock.millis(),
        credential: SchoolCredential = savedCredential,
    ) {
        seedConnection("ygu-a", SchoolConnectionStatus.ACTIVE, rememberPassword = true, lastLoginAt = lastLoginAt)
        credentialStore.store["ygu-a"] = credential
    }

    private suspend fun seedConnection(
        id: String,
        status: SchoolConnectionStatus,
        rememberPassword: Boolean = false,
        lastLoginAt: Long = 0,
    ) {
        db.schoolConnectionDao().upsert(
            SchoolConnectionEntity(
                id = id,
                schoolId = "YGU",
                accountSubject = id,
                maskedAccount = id,
                sessionRevision = 1,
                status = status,
                credentialRef = id,
                rememberPassword = rememberPassword,
                createdAt = 0,
                lastLoginAt = lastLoginAt,
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

private class PendingResponse<T> {
    val started = CompletableDeferred<Unit>()
    val response = CompletableDeferred<T>()

    suspend fun awaitResponse(): T {
        started.complete(Unit)
        return response.await()
    }
}

private class FakeCredentialStore : SchoolCredentialStore {
    val store = mutableMapOf<String, SchoolCredential>()

    override suspend fun read(credentialRef: String): SchoolCredential? = store[credentialRef]

    override suspend fun write(credentialRef: String, credential: SchoolCredential) {
        store[credentialRef] = credential
    }

    override suspend fun clear(credentialRef: String) {
        store.remove(credentialRef)
    }
}

private class NoopGradeRepository : GradeRepositoryPort {
    override fun observeSnapshot(accountKey: String, semester: String): Flow<GradeSnapshot?> = flowOf(null)
    override fun observeRecords(accountKey: String, semester: String): Flow<List<GradeRecord>> = flowOf(emptyList())
    override fun observeSemesters(accountKey: String): Flow<List<String>> = flowOf(emptyList())
    override suspend fun saveCompleteResult(
        accountKey: String,
        semester: String,
        records: List<GradeRecord>,
        queryFingerprint: String,
        parserVersion: Int,
    ) = Unit
    override suspend fun markChangesRead(accountKey: String, semester: String) = Unit
    override fun observeUnreadCount(accountKey: String): Flow<Int> = flowOf(0)
    override suspend fun deleteAccount(accountKey: String) = Unit
}

private class NoopRefreshCoordinator : RefreshCoordinatorPort {
    override val state: StateFlow<RefreshState> = MutableStateFlow(RefreshState())
    override fun onForeground() = Unit
    override fun onSessionChanged() = Unit
    override suspend fun refresh(
        resource: RefreshResource,
        queryFingerprint: String,
    ): RefreshTaskState = RefreshTaskState(resource = resource)
    override fun onLocalDataChanged(resource: RefreshResource) = Unit
}
