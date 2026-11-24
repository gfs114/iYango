package com.gfs.iyango.data.repository

import com.gfs.iyango.core.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileTermBrowseCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private fun cache() = FileTermBrowseCache(folder.root)

    @Test fun `round trip retains browse fields across cache instances without raw response`() = runTest {
        val data = SchoolTimetableData(termValue = "2026-1", termLabel = "秋季", startDate = "2026-09-01",
            totalWeeks = 22, campusCode = "1", campusName = "校区", complete = false,
            warnings = listOf("时间待确认"), fetchedAt = 123, rawPayload = "<html>sensitive response</html>",
            courses = listOf(SchoolCourseData(sourceKey = "key", name = "数学", teacher = "老师", classroom = "A101",
                dayOfWeek = 4, startSection = 3, endSection = 4, startWeek = 2, endWeek = 19,
                weekType = WeekType.ODD, selectedWeeks = listOf(3, 7), campusCode = "1", campusName = "校区",
                rawSections = "三四", rawSectionCode = "0304", note = "备注", groupKey = "g",
                teacherConfirmed = false, timeUnconfirmed = true, startTime = "10:00", endTime = "11:40")),
            timeSlots = listOf(TimeSlot(id = 4, timetableId = 9, section = 3, startTime = "10:00", endTime = "10:45",
                name = "第三节", kind = 2, hidden = true, defaultCourseName = "休息", sortOrder = 3.5)))
        cache().writeTimetable("school/account", data.termValue, data)
        assertEquals(data.copy(rawPayload = ""), cache().readTimetable("school/account", data.termValue))
        assertFalse(folder.root.walkTopDown().filter { it.isFile }.any { it.readText().contains("sensitive response") })
    }

    @Test fun `accounts and terms stay isolated and empty timetable is a hit`() = runTest {
        val cache = cache()
        val empty = SchoolTimetableData(termValue = "a")
        cache.writeTimetable("../account-a", "a", empty)
        cache.writeTimetable("account-b", "a", empty.copy(termLabel = "B"))
        cache.writeTimetable("../account-a", "b", empty.copy(termValue = "b"))
        assertEquals(empty, cache.readTimetable("../account-a", "a"))
        assertEquals("B", cache.readTimetable("account-b", "a")?.termLabel)
        assertEquals("b", cache.readTimetable("../account-a", "b")?.termValue)
        assertNull(cache.readTimetable("account-c", "a"))
        assertNull(cache.readTimetable("../account-a", "c"))
        val terms = listOf(SchoolTermOption("a", "秋季"), SchoolTermOption("b", "春季"))
        cache.writeTerms("../account-a", terms)
        assertEquals(terms, cache().readTerms("../account-a"))
        assertTrue(cache.readTerms("account-b").isEmpty())
        cache.writeTerms("../account-a", emptyList())
        assertEquals(emptyList<SchoolTermOption>(), cache.readTerms("../account-a"))
    }

    @Test fun `concurrent instances publish complete snapshots`() = runTest {
        (1..24).map { index -> async {
            cache().writeTimetable("account", "term", SchoolTimetableData(termValue = "term",
                termLabel = index.toString(), warnings = List(20) { index.toString() }))
        } }.awaitAll()
        val result = cache().readTimetable("account", "term")!!
        assertEquals(List(20) { result.termLabel }, result.warnings)
        assertTrue(folder.root.listFiles().orEmpty().all { it.extension == "json" })
    }

    @Test fun `copied cache cannot cross account boundary`() = runTest {
        val cache = cache()
        cache.writeTerms("a", listOf(SchoolTermOption("term", "A")))
        val first = folder.root.listFiles()!!.single()
        cache.writeTerms("b", listOf(SchoolTermOption("term", "B")))
        val second = folder.root.listFiles()!!.first { it != first }
        first.copyTo(second, overwrite = true)
        assertTrue(cache.readTerms("b").isEmpty())
        second.writeText("broken")
        assertTrue(cache.readTerms("b").isEmpty())
    }

    @Test fun `corrupted unsupported wrong identity and oversized files are misses`() = runTest {
        val cache = cache()
        suspend fun savedFile(): java.io.File {
            cache.writeTimetable("account", "term", SchoolTimetableData(termValue = "term"))
            return folder.root.walkTopDown().first { it.isFile && it.extension == "json" }
        }
        savedFile().writeText("broken")
        assertNull(cache.readTimetable("account", "term"))
        val version = savedFile()
        version.writeText(version.readText().replace("\"version\":1", "\"version\":99"))
        assertNull(cache.readTimetable("account", "term"))
        val identity = savedFile()
        identity.writeText(identity.readText().replace("\"term\":\"term\"", "\"term\":\"other\""))
        assertNull(cache.readTimetable("account", "term"))
        savedFile().writeBytes(ByteArray(4 * 1024 * 1024 + 1))
        assertNull(cache.readTimetable("account", "term"))
    }
}
