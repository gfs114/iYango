package com.gfs.iyango.feature.main.editor

import androidx.lifecycle.SavedStateHandle
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.model.Timetable
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 编辑器保存路径：兜底分组键在本次编辑会话内保持稳定。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CourseEditorSaveTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `冲突确认后重试沿用同一兜底分组键`() = runTest(dispatcher) {
        val batches = mutableListOf<List<Course>>()
        val model = CourseEditorViewModel(
            timetablePort(),
            dataPort(batches),
            settingsPort(),
            SavedStateHandle(),
        )
        model.update("name") { it.copy(name = "测试课程") }

        model.save(force = true)
        advanceUntilIdle()
        model.save(force = true)
        advanceUntilIdle()

        assertEquals(2, batches.size)
        val first = batches[0].single().groupKey
        assertTrue(first.isNotBlank())
        assertEquals(first, batches[1].single().groupKey)
    }

    private fun timetablePort(): TimetableRepositoryPort = Proxy.newProxyInstance(
        TimetableRepositoryPort::class.java.classLoader,
        arrayOf(TimetableRepositoryPort::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "observeCurrent" -> flowOf(Timetable(id = 1, name = "测试课表", source = "LOCAL"))
            else -> error("保存路径不应访问 ${method.name}")
        }
    } as TimetableRepositoryPort

    private fun dataPort(batches: MutableList<List<Course>>): ScheduleDataPort = Proxy.newProxyInstance(
        ScheduleDataPort::class.java.classLoader,
        arrayOf(ScheduleDataPort::class.java),
    ) { _, method, args ->
        when (method.name) {
            "saveCourses" -> {
                @Suppress("UNCHECKED_CAST")
                val courses = args[0] as List<Course>
                batches += courses
                courses.map { 1L }
            }
            "observeCourses" -> flowOf(emptyList<Course>())
            else -> error("保存路径不应访问 ${method.name}")
        }
    } as ScheduleDataPort

    private fun settingsPort(): AppSettingsPort = Proxy.newProxyInstance(
        AppSettingsPort::class.java.classLoader,
        arrayOf(AppSettingsPort::class.java),
    ) { _, method, _ ->
        if (method.name == "getDisplaySettings") flowOf(DisplaySettingsBundle())
        else error("保存路径不应访问 ${method.name}")
    } as AppSettingsPort
}
