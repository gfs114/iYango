package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.RefreshHandler
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.SessionExpiredException
import com.gfs.iyango.core.model.SchoolConnectionStatus
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * F05 个人资料刷新：用当前 ACTIVE 账号重新拉取姓名/学号/班级并回写连接行。
 *
 * 无已激活账号时按会话失效处理（与鸿蒙"请先登录"语义一致），由协调器标记
 * sessionExpired，UI 据此引导重新登录；24 小时内已有完整资料时跳过重复网络请求。
 */
@Singleton
class ProfileRefreshHandler @Inject constructor(
    private val auth: SchoolAuthPort,
    private val clock: Clock,
) : RefreshHandler {

    override val resource: RefreshResource = RefreshResource.PROFILE

    private val lastSyncedAt = ConcurrentHashMap<String, Long>()

    override suspend fun refresh(queryFingerprint: String) {
        val active = auth.observeActiveConnection().first()
        val connection = active
            ?: auth.observeConnections().first()
                .firstOrNull { it.status != SchoolConnectionStatus.LOGGED_OUT && it.rememberPassword }
            ?: throw SessionExpiredException("请先登录阳光学院教务系统")

        val profileComplete = connection.profile.displayName.isNotBlank() &&
            connection.profile.studentNo.isNotBlank() &&
            connection.profile.className.isNotBlank()
        val cacheKey = "${connection.id}:${connection.sessionRevision}"
        val freshAt = lastSyncedAt[cacheKey] ?: connection.lastLoginAt
        val withinTtl = freshAt > 0L && (clock.millis() - freshAt) in 0L..PROFILE_TTL_MILLIS
        if (connection.status == SchoolConnectionStatus.ACTIVE && profileComplete && withinTtl) {
            return
        }

        // 失败原因沿用 fetchProfile 的消息（含 SessionExpiredException 标记的会话失效）。
        auth.fetchProfile(connection.id).getOrThrow()
        lastSyncedAt[cacheKey] = clock.millis()
    }

    private companion object {
        const val PROFILE_TTL_MILLIS = 24L * 60L * 60L * 1000L
    }
}
