package com.gfs.iyango.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gfs.iyango.core.model.SchoolConnectionStatus

/**
 * 学校连接（账号）持久化行。
 *
 * 安全约束：本表**不存密码与 Cookie 明文**；[credentialRef] 只指向 Keystore
 * 加密后的凭据记录 id，凭据本身由平台层管理。
 */
@Entity(
    tableName = "school_connection",
    indices = [
        Index(value = ["accountSubject"]),
        Index(value = ["status"]),
    ],
)
data class SchoolConnectionEntity(
    @PrimaryKey val id: String,
    val schoolId: String,
    val accountSubject: String,
    val maskedAccount: String,
    val sessionRevision: Int,
    val status: SchoolConnectionStatus,
    val credentialRef: String,
    val rememberPassword: Boolean,
    val createdAt: Long,
    val lastLoginAt: Long,
    val lastError: String,
    val displayName: String,
    val studentNo: String,
    val college: String,
    val major: String,
    val className: String,
    val gender: String,
    val avatarUrl: String,
)
