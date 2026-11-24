package com.gfs.iyango.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

/**
 * 一次完整成绩查询的快照元信息。
 *
 * 分区键为（账号主体、学期）：`(accountKey, semester)` 唯一。
 */
@Entity(
    tableName = "grade_snapshot",
    indices = [
        Index(value = ["accountKey", "semester"], unique = true),
    ],
)
data class GradeSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountKey: String,
    val semester: String,
    val fetchedAt: Long,
    val complete: Boolean,
    val parserVersion: Int,
    val queryFingerprint: String,
    @ColumnInfo(defaultValue = "[]") val targetSemestersJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val queriedSemestersJson: String = "[]",
    @ColumnInfo(defaultValue = "0") val truncatedTermCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val directAllEmptyUnverified: Boolean = false,
    @ColumnInfo(defaultValue = "0") val invalidatedByPartialUpdate: Boolean = false,
)

/** 成绩记录；[isNew]/[isModified] 是相对上一次完整快照的变化标记。 */
@Entity(
    tableName = "grade_record",
    indices = [
        Index(value = ["accountKey", "semester"]),
        Index(value = ["accountKey", "isNew"]),
        Index(value = ["accountKey", "isModified"]),
    ],
)
data class GradeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountKey: String,
    val semester: String,
    val courseName: String,
    val courseCode: String,
    val credit: Double,
    val scoreText: String,
    val scoreValue: Double?,
    val gradePoint: Double?,
    val attribute: String,
    val examType: String,
    val schoolRecordId: String,
    val rowOrder: Int,
    val isNew: Boolean,
    val isModified: Boolean,
    val previousScoreText: String,
    val updateVersion: Int,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "") val rawCreditText: String = "",
    @ColumnInfo(defaultValue = "VALID") val creditStatus: String = "VALID",
    @ColumnInfo(defaultValue = "") val rawCourseAttribute: String = "",
    @ColumnInfo(defaultValue = "") val rawCourseType: String = "",
    @ColumnInfo(defaultValue = "") val rawGeneralElectiveType: String = "",
    @ColumnInfo(defaultValue = "1") val rawAttributesKnown: Boolean = true,
)
