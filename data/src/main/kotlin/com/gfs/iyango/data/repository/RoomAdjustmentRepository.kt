package com.gfs.iyango.data.repository

import androidx.room.withTransaction
import com.gfs.iyango.core.domain.AdjustmentRepositoryPort
import com.gfs.iyango.core.model.OccurrenceAdjustment
import com.gfs.iyango.core.model.TemporaryDay
import com.gfs.iyango.data.db.IYangoDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [AdjustmentRepositoryPort] 的 Room 实现。 */
@Singleton
class RoomAdjustmentRepository @Inject constructor(
    private val db: IYangoDatabase,
) : AdjustmentRepositoryPort {

    override suspend fun replaceOccurrence(
        expected: com.gfs.iyango.core.domain.OccurrenceAdjustmentExpected,
        replacement: OccurrenceAdjustment,
    ): com.gfs.iyango.core.domain.AdjustmentReplaceResult = db.withTransaction {
        val stale = com.gfs.iyango.core.domain.AdjustmentReplaceResult.Stale
        val base = expected.baseCourse
        val occurrence = expected.occurrence
        if (replacement.timetableId != base.timetableId || replacement.sourceCourseId != base.id ||
            replacement.sourceDate != occurrence.anchorDate.toString() || occurrence.isTemporary ||
            replacement.operationGroupId.isBlank()) return@withTransaction stale
        val stored = db.courseDao().getById(base.id)?.toModel()
        if (stored != base) return@withTransaction stale
        val dao = db.occurrenceAdjustmentDao()
        val previous = dao.getByTimetable(base.timetableId).map { it.toModel() }
            .filter { it.sourceCourseId == base.id && it.sourceDate == replacement.sourceDate }
        if (previous.sortedBy { it.id } != expected.adjustments.sortedBy { it.id }) return@withTransaction stale
        val effective = RoomEffectiveSchedule(db)
        val sourceDay = effective.resolveDay(occurrence.date)
        if (sourceDay.isTemporaryDay || sourceDay.timetable?.id != base.timetableId ||
            sourceDay.occurrences.none { it == occurrence }) return@withTransaction stale
        if (replacement.type == com.gfs.iyango.core.model.AdjustmentType.MOVE) {
            val targetDate = runCatching { java.time.LocalDate.parse(replacement.targetDate) }.getOrNull()
                ?: return@withTransaction com.gfs.iyango.core.domain.AdjustmentReplaceResult.Rejected("目标日期无效")
            val targetDay = effective.resolveDay(targetDate)
            val confirmed = expected.targetDay ?: return@withTransaction stale
            if (confirmed.date != targetDay.date || confirmed.timetable != targetDay.timetable ||
                confirmed.semester != targetDay.semester || confirmed.displayTimeSlots != targetDay.displayTimeSlots ||
                confirmed.timeSlots != targetDay.timeSlots || confirmed.isTemporaryDay != targetDay.isTemporaryDay ||
                confirmed.occurrences != targetDay.occurrences) return@withTransaction stale
            val warning = com.gfs.iyango.core.domain.AdjustmentTargetPolicy.warning(targetDay, base.timetableId)
            if (warning != null) return@withTransaction com.gfs.iyango.core.domain.AdjustmentReplaceResult.Rejected(warning)
            val maxSection = if (targetDay.timetable?.source == "YGU") 11 else 20
            if (replacement.targetStartSection !in 1..maxSection || replacement.targetEndSection !in replacement.targetStartSection..maxSection ||
                replacement.targetDayOfWeek != targetDate.dayOfWeek.value) return@withTransaction com.gfs.iyango.core.domain.AdjustmentReplaceResult.Rejected("请选择有效的调整时间")
        }
        dao.deleteAnchor(base.timetableId,base.id,replacement.sourceDate)
        dao.insert(replacement.copy(id = 0).toEntity())
        com.gfs.iyango.core.domain.AdjustmentReplaceResult.Saved
    }

    override fun observeAdjustments(timetableId: Long): Flow<List<OccurrenceAdjustment>> =
        db.occurrenceAdjustmentDao().observeByTimetable(timetableId)
            .map { rows -> rows.map { it.toModel() } }

    override fun observeTemporaryDays(timetableId: Long): Flow<List<TemporaryDay>> =
        db.temporaryDayDao().observeByTimetable(timetableId)
            .map { rows -> rows.map { it.toModel() } }

    override suspend fun insert(adjustment: OccurrenceAdjustment): Long {
        require(adjustment.timetableId > 0) { "临时调整缺少课表" }
        require(adjustment.operationGroupId.isNotBlank()) { "临时调整缺少操作组" }
        val rowId = db.occurrenceAdjustmentDao().insert(adjustment.toEntity())
        return if (rowId > 0) rowId else adjustment.id
    }

    override suspend fun insertTemporaryDay(day: TemporaryDay): Long {
        require(day.timetableId > 0) { "临时整天缺少课表" }
        require(day.operationGroupId.isNotBlank()) { "临时整天缺少操作组" }
        val rowId = db.temporaryDayDao().insert(day.toEntity())
        return if (rowId > 0) rowId else day.id
    }

    override suspend fun deleteGroup(operationGroupId: String) {
        if (operationGroupId.isBlank()) return
        db.withTransaction {
            db.occurrenceAdjustmentDao().deleteByGroup(operationGroupId)
            db.temporaryDayDao().deleteByGroup(operationGroupId)
        }
    }

    override suspend fun deleteAnchor(timetableId: Long, courseId: Long, sourceDate: String) {
        db.occurrenceAdjustmentDao().deleteAnchor(timetableId, courseId, sourceDate)
    }

    override suspend fun deleteTemporaryDay(timetableId: Long, date: String) {
        db.temporaryDayDao().deleteDate(timetableId, date)
    }

    override suspend fun deleteAll(timetableId: Long) {
        db.withTransaction {
            db.occurrenceAdjustmentDao().deleteByTimetable(timetableId)
            db.temporaryDayDao().deleteByTimetable(timetableId)
        }
    }
}
