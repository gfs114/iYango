package com.gfs.iyango.feature.main.grades

import com.gfs.iyango.core.model.GradeTerm

/** An inclusive local statistics filter. Its value must never be sent as a school term. */
internal class GradeTermInterval private constructor(val start: String, val end: String) {
    val value: String get() = if (start == end) start else "$PREFIX$start..$end"

    fun contains(term: String): Boolean {
        val position = positionOf(term) ?: return false
        return position in positionOf(start)!!..positionOf(end)!!
    }

    companion object {
        private const val PREFIX = "range:"
        private val pattern = Regex("(\\d{4})-(\\d{4})-(\\d{1,2})")

        fun isIntervalValue(value: String): Boolean = value.startsWith(PREFIX)

        fun between(start: String, end: String): GradeTermInterval? {
            val first = positionOf(start) ?: return null
            val last = positionOf(end) ?: return null
            return if (first <= last) GradeTermInterval(start, end) else null
        }

        fun parse(value: String): GradeTermInterval? {
            if (!isIntervalValue(value)) return null
            val parts = value.removePrefix(PREFIX).split("..")
            return if (parts.size == 2) between(parts[0], parts[1]) else null
        }

        fun ordered(terms: List<GradeTerm>): List<GradeTerm> = terms
            .filter { positionOf(it.value) != null }
            .distinctBy { it.value }
            .sortedBy { positionOf(it.value) }

        private fun positionOf(value: String): Int? {
            val match = pattern.matchEntire(value) ?: return null
            val year = match.groupValues[1].toInt()
            val nextYear = match.groupValues[2].toInt()
            val term = match.groupValues[3].toInt()
            if (nextYear != year + 1 || term !in 1..99) return null
            return year * 100 + term
        }
    }
}
