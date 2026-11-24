package com.gfs.iyango.feature.main.schedule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import com.gfs.iyango.feature.main.settings.DisplaySettingsViewModel
import java.lang.reflect.Proxy
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DisplaySettingsPreviewTest {
    private inline fun <reified T> port(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T
    @Test fun `real preview observes semester slots and selected-day changes without writes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val date = LocalDate.of(2026, 10, 5)
            val root = Timetable(id = 1, name = "Root")
            val current = MutableStateFlow<Timetable?>(root)
            val semester = MutableStateFlow<Semester?>(null)
            val slots = MutableStateFlow(emptyList<TimeSlot>())
            val days = MutableStateFlow(emptyList<EffectiveScheduleDay>())
            val settings = port<AppSettingsPort> { name, _ -> when (name) {
                "getDisplaySettings" -> flowOf(DisplaySettingsBundle())
                "getAppPreferences" -> flowOf(AppPreferences())
                else -> error("Unexpected settings write/read: $name")
            } }
            val tables = port<TimetableRepositoryPort> { name, _ ->
                check(name == "observeCurrent"); current
            }
            val data = port<ScheduleDataPort> { name, _ -> when (name) {
                "observeSemester" -> semester
                "observeTimeSlots" -> slots
                "observeCourses" -> flowOf(emptyList<Course>())
                "timeRailRows" -> emptyList<TimeRailRow>()
                else -> error("Preview attempted a course write: $name")
            } }
            val effective = port<EffectiveSchedulePort> { name, _ ->
                check(name == "observeSelectedRange"); days
            }
            val wallpaper = port<WallpaperStore> { name, _ -> check(name == "resolve"); null }
            val exams = MutableStateFlow<List<Exam>>(emptyList())
            val vm = DisplaySettingsViewModel(settings, tables, data, effective, wallpaper,
                Clock.fixed(date.atTime(8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC), port<ExamRepositoryPort> { name, args ->
                    check(name=="observeInRange")
                    assertEquals(date.minusDays(2),args[0])
                    assertEquals(date.plusDays(semester.value!!.totalWeeks.coerceIn(1,30)*7L+1),args[1])
                    exams
                }, SavedStateHandle())
            store.put("preview", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.preview.collect() }
            advanceUntilIdle()
            assertEquals(ScheduleUiState.NoSemester, vm.preview.value)
            semester.value = Semester(id = 2, timetableId = 1, startDate = date)
            val course = Course(id = 7, timetableId = 9, name = "Alternate table", dayOfWeek = 1)
            val occurrence = CourseOccurrence("move", course, date, 480, 540, false,
                anchorDate = date.minusWeeks(1), isMoved = true)
            days.value = listOf(EffectiveScheduleDay(date, root.copy(id = 9), semester.value, listOf(occurrence)))
            advanceUntilIdle()
            var ready = (vm.preview.value as ScheduleUiState.Ready).data
            assertEquals(occurrence, ready.cells.single().blocks.single().occurrence)
            val updatedSlots = listOf(TimeSlot(section = 1, startTime = "09:00", endTime = "10:00"))
            slots.value = updatedSlots
            days.value = listOf(EffectiveScheduleDay(date, root, semester.value,
                listOf(occurrence.copy(occurrenceId = "temporary", isTemporary = true)), true, updatedSlots))
            advanceUntilIdle()
            ready = (vm.preview.value as ScheduleUiState.Ready).data
            assertEquals(updatedSlots, ready.timeSlots)
            assertTrue(ready.cells.single().blocks.single().occurrence!!.isTemporary)
            exams.value=listOf(Exam(id=1,subject="实际考试",date=date.toString()))
            advanceUntilIdle()
            ready = (vm.preview.value as ScheduleUiState.Ready).data
            assertEquals("实际考试",ready.exams.single().exam.subject)
            assertNull(ready.exams.single().topRow)
            current.value = null
            advanceUntilIdle()
            assertEquals(ScheduleUiState.NoTimetable, vm.preview.value)
        } finally { store.clear(); advanceUntilIdle(); Dispatchers.resetMain() }
    }
}
