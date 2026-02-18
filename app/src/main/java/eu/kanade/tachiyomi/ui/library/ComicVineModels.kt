package eu.kanade.tachiyomi.ui.library

import java.time.LocalDate

data class UpcomingIssue(
    val mangaId: Long,
    val mangaTitle: String,
    val thumbnailUrl: String?,
    val issueTitle: String,
    val issueNumber: String?,
    val storeDate: LocalDate,
    val coverUrl: String?,
    val description: String?,
)

data class UpcomingDay(
    val date: LocalDate,
    val issues: List<UpcomingIssue>,
)
