package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.SchoolTermOption
import com.gfs.iyango.core.model.SchoolTimetableData

/** Private browsing cache; never changes the selected timetable or sync history. */
interface TermBrowseCachePort {
    suspend fun readTerms(accountKey: String): List<SchoolTermOption>
    suspend fun writeTerms(accountKey: String, terms: List<SchoolTermOption>)
    suspend fun readTimetable(accountKey: String, term: String): SchoolTimetableData?
    suspend fun writeTimetable(accountKey: String, term: String, data: SchoolTimetableData)
}
