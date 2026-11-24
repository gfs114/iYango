package com.gfs.iyango.feature.main.coursemanage

import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.Timetable
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CourseManageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val table = MutableStateFlow<Timetable?>(Timetable(id = 1, name = "课表一"))
    private val courses = MutableStateFlow(listOf(
        Course(id = 1, timetableId = 1, name = "数学", groupKey = "math", sourceKey = "school-1"),
        Course(id = 2, timetableId = 1, name = "数学", groupKey = "math", sourceKey = "school-2", dayOfWeek = 3),
        Course(id = 3, timetableId = 1, name = "英语"),
    ))
    private val copies = mutableListOf<List<Course>>()
    private val deleted = mutableListOf<Pair<Long, Set<Long>>>()
    private var failCopy = false

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun TestScope.model(): CourseManageViewModel {
        val model = CourseManageViewModel(
            proxy { name, _ -> if (name == "observeCurrent") table else error(name) },
            proxy<ScheduleDataPort> { name, args -> when (name) {
                "observeCourses" -> if (args[0] == 1L) courses else flowOf(emptyList<Course>())
                "observeSemester" -> flowOf(null as Semester?)
                "saveCourses" -> {
                    if (failCopy) error("测试写入失败")
                    @Suppress("UNCHECKED_CAST")
                    copies.add(args[0] as List<Course>)
                    copies.last().map { it.id + 100 }
                }
                "deleteCourses" -> {
                    @Suppress("UNCHECKED_CAST")
                    deleted.add(args[0] as Long to (args[1] as Set<Long>))
                    Unit
                }
                else -> error(name)
            } },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        advanceUntilIdle()
        return model
    }

    @Test fun `one course counts once across multiple arrangements`() = runTest(dispatcher) {
        val model = model()
        assertEquals(2, (model.state.value as CourseManageUiState.Ready).totalCount)
        model.selectAllVisible()
        advanceUntilIdle()
        assertEquals(2, (model.state.value as CourseManageUiState.Ready).selectedCount)
    }

    @Test fun `changing filters clears selection so hidden courses cannot be deleted`() = runTest(dispatcher) {
        val model = model()
        model.selectAllVisible()
        advanceUntilIdle()
        model.setQuery("英语")
        advanceUntilIdle()
        assertEquals(0, (model.state.value as CourseManageUiState.Ready).selectedCount)
        model.deleteSelected()
        advanceUntilIdle()
        assertTrue(deleted.isEmpty())
    }

    @Test fun `copy keeps arrangements together clears school identity and saves once`() = runTest(dispatcher) {
        val model = model()
        model.setQuery("数学")
        advanceUntilIdle()
        model.selectAllVisible()
        advanceUntilIdle()
        model.copySelected()
        model.copySelected()
        advanceUntilIdle()
        assertEquals(1, copies.size)
        val batch = copies.single()
        assertEquals(2, batch.size)
        assertEquals(1, batch.map { it.groupKey }.distinct().size)
        assertNotEquals("math", batch.first().groupKey)
        assertTrue(batch.all { it.sourceKey.isEmpty() && it.id == 0L && it.name == "数学 副本" })
    }

    @Test fun `filter matches a meeting but group deletion covers every meeting`() = runTest(dispatcher) {
        val model = model()
        model.setDay(3)
        advanceUntilIdle()
        model.selectAllVisible()
        advanceUntilIdle()
        model.deleteSelected()
        advanceUntilIdle()
        assertEquals(listOf(1L to setOf(1L, 2L)), deleted)
    }

    @Test fun `switching timetable cannot mix old courses with new table`() = runTest(dispatcher) {
        val model = model()
        model.selectAllVisible()
        advanceUntilIdle()
        table.value = Timetable(id = 2, name = "课表二")
        advanceUntilIdle()
        val ready = model.state.value as CourseManageUiState.Ready
        assertEquals(0, ready.totalCount)
        assertEquals(0, ready.selectedCount)
        model.copySelected()
        advanceUntilIdle()
        assertTrue(copies.isEmpty())
    }

    @Test fun `failed copy leaves selection available for retry`() = runTest(dispatcher) {
        val model = model()
        model.selectAllVisible()
        advanceUntilIdle()
        failCopy = true
        model.copySelected()
        advanceUntilIdle()
        val ready = model.state.value as CourseManageUiState.Ready
        assertFalse(ready.busy)
        assertEquals(2, ready.selectedCount)
        assertTrue(ready.message.contains("失败"))
    }

    @Test fun `delete confirmation uses frozen selection and refuses changed data`() = runTest(dispatcher) {
        val model = model()
        model.selectAllVisible()
        advanceUntilIdle()
        model.requestDelete()
        advanceUntilIdle()
        courses.value = courses.value.map { it.copy(name = "已同步更新") }
        advanceUntilIdle()
        model.confirmDelete()
        advanceUntilIdle()
        assertTrue(deleted.isEmpty())
        assertTrue((model.state.value as CourseManageUiState.Ready).message.contains("已变化"))
    }

    @Test fun `changing filter invalidates selection before next state emission`() = runTest(dispatcher) {
        val model = model()
        model.selectAllVisible()
        advanceUntilIdle()
        model.setDay(3)
        model.copySelected()
        advanceUntilIdle()
        assertTrue(copies.isEmpty())
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T
}
