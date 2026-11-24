package com.gfs.iyango.feature.main.summary

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.SchoolTimetablePort
import com.gfs.iyango.core.domain.SemesterSummaryCalculator
import com.gfs.iyango.core.domain.SemesterSummaryReport
import com.gfs.iyango.core.domain.TermBrowseCachePort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.SchoolTermOption
import com.gfs.iyango.core.model.SchoolTimetableData
import com.gfs.iyango.core.model.SchoolTimetableResult
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SemesterSummaryOption(
    val id: String,
    val title: String,
    val schoolTerm: String = "",
    val timetableId: Long? = null,
    val semesterId: Long? = null,
    val isCurrent: Boolean = false,
)

data class SemesterSummaryUiState(
    val loading: Boolean = true,
    val termLoading: Boolean = false,
    val hasTimetable: Boolean = false,
    val options: List<SemesterSummaryOption> = emptyList(),
    val selectedOptionId: String = "",
    val errorMessage: String? = null,
    val report: SemesterSummaryReport? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SemesterSummaryViewModel @Inject constructor(
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
    private val schoolAuth: SchoolAuthPort,
    private val gradeRepository: GradeRepositoryPort,
    private val schoolTimetable: SchoolTimetablePort,
    private val termBrowseCache: TermBrowseCachePort,
) : ViewModel() {

    private data class ScheduleSnapshotData(
        val tableName: String,
        val semester: Semester?,
        val courses: List<Course>,
        val slots: List<TimeSlot>,
    )

    private val connectionFlow: Flow<SchoolConnection?> = combine(
        schoolAuth.observeActiveConnection(),
        schoolAuth.observeConnections(),
    ) { active, all ->
        active ?: all
            .filter { it.status != SchoolConnectionStatus.LOGGED_OUT }
            .maxByOrNull { it.lastLoginAt }
    }

    private val schoolTermsFlow = MutableStateFlow<List<SchoolTermOption>>(emptyList())
    private val userSelectedOptionId = MutableStateFlow<String?>(null)
    private val remoteTimetableCache = MutableStateFlow<Map<Pair<String, String>, SchoolTimetableData>>(emptyMap())
    private val termLoadingFlow = MutableStateFlow(false)
    private val termErrorFlow = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            connectionFlow
                .distinctUntilChanged { a, b ->
                    a?.id == b?.id && a?.status == b?.status && a?.sessionRevision == b?.sessionRevision
                }
                .collect { connection ->
                    if (connection == null) {
                        schoolTermsFlow.value = emptyList()
                        return@collect
                    }
                    val cKey = cacheKey(connection)
                    val cachedTerms = runCatching {
                        withContext(Dispatchers.IO) { termBrowseCache.readTerms(cKey) }
                    }.getOrDefault(emptyList())
                    if (cachedTerms.isNotEmpty()) {
                        schoolTermsFlow.value = cachedTerms
                    }
                    if (connection.status == SchoolConnectionStatus.ACTIVE) {
                        val fetched = runCatching { schoolTimetable.listTerms(connection.id) }
                            .getOrElse { Result.failure(it) }
                        fetched.onSuccess { terms ->
                            val valid = terms.filter { it.value.isNotBlank() }.distinctBy { it.value }
                            schoolTermsFlow.value = valid
                            runCatching {
                                withContext(Dispatchers.IO) { termBrowseCache.writeTerms(cKey, valid) }
                            }
                        }
                    }
                }
        }
    }

    private val accountKeyFlow: Flow<String> = connectionFlow
        .map { conn -> conn?.let { it.accountSubject.ifEmpty { it.id } }.orEmpty() }
        .distinctUntilChanged()

    private val gradeSemestersFlow: Flow<List<String>> = accountKeyFlow
        .flatMapLatest { key ->
            if (key.isBlank()) flowOf(emptyList()) else gradeRepository.observeSemesters(key)
        }

    private val localTimetablesFlow: Flow<List<Pair<Timetable, List<Semester>>>> =
        timetableRepository.observeAll().flatMapLatest { tables ->
            if (tables.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    tables.map { table ->
                        scheduleData.observeAllSemesters(table.id).map { sems -> table to sems }
                    },
                ) { pairs -> pairs.toList() }
            }
        }

    private val optionsFlow: Flow<List<SemesterSummaryOption>> = combine(
        localTimetablesFlow,
        schoolTermsFlow,
        gradeSemestersFlow,
    ) { localEntries, schoolTerms, gradeSemesters ->
        buildSemesterOptions(localEntries, schoolTerms, gradeSemesters)
    }

    private val activeOptionFlow: Flow<SemesterSummaryOption?> = combine(
        optionsFlow,
        userSelectedOptionId,
    ) { options, selectedId ->
        options.firstOrNull { it.id == selectedId }
            ?: options.firstOrNull { it.isCurrent }
            ?: options.firstOrNull()
    }

    private val allGradeRecordsFlow: Flow<List<GradeRecord>> = combine(
        accountKeyFlow,
        gradeSemestersFlow,
        activeOptionFlow.map { it?.schoolTerm.orEmpty() }.distinctUntilChanged(),
    ) { key, cachedSemesters, selectedSchoolTerm ->
        Triple(key, cachedSemesters, selectedSchoolTerm)
    }.flatMapLatest { (accountKey, cachedSemesters, selectedSchoolTerm) ->
        if (accountKey.isBlank()) {
            flowOf(emptyList())
        } else {
            val partitions = (listOf(GradeRepositoryPort.ALL_SEMESTERS) + cachedSemesters + selectedSchoolTerm)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            combine(partitions.map { sem -> gradeRepository.observeRecords(accountKey, sem) }) { lists ->
                lists.asSequence()
                    .flatten()
                    .distinctBy { it.stableKey }
                    .toList()
            }
        }
    }

    private val selectedScheduleFlow: Flow<ScheduleSnapshotData?> = combine(
        activeOptionFlow,
        connectionFlow,
        remoteTimetableCache,
    ) { option, connection, remoteCache ->
        Triple(option, connection, remoteCache)
    }.flatMapLatest { (option, connection, remoteCache) ->
        when {
            option == null -> flowOf(null)
            option.timetableId != null -> {
                combine(
                    scheduleData.observeTimetable(option.timetableId),
                    scheduleData.observeAllSemesters(option.timetableId),
                    scheduleData.observeCourses(option.timetableId),
                    scheduleData.observeTimeSlots(option.timetableId),
                ) { timetable, semesters, courses, slots ->
                    val chosenSemester = semesters.firstOrNull { it.id == option.semesterId }
                        ?: semesters.firstOrNull { it.isCurrent }
                        ?: semesters.firstOrNull()
                    ScheduleSnapshotData(
                        tableName = timetable?.name ?: option.title,
                        semester = chosenSemester,
                        courses = courses,
                        slots = slots,
                    )
                }
            }
            else -> {
                val cKey = connection?.let(::cacheKey).orEmpty()
                val remote = remoteCache[cKey to option.schoolTerm]
                    ?: remoteCache.entries.firstOrNull { it.key.second == option.schoolTerm }?.value
                flowOf(
                    if (remote != null) {
                        remote.toSnapshot(fallbackTitle = option.title)
                    } else {
                        ScheduleSnapshotData(
                            tableName = option.title,
                            semester = Semester(
                                name = option.title,
                                startDate = LocalDate.of(2000, 1, 3),
                                totalWeeks = 20,
                            ),
                            courses = emptyList(),
                            slots = emptyList(),
                        )
                    },
                )
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(activeOptionFlow, connectionFlow) { opt, conn -> opt to conn }
                .distinctUntilChanged()
                .collect { (option, connection) ->
                    if (option == null || option.timetableId != null || option.schoolTerm.isBlank()) {
                        termLoadingFlow.value = false
                        termErrorFlow.value = null
                        return@collect
                    }
                    val termValue = option.schoolTerm
                    val cKey = connection?.let(::cacheKey).orEmpty()
                    if (remoteTimetableCache.value.containsKey(cKey to termValue)) {
                        termLoadingFlow.value = false
                        termErrorFlow.value = null
                        return@collect
                    }
                    if (cKey.isNotEmpty()) {
                        val cached = runCatching {
                            withContext(Dispatchers.IO) { termBrowseCache.readTimetable(cKey, termValue) }
                        }.getOrNull()
                        if (cached != null) {
                            remoteTimetableCache.value = remoteTimetableCache.value + ((cKey to termValue) to cached)
                            termLoadingFlow.value = false
                            termErrorFlow.value = null
                            return@collect
                        }
                    }
                    if (connection == null || connection.status != SchoolConnectionStatus.ACTIVE) {
                        termLoadingFlow.value = false
                        return@collect
                    }
                    termLoadingFlow.value = true
                    termErrorFlow.value = null
                    try {
                        when (val res = schoolTimetable.fetchTimetable(connection.id, termValue)) {
                            is SchoolTimetableResult.Success -> {
                                remoteTimetableCache.value =
                                    remoteTimetableCache.value + ((cKey to termValue) to res.data)
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        termBrowseCache.writeTimetable(cKey, termValue, res.data)
                                    }
                                }
                                termErrorFlow.value = null
                            }
                            is SchoolTimetableResult.SessionExpired -> {
                                termErrorFlow.value = res.message.ifBlank { "登录已过期，暂只统计本机成绩与缓存" }
                            }
                            is SchoolTimetableResult.Failed -> {
                                termErrorFlow.value = res.message.ifBlank { "该学期课表读取失败" }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        termErrorFlow.value = error.message ?: "该学期课表读取失败"
                    } finally {
                        termLoadingFlow.value = false
                    }
                }
        }
    }

    val uiState: StateFlow<SemesterSummaryUiState> = combine(
        combine(optionsFlow, activeOptionFlow, selectedScheduleFlow) { options, active, snapshot ->
            Triple(options, active, snapshot)
        },
        combine(allGradeRecordsFlow, termLoadingFlow, termErrorFlow) { grades, loading, err ->
            Triple(grades, loading, err)
        },
    ) { (options, activeOption, snapshot), (gradeRecords, termLoading, termError) ->
        if (activeOption == null || snapshot == null) {
            SemesterSummaryUiState(
                loading = false,
                termLoading = termLoading,
                hasTimetable = false,
                options = options,
                selectedOptionId = "",
                errorMessage = termError,
                report = null,
            )
        } else {
            val report = withContext(Dispatchers.Default) {
                SemesterSummaryCalculator.calculate(
                    timetableName = snapshot.tableName,
                    semester = snapshot.semester,
                    courses = snapshot.courses,
                    timeSlots = snapshot.slots,
                    gradeRecords = gradeRecords,
                    schoolTerm = activeOption.schoolTerm,
                )
            }
            SemesterSummaryUiState(
                loading = false,
                termLoading = termLoading,
                hasTimetable = true,
                options = options,
                selectedOptionId = activeOption.id,
                errorMessage = termError,
                report = report,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SemesterSummaryUiState(),
    )

    fun selectSemester(optionId: String) {
        if (optionId.isBlank() || userSelectedOptionId.value == optionId) return
        userSelectedOptionId.value = optionId
    }

    suspend fun renderPoster(
        report: SemesterSummaryReport,
        style: PosterStyle,
        accentArgb: Int,
        wallpaperPath: String?,
    ): Bitmap = withContext(Dispatchers.Default) {
        SemesterSummaryPosterRenderer.render(
            report = report,
            style = style,
            accentArgb = accentArgb,
            wallpaperPath = wallpaperPath,
        )
    }

    private fun buildSemesterOptions(
        localEntries: List<Pair<Timetable, List<Semester>>>,
        schoolTerms: List<SchoolTermOption>,
        gradeSemesters: List<String>,
    ): List<SemesterSummaryOption> {
        val termByValue = schoolTerms.associateBy { it.value.trim() }
        val termByNormalized = schoolTerms.associateBy {
            SemesterSummaryCalculator.normalizeSemesterKey(it.value.ifBlank { it.label })
        }

        val sortedLocal = localEntries.sortedByDescending { it.first.isCurrent }
        val options = mutableListOf<SemesterSummaryOption>()
        val representedKeys = mutableSetOf<String>()

        fun markRepresented(vararg keys: String) {
            keys.forEach { raw ->
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) {
                    representedKeys += trimmed
                    val norm = SemesterSummaryCalculator.normalizeSemesterKey(trimmed)
                    if (norm.isNotEmpty()) representedKeys += norm
                }
            }
        }

        for ((timetable, semesters) in sortedLocal) {
            val orderedSemesters = if (semesters.isEmpty()) {
                listOf(null)
            } else {
                semesters.sortedWith(
                    compareByDescending<Semester> { it.isCurrent }.thenByDescending { it.startDate },
                )
            }
            for (semester in orderedSemesters) {
                val title = semester?.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?: timetable.name.trim().ifEmpty { "当前课表" }
                val rawTerm = timetable.schoolTerm.trim()
                val matchedSchoolTerm = termByValue[rawTerm]
                    ?: termByNormalized[SemesterSummaryCalculator.normalizeSemesterKey(rawTerm.ifBlank { title })]
                val effectiveTerm = rawTerm.ifBlank { matchedSchoolTerm?.value.orEmpty() }
                options += SemesterSummaryOption(
                    id = "local:${timetable.id}:${semester?.id ?: 0L}",
                    title = title,
                    schoolTerm = effectiveTerm,
                    timetableId = timetable.id,
                    semesterId = semester?.id,
                    isCurrent = timetable.isCurrent && (semester == null || semester.isCurrent),
                )
                markRepresented(effectiveTerm, title, timetable.name, matchedSchoolTerm?.label.orEmpty())
            }
        }

        for (term in schoolTerms) {
            val value = term.value.trim()
            val label = term.label.trim().ifBlank { value }
            val normValue = SemesterSummaryCalculator.normalizeSemesterKey(value)
            val normLabel = SemesterSummaryCalculator.normalizeSemesterKey(label)
            if (value in representedKeys || label in representedKeys ||
                normValue in representedKeys || normLabel in representedKeys
            ) {
                continue
            }
            options += SemesterSummaryOption(
                id = "term:$value",
                title = label,
                schoolTerm = value,
                timetableId = null,
                semesterId = null,
                isCurrent = false,
            )
            markRepresented(value, label)
        }

        for (sem in gradeSemesters) {
            val trimmed = sem.trim()
            if (trimmed.isEmpty() || trimmed == GradeRepositoryPort.ALL_SEMESTERS) continue
            val norm = SemesterSummaryCalculator.normalizeSemesterKey(trimmed)
            if (trimmed in representedKeys || norm in representedKeys) continue
            options += SemesterSummaryOption(
                id = "grade:$trimmed",
                title = trimmed,
                schoolTerm = trimmed,
                timetableId = null,
                semesterId = null,
                isCurrent = false,
            )
            markRepresented(trimmed)
        }

        return options
    }

    private fun SchoolTimetableData.toSnapshot(fallbackTitle: String): ScheduleSnapshotData {
        val knownDate = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: LocalDate.of(2000, 1, 3)
        val lastWeek = courses.maxOfOrNull { maxOf(it.endWeek, it.selectedWeeks.maxOrNull() ?: 1) } ?: 1
        val total = maxOf(totalWeeks, lastWeek).coerceIn(1, 30)
        val title = termLabel.ifBlank { termValue }.ifBlank { fallbackTitle }
        val semester = Semester(
            name = title,
            startDate = knownDate,
            totalWeeks = total,
        )
        val mappedCourses = courses.mapIndexed { index, course ->
            Course(
                id = -(index + 1L),
                name = course.name,
                teacher = course.teacher,
                classroom = course.classroom,
                dayOfWeek = course.dayOfWeek,
                startSection = course.startSection,
                endSection = course.endSection,
                startWeek = course.startWeek,
                endWeek = course.endWeek,
                weekType = course.weekType,
                selectedWeeks = course.selectedWeeks,
                color = REMOTE_COURSE_PALETTE[abs(course.name.hashCode()) % REMOTE_COURSE_PALETTE.size],
            )
        }
        return ScheduleSnapshotData(
            tableName = title,
            semester = semester,
            courses = mappedCourses,
            slots = timeSlots,
        )
    }

    private fun cacheKey(account: SchoolConnection): String = listOf(
        account.schoolId,
        account.accountSubject.ifBlank { account.id },
    ).joinToString("\u0000")

    private companion object {
        val REMOTE_COURSE_PALETTE = listOf(
            "#3E7BFA",
            "#0EA5E9",
            "#10B981",
            "#F59E0B",
            "#EC4899",
            "#8B5CF6",
        )
    }
}
