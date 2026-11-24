package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.ClassroomBuilding
import com.gfs.iyango.core.model.ClassroomDetail
import com.gfs.iyango.core.model.ClassroomDetailRequest
import com.gfs.iyango.core.model.ClassroomMetadata
import com.gfs.iyango.core.model.ClassroomQuery
import com.gfs.iyango.core.model.ClassroomResult
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolCampusOption
import com.gfs.iyango.core.model.SchoolLoginResult
import com.gfs.iyango.core.model.SchoolProfile
import com.gfs.iyango.core.model.SchoolTermOption
import com.gfs.iyango.core.model.SchoolTimetableResult
import kotlinx.coroutines.flow.Flow

/**
 * 教务身份端口：登录、会话、账号历史与个人资料。
 *
 * 实现方约束：
 * - 密码/Cookie 只经 Keystore 加密后落盘，不进入 Room/DataStore 明文字段；
 * - 登录/切换账号时递增 `sessionRevision`，旧响应不得提交；
 * - 退出保留本地已同步课表，但停用该连接的网络更新。
 */
interface SchoolAuthPort {

    fun observeConnections(): Flow<List<SchoolConnection>>

    fun observeActiveConnection(): Flow<SchoolConnection?>

    suspend fun getConnection(connectionId: String): SchoolConnection?

    /**
     * 历史账号的登录账号（用于登录表单回填）。
     *
     * 只返回 Keystore 中保存的原始登录账号，绝不返回密码或 Cookie；
     * 凭据已被清除时回退到学号（[SchoolConnection.accountSubject]），两者皆空返回 null。
     */
    suspend fun historyAccount(connectionId: String): String?

    /**
     * 直接用账号密码登录。
     *
     * 提交前由调用方保证 `account.trim().isNotEmpty() && password.isNotEmpty() && !submitting`；
     * 实现方仍需再次校验并只提交一次。
     */
    suspend fun login(account: String, password: String, rememberPassword: Boolean): SchoolLoginResult

    /** 用已保存会话恢复连接；失败标记 EXPIRED，不无限重试。 */
    suspend fun restoreSession(connectionId: String): SchoolLoginResult

    /** 读取/刷新个人资料。 */
    suspend fun fetchProfile(connectionId: String): Result<SchoolProfile>

    /** 退出：保留课表数据，停用网络更新；可选清除已保存密码。 */
    suspend fun logout(connectionId: String, clearSavedPassword: Boolean)

    /** 删除历史账号（连带凭据；课表数据由调用方决定是否保留）。 */
    suspend fun deleteConnection(connectionId: String)

    /**
     * 清空历史账号：删除除 [keepConnectionId]（当前登录账号，可为空）外的全部连接
     * 与保存凭据；本地已同步课表不受影响。
     */
    suspend fun clearHistory(keepConnectionId: String)
}

/** 教务课表读取端口。 */
interface SchoolTimetablePort {

    /** 可选学期列表；学校入口页当前选中的学期排在最前。 */
    suspend fun listTerms(connectionId: String): Result<List<SchoolTermOption>>

    suspend fun listCampuses(connectionId: String): Result<List<SchoolCampusOption>>

    /**
     * 读取指定学期/校区的课表。
     *
     * @param termValue 后端实际学期值
     * @param campusCode 后端校区代码；空表示不指定
     */
    suspend fun fetchTimetable(
        connectionId: String,
        termValue: String,
        campusCode: String = "",
    ): SchoolTimetableResult
}

/**
 * 教室查询端口。
 *
 * 元数据与教学楼按连接/校区缓存；查询与详情分别以条件指纹和详情请求键标识，
 * 旧响应由调用方按连接、校区与查询版本丢弃。
 */
interface ClassroomRepositoryPort {

    suspend fun metadata(connectionId: String): Result<ClassroomMetadata>

    suspend fun buildings(connectionId: String, campusCode: String): Result<List<ClassroomBuilding>>

    /** 按已确认条件查询占用；实现方可返回缓存并标记 stale。 */
    suspend fun query(
        connectionId: String,
        query: ClassroomQuery,
    ): Result<ClassroomResult>

    /** 加载单条占用详情；失败允许对该时段单独重试。 */
    suspend fun detail(
        connectionId: String,
        request: ClassroomDetailRequest,
    ): Result<ClassroomDetail>
}

/** 成绩查询端口别名（保留在领域层，避免 feature 依赖学校适配层）。 */
typealias SchoolGradePort = GradeQueryPort
