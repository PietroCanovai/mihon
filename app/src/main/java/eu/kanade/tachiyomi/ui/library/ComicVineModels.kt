package eu.kanade.tachiyomi.ui.library

import java.time.LocalDate

enum class DatePrecision { EXACT, MONTH_ONLY, YEAR_ONLY }

data class UpcomingIssue(
    val mangaId: Long,
    val mangaTitle: String,
    val thumbnailUrl: String?,
    val issueTitle: String,
    val issueNumber: String?,
    val storeDate: LocalDate,       // used for sorting/grouping
    val displayDate: String,        // human readable e.g. "February 2026" or "2026-02-18"
    val datePrecision: DatePrecision,
    val coverUrl: String?,
    val description: String?,
)

data class UpcomingDay(
    val date: LocalDate,
    val displayDate: String,
    val datePrecision: DatePrecision,
    val issues: List<UpcomingIssue>,
)
