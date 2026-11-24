package com.gfs.iyango.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.gfs.iyango.core.domain.AdjustmentRepositoryPort
import com.gfs.iyango.core.domain.AiChatPort
import com.gfs.iyango.core.domain.AiConfigStorePort
import com.gfs.iyango.core.domain.AiScheduleImportPort
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.AppUpdatePort
import com.gfs.iyango.core.domain.BingWallpaperPort
import com.gfs.iyango.core.domain.CalendarCoordinatorPort
import com.gfs.iyango.core.domain.CalendarPlatformPort
import com.gfs.iyango.core.domain.CampusAiAssistantPort
import com.gfs.iyango.core.domain.ClassroomRepositoryPort
import com.gfs.iyango.core.domain.CourseRawStorePort
import com.gfs.iyango.core.domain.CreditProgressPort
import com.gfs.iyango.core.domain.CustomFontStore
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.ExamRepositoryPort
import com.gfs.iyango.core.domain.GradeCheckSchedulerPort
import com.gfs.iyango.core.domain.GradeCurrentSemesterResolverPort
import com.gfs.iyango.core.domain.GradeMonitorCoordinatorPort
import com.gfs.iyango.core.domain.GradeNotificationPort
import com.gfs.iyango.core.domain.GradeQueryPort
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.ReminderPlatformPort
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.ScheduleTransferPort
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.SchoolCredentialStore
import com.gfs.iyango.core.domain.TermBrowseCachePort
import com.gfs.iyango.core.domain.SchoolTimetablePort
import com.gfs.iyango.core.domain.SchoolTimetableSyncPort
import com.gfs.iyango.core.domain.SettingsBackupPort
import com.gfs.iyango.core.domain.SyncHistoryPort
import com.gfs.iyango.core.domain.SystemMappingPort
import com.gfs.iyango.core.domain.ThemeSettingsPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.domain.WallpaperStore
import com.gfs.iyango.core.domain.WidgetConfigPort
import com.gfs.iyango.platform.android.AndroidAiCredentialStore
import com.gfs.iyango.platform.android.AndroidCalendarPlatform
import com.gfs.iyango.platform.android.AndroidCustomFontStore
import com.gfs.iyango.platform.android.AndroidGradeCheckScheduler
import com.gfs.iyango.platform.android.AndroidGradeNotificationPlatform
import com.gfs.iyango.platform.android.AndroidReminderPlatform
import com.gfs.iyango.platform.android.AndroidSchoolCredentialStore
import com.gfs.iyango.platform.android.AndroidWallpaperStore
import com.gfs.iyango.data.ai.DefaultCampusAiAssistant
import com.gfs.iyango.data.ai.OpenAiScheduleImportClient
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.update.HttpAppUpdateRepository
import com.gfs.iyango.data.repository.AppRefreshCoordinator
import com.gfs.iyango.data.repository.DataStoreAppSettings
import com.gfs.iyango.data.repository.DataStoreThemeSettings
import com.gfs.iyango.data.repository.DefaultCalendarCoordinator
import com.gfs.iyango.data.repository.DefaultGradeMonitorCoordinator
import com.gfs.iyango.data.repository.DefaultReminderCoordinator
import com.gfs.iyango.data.repository.DefaultSettingsBackup
import com.gfs.iyango.data.repository.FileCourseRawStore
import com.gfs.iyango.data.repository.FileTermBrowseCache
import com.gfs.iyango.data.repository.FileDiagnosticLog
import com.gfs.iyango.data.repository.HttpBingWallpaperRepository
import com.gfs.iyango.data.repository.YguGradeQueryRepository
import com.gfs.iyango.data.repository.IYangoDatabaseProvider
import com.gfs.iyango.data.repository.RoomAdjustmentRepository
import com.gfs.iyango.data.repository.RoomClassroomRepository
import com.gfs.iyango.data.repository.RoomCreditProgressRepository
import com.gfs.iyango.data.repository.RoomEffectiveSchedule
import com.gfs.iyango.data.repository.RoomExamRepository
import com.gfs.iyango.data.repository.RoomGradeCurrentSemesterResolver
import com.gfs.iyango.data.repository.RoomGradeRepository
import com.gfs.iyango.data.repository.RoomScheduleDataPort
import com.gfs.iyango.data.repository.RoomScheduleTransferPort
import com.gfs.iyango.data.repository.RoomSchoolAuthRepository
import com.gfs.iyango.data.repository.RoomSchoolTimetableRepository
import com.gfs.iyango.data.repository.RoomSyncHistoryRepository
import com.gfs.iyango.data.repository.RoomSystemMappingRepository
import com.gfs.iyango.data.repository.RoomTimetableRepository
import com.gfs.iyango.data.repository.RoomWidgetConfigRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * 应用级绑定模块（实施方案 5.1：绑定集中在 app，data 只提供可构造实现类）。
 *
 * 说明：
 * - [RoomTimetableRepository] / [RoomScheduleDataPort] 均为 data 层 @Inject 构造类，
 *   除端口绑定外无需显式 @Provides；
 * - [com.gfs.iyango.data.seed.DemoSeeder] 为 @Inject 构造类（依赖 [IYangoDatabase]），
 *   Hilt 自动构造，无需显式绑定；
 * - 数据库构造封装在 data 的 [IYangoDatabaseProvider]，此处为其构造参数
 *   `allowDestructive` 提供绑定：debug 构建允许破坏性迁移，release 禁止。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {

    /** 课表仓储端口 → Room 实现。 */
    @Binds
    @Singleton
    abstract fun bindTimetableRepositoryPort(impl: RoomTimetableRepository): TimetableRepositoryPort

    /** 单课表数据端口 → Room 实现。 */
    @Binds
    @Singleton
    abstract fun bindScheduleDataPort(impl: RoomScheduleDataPort): ScheduleDataPort

    /** 课表 JSON 导入导出端口 → W5 服务外观。 */
    @Binds
    @Singleton
    abstract fun bindScheduleTransferPort(impl: RoomScheduleTransferPort): ScheduleTransferPort

    /** 主题外观设置端口 → DataStore 实现。 */
    @Binds
    @Singleton
    abstract fun bindThemeSettingsPort(impl: DataStoreThemeSettings): ThemeSettingsPort

    /** 显示/提醒/日历/应用偏好设置端口 → DataStore 实现。 */
    @Binds
    @Singleton
    abstract fun bindAppSettingsPort(impl: DataStoreAppSettings): AppSettingsPort

    /** 考试仓储端口 → Room 实现。 */
    @Binds
    @Singleton
    abstract fun bindExamRepositoryPort(impl: RoomExamRepository): ExamRepositoryPort

    /** 成绩离线仓储端口 → Room 实现。 */
    @Binds
    @Singleton
    abstract fun bindGradeRepositoryPort(impl: RoomGradeRepository): GradeRepositoryPort

    /** 学分进度目标、分类与消歧规则端口 → Room 实现。 */
    @Binds
    @Singleton
    abstract fun bindCreditProgressPort(impl: RoomCreditProgressRepository): CreditProgressPort

    /** 成绩查询端口 → 阳光学院 HTTP + HTML/JSON 解析实现。 */
    @Binds
    @Singleton
    abstract fun bindGradeQueryPort(impl: YguGradeQueryRepository): GradeQueryPort

    /** 临时调整仓储端口 → Room 实现。 */
    @Binds
    @Singleton
    abstract fun bindAdjustmentRepositoryPort(impl: RoomAdjustmentRepository): AdjustmentRepositoryPort

    /** 有效课表查询端口 → Room 实现（自动选表 + 临时调整）。 */
    @Binds
    @Singleton
    abstract fun bindEffectiveSchedulePort(impl: RoomEffectiveSchedule): EffectiveSchedulePort

    /** 本机系统记录映射端口 → Room 实现。 */
    @Binds
    @Singleton
    abstract fun bindSystemMappingPort(impl: RoomSystemMappingRepository): SystemMappingPort

    /** 桌面组件配置端口 → Room 实现。 */
    @Binds
    @Singleton
    abstract fun bindWidgetConfigPort(impl: RoomWidgetConfigRepository): WidgetConfigPort

    /** 统一刷新协调器端口 → 应用级实现。 */
    @Binds
    @Singleton
    abstract fun bindRefreshCoordinatorPort(impl: AppRefreshCoordinator): RefreshCoordinatorPort

    /** 壁纸私有文件存储端口 → Android 解码/裁剪实现。 */
    @Binds
    @Singleton
    abstract fun bindWallpaperStore(impl: AndroidWallpaperStore): WallpaperStore

    /** 通知提醒平台端口 → AlarmManager/Notification 实现。 */
    @Binds
    @Singleton
    abstract fun bindReminderPlatformPort(impl: AndroidReminderPlatform): ReminderPlatformPort

    /** 课程提醒协调器端口 → 有效实例驱动的计划重建实现。 */
    @Binds
    @Singleton
    abstract fun bindReminderCoordinatorPort(impl: DefaultReminderCoordinator): ReminderCoordinatorPort

    /** 系统日历平台端口 → CalendarProvider 实现。 */
    @Binds
    @Singleton
    abstract fun bindCalendarPlatformPort(impl: AndroidCalendarPlatform): CalendarPlatformPort

    /** 日历同步协调器端口 → 差异对账实现。 */
    @Binds
    @Singleton
    abstract fun bindCalendarCoordinatorPort(impl: DefaultCalendarCoordinator): CalendarCoordinatorPort

    /** 设置备份/恢复/重置端口 → 版本化 JSON 实现。 */
    @Binds
    @Singleton
    abstract fun bindSettingsBackupPort(impl: DefaultSettingsBackup): SettingsBackupPort

    /** 教务身份端口 → Room + Keystore 实现。 */
    @Binds
    @Singleton
    abstract fun bindSchoolAuthPort(impl: RoomSchoolAuthRepository): SchoolAuthPort

    /** 教务课表读取端口 → YGU + Room 实现。 */
    @Binds
    @Singleton
    abstract fun bindSchoolTimetablePort(impl: RoomSchoolTimetableRepository): SchoolTimetablePort

    /** 教务课表同步端口（预览/提交/快速刷新） → YGU + Room 实现。 */
    @Binds
    @Singleton
    abstract fun bindSchoolTimetableSyncPort(impl: RoomSchoolTimetableRepository): SchoolTimetableSyncPort

    /** 教务同步变更记录端口 → Room 实现。 */
    @Binds
    @Singleton
    abstract fun bindSyncHistoryPort(impl: RoomSyncHistoryRepository): SyncHistoryPort

    /** 教室查询端口 → 阳光学院 HTTP + 解析实现。 */
    @Binds
    @Singleton
    abstract fun bindClassroomRepositoryPort(impl: RoomClassroomRepository): ClassroomRepositoryPort

    /** 教务凭据加密存储端口 → Android Keystore 实现。 */
    @Binds
    @Singleton
    abstract fun bindSchoolCredentialStore(impl: AndroidSchoolCredentialStore): SchoolCredentialStore

    /** 诊断日志端口 → 有上限的本地 JSONL 实现。 */
    @Binds
    @Singleton
    abstract fun bindDiagnosticLogPort(impl: FileDiagnosticLog): DiagnosticLogPort

    @Binds
    @Singleton
    abstract fun bindTermBrowseCachePort(impl: FileTermBrowseCache): TermBrowseCachePort

    /** 教务课表原始 HTML 私有文件存储端口。 */
    @Binds
    @Singleton
    abstract fun bindCourseRawStorePort(impl: FileCourseRawStore): CourseRawStorePort

    /** 自定义字体私有文件存储端口。 */
    @Binds
    @Singleton
    abstract fun bindCustomFontStore(impl: AndroidCustomFontStore): CustomFontStore

    /** 微软 Bing 每日壁纸网络端口。 */
    @Binds
    @Singleton
    abstract fun bindBingWallpaperPort(impl: HttpBingWallpaperRepository): BingWallpaperPort

    /** BYOK AI 接口配置加密存储端口 → Android Keystore 实现。 */
    @Binds
    @Singleton
    abstract fun bindAiConfigStorePort(impl: AndroidAiCredentialStore): AiConfigStorePort

    /** BYOK AI 课表识别客户端端口 → 多协议直连实现。 */
    @Binds
    @Singleton
    abstract fun bindAiScheduleImportPort(impl: OpenAiScheduleImportClient): AiScheduleImportPort

    /** 统一多协议 AI 对话与 Tool Calling 端口。 */
    @Binds
    @Singleton
    abstract fun bindAiChatPort(impl: OpenAiScheduleImportClient): AiChatPort

    /** 本地 Tool-Calling 校园 AI 助理端口。 */
    @Binds
    @Singleton
    abstract fun bindCampusAiAssistantPort(impl: DefaultCampusAiAssistant): CampusAiAssistantPort

    /** 应用更新自动/手动检测端口。 */
    @Binds
    @Singleton
    abstract fun bindAppUpdatePort(impl: HttpAppUpdateRepository): AppUpdatePort

    /** 新出分通知平台端口 → Notification 实现。 */
    @Binds
    @Singleton
    abstract fun bindGradeNotificationPort(impl: AndroidGradeNotificationPlatform): GradeNotificationPort

    /** 新出分后台周期调度端口 → AlarmManager 非精确闹钟实现。 */
    @Binds
    @Singleton
    abstract fun bindGradeCheckSchedulerPort(impl: AndroidGradeCheckScheduler): GradeCheckSchedulerPort

    /** 当前教务学期解析端口 → Room 实现（课表绑定优先，成绩最近学期回退）。 */
    @Binds
    @Singleton
    abstract fun bindGradeCurrentSemesterResolverPort(
        impl: RoomGradeCurrentSemesterResolver,
    ): GradeCurrentSemesterResolverPort

    /** 新出分通知协调端口 → data 协调器实现。 */
    @Binds
    @Singleton
    abstract fun bindGradeMonitorCoordinatorPort(impl: DefaultGradeMonitorCoordinator): GradeMonitorCoordinatorPort

    companion object {

        /** 全应用统一时钟（可测试性：时间比较必须走 Clock 注入）。 */
        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.systemDefaultZone()

        /**
         * 是否允许破坏性迁移：仅 debug 构建（applicationId 带 .debug 后缀）允许。
         *
         * 说明：AGP 9 起 application 模块默认不再生成 BuildConfig（本工程未开启
         * buildFeatures.buildConfig，且约束不允许修改构建脚本），故用运行时等价物
         * [ApplicationInfo.FLAG_DEBUGGABLE] 判定——AGP 的 debug 构建类型默认
         * debuggable=true、release=false，与 BuildConfig.DEBUG 的判定语义一致。
         */
        @Provides
        @Singleton
        fun provideAllowDestructive(@ApplicationContext context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        /** 数据库单例：由 data 层 provider 懒创建。 */
        @Provides
        @Singleton
        fun provideIYangoDatabase(provider: IYangoDatabaseProvider): IYangoDatabase =
            provider.get()
    }
}
