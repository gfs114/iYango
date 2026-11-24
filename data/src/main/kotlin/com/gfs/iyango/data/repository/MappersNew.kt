package com.gfs.iyango.data.repository

import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSnapshot
import com.gfs.iyango.core.model.OccurrenceAdjustment
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolProfile
import com.gfs.iyango.core.model.SystemMapping
import com.gfs.iyango.core.model.TemporaryDay
import com.gfs.iyango.core.model.WidgetConfig
import com.gfs.iyango.data.db.ExamEntity
import com.gfs.iyango.data.db.GradeRecordEntity
import com.gfs.iyango.data.db.GradeSnapshotEntity
import com.gfs.iyango.data.db.OccurrenceAdjustmentEntity
import com.gfs.iyango.data.db.SchoolConnectionEntity
import com.gfs.iyango.data.db.SystemMappingEntity
import com.gfs.iyango.data.db.TemporaryDayEntity
import com.gfs.iyango.data.db.WidgetConfigEntity

/** 学校连接实体 ↔ 模型。 */
fun SchoolConnectionEntity.toModel(): SchoolConnection = SchoolConnection(
    id = id,
    schoolId = schoolId,
    accountSubject = accountSubject,
    maskedAccount = maskedAccount,
    sessionRevision = sessionRevision,
    status = status,
    credentialRef = credentialRef,
    rememberPassword = rememberPassword,
    createdAt = createdAt,
    lastLoginAt = lastLoginAt,
    lastError = lastError,
    profile = SchoolProfile(
        displayName = displayName,
        studentNo = studentNo,
        college = college,
        major = major,
        className = className,
        gender = gender,
        avatarUrl = avatarUrl,
    ),
)

fun SchoolConnection.toEntity(): SchoolConnectionEntity = SchoolConnectionEntity(
    id = id,
    schoolId = schoolId,
    accountSubject = accountSubject,
    maskedAccount = maskedAccount,
    sessionRevision = sessionRevision,
    status = status,
    credentialRef = credentialRef,
    rememberPassword = rememberPassword,
    createdAt = createdAt,
    lastLoginAt = lastLoginAt,
    lastError = lastError,
    displayName = profile.displayName,
    studentNo = profile.studentNo,
    college = profile.college,
    major = profile.major,
    className = profile.className,
    gender = profile.gender,
    avatarUrl = profile.avatarUrl,
)

/** 考试实体 ↔ 模型。 */
fun ExamEntity.toModel(): Exam = Exam(
    id = id,
    source = source,
    connectionId = connectionId,
    semester = semester,
    subject = subject,
    type = type,
    date = date,
    endDate = endDate,
    startTime = startTime,
    endTime = endTime,
    timeZone = timeZone,
    location = location,
    seat = seat,
    note = note,
    status = status,
    sourceExamId = sourceExamId,
    updatedAt = updatedAt,
)

fun Exam.toEntity(): ExamEntity = ExamEntity(
    id = id,
    source = source,
    connectionId = connectionId,
    semester = semester,
    subject = subject,
    type = type,
    date = date,
    endDate = endDate,
    startTime = startTime,
    endTime = endTime,
    timeZone = timeZone,
    location = location,
    seat = seat,
    note = note,
    status = status,
    sourceExamId = sourceExamId,
    updatedAt = updatedAt,
)

/** 成绩快照/记录实体 ↔ 模型。 */
fun GradeSnapshotEntity.toModel(): GradeSnapshot = GradeSnapshot(
    id = id,
    accountKey = accountKey,
    semester = semester,
    fetchedAt = fetchedAt,
    complete = complete,
    parserVersion = parserVersion,
    queryFingerprint = queryFingerprint,
    targetSemesters = CreditJsonCodec.decodeStringList(targetSemestersJson),
    queriedSemesters = CreditJsonCodec.decodeStringList(queriedSemestersJson),
    truncatedTermCount = truncatedTermCount,
    directAllEmptyUnverified = directAllEmptyUnverified,
    invalidatedByPartialUpdate = invalidatedByPartialUpdate,
)

fun GradeRecordEntity.toModel(): GradeRecord = GradeRecord(
    id = id,
    accountKey = accountKey,
    semester = semester,
    courseName = courseName,
    courseCode = courseCode,
    credit = credit,
    rawCreditText = rawCreditText,
    creditStatus = CreditJsonCodec.decodeCreditValueStatus(creditStatus),
    scoreText = scoreText,
    scoreValue = scoreValue,
    gradePoint = gradePoint,
    attribute = attribute,
    rawCourseAttribute = rawCourseAttribute,
    rawCourseType = rawCourseType,
    rawGeneralElectiveType = rawGeneralElectiveType,
    rawAttributesKnown = rawAttributesKnown,
    examType = examType,
    schoolRecordId = schoolRecordId,
    rowOrder = rowOrder,
    isNew = isNew,
    isModified = isModified,
    previousScoreText = previousScoreText,
    updateVersion = updateVersion,
    updatedAt = updatedAt,
)

fun GradeRecord.toEntity(): GradeRecordEntity = GradeRecordEntity(
    id = id,
    accountKey = accountKey,
    semester = semester,
    courseName = courseName,
    courseCode = courseCode,
    credit = credit,
    rawCreditText = rawCreditText,
    creditStatus = creditStatus.name,
    scoreText = scoreText,
    scoreValue = scoreValue,
    gradePoint = gradePoint,
    attribute = attribute,
    rawCourseAttribute = rawCourseAttribute,
    rawCourseType = rawCourseType,
    rawGeneralElectiveType = rawGeneralElectiveType,
    rawAttributesKnown = rawAttributesKnown,
    examType = examType,
    schoolRecordId = schoolRecordId,
    rowOrder = rowOrder,
    isNew = isNew,
    isModified = isModified,
    previousScoreText = previousScoreText,
    updateVersion = updateVersion,
    updatedAt = updatedAt,
)

/** 临时调整实体 ↔ 模型。 */
fun OccurrenceAdjustmentEntity.toModel(): OccurrenceAdjustment = OccurrenceAdjustment(
    id = id,
    timetableId = timetableId,
    operationGroupId = operationGroupId,
    type = type,
    sourceCourseId = sourceCourseId,
    sourceDate = sourceDate,
    targetDate = targetDate,
    targetDayOfWeek = targetDayOfWeek,
    targetStartSection = targetStartSection,
    targetEndSection = targetEndSection,
    targetStartTime = targetStartTime,
    targetEndTime = targetEndTime,
    targetClassroom = targetClassroom,
    note = note,
    createdAt = createdAt,
)

fun OccurrenceAdjustment.toEntity(): OccurrenceAdjustmentEntity = OccurrenceAdjustmentEntity(
    id = id,
    timetableId = timetableId,
    operationGroupId = operationGroupId,
    type = type,
    sourceCourseId = sourceCourseId,
    sourceDate = sourceDate,
    targetDate = targetDate,
    targetDayOfWeek = targetDayOfWeek,
    targetStartSection = targetStartSection,
    targetEndSection = targetEndSection,
    targetStartTime = targetStartTime,
    targetEndTime = targetEndTime,
    targetClassroom = targetClassroom,
    note = note,
    createdAt = createdAt,
)

fun TemporaryDayEntity.toModel(): TemporaryDay = TemporaryDay(
    id = id,
    timetableId = timetableId,
    operationGroupId = operationGroupId,
    date = date,
    sourceTimetableId = sourceTimetableId,
    title = title,
    createdAt = createdAt,
)

fun TemporaryDay.toEntity(): TemporaryDayEntity = TemporaryDayEntity(
    id = id,
    timetableId = timetableId,
    operationGroupId = operationGroupId,
    date = date,
    sourceTimetableId = sourceTimetableId,
    title = title,
    createdAt = createdAt,
)

/** 系统映射实体 ↔ 模型。 */
fun SystemMappingEntity.toModel(): SystemMapping = SystemMapping(
    id = id,
    kind = kind,
    occurrenceId = occurrenceId,
    externalId = externalId,
    payloadHash = payloadHash,
    planRevision = planRevision,
    ownerId = ownerId,
    updatedAt = updatedAt,
)

fun SystemMapping.toEntity(): SystemMappingEntity = SystemMappingEntity(
    id = id,
    kind = kind,
    occurrenceId = occurrenceId,
    externalId = externalId,
    payloadHash = payloadHash,
    planRevision = planRevision,
    ownerId = ownerId,
    updatedAt = updatedAt,
)

/** 桌面组件配置实体 ↔ 模型。 */
fun WidgetConfigEntity.toModel(): WidgetConfig = WidgetConfig(
    widgetId = widgetId,
    timetableId = timetableId,
    layout = layout,
    appearance = appearance,
    updatedAt = updatedAt,
    glassOpacityPercent = glassOpacityPercent,
    textSizeSp = textSizeSp,
    showTeacher = showTeacher,
    showLocation = showLocation,
    showBg = showBg,
    bgColor = bgColor,
    backgroundUri = backgroundUri,
    showHeaderArea = showHeaderArea,
    showDate = showDate,
    showButton = showButton,
    showColor = showColor,
    textColor = textColor,
    courseTextColor = courseTextColor,
    strokeColor = strokeColor,
    textColorCompose = textColorCompose,
    strokeColorCompose = strokeColorCompose,
    headerTextSizeSp = headerTextSizeSp,
    itemHeightDp = itemHeightDp,
    radiusDp = radiusDp,
    itemAlpha = itemAlpha,
    otherWeekCourseAlpha = otherWeekCourseAlpha,
    itemCenterHorizontal = itemCenterHorizontal,
    itemCenterVertical = itemCenterVertical,
    showTime = showTime,
    showRoomPrefix = showRoomPrefix,
    showTimeBar = showTimeBar,
    showSat = showSat,
    showSun = showSun,
    showOtherWeekCourse = showOtherWeekCourse,
    showGrid = showGrid,
    useDottedLine = useDottedLine,
)

fun WidgetConfig.toEntity(): WidgetConfigEntity = WidgetConfigEntity(
    widgetId = widgetId,
    timetableId = timetableId,
    layout = layout,
    appearance = appearance,
    updatedAt = updatedAt,
    glassOpacityPercent = glassOpacityPercent,
    textSizeSp = textSizeSp,
    showTeacher = showTeacher,
    showLocation = showLocation,
    showBg = showBg,
    bgColor = bgColor,
    backgroundUri = backgroundUri,
    showHeaderArea = showHeaderArea,
    showDate = showDate,
    showButton = showButton,
    showColor = showColor,
    textColor = textColor,
    courseTextColor = courseTextColor,
    strokeColor = strokeColor,
    textColorCompose = textColorCompose,
    strokeColorCompose = strokeColorCompose,
    headerTextSizeSp = headerTextSizeSp,
    itemHeightDp = itemHeightDp,
    radiusDp = radiusDp,
    itemAlpha = itemAlpha,
    otherWeekCourseAlpha = otherWeekCourseAlpha,
    itemCenterHorizontal = itemCenterHorizontal,
    itemCenterVertical = itemCenterVertical,
    showTime = showTime,
    showRoomPrefix = showRoomPrefix,
    showTimeBar = showTimeBar,
    showSat = showSat,
    showSun = showSun,
    showOtherWeekCourse = showOtherWeekCourse,
    showGrid = showGrid,
    useDottedLine = useDottedLine,
)
