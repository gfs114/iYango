package com.gfs.iyango.data.repository

import androidx.room.Room
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SchoolConnectionEntity
import com.gfs.iyango.data.school.ygu.YguGradesClient
import com.gfs.iyango.data.school.ygu.YguGradesClientStatus
import com.gfs.iyango.data.school.ygu.YguHttpResponse
import java.net.URLDecoder
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YguGradeSessionTest {
    private lateinit var db: IYangoDatabase
    private lateinit var query: YguGradeQueryRepository
    private var credential = SchoolCredential(account = "student", password = "", cookieHeader = "bzb_jsxsd=session-0")
    private var requests = 0
    private var allSupported = true
    private var omitDirectTerms = false
    private var responseStatus = 200
    private val submitted = mutableListOf<String>()
    private val store = object : SchoolCredentialStore {
        override suspend fun read(credentialRef: String) = credential
        override suspend fun write(credentialRef: String, credential: SchoolCredential) { this@YguGradeSessionTest.credential = credential }
        override suspend fun clear(credentialRef: String) { error("Unexpected credential removal") }
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), IYangoDatabase::class.java)
            .allowMainThreadQueries().build()
        val refresh = object : RefreshCoordinatorPort {
            override val state = MutableStateFlow(RefreshState())
            override suspend fun refresh(resource: RefreshResource, queryFingerprint: String) = error("Unexpected refresh")
            override fun onForeground() {}
            override fun onSessionChanged() {}
            override fun onLocalDataChanged(resource: RefreshResource) {}
        }
        val auth = RoomSchoolAuthRepository(db, store, RoomGradeRepository(db, Clock.systemUTC()), refresh, Clock.systemUTC())
        query = YguGradeQueryRepository(db, store, auth).apply { createClient = ::client }
    }
    @After fun cleanup() { db.close() }

    private fun client() = YguGradesClient(
        get = { _, cookie, _, _ -> response(cookie, form()) },
        post = { _, body, cookie, _, _ ->
            val term = URLDecoder.decode(body.substringAfter("kksj=").substringBefore('&'), "UTF-8")
            submitted += term
            val terms = if (term.isEmpty()) listOf("2026-1", "2026-2") else listOf(term)
            response(cookie, "<table><tr><th>学期</th><th>课程名称</th><th>成绩</th><th>学分</th></tr>" +
                terms.joinToString("") { "<tr><td>${if (term.isEmpty() && omitDirectTerms) "" else it}</td><td>课程$it</td><td>90</td><td>2</td></tr>" } + "</table>")
        },
    )
    private fun response(cookie: String, body: String): YguHttpResponse {
        assertEquals("Every request must carry the session returned by the preceding response", "bzb_jsxsd=session-$requests", cookie)
        requests++
        return YguHttpResponse(responseStatus, body, listOf("bzb_jsxsd=session-$requests; Path=/"), "")
    }
    private fun form() = "<form method='post' action='/jsxsd/kscj/cjcx_list'><select name='kksj'>" +
        (if (allSupported) "<option value=''>全部学期</option>" else "") +
        "<option value='2026-1'>2026-1</option><option value='2026-2'>2026-2</option></select></form>"
    private suspend fun seed() {
        db.schoolConnectionDao().upsert(SchoolConnectionEntity(
            id = "a", schoolId = "YGU", accountSubject = "student", maskedAccount = "student",
            sessionRevision = 1, status = SchoolConnectionStatus.ACTIVE, credentialRef = "a",
            rememberPassword = false, createdAt = 0, lastLoginAt = 0, lastError = "",
            displayName = "", studentNo = "", college = "", major = "", className = "", gender = "", avatarUrl = "",
        ))
    }

    @Test fun `all semester refresh preserves the returned session and keeps the account active`() = runTest {
        seed()
        val result = query.query("a", "")
        assertTrue("Valid responses must not become SessionExpired: $result", result is GradeQueryResult.Success)
        assertEquals(2, (result as GradeQueryResult.Success).records.size)
        assertEquals(listOf(""), submitted)
        assertEquals(SchoolConnectionStatus.ACTIVE, db.schoolConnectionDao().getById("a")!!.status)
        assertEquals("bzb_jsxsd=session-3", credential.cookieHeader)
    }
    @Test fun `per semester fallback carries renewed cookies across every semester`() = runTest {
        seed(); allSupported = false
        val result = query.query("a", "")
        assertTrue(result is GradeQueryResult.Success)
        assertEquals(listOf("2026-1", "2026-2"), submitted)
        assertEquals(2, (result as GradeQueryResult.Success).records.size)
        assertEquals("bzb_jsxsd=session-5", credential.cookieHeader)
    }
    @Test fun `metadata and single semester queries persist renewed sessions`() = runTest {
        seed()
        assertTrue(query.listTerms("a").isSuccess)
        assertEquals("bzb_jsxsd=session-1", credential.cookieHeader)
        assertTrue(query.query("a", "2026-1") is GradeQueryResult.Success)
        assertEquals("bzb_jsxsd=session-3", credential.cookieHeader)
    }
    @Test fun `fallback after an unlabelled all term response also retains the latest session`() = runTest {
        seed(); omitDirectTerms = true
        val result = query.query("a", "")
        assertTrue(result is GradeQueryResult.Success)
        assertEquals(2, (result as GradeQueryResult.Success).records.size)
        assertEquals(listOf("", "2026-1", "2026-2"), submitted)
        assertEquals("bzb_jsxsd=session-7", credential.cookieHeader)
    }
    @Test fun `a real unauthorized response still expires the account and clears the cookie`() = runTest {
        seed(); responseStatus = 401
        assertTrue(query.query("a", "") is GradeQueryResult.SessionExpired)
        assertEquals(SchoolConnectionStatus.EXPIRED, db.schoolConnectionDao().getById("a")!!.status)
        assertEquals("", credential.cookieHeader)
    }
    @Test fun `access restriction does not expire a valid account`() = runTest {
        seed(); responseStatus = 403
        assertTrue(query.query("a", "") is GradeQueryResult.Failed)
        assertEquals(SchoolConnectionStatus.ACTIVE, db.schoolConnectionDao().getById("a")!!.status)
        assertTrue(credential.cookieHeader.isNotBlank())
    }
    @Test fun `client clears temporary secrets but exposes the next session in its result`() = runTest {
        val client = client()
        val result = client.fetchOptions(credential.cookieHeader)
        assertEquals(YguGradesClientStatus.OK, result.status)
        assertEquals("bzb_jsxsd=session-1", result.cookieHeader)
        assertEquals("", client.cookieHeader())
    }
}
