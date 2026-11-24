package com.gfs.iyango.data.school.ygu

/** 成绩页解析出的一行（保留学校全部列，映射时再挑选）。 */
internal data class YguGradeRow(
    val rowNumber: String = "",
    val term: String = "",
    val courseCode: String = "",
    val courseName: String = "",
    val offeringUnit: String = "",
    val score: String = "",
    val scoreFlag: String = "",
    val credit: String = "",
    val totalHours: String = "",
    val gradePoint: String = "",
    val retakeTerm: String = "",
    val assessmentMethod: String = "",
    val examType: String = "",
    val courseAttribute: String = "",
    val courseType: String = "",
    val generalElectiveType: String = "",
)

/** 学校成绩表单里的学期选项（value 即 kksj）。 */
internal data class YguSemesterOption(
    val value: String = "",
    val label: String = "",
)

internal enum class YguGradesStatus {
    OK,
    EMPTY,
    INVALID,
    LOGIN,
    RESTRICTED,
}

internal data class YguGradesParseResult(
    val status: YguGradesStatus = YguGradesStatus.INVALID,
    val records: List<YguGradeRow> = emptyList(),
    val message: String = "",
)

internal data class YguGradesQueryField(
    val name: String,
    val value: String,
)

internal data class YguGradesQueryForm(
    val supported: Boolean = false,
    val isJson: Boolean = false,
    val hasForms: Boolean = false,
    val message: String = "",
    val actionUrl: String = "",
    val method: String = "",
    val fields: List<YguGradesQueryField> = emptyList(),
    val semesters: List<YguSemesterOption> = emptyList(),
    val selectedTerm: String = "",
)

internal enum class YguGradesClientStatus {
    OK,
    EMPTY,
    INVALID,
    LOGIN,
    RESTRICTED,
    UNSUPPORTED,
    ERROR,
    CANCELLED,
}

internal data class YguGradesOutcome(
    val status: YguGradesClientStatus = YguGradesClientStatus.ERROR,
    val records: List<YguGradeRow> = emptyList(),
    val semesters: List<YguSemesterOption> = emptyList(),
    val selectedTerm: String = "",
    val message: String = "",
    val cookieHeader: String = "",
)

internal data class YguGradesJsonPage(
    val status: YguGradesStatus = YguGradesStatus.INVALID,
    val records: List<YguGradeRow> = emptyList(),
    val total: Int = 0,
    val message: String = "",
    val fingerprint: String = "",
)
