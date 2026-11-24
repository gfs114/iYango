package com.gfs.iyango.data.repository

import android.content.Context
import com.gfs.iyango.core.domain.TermBrowseCachePort
import com.gfs.iyango.core.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private, disposable browse snapshots. No credentials, raw responses, or Room state. */
@Singleton
class FileTermBrowseCache internal constructor(private val directory: File) : TermBrowseCachePort {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "term-browse"))

    override suspend fun readTerms(accountKey: String): List<SchoolTermOption> =
        read(accountKey, null)?.terms?.map { it.toModel() }.orEmpty()

    override suspend fun writeTerms(accountKey: String, terms: List<SchoolTermOption>) {
        write(accountKey, null, Entry(accountHash = digest(accountKey), terms = terms.map { it.toDto() }))
    }

    override suspend fun readTimetable(accountKey: String, term: String): SchoolTimetableData? =
        read(accountKey, term)?.timetable?.toModel()

    override suspend fun writeTimetable(accountKey: String, term: String, data: SchoolTimetableData) {
        require(data.termValue == term) { "Snapshot term does not match cache key" }
        write(accountKey, term, Entry(accountHash = digest(accountKey), term = term, timetable = data.toDto()))
    }

    private fun file(accountKey: String, term: String?): File =
        File(directory, digest(accountKey) + "-" + (term?.let { "term-" + digest(it) } ?: "terms") + ".json")

    private suspend fun read(accountKey: String, term: String?): Entry? = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val encoded = file(accountKey, term).inputStream().use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (bytes.size() + count > MAX_BYTES) return@withLock null
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString(Charsets.UTF_8.name())
                }
                json.decodeFromString<Entry>(encoded).takeIf {
                    it.version == 1 && it.accountHash == digest(accountKey) && it.term == term &&
                        (term == null || it.timetable?.termValue == term) &&
                        it.timetable?.courses.orEmpty().all { course -> course.weekType in 0..2 }
                }
            } catch (_: IOException) { null
            } catch (_: SerializationException) { null
            } catch (_: IllegalArgumentException) { null }
        }
    }

    private suspend fun write(accountKey: String, term: String?, entry: Entry) = withContext(Dispatchers.IO) {
        lock.withLock {
            val bytes = json.encodeToString(entry).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES) { "Browse snapshot exceeds cache limit" }
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create browse cache" }
            val target = file(accountKey, term)
            val temporary = File.createTempFile("browse-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                // Same filesystem atomic replacement: interrupted writes leave the previous snapshot intact.
                Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        // Shared across instances to serialize readers and writers in this app process.
        val lock = Mutex()
        val json = Json { encodeDefaults = true }
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
private data class Entry(
    val version: Int = 1,
    val accountHash: String,
    val term: String? = null,
    val terms: List<SchoolTermOptionDto>? = null,
    val timetable: SchoolTimetableDataDto? = null,
)

@Serializable
private data class SchoolTermOptionDto(
    val value: String = "",
    val label: String = "",
)

private fun SchoolTermOption.toDto() = SchoolTermOptionDto(
    value = value,
    label = label,
)

private fun SchoolTermOptionDto.toModel() = SchoolTermOption(
    value = value,
    label = label,
)


@Serializable
private data class SchoolCourseDataDto(
    val sourceKey: String = "",
    val name: String = "",
    val teacher: String = "",
    val classroom: String = "",
    val dayOfWeek: Int = 1,
    val startSection: Int = 1,
    val endSection: Int = 1,
    val startWeek: Int = 1,
    val endWeek: Int = 1,
    val weekType: Int = 0,
    val selectedWeeks: List<Int> = emptyList(),
    val campusCode: String = "",
    val campusName: String = "",
    val rawSections: String = "",
    val rawSectionCode: String = "",
    val note: String = "",
    val groupKey: String = "",
    val teacherConfirmed: Boolean = true,
    val timeUnconfirmed: Boolean = false,
    val startTime: String = "",
    val endTime: String = "",
)

private fun SchoolCourseData.toDto() = SchoolCourseDataDto(
    sourceKey = sourceKey,
    name = name,
    teacher = teacher,
    classroom = classroom,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    endSection = endSection,
    startWeek = startWeek,
    endWeek = endWeek,
    weekType = weekType.code,
    selectedWeeks = selectedWeeks,
    campusCode = campusCode,
    campusName = campusName,
    rawSections = rawSections,
    rawSectionCode = rawSectionCode,
    note = note,
    groupKey = groupKey,
    teacherConfirmed = teacherConfirmed,
    timeUnconfirmed = timeUnconfirmed,
    startTime = startTime,
    endTime = endTime,
)

private fun SchoolCourseDataDto.toModel() = SchoolCourseData(
    sourceKey = sourceKey,
    name = name,
    teacher = teacher,
    classroom = classroom,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    endSection = endSection,
    startWeek = startWeek,
    endWeek = endWeek,
    weekType = WeekType.fromCode(weekType),
    selectedWeeks = selectedWeeks,
    campusCode = campusCode,
    campusName = campusName,
    rawSections = rawSections,
    rawSectionCode = rawSectionCode,
    note = note,
    groupKey = groupKey,
    teacherConfirmed = teacherConfirmed,
    timeUnconfirmed = timeUnconfirmed,
    startTime = startTime,
    endTime = endTime,
)


@Serializable
private data class SchoolTimetableDataDto(
    val termValue: String = "",
    val termLabel: String = "",
    val startDate: String = "",
    val totalWeeks: Int = 20,
    val campusCode: String = "",
    val campusName: String = "",
    val courses: List<SchoolCourseDataDto> = emptyList(),
    val warnings: List<String> = emptyList(),
    val complete: Boolean = true,
    val fetchedAt: Long = 0,
    val timeSlots: List<TimeSlotDto> = emptyList(),
)

private fun SchoolTimetableData.toDto() = SchoolTimetableDataDto(
    termValue = termValue,
    termLabel = termLabel,
    startDate = startDate,
    totalWeeks = totalWeeks,
    campusCode = campusCode,
    campusName = campusName,
    courses = courses.map { it.toDto() },
    warnings = warnings,
    complete = complete,
    fetchedAt = fetchedAt,
    timeSlots = timeSlots.map { it.toDto() },
)

private fun SchoolTimetableDataDto.toModel() = SchoolTimetableData(
    termValue = termValue,
    termLabel = termLabel,
    startDate = startDate,
    totalWeeks = totalWeeks,
    campusCode = campusCode,
    campusName = campusName,
    courses = courses.map { it.toModel() },
    warnings = warnings,
    complete = complete,
    fetchedAt = fetchedAt,
    timeSlots = timeSlots.map { it.toModel() },
)


@Serializable
private data class TimeSlotDto(
    val id: Long = 0,
    val timetableId: Long = 0,
    val section: Int = 1,
    val startTime: String = "",
    val endTime: String = "",
    val name: String = "",
    val kind: Int = 0,
    val hidden: Boolean = false,
    val defaultCourseName: String = "",
    val sortOrder: Double = 0.0,
)

private fun TimeSlot.toDto() = TimeSlotDto(
    id = id,
    timetableId = timetableId,
    section = section,
    startTime = startTime,
    endTime = endTime,
    name = name,
    kind = kind,
    hidden = hidden,
    defaultCourseName = defaultCourseName,
    sortOrder = sortOrder,
)

private fun TimeSlotDto.toModel() = TimeSlot(
    id = id,
    timetableId = timetableId,
    section = section,
    startTime = startTime,
    endTime = endTime,
    name = name,
    kind = kind,
    hidden = hidden,
    defaultCourseName = defaultCourseName,
    sortOrder = sortOrder,
)

