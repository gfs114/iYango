package com.gfs.iyango.data.repository

import com.gfs.iyango.core.model.CreditAccountPlanBackup
import com.gfs.iyango.core.model.CreditCategoryPlan
import com.gfs.iyango.core.model.CreditCategoryPlanBackup
import com.gfs.iyango.core.model.CreditResolutionAction
import com.gfs.iyango.core.model.CreditResolutionRule
import com.gfs.iyango.core.model.CreditResolutionRuleBackup
import com.gfs.iyango.core.model.CreditSplitAssignment
import com.gfs.iyango.core.model.CreditSplitAssignmentBackup
import com.gfs.iyango.core.model.CreditValueStatus
import com.gfs.iyango.core.model.CurriculumCourseItem
import com.gfs.iyango.core.model.CurriculumNatureGroup
import com.gfs.iyango.core.model.CurriculumProgressSnapshot
import com.gfs.iyango.core.model.CurriculumStudyStatus
import com.gfs.iyango.core.model.deduplicateCoursesByName
import com.gfs.iyango.core.model.parseCreditBigDecimal
import com.gfs.iyango.data.db.CreditCategoryPlanEntity
import com.gfs.iyango.data.db.CreditResolutionRuleEntity
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object CreditJsonCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val stringListSerializer = ListSerializer(String.serializer())
    private val splitDtoListSerializer = ListSerializer(CreditSplitAssignmentDto.serializer())
    private val backupListSerializer = ListSerializer(CreditAccountPlanBackupDto.serializer())

    fun encodeStringList(values: List<String>): String =
        if (values.isEmpty()) "[]" else json.encodeToString(stringListSerializer, values)

    fun decodeStringList(raw: String): List<String> =
        if (raw.isBlank() || raw == "[]") emptyList()
        else runCatching { json.decodeFromString(stringListSerializer, raw) }.getOrDefault(emptyList())

    fun encodeSplitAssignments(values: List<CreditSplitAssignment>): String =
        if (values.isEmpty()) "[]"
        else json.encodeToString(
            splitDtoListSerializer,
            values.map { it.toDto() },
        )

    fun decodeSplitAssignments(raw: String): List<CreditSplitAssignment> =
        if (raw.isBlank() || raw == "[]") emptyList()
        else runCatching { json.decodeFromString(splitDtoListSerializer, raw) }
            .getOrDefault(emptyList())
            .map { it.toModel() }

    fun encodeBackupList(values: List<CreditAccountPlanBackup>): String =
        json.encodeToString(backupListSerializer, values.map { it.toDto() })

    fun decodeBackupList(raw: String): List<CreditAccountPlanBackup> =
        if (raw.isBlank()) emptyList()
        else runCatching { json.decodeFromString(backupListSerializer, raw) }
            .getOrDefault(emptyList())
            .map { it.toModel() }

    fun decodeCreditValueStatus(raw: String): CreditValueStatus =
        CreditValueStatus.entries.firstOrNull { it.name == raw } ?: CreditValueStatus.VALID

    fun encodeCurriculumSnapshot(snapshot: CurriculumProgressSnapshot?): String =
        if (snapshot == null) "" else json.encodeToString(CurriculumProgressSnapshotDto.serializer(), snapshot.toDto())

    fun decodeCurriculumSnapshot(raw: String): CurriculumProgressSnapshot? =
        if (raw.isBlank()) null
        else runCatching { json.decodeFromString(CurriculumProgressSnapshotDto.serializer(), raw).toModel() }.getOrNull()
}

@Serializable
internal data class CreditSplitAssignmentDto(
    val anchorKey: String = "",
    val occurrenceIndex: Int = 0,
    val customGroupKey: String = "",
    val assignedCategoryId: String? = null,
    val expectedFingerprint: String = "",
    val exclude: Boolean = false,
    val manualPassConfirmed: Boolean = false,
)

@Serializable
internal data class CreditCategoryPlanBackupDto(
    val categoryId: String = "",
    val displayName: String = "",
    val targetCreditsText: String? = null,
    val mappedRawAttributes: List<String> = emptyList(),
    val sortOrder: Int = 0,
)

@Serializable
internal data class CreditResolutionRuleBackupDto(
    val ruleKey: String = "",
    val action: String = CreditResolutionAction.MERGE_AS_SINGLE_COURSE.name,
    val assignedCategoryId: String? = null,
    val chosenAnchorKey: String? = null,
    val splitAssignments: List<CreditSplitAssignmentDto> = emptyList(),
    val expectedFingerprints: List<String> = emptyList(),
    val manualPassConfirmed: Boolean = false,
    val note: String = "",
    val updatedAtEpochMillis: Long = 0L,
)

@Serializable
internal data class CurriculumCourseItemDto(
    val courseCode: String = "",
    val courseName: String = "",
    val creditText: String = "",
    val courseAttribute: String = "",
    val courseNature: String = "",
    val studyStatus: String = CurriculumStudyStatus.UNKNOWN.name,
    val scoreText: String = "",
    val isPassed: Boolean = false,
    val remark: String = "",
)

@Serializable
internal data class CurriculumNatureGroupDto(
    val courseNature: String = "",
    val earnedCreditsText: String = "0",
    val inProgressCreditsText: String = "0",
    val courses: List<CurriculumCourseItemDto> = emptyList(),
)

@Serializable
internal data class CurriculumProgressSnapshotDto(
    val planTitle: String = "",
    val totalEarnedCreditsText: String = "0",
    val totalInProgressCreditsText: String = "0",
    val groups: List<CurriculumNatureGroupDto> = emptyList(),
    val fetchedAtEpochMillis: Long = 0L,
)

@Serializable
internal data class CreditAccountPlanBackupDto(
    val schoolId: String = "YGU",
    val subjectFingerprint: String = "",
    val totalTargetCreditsText: String? = null,
    val userNote: String = "",
    val categories: List<CreditCategoryPlanBackupDto> = emptyList(),
    val rules: List<CreditResolutionRuleBackupDto> = emptyList(),
    val updatedAtEpochMillis: Long = 0L,
    val curriculumSnapshot: CurriculumProgressSnapshotDto? = null,
)

internal fun CreditSplitAssignment.toDto(): CreditSplitAssignmentDto = CreditSplitAssignmentDto(
    anchorKey = anchorKey,
    occurrenceIndex = occurrenceIndex,
    customGroupKey = customGroupKey,
    assignedCategoryId = assignedCategoryId,
    expectedFingerprint = expectedFingerprint,
    exclude = exclude,
    manualPassConfirmed = manualPassConfirmed,
)

internal fun CreditSplitAssignmentDto.toModel(): CreditSplitAssignment = CreditSplitAssignment(
    anchorKey = anchorKey,
    occurrenceIndex = occurrenceIndex,
    customGroupKey = customGroupKey,
    assignedCategoryId = assignedCategoryId,
    expectedFingerprint = expectedFingerprint,
    exclude = exclude,
    manualPassConfirmed = manualPassConfirmed,
)

internal fun CreditCategoryPlanEntity.toModel(): CreditCategoryPlan = CreditCategoryPlan(
    categoryId = categoryId,
    displayName = displayName,
    targetCredits = targetCreditsText?.let(::parseCreditBigDecimal),
    mappedRawAttributes = CreditJsonCodec.decodeStringList(mappedRawAttributesJson)
        .filter { it.isNotBlank() }
        .toSet(),
    sortOrder = sortOrder,
)

internal fun CreditCategoryPlan.toEntity(accountKey: String): CreditCategoryPlanEntity =
    CreditCategoryPlanEntity(
        accountKey = accountKey,
        categoryId = categoryId,
        displayName = displayName,
        targetCreditsText = targetCredits?.toPlainString(),
        mappedRawAttributesJson = CreditJsonCodec.encodeStringList(mappedRawAttributes.toList()),
        sortOrder = sortOrder,
    )

internal fun CreditResolutionRuleEntity.toModel(): CreditResolutionRule {
    val parsedAction = CreditResolutionAction.entries.firstOrNull { it.name == action }
        ?: CreditResolutionAction.MERGE_AS_SINGLE_COURSE
    return CreditResolutionRule(
        ruleKey = ruleKey,
        action = parsedAction,
        assignedCategoryId = assignedCategoryId,
        chosenAnchorKey = chosenAnchorKey,
        splitAssignments = CreditJsonCodec.decodeSplitAssignments(splitAssignmentsJson),
        expectedFingerprints = CreditJsonCodec.decodeStringList(expectedFingerprintsJson),
        requiresPostRestoreVerification = requiresPostRestoreVerification,
        manualPassConfirmed = manualPassConfirmed,
        note = note,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun CreditResolutionRule.toEntity(accountKey: String): CreditResolutionRuleEntity =
    CreditResolutionRuleEntity(
        accountKey = accountKey,
        ruleKey = ruleKey,
        action = action.name,
        assignedCategoryId = assignedCategoryId,
        chosenAnchorKey = chosenAnchorKey,
        splitAssignmentsJson = CreditJsonCodec.encodeSplitAssignments(splitAssignments),
        expectedFingerprintsJson = CreditJsonCodec.encodeStringList(expectedFingerprints),
        requiresPostRestoreVerification = requiresPostRestoreVerification,
        manualPassConfirmed = manualPassConfirmed,
        note = note,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

internal fun CurriculumProgressSnapshot.toDto(): CurriculumProgressSnapshotDto = CurriculumProgressSnapshotDto(
    planTitle = planTitle,
    totalEarnedCreditsText = totalEarnedCredits.stripTrailingZeros().toPlainString(),
    totalInProgressCreditsText = totalInProgressCredits.stripTrailingZeros().toPlainString(),
    groups = groups.map { group ->
        CurriculumNatureGroupDto(
            courseNature = group.courseNature,
            earnedCreditsText = group.earnedCredits.stripTrailingZeros().toPlainString(),
            inProgressCreditsText = group.inProgressCredits.stripTrailingZeros().toPlainString(),
            courses = group.courses.map { course ->
                CurriculumCourseItemDto(
                    courseCode = course.courseCode,
                    courseName = course.courseName,
                    creditText = course.creditText.ifEmpty { course.credit.stripTrailingZeros().toPlainString() },
                    courseAttribute = course.courseAttribute,
                    courseNature = course.courseNature,
                    studyStatus = course.studyStatus.name,
                    scoreText = course.scoreText,
                    isPassed = course.isPassed,
                    remark = course.remark,
                )
            },
        )
    },
    fetchedAtEpochMillis = fetchedAtEpochMillis,
)

internal fun CurriculumProgressSnapshotDto.toModel(): CurriculumProgressSnapshot = CurriculumProgressSnapshot(
    planTitle = planTitle,
    totalEarnedCredits = (parseCreditBigDecimal(totalEarnedCreditsText) ?: BigDecimal.ZERO).stripTrailingZeros(),
    totalInProgressCredits = (parseCreditBigDecimal(totalInProgressCreditsText) ?: BigDecimal.ZERO).stripTrailingZeros(),
    groups = groups.map { group ->
        CurriculumNatureGroup(
            courseNature = group.courseNature,
            earnedCredits = (parseCreditBigDecimal(group.earnedCreditsText) ?: BigDecimal.ZERO).stripTrailingZeros(),
            inProgressCredits = (parseCreditBigDecimal(group.inProgressCreditsText) ?: BigDecimal.ZERO).stripTrailingZeros(),
            courses = group.courses.map { course ->
                CurriculumCourseItem(
                    courseCode = course.courseCode,
                    courseName = course.courseName,
                    credit = (parseCreditBigDecimal(course.creditText) ?: BigDecimal.ZERO).stripTrailingZeros(),
                    creditText = course.creditText,
                    courseAttribute = course.courseAttribute,
                    courseNature = course.courseNature,
                    studyStatus = CurriculumStudyStatus.entries.firstOrNull { it.name == course.studyStatus }
                        ?: CurriculumStudyStatus.UNKNOWN,
                    scoreText = course.scoreText,
                    isPassed = course.isPassed,
                    remark = course.remark,
                )
            },
        )
    },
    fetchedAtEpochMillis = fetchedAtEpochMillis,
).deduplicateCoursesByName()

internal fun CreditAccountPlanBackup.toDto(): CreditAccountPlanBackupDto = CreditAccountPlanBackupDto(
    schoolId = schoolId,
    subjectFingerprint = subjectFingerprint,
    totalTargetCreditsText = totalTargetCreditsText,
    userNote = userNote,
    categories = categories.map {
        CreditCategoryPlanBackupDto(
            categoryId = it.categoryId,
            displayName = it.displayName,
            targetCreditsText = it.targetCreditsText,
            mappedRawAttributes = it.mappedRawAttributes,
            sortOrder = it.sortOrder,
        )
    },
    rules = rules.map {
        CreditResolutionRuleBackupDto(
            ruleKey = it.ruleKey,
            action = it.action.name,
            assignedCategoryId = it.assignedCategoryId,
            chosenAnchorKey = it.chosenAnchorKey,
            splitAssignments = it.splitAssignments.map { s ->
                CreditSplitAssignmentDto(
                    anchorKey = s.anchorKey,
                    occurrenceIndex = s.occurrenceIndex,
                    customGroupKey = s.customGroupKey,
                    assignedCategoryId = s.assignedCategoryId,
                    expectedFingerprint = s.expectedFingerprint,
                    exclude = s.exclude,
                    manualPassConfirmed = s.manualPassConfirmed,
                )
            },
            expectedFingerprints = it.expectedFingerprints,
            manualPassConfirmed = it.manualPassConfirmed,
            note = it.note,
            updatedAtEpochMillis = it.updatedAtEpochMillis,
        )
    },
    updatedAtEpochMillis = updatedAtEpochMillis,
    curriculumSnapshot = curriculumSnapshot?.toDto(),
)

internal fun CreditAccountPlanBackupDto.toModel(): CreditAccountPlanBackup = CreditAccountPlanBackup(
    schoolId = schoolId,
    subjectFingerprint = subjectFingerprint,
    totalTargetCreditsText = totalTargetCreditsText,
    userNote = userNote,
    categories = categories.map {
        CreditCategoryPlanBackup(
            categoryId = it.categoryId,
            displayName = it.displayName,
            targetCreditsText = it.targetCreditsText,
            mappedRawAttributes = it.mappedRawAttributes,
            sortOrder = it.sortOrder,
        )
    },
    rules = rules.map {
        val parsedAction = CreditResolutionAction.entries.firstOrNull { act -> act.name == it.action }
            ?: CreditResolutionAction.MERGE_AS_SINGLE_COURSE
        CreditResolutionRuleBackup(
            ruleKey = it.ruleKey,
            action = parsedAction,
            assignedCategoryId = it.assignedCategoryId,
            chosenAnchorKey = it.chosenAnchorKey,
            splitAssignments = it.splitAssignments.map { s ->
                CreditSplitAssignmentBackup(
                    anchorKey = s.anchorKey,
                    occurrenceIndex = s.occurrenceIndex,
                    customGroupKey = s.customGroupKey,
                    assignedCategoryId = s.assignedCategoryId,
                    expectedFingerprint = s.expectedFingerprint,
                    exclude = s.exclude,
                    manualPassConfirmed = s.manualPassConfirmed,
                )
            },
            expectedFingerprints = it.expectedFingerprints,
            manualPassConfirmed = it.manualPassConfirmed,
            note = it.note,
            updatedAtEpochMillis = it.updatedAtEpochMillis,
        )
    },
    updatedAtEpochMillis = updatedAtEpochMillis,
    curriculumSnapshot = curriculumSnapshot?.toModel(),
)
