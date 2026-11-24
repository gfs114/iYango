package com.gfs.iyango.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 单个账号的学分总目标与计划配置（与成绩缓存解耦，退出登录保留）。 */
@Entity(tableName = "credit_plan")
data class CreditPlanEntity(
    @PrimaryKey val accountKey: String,
    @ColumnInfo(defaultValue = "YGU") val schoolId: String = "YGU",
    val totalTargetCreditsText: String? = null,
    @ColumnInfo(defaultValue = "") val userNote: String = "",
    @ColumnInfo(defaultValue = "") val curriculumSnapshotJson: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAtEpochMillis: Long = 0L,
)

/** 用户可编辑的学分分类配额与原始属性映射表。 */
@Entity(
    tableName = "credit_category_plan",
    primaryKeys = ["accountKey", "categoryId"],
    indices = [Index(value = ["accountKey"])],
)
data class CreditCategoryPlanEntity(
    val accountKey: String,
    val categoryId: String,
    val displayName: String,
    val targetCreditsText: String? = null,
    @ColumnInfo(defaultValue = "[]") val mappedRawAttributesJson: String = "[]",
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)

/** 人工消歧确认规则表：合并、拆分、排除或手动认定通过。 */
@Entity(
    tableName = "credit_resolution_rule",
    primaryKeys = ["accountKey", "ruleKey"],
    indices = [Index(value = ["accountKey"])],
)
data class CreditResolutionRuleEntity(
    val accountKey: String,
    val ruleKey: String,
    val action: String,
    val assignedCategoryId: String? = null,
    val chosenAnchorKey: String? = null,
    @ColumnInfo(defaultValue = "[]") val splitAssignmentsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val expectedFingerprintsJson: String = "[]",
    @ColumnInfo(defaultValue = "0") val requiresPostRestoreVerification: Boolean = false,
    @ColumnInfo(defaultValue = "0") val manualPassConfirmed: Boolean = false,
    @ColumnInfo(defaultValue = "") val note: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAtEpochMillis: Long = 0L,
)
