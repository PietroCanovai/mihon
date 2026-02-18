package eu.kanade.tachiyomi.ui.library

import android.content.Context
import eu.kanade.tachiyomi.data.preference.ComicVinePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tachiyomi.domain.manga.model.Manga
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class ComicVineRepository(context: Context) {

    private val prefs = ComicVinePreferences(context)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val today = LocalDate.now()

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mihon/1.0")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        return JSONObject(response)
    }

    private suspend fun findVolumeId(manga: Manga): Int? {
        prefs.getCachedVolumeId(manga.id)?.let {
            android.util.Log.d("ComicVine", "Cache hit for ${manga.title}: volume $it")
            return it
        }

        val apiKey = prefs.getApiKey()
        val query = URLEncoder.encode(manga.title, "UTF-8")
        val url = "https://comicvine.gamespot.com/api/search/?api_key=$apiKey&format=json" +
            "&query=$query&resources=volume" +
            "&field_list=id,name,count_of_issues,start_year,publisher" +
            "&limit=10"

        return try {
            android.util.Log.d("ComicVine", "Searching volume for: ${manga.title}")
            val json = get(url)
            val results = json.getJSONArray("results")
            if (results.length() == 0) return null

            val currentYear = today.year

            data class Candidate(val id: Int, val issueCount: Int, val startYear: Int, val score: Double)

            val candidates = mutableListOf<Candidate>()
            for (i in 0 until results.length()) {
                val result = results.getJSONObject(i)
                val name = result.optString("name", "")
                val id = result.getInt("id")
                val issueCount = result.optInt("count_of_issues", 0)
                val startYear = result.optString("start_year", "0").toIntOrNull() ?: 0

                if (!name.equals(manga.title, ignoreCase = true)) continue

                // Recency is the dominant factor — a series from last 3 years
                // gets a massive bonus that can't be overcome by sheer issue count
                val recencyBonus = when {
                    startYear >= currentYear - 1 -> 10000.0  // current or last year — almost certainly what user wants
                    startYear >= currentYear - 3 -> 500.0    // recent series
                    startYear >= currentYear - 10 -> 0.0     // older series, just use issue count
                    else -> -100.0                           // penalize very old series
                }
                val score = issueCount.toDouble() + recencyBonus

                android.util.Log.d("ComicVine", "  Candidate: $name id=$id issues=$issueCount year=$startYear score=$score")
                candidates.add(Candidate(id, issueCount, startYear, score))
            }

            val bestId = if (candidates.isNotEmpty()) {
                candidates.maxByOrNull { it.score }!!.id
            } else {
                android.util.Log.d("ComicVine", "  No exact match, using first result")
                results.getJSONObject(0).getInt("id")
            }

            android.util.Log.d("ComicVine", "Best volume ID for ${manga.title}: $bestId")
            bestId.also { prefs.setCachedVolumeId(manga.id, it) }
        } catch (e: Exception) {
            android.util.Log.e("ComicVine", "Error searching volume for ${manga.title}", e)
            null
        }
    }

    private fun parseIssueDate(
        storeDateStr: String,
        coverDateStr: String,
    ): Triple<LocalDate, String, DatePrecision>? {
        // Try store_date first — most precise
        if (storeDateStr.isNotBlank() && storeDateStr != "null") {
            try {
                val date = LocalDate.parse(storeDateStr, dateFormatter)
                val day = date.dayOfMonth
                val suffix = when {
                    day in 11..13 -> "th"
                    day % 10 == 1 -> "st"
                    day % 10 == 2 -> "nd"
                    day % 10 == 3 -> "rd"
                    else -> "th"
                }
                val month = date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                return Triple(date, "$month $day$suffix, ${date.year}", DatePrecision.EXACT)
            } catch (_: Exception) {}
        }

        // Fall back to cover_date — usually YYYY-MM-01 meaning month/year only
        if (coverDateStr.isNotBlank() && coverDateStr != "null") {
            try {
                val date = LocalDate.parse(coverDateStr, dateFormatter)
                val month = date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                return if (date.dayOfMonth == 1) {
                    Triple(date, "$month ${date.year}", DatePrecision.MONTH_ONLY)
                } else {
                    val day = date.dayOfMonth
                    val suffix = when {
                        day in 11..13 -> "th"
                        day % 10 == 1 -> "st"
                        day % 10 == 2 -> "nd"
                        day % 10 == 3 -> "rd"
                        else -> "th"
                    }
                    Triple(date, "$month $day$suffix, ${date.year}", DatePrecision.EXACT)
                }
            } catch (_: Exception) {}
        }

        return null
    }

    private suspend fun getUpcomingIssues(manga: Manga, volumeId: Int): List<UpcomingIssue> {
        val apiKey = prefs.getApiKey()
        val cutoffPast = today.minusDays(30)
        val cutoffPastStr = cutoffPast.format(dateFormatter)
        // Cover dates run ~6-8 weeks ahead of store dates, so push window out further
        val cutoffFutureStr = today.plusMonths(9).format(dateFormatter)

        // Filter by cover_date range — this catches both recent issues (with store_date)
        // and upcoming ones (that only have a future cover_date set)
        val url = "https://comicvine.gamespot.com/api/issues/?api_key=$apiKey&format=json" +
            "&filter=volume:$volumeId,cover_date:$cutoffPastStr|$cutoffFutureStr" +
            "&field_list=id,name,issue_number,store_date,cover_date,image,description" +
            "&sort=cover_date:asc" +
            "&limit=30"

        return try {
            android.util.Log.d("ComicVine", "Fetching issues for ${manga.title} (volume $volumeId)")
            val json = get(url)
            val results = json.getJSONArray("results")
            android.util.Log.d("ComicVine", "Got ${results.length()} results for ${manga.title}")

            val issues = mutableListOf<UpcomingIssue>()
            for (i in 0 until results.length()) {
                val issue = results.getJSONObject(i)
                val storeDateStr = issue.optString("store_date", "")
                val coverDateStr = issue.optString("cover_date", "")
                android.util.Log.d("ComicVine", "  Issue: store=$storeDateStr cover=$coverDateStr name=${issue.optString("name")}")

                val (sortDate, displayDate, precision) = parseIssueDate(storeDateStr, coverDateStr)
                    ?: continue

                // For past cutoff: use store_date if available (more accurate),
                // otherwise use cover_date
                val pastCheckDate = if (storeDateStr.isNotBlank() && storeDateStr != "null") {
                    try { LocalDate.parse(storeDateStr, dateFormatter) } catch (_: Exception) { sortDate }
                } else {
                    sortDate
                }

                if (pastCheckDate.isBefore(cutoffPast)) {
                    android.util.Log.d("ComicVine", "  Skipping — too old: $pastCheckDate")
                    continue
                }

                val imageObj = issue.optJSONObject("image")
                val coverUrl = imageObj?.optString("medium_url")?.let {
                    if (it.isBlank() || it == "null") null else it
                }

                issues.add(
                    UpcomingIssue(
                        mangaId = manga.id,
                        mangaTitle = manga.title,
                        thumbnailUrl = manga.thumbnailUrl,
                        issueTitle = issue.optString("name", "").let {
                            if (it.isBlank() || it == "null") "" else it
                        },
                        issueNumber = issue.optString("issue_number").let {
                            if (it.isBlank() || it == "null") null else it
                        },
                        storeDate = sortDate,
                        displayDate = displayDate,
                        datePrecision = precision,
                        coverUrl = coverUrl,
                        description = issue.optString("description").let {
                            if (it.isBlank() || it == "null") null else it
                        },
                    ),
                )
            }

            android.util.Log.d("ComicVine", "Kept ${issues.size} issues for ${manga.title}")
            issues.sortedBy { it.storeDate }
        } catch (e: Exception) {
            android.util.Log.e("ComicVine", "Error fetching issues for volume $volumeId", e)
            emptyList()
        }
    }

    suspend fun getUpcomingForLibrary(libraryManga: List<Manga>): List<UpcomingDay> {
        return withContext(Dispatchers.IO) {
            android.util.Log.d("ComicVine", "Library has ${libraryManga.size} manga")

            val allIssues = libraryManga.chunked(3).flatMap { chunk ->
                chunk.map { manga ->
                    async {
                        android.util.Log.d("ComicVine", "Processing: ${manga.title}")
                        val volumeId = findVolumeId(manga)
                        if (volumeId == null) {
                            android.util.Log.d("ComicVine", "No volume found for ${manga.title}")
                            return@async emptyList()
                        }
                        getUpcomingIssues(manga, volumeId)
                    }
                }.awaitAll().flatten()
            }

            android.util.Log.d("ComicVine", "Total issues found: ${allIssues.size}")

            allIssues
                .groupBy { it.storeDate }
                .map { (date, issues) ->
                    val first = issues.first()
                    UpcomingDay(
                        date = date,
                        displayDate = first.displayDate,
                        datePrecision = first.datePrecision,
                        issues = issues.sortedBy { it.mangaTitle },
                    )
                }
                .sortedBy { it.date }
        }
    }
}
