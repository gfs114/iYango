package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.SystemMapping
import com.gfs.iyango.core.model.SystemMappingKind
import com.gfs.iyango.core.model.WidgetConfig

/**
 * 本机系统记录映射端口（提醒/日历/组件）。
 *
 * 这些 ID 只在本机有效，设置备份必须排除；[ownerId] 用于区分安装实例/账号。
 */
interface SystemMappingPort {

    suspend fun all(kind: SystemMappingKind, ownerId: String): List<SystemMapping>

    suspend fun upsert(mapping: SystemMapping): Long

    suspend fun delete(kind: SystemMappingKind, occurrenceId: String, ownerId: String)

    suspend fun deleteKind(kind: SystemMappingKind, ownerId: String)
}

/** 桌面组件实例配置端口（仅本机）。 */
interface WidgetConfigPort {

    fun observeAll(): kotlinx.coroutines.flow.Flow<List<WidgetConfig>> = kotlinx.coroutines.flow.flow { emit(all()) }

    suspend fun all(): List<WidgetConfig>

    suspend fun get(widgetId: Int): WidgetConfig?

    suspend fun save(config: WidgetConfig)

    suspend fun delete(widgetId: Int)
}
