package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 学分进度计划、分类与消歧规则 DAO。 */
@Dao
interface CreditProgressDao {

    @Query("SELECT * FROM credit_plan WHERE accountKey = :accountKey LIMIT 1")
    fun observePlan(accountKey: String): Flow<CreditPlanEntity?>

    @Query("SELECT * FROM credit_plan WHERE accountKey = :accountKey LIMIT 1")
    suspend fun getPlan(accountKey: String): CreditPlanEntity?

    @Query("SELECT * FROM credit_plan")
    suspend fun getAllPlans(): List<CreditPlanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlan(entity: CreditPlanEntity)

    @Query("DELETE FROM credit_plan WHERE accountKey = :accountKey")
    suspend fun deletePlan(accountKey: String)

    @Query("SELECT * FROM credit_category_plan WHERE accountKey = :accountKey ORDER BY sortOrder ASC, categoryId ASC")
    fun observeCategories(accountKey: String): Flow<List<CreditCategoryPlanEntity>>

    @Query("SELECT * FROM credit_category_plan WHERE accountKey = :accountKey ORDER BY sortOrder ASC, categoryId ASC")
    suspend fun getCategories(accountKey: String): List<CreditCategoryPlanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(entity: CreditCategoryPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(entities: List<CreditCategoryPlanEntity>)

    @Query("DELETE FROM credit_category_plan WHERE accountKey = :accountKey AND categoryId = :categoryId")
    suspend fun deleteCategory(accountKey: String, categoryId: String)

    @Query("DELETE FROM credit_category_plan WHERE accountKey = :accountKey")
    suspend fun deleteAllCategories(accountKey: String)

    @Query("SELECT * FROM credit_resolution_rule WHERE accountKey = :accountKey ORDER BY updatedAtEpochMillis ASC, ruleKey ASC")
    fun observeRules(accountKey: String): Flow<List<CreditResolutionRuleEntity>>

    @Query("SELECT * FROM credit_resolution_rule WHERE accountKey = :accountKey ORDER BY updatedAtEpochMillis ASC, ruleKey ASC")
    suspend fun getRules(accountKey: String): List<CreditResolutionRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(entity: CreditResolutionRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRules(entities: List<CreditResolutionRuleEntity>)

    @Query("DELETE FROM credit_resolution_rule WHERE accountKey = :accountKey AND ruleKey = :ruleKey")
    suspend fun deleteRule(accountKey: String, ruleKey: String)

    @Query("DELETE FROM credit_resolution_rule WHERE accountKey = :accountKey")
    suspend fun deleteAllRules(accountKey: String)

    @Query("UPDATE credit_resolution_rule SET requiresPostRestoreVerification = 0 WHERE accountKey = :accountKey AND ruleKey = :ruleKey")
    suspend fun clearPostRestoreVerification(accountKey: String, ruleKey: String)
}
