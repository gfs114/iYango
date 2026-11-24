package com.gfs.iyango.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gfs.iyango.core.model.ExamSource
import com.gfs.iyango.core.model.ExamStatus

/** 考试/考试类个人安排。 */
@Entity(
    tableName = "exam",
    indices = [
        Index(value = ["date"]),
        Index(value = ["connectionId", "semester"]),
    ],
)
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: ExamSource,
    val connectionId: String,
    val semester: String,
    val subject: String,
    val type: String,
    val date: String,
    val endDate: String,
    val startTime: String,
    val endTime: String,
    val timeZone: String,
    val location: String,
    val seat: String,
    val note: String,
    val status: ExamStatus,
    val sourceExamId: String,
    val updatedAt: Long,
)
