package com.gfs.iyango.core.domain

/**
 * 一条教务凭据。
 *
 * 安全边界：整条记录只允许经 Keystore 加密后落盘；[password] 仅在用户选择
 * “记住密码”时非空，[cookieHeader] 是会话凭据，任何时候都不得进入 Room、
 * DataStore、日志或页面状态。
 */
data class SchoolCredential(
    val account: String = "",
    val password: String = "",
    val cookieHeader: String = "",
)

/** 教务凭据的加密存储端口（实现方使用 Android Keystore）。 */
interface SchoolCredentialStore {

    suspend fun read(credentialRef: String): SchoolCredential?

    suspend fun write(credentialRef: String, credential: SchoolCredential)

    suspend fun clear(credentialRef: String)
}
