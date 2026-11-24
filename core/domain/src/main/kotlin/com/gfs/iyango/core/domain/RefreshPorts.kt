package com.gfs.iyango.core.domain

import kotlinx.coroutines.flow.StateFlow

/** 可独立刷新的资源。 */
enum class RefreshResource {
    TIMETABLE,
    PROFILE,
    GRADES,
    CLASSROOM,
    EXAMS,
}

/** 单次刷新结果。 */
enum class RefreshOutcome {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
}

/** 某资源的刷新状态。 */
data class RefreshTaskState(
    val resource: RefreshResource = RefreshResource.TIMETABLE,
    val outcome: RefreshOutcome = RefreshOutcome.IDLE,
    val message: String = "",
    /** 本轮失败源于登录状态被学校确认失效；UI 据此引导用户回到教务账号页。 */
    val sessionExpired: Boolean = false,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val foregroundGeneration: Int = 0,
    val sessionRevision: Int = 0,
    val queryFingerprint: String = "",
) {
    val isRefreshing: Boolean get() = outcome == RefreshOutcome.RUNNING
}

/**
 * 会话失效信号：刷新处理器确认登录状态已失效时抛出；协调器据此在任务状态上
 * 标记 [RefreshTaskState.sessionExpired]，与普通失败区分开。
 */
class SessionExpiredException(message: String) : IllegalStateException(message)

/** 应用级刷新状态（各资源分开，避免一个资源失败影响其他下拉圆圈）。 */
data class RefreshState(
    val tasks: Map<RefreshResource, RefreshTaskState> = emptyMap(),
) {
    fun task(resource: RefreshResource): RefreshTaskState =
        tasks[resource] ?: RefreshTaskState(resource = resource)
}

/**
 * 单一资源的刷新执行器（F01–F04 学校接入层注册到协调器）。
 *
 * 抛出异常表示失败；[accepts] 返回 false 表示当前条件不足以发起请求
 * （如成绩/教室尚无已确认查询条件），协调器将跳过而不是发出无界查询。
 */
interface RefreshHandler {

    /** 本执行器负责的资源。 */
    val resource: RefreshResource

    /** 当前查询指纹是否可刷新。 */
    fun accepts(queryFingerprint: String): Boolean = true

    /** 执行一次刷新；失败时抛异常。 */
    suspend fun refresh(queryFingerprint: String)
}

/**
 * 统一刷新协调器端口（F05）。
 *
 * 任务键语义：`(connectionId, sessionRevision, foregroundGeneration, resource,
 * queryFingerprint)`。同代次同条件共享进行中的任务；切账号/新前台代次使旧响应失效。
 *
 * 协调器只处理“请求身份”；课表提交时仍必须按 F02 在事务内比较 `dataRevision`。
 */
interface RefreshCoordinatorPort {

    val state: StateFlow<RefreshState>

    /** 冷启动就绪 / 进程回到前台：开启新前台代次。 */
    fun onForeground()

    /** 登录、切账号、退出：递增会话代次。 */
    fun onSessionChanged()

    /**
     * 刷新指定资源。
     *
     * @param queryFingerprint 成绩/教室等需要已确认条件的资源传入；无条件下刷新应传空并由
     *   实现方跳过无界查询。
     */
    suspend fun refresh(
        resource: RefreshResource,
        queryFingerprint: String = "",
    ): RefreshTaskState

    /** 本地业务写入后使旧快照失效（不自动发网络请求）。 */
    fun onLocalDataChanged(resource: RefreshResource)
}
