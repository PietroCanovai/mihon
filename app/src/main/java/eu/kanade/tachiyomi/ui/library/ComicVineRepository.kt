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
            android.util.Log.d("ComicVine", "Search results count for ${manga.title}: ${results.length()}")
            if (results.length() == 0) return null

            // Score each result — prefer exact name match with most issues
            data class Candidate(val id: Int, val name: String, val issueCount: Int, val score: Int)

            val candidates = mutableListOf<Candidate>()
            for (i in 0 until results.length()) {
                val result = results.getJSONObject(i)
                val name = result.optString("name", "")
                val id = result.getInt("id")
                val issueCount = result.optInt("count_of_issues", 0)

                // Only consider exact title matches to avoid e.g.
                // "Absolute Batman: The Killing Joke" when searching "Absolute Batman"
                if (!name.equals(manga.title, ignoreCase = true)) {
                    android.util.Log.d("ComicVine", "  Skipping non-exact match: $name")
                    continue
                }

                // Score: heavily weight issue count so we pick the ongoing series
                // over collected editions (which have 1-2 issues)
                val score = issueCount
                android.util.Log.d("ComicVine", "  Candidate: $name id=$id issues=$issueCount score=$score")
                candidates.add(Candidate(id, name, issueCount, score))
            }

            // If no exact matches found, fall back to first result
            val bestId = if (candidates.isNotEmpty()) {
                candidates.maxByOrNull { it.score }!!.id
            } else {
                android.util.Log.d("ComicVine", "  No exact matches, falling back to first result")
                results.getJSONObject(0).getInt("id")
            }

            android.util.Log.d("ComicVine", "Best volume ID for ${manga.title}: $bestId")
            bestId.also { prefs.setCachedVolumeId(manga.id, it) }
        } catch (e: Exception) {
            android.util.Log.e("ComicVine", "Error searching volume for ${manga.title}", e)
            null
        }
    }

    private suspend fun getUpcomingIssues(manga: Manga, volumeId: Int): List<UpcomingIssue> {
        val apiKey = prefs.getApiKey()
        // Show issues from 30 days ago through 3 months ahead
        val fromStr = today.minusDays(30).format(dateFormatter)
        val toStr = today.plusMonths(3).format(dateFormatter)

        val url = "https://comicvine.gamespot.com/api/issues/?api_key=$apiKey&format=json" +
            "&filter=volume:$volumeId,store_date:$fromStr|$toStr" +
            "&field_list=id,name,issue_number,store_date,image,description" +
            "&sort=store_date:asc" +
            "&limit=10"

        return try {
            android.util.Log.d("ComicVine", "Fetching issues URL: $url")
            val json = get(url)
            val results = json.getJSONArray("results")
            android.util.Log.d("ComicVine", "Issues count for volume $volumeId: ${results.length()}")

            val issues = mutableListOf<UpcomingIssue>()
            for (i in 0 until results.length()) {
                val issue = results.getJSONObject(i)
                val storeDateStr = issue.optString("store_date", "")
                android.util.Log.d("ComicVine", "  Issue $i: store_date=$storeDateStr name=${issue.optString("name")}")
                if (storeDateStr.isBlank() || storeDateStr == "null") continue
                val storeDate = try {
                    LocalDate.parse(storeDateStr, dateFormatter)
                } catch (e: Exception) {
                    continue
                }

                val imageObj = issue.optJSONObject("image")
                val coverUrl = imageObj?.optString("medium_url")?.takeIf { it.isNotBlank() }

                issues.add(
                    UpcomingIssue(
                        mangaId = manga.id,
                        mangaTitle = manga.title,
                        thumbnailUrl = manga.thumbnailUrl,
                        issueTitle = issue.optString("name", "").ifBlank { manga.title },
                        issueNumber = issue.optString("issue_number").takeIf { it.isNotBlank() },
                        storeDate = storeDate,
                        coverUrl = coverUrl,
                        description = issue.optString("description").takeIf { it.isNotBlank() },
                    ),
                )
            }
            issues
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
                        if (volumeId == null) return@async emptyList()
                        val issues = getUpcomingIssues(manga, volumeId)
                        android.util.Log.d("ComicVine", "Issues found for ${manga.title}: ${issues.size}")
                        issues
                    }
                }.awaitAll().flatten()
            }

            android.util.Log.d("ComicVine", "Total issues found: ${allIssues.size}")

            allIssues
                .groupBy { it.storeDate }
                .map { (date, issues) -> UpcomingDay(date, issues.sortedBy { it.mangaTitle }) }
                .sortedBy { it.date }
        }
    }
}
