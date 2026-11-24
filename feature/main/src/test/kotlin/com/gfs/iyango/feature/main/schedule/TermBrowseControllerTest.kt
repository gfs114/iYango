package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TermBrowseControllerTest {
    private class Cache : TermBrowseCachePort {
        val terms = mutableMapOf<String,List<SchoolTermOption>>()
        val tables = mutableMapOf<Pair<String,String>,SchoolTimetableData>()
        override suspend fun readTerms(accountKey:String) = terms[accountKey].orEmpty()
        override suspend fun writeTerms(accountKey:String,terms:List<SchoolTermOption>) { this.terms[accountKey]=terms }
        override suspend fun readTimetable(accountKey:String,term:String) = tables[accountKey to term]
        override suspend fun writeTimetable(accountKey:String,term:String,data:SchoolTimetableData) { tables[accountKey to term]=data }
    }
    private class School : SchoolTimetablePort {
        var terms: Result<List<SchoolTermOption>> = Result.success(listOf(SchoolTermOption("new","new"),SchoolTermOption("old","old")))
        var fetch: suspend (String,String)->SchoolTimetableResult = { _,term -> SchoolTimetableResult.Success(SchoolTimetableData(termValue=term)) }
        override suspend fun listTerms(connectionId:String)=terms
        override suspend fun listCampuses(connectionId:String) = Result.success(emptyList<SchoolCampusOption>())
        override suspend fun fetchTimetable(connectionId:String,termValue:String,campusCode:String)=fetch(connectionId,termValue)
    }
    @Test fun `only school options selectable and cached data survives refresh failure`() = runTest {
        val school=School(); val cache=Cache(); val c=TermBrowseController(school,cache,backgroundScope, StandardTestDispatcher(testScheduler))
        c.accountChanged(SchoolConnection(id="a",accountSubject="one")); runCurrent()
        c.select("invented"); runCurrent(); assertNull(c.state.value.selected)
        c.select("old"); runCurrent(); assertNotNull(c.state.value.data)
        school.fetch={_,_->SchoolTimetableResult.Failed("offline")}
        c.refresh(); runCurrent(); assertNotNull(c.state.value.data); assertEquals("offline",c.state.value.error)
        c.returnToCurrent(); assertNull(c.state.value.selected)
    }
    @Test fun `stalled historical refresh stops loading at its deadline and preserves cached data`() = runTest {
        val school = School()
        val c = TermBrowseController(school, Cache(), backgroundScope, StandardTestDispatcher(testScheduler))
        c.accountChanged(SchoolConnection(id = "a", accountSubject = "one")); runCurrent()
        c.select("old"); runCurrent()
        val cached = c.state.value.data
        school.fetch = { _, _ -> awaitCancellation() }
        c.refresh(); runCurrent()
        advanceTimeBy(30_001); runCurrent()
        assertFalse(c.state.value.loading)
        assertTrue(c.state.value.error.orEmpty().contains("超时"))
        assertEquals(cached, c.state.value.data)
    }

    @Test fun `historical manual refresh reports success and return to current clears the notice`() = runTest {
        val school = School()
        val c = TermBrowseController(school, Cache(), backgroundScope, StandardTestDispatcher(testScheduler))
        c.accountChanged(SchoolConnection(id = "a", accountSubject = "one")); runCurrent()
        c.select("old"); runCurrent()
        assertNull(c.state.value.refreshNotice)
        c.refresh(); runCurrent()
        assertEquals("历史课表刷新成功", c.state.value.refreshNotice)
        c.returnToCurrent()
        assertNull(c.state.value.refreshNotice)
    }

    @Test fun `late term and account results cannot replace new selection`() = runTest {
        val school=School(); val c=TermBrowseController(school,Cache(),backgroundScope, StandardTestDispatcher(testScheduler))
        val delayed=CompletableDeferred<SchoolTimetableResult>()
        school.fetch={_,term-> if(term=="old") withContext(NonCancellable){delayed.await()} else SchoolTimetableResult.Success(SchoolTimetableData(termValue=term)) }
        c.accountChanged(SchoolConnection(id="a",accountSubject="one")); runCurrent()
        c.select("old"); runCurrent(); c.select("new"); runCurrent()
        delayed.complete(SchoolTimetableResult.Success(SchoolTimetableData(termValue="old"))); runCurrent()
        assertEquals("new",c.state.value.data?.termValue)
        c.pickWeek(4); c.select("old"); runCurrent(); c.select("new"); runCurrent(); assertEquals(4,c.state.value.week)
        c.accountChanged(SchoolConnection(id="a",accountSubject="two")); runCurrent(); assertNull(c.state.value.data)
    }
    @Test fun `cached school list supports offline selection after recreation`() = runTest {
        val school=School(); val cache=Cache(); val account=SchoolConnection(id="a",accountSubject="one")
        val c=TermBrowseController(school,cache,backgroundScope, StandardTestDispatcher(testScheduler)); c.accountChanged(account); runCurrent(); c.select("old"); runCurrent()
        school.terms=Result.failure(IllegalStateException("offline")); school.fetch={_,_->SchoolTimetableResult.Failed("offline")}
        val restored=TermBrowseController(school,cache,backgroundScope, StandardTestDispatcher(testScheduler)); restored.accountChanged(account); runCurrent(); restored.select("old"); runCurrent()
        assertEquals("old",restored.state.value.data?.termValue)
        assertTrue(restored.state.value.data!!.courses.isEmpty())
    }
    @Test fun `expired same account keeps cached term and fences late fetch`() = runTest {
        val school=School(); val c=TermBrowseController(school,Cache(),backgroundScope, StandardTestDispatcher(testScheduler))
        val account=SchoolConnection(id="a",accountSubject="one",status=SchoolConnectionStatus.ACTIVE)
        c.accountChanged(account); runCurrent(); c.select("old"); runCurrent(); c.pickWeek(3)
        val late=CompletableDeferred<SchoolTimetableResult>()
        school.fetch={_,_->withContext(NonCancellable){late.await()}}
        c.refresh(); runCurrent(); c.accountChanged(account.copy(status=SchoolConnectionStatus.EXPIRED)); runCurrent()
        late.complete(SchoolTimetableResult.Success(SchoolTimetableData(termValue="old",termLabel="late"))); runCurrent()
        assertEquals("old",c.state.value.selected); assertEquals(3,c.state.value.week)
        assertEquals("",c.state.value.data?.termLabel); assertTrue(c.state.value.loginRequired)
    }
    @Test fun `latest list request wins even if cancelled request returns`() = runTest {
        val slow=CompletableDeferred<Result<List<SchoolTermOption>>>()
        var count=0
        val school=object:SchoolTimetablePort {
            override suspend fun listTerms(connectionId:String):Result<List<SchoolTermOption>> =
                if(count++==0) withContext(NonCancellable){slow.await()} else Result.success(listOf(SchoolTermOption("new")))
            override suspend fun listCampuses(connectionId:String)=Result.success(emptyList<SchoolCampusOption>())
            override suspend fun fetchTimetable(connectionId:String,termValue:String,campusCode:String)=SchoolTimetableResult.Failed("unused")
        }
        val c=TermBrowseController(school,Cache(),backgroundScope, StandardTestDispatcher(testScheduler))
        c.accountChanged(SchoolConnection(id="a")); runCurrent(); c.loadTerms(); runCurrent()
        slow.complete(Result.success(listOf(SchoolTermOption("old")))); runCurrent()
        assertEquals(listOf("new"),c.state.value.terms.map{it.value})
    }

    @Test fun `school read and parse never occupy the state owner thread`() = runTest {
        val owner = Thread.currentThread()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val readThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val school = object : SchoolTimetablePort {
            override suspend fun listTerms(connectionId: String): Result<List<SchoolTermOption>> {
                readThread.set(Thread.currentThread())
                entered.countDown()
                release.await(2, java.util.concurrent.TimeUnit.SECONDS)
                return Result.success(emptyList())
            }
            override suspend fun listCampuses(connectionId: String) = Result.success(emptyList<SchoolCampusOption>())
            override suspend fun fetchTimetable(connectionId: String, termValue: String, campusCode: String) =
                SchoolTimetableResult.Failed("unused")
        }
        val controller = TermBrowseController(school, Cache(), backgroundScope)
        try {
            controller.accountChanged(SchoolConnection(id = "a"))
            runCurrent()
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertNotSame(owner, readThread.get())
            assertTrue(controller.state.value.listLoading)
        } finally {
            release.countDown()
        }
    }

    @Test fun `school timetable parsing also runs away from the state owner`() = runBlocking {
        val owner = Thread.currentThread()
        val listed = CompletableDeferred<Thread>()
        val fetched = CompletableDeferred<Thread>()
        val school = object : SchoolTimetablePort {
            override suspend fun listTerms(connectionId: String): Result<List<SchoolTermOption>> {
                listed.complete(Thread.currentThread())
                return Result.success(listOf(SchoolTermOption("term")))
            }
            override suspend fun listCampuses(connectionId: String) = Result.success(emptyList<SchoolCampusOption>())
            override suspend fun fetchTimetable(connectionId: String, termValue: String, campusCode: String): SchoolTimetableResult {
                fetched.complete(Thread.currentThread())
                return SchoolTimetableResult.Success(SchoolTimetableData(termValue = termValue))
            }
        }
        // This checks real threads; a virtual timeout must not race the real worker.
        val requestScope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = TermBrowseController(school, Cache(), requestScope)
        try {
            withTimeout(5_000) {
                controller.accountChanged(SchoolConnection(id = "a"))
                assertNotSame(owner, listed.await())
                controller.state.first { !it.listLoading }
                controller.select("term")
                assertNotSame(owner, fetched.await())
            }
        } finally { requestScope.cancel() }
    }

}

