package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 成绩快照 DAO。 */
@Dao
interface GradeSnapshotDao {

    @Query("SELECT * FROM grade_snapshot WHERE accountKey = :accountKey AND semester = :semester LIMIT 1")
    fun observe(accountKey: String, semester: String): Flow<GradeSnapshotEntity?>

    @Query("SELECT * FROM grade_snapshot WHERE accountKey = :accountKey AND semester = :semester LIMIT 1")
    suspend fun get(accountKey: String, semester: String): GradeSnapshotEntity?

    @Query("SELECT * FROM grade_snapshot WHERE accountKey = :accountKey ORDER BY fetchedAt DESC")
    fun observeAllForAccount(accountKey: String): Flow<List<GradeSnapshotEntity>>

    @Query("SELECT * FROM grade_snapshot WHERE accountKey = :accountKey ORDER BY fetchedAt DESC")
    suspend fun getAllForAccount(accountKey: String): List<GradeSnapshotEntity>

    @Query("UPDATE grade_snapshot SET invalidatedByPartialUpdate = 1 WHERE accountKey = :accountKey AND semester = '*'")
    suspend fun markAllSemestersInvalidatedByPartialUpdate(accountKey: String)

    @Query("SELECT semester FROM grade_snapshot WHERE accountKey = :accountKey ORDER BY fetchedAt DESC")
    fun observeSemesters(accountKey: String): Flow<List<String>>

    @Query("DELETE FROM grade_snapshot WHERE accountKey = :accountKey AND semester = :semester")
    suspend fun delete(accountKey: String, semester: String)

    @Query("DELETE FROM grade_snapshot WHERE accountKey = :accountKey")
    suspend fun deleteAccount(accountKey: String)

    @Insert
    suspend fun insert(entity: GradeSnapshotEntity): Long
}

/** 成绩记录 DAO。 */
@Dao
interface GradeRecordDao {

    @Query(
        "SELECT * FROM grade_record WHERE accountKey = :accountKey AND semester = :semester " +
            "ORDER BY rowOrder ASC, id ASC",
    )
    fun observe(accountKey: String, semester: String): Flow<List<GradeRecordEntity>>

    @Query(
        "SELECT * FROM grade_record WHERE accountKey = :accountKey " +
            "ORDER BY rowOrder ASC, id ASC",
    )
    fun observeAllForAccount(accountKey: String): Flow<List<GradeRecordEntity>>

    @Query(
        "SELECT * FROM grade_record WHERE accountKey = :accountKey AND semester = :semester " +
            "ORDER BY rowOrder ASC, id ASC",
    )
    suspend fun getAll(accountKey: String, semester: String): List<GradeRecordEntity>

    @Query("SELECT COUNT(*) FROM grade_record WHERE accountKey = :accountKey AND (isNew = 1 OR isModified = 1)")
    fun observeUnreadCount(accountKey: String): Flow<Int>

    @Query(
        "UPDATE grade_record SET isNew = 0, isModified = 0 " +
            "WHERE accountKey = :accountKey AND (" +
            "semester = :semester OR " +
            "(semester = '*' AND schoolRecordId LIKE 'ygu:' || :semester || '|%')" +
            ")",
    )
    suspend fun markChangesRead(accountKey: String, semester: String)

    @Query("DELETE FROM grade_record WHERE accountKey = :accountKey AND semester = :semester")
    suspend fun delete(accountKey: String, semester: String)

    @Query("DELETE FROM grade_record WHERE accountKey = :accountKey")
    suspend fun deleteAccount(accountKey: String)

    @Insert
    suspend fun insertAll(entities: List<GradeRecordEntity>)
}
