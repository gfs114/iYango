package com.gfs.iyango.data.repository

import androidx.room.withTransaction
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.data.db.IYangoDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [TimetableRepositoryPort] 的 Room 实现：课表聚合的生命周期管理。
 *
 * 关键语义：
 * - "当前课表"不变式（全表至多一行 isCurrent=1）由 [TimetableDao.setCurrent] 的事务
 *   （先清零再置位）维护，[upsert] 又把写入与置位整体包进 `db.withTransaction` 单事务，
 *   进程死亡/并发也不会破坏不变式；
 * - 新建课表（id=0）且库中尚无当前课表时，自动成为当前课表（覆盖"新建第一条"场景）；
 * - 删除课表后，其学期/作息/课程/导入记录依赖外键 ON DELETE CASCADE 级联清理。
 *
 * DAO 层的 Room 行为验证（CRUD/级联/事务）留给 P2 的 Robolectric/仪器测试。
 */
@Singleton
class RoomTimetableRepository @Inject constructor(
    private val db: IYangoDatabase,
) : TimetableRepositoryPort {

    /** 观察全部课表（含非当前），按 id 升序。 */
    override fun observeAll(): Flow<List<Timetable>> =
        db.timetableDao().observeAll().map { rows -> rows.map { it.toModel() } }

    /** 观察当前课表；尚无当前课表时发射 null。 */
    override fun observeCurrent(): Flow<Timetable?> =
        db.timetableDao().observeCurrent().map { it?.toModel() }

    /** 把 [id] 设为当前课表（DAO 事务内先清零再置位）。 */
    override suspend fun setCurrent(id: Long) {
        db.timetableDao().setCurrent(id)
    }

    /**
     * 新增或更新课表，返回持久化后的 id。
     *
     * 整个 upsert（写入 + 当前标记的清零/置位判断）在单个 `db.withTransaction` 内完成，
     * 保证进程死亡或并发写入时不会出现"插入完成但置位未完成"的中间态，
     * 维护"全表至多一行 isCurrent=1"不变式。
     *
     * isCurrent 语义（与原实现一致）：
     * - 显式传入 isCurrent=true → 落库后调用 [com.gfs.iyango.data.db.TimetableDao.setCurrent]，
     *   清掉其他行的当前标记，维持不变式；
     * - 新建（id=0）且全表没有当前课表 → 自动置为当前（第一条课表开箱即用）；
     * - 其余情况按传入值原样落库，不改动其他行。
     */
    override suspend fun upsert(timetable: Timetable): Long {
        return db.withTransaction {
            val entity = timetable.toEntity()
            val rowId = db.timetableDao().upsert(entity)
            // @Upsert 走更新分支时返回 -1，此时持久化 id 即实体自身主键。
            val savedId = if (rowId > 0) rowId else entity.id
            when {
                timetable.isCurrent -> db.timetableDao().setCurrent(savedId)
                entity.id == 0L && db.timetableDao().countCurrent() == 0 ->
                    db.timetableDao().setCurrent(savedId)
            }
            savedId
        }
    }

    /** 删除课表；学期/作息/课程/导入记录由外键级联删除。 */
    override suspend fun delete(id: Long) {
        db.timetableDao().deleteById(id)
    }

    /**
     * 整包复制课表（学期/作息/课程），全部在单个 `db.withTransaction` 内完成，
     * 任一步失败整体回滚，不会留下半套课表。
     *
     * 复制语义：
     * - 新课表名 = 原名 + "副本"，isCurrent=false（不抢占当前课表）；
     * - 学期复制后置为副本课表的当前学期；
     * - 作息整表复制（id 归零）；
     * - 课程 id 归零为新行，单次调整记录展平为普通课程
     *   （overrideOfId/overrideWeek 置 NULL，避免引用已换 id 的原行）。
     */
    override suspend fun duplicate(id: Long): Long = db.withTransaction {
        val source = db.timetableDao().getById(id)
            ?: throw IllegalArgumentException("课表不存在：$id")
        val rowId = db.timetableDao().upsert(
            source.copy(id = 0, name = source.name + "副本", isCurrent = false),
        )
        check(rowId > 0) { "复制课表失败" }
        val newId = rowId

        db.semesterDao().getCurrent(id)?.let { semester ->
            db.semesterDao().upsert(
                semester.copy(id = 0, timetableId = newId, isCurrent = true),
            )
        }
        db.timeSlotDao().getByTimetable(id).takeIf { it.isNotEmpty() }?.let { slots ->
            db.timeSlotDao().deleteByTimetable(newId)
            db.timeSlotDao().upsertAll(slots.map { it.copy(id = 0, timetableId = newId) })
        }
        db.courseDao().getAllByTimetable(id).forEach { course ->
            db.courseDao().upsert(
                course.copy(
                    id = 0,
                    timetableId = newId,
                    overrideOfId = null,
                    overrideWeek = null,
                ),
            )
        }
        newId
    }
}
