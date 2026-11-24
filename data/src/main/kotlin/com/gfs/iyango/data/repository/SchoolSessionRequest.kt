package com.gfs.iyango.data.repository

import androidx.room.withTransaction
import com.gfs.iyango.core.domain.SchoolCredential
import com.gfs.iyango.core.domain.SchoolCredentialStore
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SchoolConnectionEntity

/** One school request owns its starting session and can renew it at most once. */
internal class SchoolSessionRequest private constructor(
    private val db: IYangoDatabase,
    private val store: SchoolCredentialStore,
    private val auth: RoomSchoolAuthRepository?,
    private val connection: SchoolConnectionEntity,
    credential: SchoolCredential,
) {
    var credential = credential
        private set
    private var attemptedRenewal = false

    suspend fun renew(): SchoolCredential? {
        if (attemptedRenewal) return null
        attemptedRenewal = true
        val renewed = auth?.trySilentRelogin(connection, credential) ?: return null
        credential = renewed
        return renewed
    }

    suspend fun expire() {
        db.withTransaction {
            val current = db.currentSchoolSession(connection) ?: return@withTransaction
            if (store.read(current.id) != credential) return@withTransaction
            store.write(current.id, credential.copy(cookieHeader = ""))
            db.schoolConnectionDao().updateStatus(current.id, SchoolConnectionStatus.EXPIRED, EXPIRED_MESSAGE)
        }
    }

    /** Check the session and publish the response together with any updated Cookie. */
    suspend fun finish(
        cookieHeader: String,
        publish: suspend (SchoolConnectionEntity) -> Unit = {},
    ): Boolean = db.withTransaction {
        val current = db.currentSchoolSession(connection) ?: return@withTransaction false
        val stored = store.read(current.id) ?: return@withTransaction false
        if (stored.cookieHeader.isEmpty()) return@withTransaction false
        if (stored == credential && cookieHeader.isNotEmpty() && cookieHeader != stored.cookieHeader) {
            credential = stored.copy(cookieHeader = cookieHeader)
            store.write(current.id, credential)
        } else {
            credential = stored
        }
        publish(current)
        true
    }

    companion object {
        private const val EXPIRED_MESSAGE = "登录状态已失效，请重新登录"

        suspend fun open(
            db: IYangoDatabase,
            store: SchoolCredentialStore,
            auth: RoomSchoolAuthRepository?,
            connection: SchoolConnectionEntity,
        ): SchoolSessionRequest? {
            val session = db.withTransaction {
                val current = db.currentSchoolSession(connection) ?: return@withTransaction null
                val credential = store.read(current.id) ?: run {
                    db.schoolConnectionDao().updateStatus(current.id, SchoolConnectionStatus.EXPIRED, EXPIRED_MESSAGE)
                    return@withTransaction null
                }
                SchoolSessionRequest(db, store, auth, current, credential)
            } ?: return null
            if (session.credential.cookieHeader.isEmpty() && session.renew() == null) {
                session.expire()
                return null
            }
            return session
        }
    }
}

/** Call inside the transaction that reads or writes the associated credentials. */
internal suspend fun IYangoDatabase.currentSchoolSession(expected: SchoolConnectionEntity): SchoolConnectionEntity? {
    val current = schoolConnectionDao().getById(expected.id) ?: return null
    if (current.status == SchoolConnectionStatus.LOGGED_OUT || current.sessionRevision != expected.sessionRevision ||
        current.createdAt != expected.createdAt) return null
    val active = schoolConnectionDao().getActive()
    return current.takeIf { active == null || active.id == current.id }
}
