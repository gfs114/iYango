package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.RefreshHandler
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.SchoolSyncCommitResult
import com.gfs.iyango.core.domain.SchoolTimetablePort
import com.gfs.iyango.core.domain.SchoolTimetableSyncPort
import com.gfs.iyango.core.domain.SessionExpiredException
import com.gfs.iyango.data.db.IYangoDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F05 课表自动刷新：有绑定学期时只刷新该学期；首次登录尚无绑定时取学校入口页
 * 当前学期并直接新建课表（与鸿蒙 `YguQuickSync` 一致）。
 *
 * 预览期间用户在本地改过课表时提交返回 [SchoolSyncCommitResult.Conflict]，
 * 此时保留本地数据并提示需要重新同步，不得将未提交的结果报告为成功。
 */
@Singleton
class SchoolTimetableRefreshHandler @Inject constructor(
    private val db: IYangoDatabase,
    private val timetable: SchoolTimetablePort,
    private val sync: SchoolTimetableSyncPort,
) : RefreshHandler {

    override val resource: RefreshResource = RefreshResource.TIMETABLE

    override suspend fun refresh(queryFingerprint: String) {
        val connection = db.schoolConnectionDao().getActive()
            ?: db.schoolConnectionDao().getAll()
                .firstOrNull { it.status != com.gfs.iyango.core.model.SchoolConnectionStatus.LOGGED_OUT && it.rememberPassword }
            ?: throw SessionExpiredException("请先登录阳光学院教务系统")
        val boundTerm = db.timetableDao().getLatestYguBinding(connection.id)?.schoolTerm
            ?.takeIf { it.isNotBlank() }
        val term = boundTerm ?: currentSchoolTerm(connection.id)
        when (val result = sync.quickSync(connection.id, term)) {
            is SchoolSyncCommitResult.Success -> Unit
            is SchoolSyncCommitResult.Conflict -> error(result.message.ifBlank { "课表已在本地修改，请重新同步" })
            is SchoolSyncCommitResult.SessionExpired -> throw SessionExpiredException(result.message)
            is SchoolSyncCommitResult.Failed -> error(result.message)
        }
    }

    /** 学校入口页当前选中的学期；[SchoolTimetablePort.listTerms] 已把该项排在最前。 */
    private suspend fun currentSchoolTerm(connectionId: String): String {
        val options = timetable.listTerms(connectionId).getOrElse { failure ->
            if (failure is SessionExpiredException) throw failure
            throw IllegalStateException(
                failure.message?.takeIf { it.isNotBlank() }
                    ?: "未能获取教务学期，请稍后在「同步教务课表」中选择学期",
            )
        }
        return options.firstOrNull()?.value?.takeIf { it.isNotBlank() }
            ?: error("未能获取教务学期，请稍后在「同步教务课表」中选择学期")
    }
}
