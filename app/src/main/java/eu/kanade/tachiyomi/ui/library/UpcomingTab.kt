package eu.kanade.tachiyomi.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.tachiyomi.data.preference.ComicVinePreferences
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.manga.interactor.GetLibraryManga
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface UpcomingState {
    data object NoApiKey : UpcomingState
    data object Loading : UpcomingState
    data object Empty : UpcomingState
    data class Error(val message: String) : UpcomingState
    data class Success(val days: List<UpcomingDay>) : UpcomingState
}

@Composable
fun UpcomingTab() {
    val context = LocalContext.current
    val prefs = remember { ComicVinePreferences(context) }
    val repo = remember { ComicVineRepository(context) }
    val getLibraryManga = remember { Injekt.get<GetLibraryManga>() }

    var state by remember { mutableStateOf<UpcomingState>(UpcomingState.Loading) }

    LaunchedEffect(Unit) {
        val apiKey = prefs.getApiKey()
        if (apiKey.isBlank()) {
            state = UpcomingState.NoApiKey
            return@LaunchedEffect
        }
        state = UpcomingState.Loading
        try {
            val libraryManga = getLibraryManga.await().map { it.manga }
            if (libraryManga.isEmpty()) {
                state = UpcomingState.Empty
                return@LaunchedEffect
            }
            val days = repo.getUpcomingForLibrary(libraryManga)
            state = if (days.isEmpty()) UpcomingState.Empty else UpcomingState.Success(days)
        } catch (e: Exception) {
            state = UpcomingState.Error(e.message ?: "Unknown error")
        }
    }

    when (val s = state) {
        is UpcomingState.NoApiKey -> CenteredMessage("No ComicVine API key set.\nAdd it via the ⋮ menu → ComicVine API.")
        is UpcomingState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is UpcomingState.Empty -> CenteredMessage("No upcoming releases found for your library.")
        is UpcomingState.Error -> CenteredMessage("Error: ${s.message}")
        is UpcomingState.Success -> UpcomingList(s.days)
    }
}

@Composable
private fun UpcomingList(days: List<UpcomingDay>) {
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        days.forEach { day ->
            item(key = day.date.toString()) {
                DateHeader(
                    date = day.date,
                    formatter = dateFormatter,
                )
            }
            items(
                items = day.issues,
                key = { "${it.mangaId}-${it.storeDate}-${it.issueNumber}" },
            ) { issue ->
                IssueRow(issue = issue)
            }
        }
    }
}

@Composable
private fun DateHeader(date: LocalDate, formatter: DateTimeFormatter) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(formatter)
    }
    // Add "th", "st", "nd", "rd" suffix to day
    val day = date.dayOfMonth
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    val fullLabel = if (date == today || date == today.plusDays(1)) {
        label
    } else {
        val base = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
        "$base$suffix"
    }

    Text(
        text = fullLabel,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun IssueRow(issue: UpcomingIssue) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Cover image
        val imageUrl = issue.coverUrl ?: issue.thumbnailUrl
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = issue.mangaTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 80.dp, height = 110.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Text info
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = issue.mangaTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!issue.issueNumber.isNullOrBlank()) {
                Text(
                    text = "Issue #${issue.issueNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!issue.issueTitle.isNullOrBlank() && issue.issueTitle != issue.mangaTitle) {
                Text(
                    text = issue.issueTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!issue.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = issue.description
                        .replace(Regex("<[^>]*>"), "") // strip HTML tags
                        .trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}
