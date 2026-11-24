package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.ClassroomRepositoryPort
import com.gfs.iyango.core.domain.SchoolCredentialStore
import com.gfs.iyango.core.model.ClassroomBuilding
import com.gfs.iyango.core.model.ClassroomDetail
import com.gfs.iyango.core.model.ClassroomDetailRequest
import com.gfs.iyango.core.model.ClassroomMetadata
import com.gfs.iyango.core.model.ClassroomQuery
import com.gfs.iyango.core.model.ClassroomResult
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.school.ygu.YguClassroomClient
import com.gfs.iyango.data.school.ygu.YguClassroomClientStatus
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ClassroomRepositoryPort] 的阳光学院实现。
 *
 * - metadata 每次进入页面重新读取；教学楼按「连接 + 校区」在内存中缓存，
 *   切校区不会复用另一校区的旧列表；
 * - 查询/详情互不共享请求键；详情由调用方按连接、学期/周、星期、教室和
 *   原始节次组生成键，仓库返回的结果也带同一键，供界面丢弃迟到响应；
 * - 每次请求后把续期 Cookie 写回 Keystore；登录失效时清空 Cookie、
 *   标记连接过期，并丢弃该连接的教学楼缓存。
 */
@Singleton
class RoomClassroomRepository @Inject constructor(
    private val db: IYangoDatabase,
    private val credentialStore: SchoolCredentialStore,
    private val clock: Clock,
    private val authRepository: RoomSchoolAuthRepository,
) : ClassroomRepositoryPort {

    private val buildingCache = ConcurrentHashMap<String, CachedBuildings>()

    override suspend fun metadata(connectionId: String): Result<ClassroomMetadata> {
        val session = access(connectionId)
            ?: return Result.failure(IllegalStateException(MESSAGE_LOGIN))
        var credential = session.credential
        var client = YguClassroomClient()
        var load = client.fetchMetadata(credential.cookieHeader)
        if (load.status == YguClassroomClientStatus.LOGIN) {
            val relogged = session.renew()
            if (relogged != null) {
                credential = relogged
                client = YguClassroomClient()
                load = client.fetchMetadata(credential.cookieHeader)
            }
        }
        if (load.status != YguClassroomClientStatus.LOGIN && !session.finish(load.cookieHeader)) {
            return Result.failure(IllegalStateException(MESSAGE_LOGIN))
        }
        return when (load.status) {
            YguClassroomClientStatus.OK -> Result.success(load.metadata)
            YguClassroomClientStatus.LOGIN -> {
                session.expire()
                Result.failure(IllegalStateException(load.message.ifEmpty { MESSAGE_LOGIN }))
            }
            else -> Result.failure(
                IllegalStateException(load.message.ifEmpty { MESSAGE_METADATA_UNAVAILABLE }),
            )
        }
    }

    override suspend fun buildings(
        connectionId: String,
        campusCode: String,
    ): Result<List<ClassroomBuilding>> {
        val key = "$connectionId|$campusCode"
        val cached = buildingCache[key]
        if (cached != null && clock.millis() - cached.at < BUILDING_CACHE_MS) {
            return Result.success(cached.buildings)
        }
        val session = access(connectionId)
            ?: return Result.failure(IllegalStateException(MESSAGE_LOGIN))
        var credential = session.credential
        var client = YguClassroomClient()
        var load = client.fetchBuildings(credential.cookieHeader, campusCode)
        if (load.status == YguClassroomClientStatus.LOGIN) {
            val relogged = session.renew()
            if (relogged != null) {
                credential = relogged
                client = YguClassroomClient()
                load = client.fetchBuildings(credential.cookieHeader, campusCode)
            }
        }
        if (load.status != YguClassroomClientStatus.LOGIN && !session.finish(load.cookieHeader)) {
            return Result.failure(IllegalStateException(MESSAGE_LOGIN))
        }
        return when (load.status) {
            YguClassroomClientStatus.OK -> {
                buildingCache[key] = CachedBuildings(load.buildings, clock.millis())
                Result.success(load.buildings)
            }
            YguClassroomClientStatus.LOGIN -> {
                clearBuildings(connectionId)
                session.expire()
                Result.failure(IllegalStateException(load.message.ifEmpty { MESSAGE_LOGIN }))
            }
            else -> Result.failure(
                IllegalStateException(load.message.ifEmpty { MESSAGE_BUILDINGS_UNAVAILABLE }),
            )
        }
    }

    override suspend fun query(
        connectionId: String,
        query: ClassroomQuery,
    ): Result<ClassroomResult> {
        queryProblem(query)?.let { return Result.failure(IllegalArgumentException(it)) }
        val session = access(connectionId)
            ?: return Result.failure(IllegalStateException(MESSAGE_LOGIN))
        var credential = session.credential
        var client = YguClassroomClient()
        var load = client.query(credential.cookieHeader, query)
        if (load.status == YguClassroomClientStatus.LOGIN) {
            val relogged = session.renew()
            if (relogged != null) {
                credential = relogged
                client = YguClassroomClient()
                load = client.query(credential.cookieHeader, query)
            }
        }
        if (load.status != YguClassroomClientStatus.LOGIN && !session.finish(load.cookieHeader)) {
            return Result.failure(IllegalStateException(MESSAGE_LOGIN))
        }
        return when (load.status) {
            YguClassroomClientStatus.OK, YguClassroomClientStatus.EMPTY -> {
                val result = load.result
                if (result == null) {
                    Result.failure(IllegalStateException(MESSAGE_QUERY_UNAVAILABLE))
                } else {
                    Result.success(result)
                }
            }
            YguClassroomClientStatus.LOGIN -> {
                clearBuildings(connectionId)
                session.expire()
                Result.failure(IllegalStateException(load.message.ifEmpty { MESSAGE_LOGIN }))
            }
            else -> Result.failure(
                IllegalStateException(load.message.ifEmpty { MESSAGE_QUERY_UNAVAILABLE }),
            )
        }
    }

    override suspend fun detail(
        connectionId: String,
        request: ClassroomDetailRequest,
    ): Result<ClassroomDetail> {
        detailProblem(request)?.let { return Result.failure(IllegalArgumentException(it)) }
        val session = access(connectionId)
            ?: return Result.failure(IllegalStateException(MESSAGE_LOGIN))
        var credential = session.credential
        var client = YguClassroomClient()
        var load = client.fetchCourseDetail(credential.cookieHeader, request)
        if (load.status == YguClassroomClientStatus.LOGIN) {
            val relogged = session.renew()
            if (relogged != null) {
                credential = relogged
                client = YguClassroomClient()
                load = client.fetchCourseDetail(credential.cookieHeader, request)
            }
        }
        if (load.status != YguClassroomClientStatus.LOGIN && !session.finish(load.cookieHeader)) {
            return Result.failure(IllegalStateException(MESSAGE_LOGIN))
        }
        return when (load.status) {
            YguClassroomClientStatus.OK, YguClassroomClientStatus.EMPTY -> Result.success(
                ClassroomDetail(key = request.key(connectionId), courses = load.courses),
            )
            YguClassroomClientStatus.LOGIN -> {
                clearBuildings(connectionId)
                session.expire()
                Result.failure(IllegalStateException(load.message.ifEmpty { MESSAGE_LOGIN }))
            }
            else -> Result.failure(
                IllegalStateException(load.message.ifEmpty { MESSAGE_DETAIL_UNAVAILABLE }),
            )
        }
    }

    private suspend fun access(connectionId: String): SchoolSessionRequest? {
        val connection = db.schoolConnectionDao().getById(connectionId) ?: return null
        return SchoolSessionRequest.open(db, credentialStore, authRepository, connection)
    }

    private fun queryProblem(query: ClassroomQuery): String? = when {
        query.termValue.isBlank() -> "请选择学期"
        query.timeModeId.isBlank() -> "请选择时间模式"
        query.week !in 1..60 -> "请选择周次"
        query.dayOfWeek !in 1..7 -> "请选择星期"
        query.allSectionCodes.isEmpty() -> "学校未提供节次选项，请稍后重试"
        query.allSectionCodes.size > MAX_GROUPS -> "节次选项异常，请重新加载"
        query.allSectionCodes.any { it.isBlank() || it.length > 32 } -> "节次选项异常，请重新加载"
        else -> null
    }

    private fun detailProblem(request: ClassroomDetailRequest): String? = when {
        request.termValue.isBlank() -> "请重新选择学期后再试"
        request.timeModeId.isBlank() -> "请重新选择时间模式后再试"
        request.week !in 1..60 || request.dayOfWeek !in 1..7 -> "请重新选择周次和星期后再试"
        request.rawSectionCode.isBlank() -> "节次信息缺失，请重新查询"
        request.roomId.isBlank() -> "教室标识缺失，暂不能查看详情"
        request.allSectionCodes.isEmpty() -> "学校未提供节次选项，请稍后重试"
        else -> null
    }

    private fun clearBuildings(connectionId: String) {
        val prefix = "$connectionId|"
        buildingCache.keys.removeAll { it.startsWith(prefix) }
    }

    private data class CachedBuildings(
        val buildings: List<ClassroomBuilding>,
        val at: Long,
    )

    private companion object {
        const val BUILDING_CACHE_MS = 10 * 60 * 1000L
        const val MAX_GROUPS = 24
        const val MESSAGE_LOGIN = "登录状态已失效，请重新登录"
        const val MESSAGE_METADATA_UNAVAILABLE = "教室查询条件加载失败，请稍后重试"
        const val MESSAGE_BUILDINGS_UNAVAILABLE = "教学楼列表加载失败，请重试或选择全部教学楼"
        const val MESSAGE_QUERY_UNAVAILABLE = "教室查询暂不可用，请稍后重试"
        const val MESSAGE_DETAIL_UNAVAILABLE = "课程详情加载失败，请重试"
    }
}
