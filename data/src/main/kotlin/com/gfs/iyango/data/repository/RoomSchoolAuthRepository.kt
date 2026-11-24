package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.CreditProgressPort
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.NoopCreditProgressPort
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.SchoolCredential
import com.gfs.iyango.core.domain.SchoolCredentialStore
import com.gfs.iyango.core.domain.SessionExpiredException
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.SchoolLoginResult
import com.gfs.iyango.core.model.SchoolProfile
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SchoolConnectionEntity
import com.gfs.iyango.data.school.ygu.YguDirectLogin
import com.gfs.iyango.data.school.ygu.YguLoginOutcome
import com.gfs.iyango.data.school.ygu.YguProfile
import com.gfs.iyango.data.school.ygu.YguProfileRead
import com.gfs.iyango.data.school.ygu.YguSessionClient
import com.gfs.iyango.data.school.ygu.YguSessionState
import java.security.MessageDigest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SchoolAuthPort] 的 Room + Keystore 实现。
 *
 * - 连接 id 由规范化账号的哈希派生，重复登录复用同一条连接，已绑定的课表不丢；
 * - 密码与 Cookie 只写 [SchoolCredentialStore]（Keystore 加密），Room 只存
 *   `credentialRef` 与脱敏展示字段；
 * - 登录成功/退出时递增 `sessionRevision`，让切换账号前发出的旧响应失效。
 */
@Singleton
class RoomSchoolAuthRepository @Inject constructor(
    private val db: IYangoDatabase,
    private val credentialStore: SchoolCredentialStore,
    private val gradeRepository: GradeRepositoryPort,
    private val refreshCoordinator: RefreshCoordinatorPort,
    private val clock: Clock,
    private val creditProgressRepository: CreditProgressPort = NoopCreditProgressPort,
) : SchoolAuthPort {

    private val loginMutex = Mutex()
    private val renewalMutex = Mutex()

    /**
     * 测试缝：默认走真实网络客户端，单测注入桩实现以驱动登录/恢复的激活分支。
     * 仅 data 模块内部可见，生产代码不得赋值。
     */
    internal var directLogin: suspend (String, String) -> YguLoginOutcome =
        { account, password -> YguDirectLogin.login(account, password) }
    internal var verifySession: suspend (String) -> YguSessionState =
        { cookieHeader -> YguSessionClient.verify(cookieHeader) }
    internal var fetchProfileRead: suspend (String) -> YguProfileRead =
        { cookieHeader -> YguProfile.fetchProfile(cookieHeader) }

    companion object {
        /** 记住密码状态下的静默自动续期窗口：7 天内 Cookie 过期自动重登，超过 7 天要求手动登录。 */
        const val AUTO_RELOGIN_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000L
    }

    /**
     * 当学校服务端短效 Cookie 过期时，若账号开启了记住密码且距上次登录未超过 7 天，
     * 静默使用保存的凭据重新登录并返回新凭据；不满足条件或重登失败时返回 null。
     */
    internal suspend fun trySilentRelogin(
        expected: SchoolConnectionEntity,
        previous: SchoolCredential,
    ): SchoolCredential? = renewalMutex.withLock {
        val credential = db.withTransaction {
            val current = db.currentSchoolSession(expected) ?: return@withTransaction null
            val stored = credentialStore.read(current.id) ?: return@withTransaction null
            stored.takeIf { it != previous || canAutoRelogin(current) }
        } ?: return@withLock null
        // A parallel request may already have renewed the same session.
        if (credential != previous) return@withLock credential.takeIf { it.cookieHeader.isNotEmpty() }
        if (credential.account.isBlank() || credential.password.isEmpty()) return@withLock null
        val normalized = YguDirectLogin.normalizeHalfWidth(credential.account.trim())
        val outcome = directLogin(normalized, credential.password)
        if (outcome !is YguLoginOutcome.Success || outcome.cookieHeader.isEmpty()) return@withLock null
        db.withTransaction {
            val current = db.currentSchoolSession(expected) ?: return@withTransaction null
            if (!current.rememberPassword) return@withTransaction null
            val stored = credentialStore.read(current.id) ?: return@withTransaction null
            if (stored != credential) return@withTransaction stored.takeIf { it.cookieHeader.isNotEmpty() }
            val renewed = stored.copy(cookieHeader = outcome.cookieHeader)
            credentialStore.write(current.id, renewed)
            db.schoolConnectionDao().updateStatus(current.id, SchoolConnectionStatus.ACTIVE, "")
            renewed
        }
    }

    private fun canAutoRelogin(entity: SchoolConnectionEntity): Boolean {
        if (!entity.rememberPassword) return false
        if (entity.status == SchoolConnectionStatus.LOGGED_OUT) return false
        if (entity.lastLoginAt <= 0L) return false
        val elapsed = clock.millis() - entity.lastLoginAt
        return elapsed in 0..AUTO_RELOGIN_WINDOW_MILLIS
    }

    override fun observeConnections(): Flow<List<SchoolConnection>> =
        db.schoolConnectionDao().observeAll().map { rows -> rows.map { it.toModel() } }

    override fun observeActiveConnection(): Flow<SchoolConnection?> =
        db.schoolConnectionDao().observeActive().map { it?.toModel() }

    override suspend fun getConnection(connectionId: String): SchoolConnection? =
        db.schoolConnectionDao().getById(connectionId)?.toModel()

    override suspend fun historyAccount(connectionId: String): String? {
        val connection = db.schoolConnectionDao().getById(connectionId) ?: return null
        val account = credentialStore.read(connectionId)?.account?.trim().orEmpty()
        return account.ifEmpty { connection.accountSubject }.ifEmpty { null }
    }

    override suspend fun login(
        account: String,
        password: String,
        rememberPassword: Boolean,
    ): SchoolLoginResult = loginMutex.withLock {
        loginLocked(account, password, rememberPassword)
    }

    private suspend fun loginLocked(
        account: String,
        password: String,
        rememberPassword: Boolean,
    ): SchoolLoginResult {
        val normalized = YguDirectLogin.normalizeHalfWidth(account.trim())
        if (normalized.isEmpty() || password.isEmpty()) {
            return SchoolLoginResult.Failed("请输入教务账号和密码")
        }
        val connectionId = connectionIdOf(normalized)
        val existing = db.schoolConnectionDao().getById(connectionId)
        val outcome = directLogin(normalized, password)
        if (outcome !is YguLoginOutcome.Success) {
            val message = when (outcome) {
                is YguLoginOutcome.Rejected -> outcome.message
                is YguLoginOutcome.Failed -> outcome.message
                else -> "登录失败，请稍后重试"
            }
            if (existing != null) {
                recordManualFailure(existing, message)
            }
            return if (outcome is YguLoginOutcome.Rejected) {
                SchoolLoginResult.Rejected(message)
            } else {
                SchoolLoginResult.Failed(message)
            }
        }

        val profileRead = fetchProfileRead(outcome.cookieHeader)
        if (profileRead.expired) {
            return SchoolLoginResult.Failed(profileRead.message.ifEmpty { "登录状态已失效，请重新登录" })
        }
        val profile = profileRead.profile
        val subject = profile.studentId.ifEmpty { normalized }
        val now = clock.millis()
        val revision = (existing?.sessionRevision ?: 0) + 1
        val cookieHeader = profileRead.cookieHeader.ifEmpty { outcome.cookieHeader }
        val entity = SchoolConnectionEntity(
            id = connectionId,
            schoolId = "YGU",
            accountSubject = subject,
            maskedAccount = maskAccount(normalized),
            sessionRevision = revision,
            status = SchoolConnectionStatus.ACTIVE,
            credentialRef = connectionId,
            rememberPassword = rememberPassword,
            createdAt = existing?.createdAt ?: now,
            lastLoginAt = now,
            lastError = "",
            displayName = profile.name,
            studentNo = profile.studentId,
            college = "",
            major = "",
            className = profile.className,
            gender = "",
            avatarUrl = "",
        )
        db.withTransaction {
            credentialStore.write(
                connectionId,
                SchoolCredential(
                    account = normalized,
                    password = if (rememberPassword) password else "",
                    cookieHeader = cookieHeader,
                ),
            )
            db.schoolConnectionDao().upsert(entity)
            // 激活与去激活同事务：进程任意时刻至多一个 ACTIVE 账号。
            db.schoolConnectionDao().deactivateOthers(connectionId)
        }
        val resolvedLoginSubject = entity.accountSubject.ifEmpty { entity.id }
        creditProgressRepository.migrateAccountIdentity(
            fromAccountKey = connectionId,
            toAccountKey = resolvedLoginSubject,
            schoolId = "YGU",
        )
        refreshCoordinator.onSessionChanged()
        return SchoolLoginResult.Success(entity.toModel())
    }

    override suspend fun restoreSession(connectionId: String): SchoolLoginResult =
        loginMutex.withLock { restoreLocked(connectionId) }

    private suspend fun restoreLocked(connectionId: String): SchoolLoginResult {
        val existing = db.schoolConnectionDao().getById(connectionId)
            ?: return SchoolLoginResult.Failed("未找到该教务账号，请重新登录")
        val credential = credentialStore.read(connectionId)
        val canRelogin = credential != null && credential.account.isNotEmpty() &&
            credential.password.isNotEmpty()
        if (credential == null || credential.cookieHeader.isEmpty()) {
            if (canRelogin) {
                return loginLocked(credential.account, credential.password, existing.rememberPassword)
            }
            markExpired(existing)
            return SchoolLoginResult.Failed("登录状态已失效，请重新登录")
        }
        return when (val state = verifySession(credential.cookieHeader)) {
            is YguSessionState.Active -> {
                val profileRead = fetchProfileRead(state.cookieHeader)
                if (profileRead.expired) {
                    return SchoolLoginResult.Failed(profileRead.message.ifEmpty { "登录状态已失效，请重新登录" })
                }
                val profile = profileRead.profile
                val now = clock.millis()
                val entity = existing.copy(
                    accountSubject = profile.studentId.ifEmpty { existing.accountSubject },
                    status = SchoolConnectionStatus.ACTIVE,
                    sessionRevision = existing.sessionRevision + 1,
                    lastLoginAt = now,
                    lastError = "",
                    displayName = profile.name.ifEmpty { existing.displayName },
                    studentNo = profile.studentId.ifEmpty { existing.studentNo },
                    className = profile.className.ifEmpty { existing.className },
                )
                db.withTransaction {
                    val cookieHeader = profileRead.cookieHeader.ifEmpty { state.cookieHeader }
                    credentialStore.write(connectionId, credential.copy(cookieHeader = cookieHeader))
                    db.schoolConnectionDao().upsert(entity)
                    // 与登录路径同款约束：恢复会话激活前先下线其余账号，且同事务落库。
                    db.schoolConnectionDao().deactivateOthers(connectionId)
                }
                val resolvedRestoreSubject = entity.accountSubject.ifEmpty { entity.id }
                creditProgressRepository.migrateAccountIdentity(
                    fromAccountKey = connectionId,
                    toAccountKey = resolvedRestoreSubject,
                    schoolId = "YGU",
                )
                refreshCoordinator.onSessionChanged()
                SchoolLoginResult.Success(entity.toModel())
            }
            is YguSessionState.Expired -> {
                if (canRelogin) {
                    // 只重试一次；失败即停在 EXPIRED，交给用户手动重登，避免循环。
                    return loginLocked(credential.account, credential.password, existing.rememberPassword)
                }
                markExpired(existing)
                SchoolLoginResult.Failed("登录状态已失效，请重新登录")
            }
            is YguSessionState.Failed -> {
                recordManualFailure(existing, state.message)
                SchoolLoginResult.Failed(state.message)
            }
        }
    }

    private suspend fun recordManualFailure(existing: SchoolConnectionEntity, message: String) {
        val status = if (existing.status == SchoolConnectionStatus.LOGGED_OUT) existing.status else SchoolConnectionStatus.FAILED
        db.schoolConnectionDao().updateStatus(existing.id, status, message)
    }

    private suspend fun markExpired(existing: SchoolConnectionEntity) = db.withTransaction {
        if (existing.status == SchoolConnectionStatus.LOGGED_OUT) return@withTransaction
        val credential = credentialStore.read(existing.id)
        if (credential != null) {
            credentialStore.write(existing.id, credential.copy(cookieHeader = ""))
        }
        db.schoolConnectionDao().updateStatus(existing.id, SchoolConnectionStatus.EXPIRED, "登录状态已失效，请重新登录")
    }

    override suspend fun fetchProfile(connectionId: String): Result<SchoolProfile> {
        val existing = db.schoolConnectionDao().getById(connectionId)
            ?: return Result.failure(IllegalStateException("未找到该教务账号"))
        val session = SchoolSessionRequest.open(db, credentialStore, this, existing)
            ?: return Result.failure(SessionExpiredException("登录状态已失效，请重新登录"))
        var read = fetchProfileRead(session.credential.cookieHeader)
        if (read.expired) {
            session.renew()?.let { read = fetchProfileRead(it.cookieHeader) }
        }
        if (read.expired) {
            session.expire()
            return Result.failure(SessionExpiredException("登录状态已失效，请重新登录"))
        }
        if (!read.authenticated || (read.profile.name.isEmpty() && read.profile.studentId.isEmpty())) {
            session.finish(read.cookieHeader)
            return Result.failure(IllegalStateException(read.message.ifEmpty { "个人信息暂未获取，请稍后重试" }))
        }
        val profile = SchoolProfile(
            displayName = read.profile.name,
            studentNo = read.profile.studentId,
            className = read.profile.className,
        )
        val accepted = session.finish(read.cookieHeader) { latest ->
            val previousSubject = latest.accountSubject
            val resolvedSubject = profile.studentNo.ifEmpty { latest.accountSubject }
            db.schoolConnectionDao().upsert(
                latest.copy(
                    accountSubject = resolvedSubject,
                    status = SchoolConnectionStatus.ACTIVE,
                    lastError = "",
                    displayName = profile.displayName,
                    studentNo = profile.studentNo,
                    className = profile.className,
                ),
            )
            val finalKey = resolvedSubject.ifEmpty { connectionId }
            creditProgressRepository.migrateAccountIdentity(
                fromAccountKey = connectionId,
                toAccountKey = finalKey,
                schoolId = "YGU",
            )
            if (previousSubject.isNotBlank() && previousSubject != finalKey) {
                creditProgressRepository.migrateAccountIdentity(
                    fromAccountKey = previousSubject,
                    toAccountKey = finalKey,
                    schoolId = "YGU",
                )
            }
        }
        if (!accepted) return Result.failure(SessionExpiredException("登录状态已失效，请重新登录"))
        return Result.success(profile)
    }

    override suspend fun logout(connectionId: String, clearSavedPassword: Boolean) = loginMutex.withLock {
        val existing = db.withTransaction {
            val existing = db.schoolConnectionDao().getById(connectionId) ?: return@withTransaction null
            val credential = credentialStore.read(connectionId)
            if (credential != null) {
                credentialStore.write(
                    connectionId,
                    credential.copy(
                        password = if (clearSavedPassword) "" else credential.password,
                        cookieHeader = "",
                    ),
                )
            }
            db.schoolConnectionDao().upsert(
                existing.copy(
                    status = SchoolConnectionStatus.LOGGED_OUT,
                    sessionRevision = existing.sessionRevision + 1,
                    lastError = "",
                    rememberPassword = if (clearSavedPassword) false else existing.rememberPassword,
                ),
            )
            existing
        } ?: return@withLock
        // E03：主动退出清除该账号成绩缓存；服务端会话过期则保留缓存供离线查看。
        gradeRepository.deleteAccount(
            existing.accountSubject.ifEmpty { existing.id },
        )
        refreshCoordinator.onSessionChanged()
    }

    override suspend fun deleteConnection(connectionId: String) = loginMutex.withLock {
        val existing = db.withTransaction {
            val row = db.schoolConnectionDao().getById(connectionId)
            credentialStore.clear(connectionId)
            db.schoolConnectionDao().deleteById(connectionId)
            row
        }
        if (existing != null) {
            val subjectKey = existing.accountSubject.ifEmpty { existing.id }
            gradeRepository.deleteAccount(subjectKey)
            creditProgressRepository.deleteAccount(subjectKey)
            if (existing.id.isNotBlank() && existing.id != subjectKey) {
                creditProgressRepository.deleteAccount(existing.id)
            }
        }
        refreshCoordinator.onSessionChanged()
    }

    override suspend fun clearHistory(keepConnectionId: String) = loginMutex.withLock {
        val rows = db.schoolConnectionDao().getAll()
        val removed = rows.filter { it.id != keepConnectionId }
        if (removed.isEmpty()) return@withLock
        // 连接行的删除在单个事务内完成；凭据与成绩缓存随后清理，失败不影响账号删除。
        db.withTransaction {
            removed.forEach { db.schoolConnectionDao().deleteById(it.id) }
        }
        removed.forEach { connection ->
            runCatching { credentialStore.clear(connection.id) }
            runCatching {
                val subjectKey = connection.accountSubject.ifEmpty { connection.id }
                gradeRepository.deleteAccount(subjectKey)
                creditProgressRepository.deleteAccount(subjectKey)
                if (connection.id.isNotBlank() && connection.id != subjectKey) {
                    creditProgressRepository.deleteAccount(connection.id)
                }
            }
        }
        refreshCoordinator.onSessionChanged()
    }

    private fun maskAccount(account: String): String = when {
        account.length <= 2 -> "**"
        account.length <= 6 -> account.first() + "****" + account.last()
        else -> account.take(3) + "****" + account.takeLast(3)
    }

    private fun connectionIdOf(account: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(account.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "ygu-" + hex.take(32)
    }

    private fun SchoolConnectionEntity.toModel(): SchoolConnection = SchoolConnection(
        id = id,
        schoolId = schoolId,
        accountSubject = accountSubject,
        maskedAccount = maskedAccount,
        sessionRevision = sessionRevision,
        status = status,
        credentialRef = credentialRef,
        rememberPassword = rememberPassword,
        createdAt = createdAt,
        lastLoginAt = lastLoginAt,
        lastError = lastError,
        profile = SchoolProfile(
            displayName = displayName,
            studentNo = studentNo,
            college = college,
            major = major,
            className = className,
            gender = gender,
            avatarUrl = avatarUrl,
        ),
    )
}
