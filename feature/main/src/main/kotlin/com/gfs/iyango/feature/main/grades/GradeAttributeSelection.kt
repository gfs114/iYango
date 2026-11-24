package com.gfs.iyango.feature.main.grades

import com.gfs.iyango.core.model.GradeRecord

/** Attribute groups are derived from the currently displayed records, never from another semester. */
internal data class GradeAttributeGroup(val value: String, val recordIds: Set<Long>) {
    val label: String get() = gradeAttributeLabel(value).ifEmpty { "未标注" }
}

internal fun gradeAttributeLabel(value: String): String = value.replace('·', ' ')

internal fun gradeAttributeGroups(records: List<GradeRecord>): List<GradeAttributeGroup> =
    records.filter { it.id > 0 }
        .groupBy { it.attribute.trim() }
        .map { (attribute, rows) -> GradeAttributeGroup(attribute, rows.map { it.id }.toSet()) }
