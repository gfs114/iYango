package com.gfs.iyango.core.model

/**
 * 学校连接状态。
 *
 * - [LOGGED_OUT]：从未登录或用户主动退出；
 * - [VERIFYING]：正在提交凭据；
 * - [ACTIVE]：会话可用；
 * - [EXPIRED]：服务端会话失效，需要重新登录；
 * - [FAILED]：最近一次登录/读取失败。
 */
enum class SchoolConnectionStatus {
    LOGGED_OUT,
    VERIFYING,
    ACTIVE,
    EXPIRED,
    FAILED,
}

/**
 * 脱敏后的个人资料。
 *
 * 只保存展示所需的字段；密码、Cookie、完整学号照片等内容不得进入本模型。
 */
data class SchoolProfile(
    val displayName: String = "",
    val studentNo: String = "",
    val college: String = "",
    val major: String = "",
    val className: String = "",
    val gender: String = "",
    val avatarUrl: String = "",
)

/**
 * 一条学校连接（账号）。
 *
 * 约束：
 * - [id] 是稳定连接 id，与 `Timetable.connectionId` 对应；
 * - [accountSubject] 是**已验证**的账号主体（通常是学号/工号），用于成绩缓存分区；
 * - [sessionRevision] 在登录、切换账号、退出时递增，旧响应不得提交；
 * - [credentialRef] 指向 Keystore 加密后的凭据记录，模型本身不携带密文；
 * - [maskedAccount] 仅用于历史账号列表展示。
 */
data class SchoolConnection(
    val id: String = "",
    val schoolId: String = "YGU",
    val accountSubject: String = "",
    val maskedAccount: String = "",
    val sessionRevision: Int = 0,
    val status: SchoolConnectionStatus = SchoolConnectionStatus.LOGGED_OUT,
    val credentialRef: String = "",
    val rememberPassword: Boolean = false,
    val createdAt: Long = 0,
    val lastLoginAt: Long = 0,
    val lastError: String = "",
    val profile: SchoolProfile = SchoolProfile(),
)

/** 账号历史条目（用于登录页快速选择已登录过的账号）。 */
data class AccountHistoryEntry(
    val connectionId: String,
    val maskedAccount: String,
    val displayName: String,
    val lastLoginAt: Long,
    val hasSavedPassword: Boolean,
)
