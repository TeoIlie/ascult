package com.teoilie.ascult.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teoilie.ascult.R
import com.teoilie.ascult.history.HistoryEntry
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Saved sermons, newest first, grouped by week and month; long-press selects several to delete. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenEntry: (String) -> Unit, viewModel: HistoryViewModel = viewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val deletedCount by viewModel.deletedCount.collectAsStateWithLifecycle()
    var selection by rememberSaveable { mutableStateOf<Set<String>?>(null) } // null: not selecting
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    BackHandler(enabled = selection != null) { selection = null }
    LaunchedEffect(deletedCount) {
        if (deletedCount == 0) return@LaunchedEffect
        val message = if (deletedCount == 1) "Sermon deleted" else "$deletedCount sermons deleted"
        val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.forgetDeleted()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val selected = selection
            if (selected == null) {
                LargeFlexibleTopAppBar(
                    title = { Text("History") },
                    subtitle = { Text(historySummary(entries)) },
                    navigationIcon = { BackButton(onBack) },
                    scrollBehavior = scrollBehavior,
                )
            } else {
                TopAppBar(
                    title = { Text(if (selected.isEmpty()) "Select sermons" else "${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selection = null }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = {
                                viewModel.delete(selected)
                                selection = null
                            },
                            enabled = selected.isNotEmpty(),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text("Delete") }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (entries.isEmpty()) {
            Text(
                "No saved sermons yet. After Stop, choose Save to keep one here.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(horizontal = 32.dp, vertical = 24.dp),
            )
            return@Scaffold
        }
        SettingsColumn(Modifier.fillMaxSize().padding(padding)) {
            for ((group, items) in historyGroups(entries, System.currentTimeMillis())) {
                SettingsGroup(group) {
                    items.forEachIndexed { index, entry ->
                        val selected = selection
                        EntryRow(
                            entry,
                            index,
                            items.size,
                            selected = selected?.contains(entry.id),
                            onClick = {
                                if (selected == null) onOpenEntry(entry.id)
                                else selection = if (entry.id in selected) selected - entry.id else selected + entry.id
                            },
                            onLongClick = { if (selected == null) selection = setOf(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

/** One sermon; [selected] is null when not selecting. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EntryRow(entry: HistoryEntry, index: Int, count: Int, selected: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit) {
    SegmentedListItem(
        selected = selected == true,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = {
            when (selected) {
                null -> TonalIcon(if (entry.audio != null) R.drawable.ic_graphic_eq else R.drawable.ic_notes)
                true -> Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
                false -> Box(Modifier.size(40.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val size = if (selected != null && entry.audioBytes > 0) " · ${megabytes(entry.audioBytes)} MB" else ""
                Text(entry.summary() + size)
                Text(
                    entry.shownEnglish,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        onLongClick = onLongClick,
        onLongClickLabel = "Select",
    ) { Text(entry.title) }
}

/** e.g. "7 sermons · 266 MB of recordings" */
private fun historySummary(entries: List<HistoryEntry>): String {
    val count = if (entries.size == 1) "1 sermon" else "${entries.size} sermons"
    val bytes = entries.sumOf { it.audioBytes }
    return if (bytes > 0) "$count · ${megabytes(bytes)} MB of recordings" else count
}

internal fun megabytes(bytes: Long): Long = (bytes + 500_000) / 1_000_000

/**
 * [entries] (newest first) under headings: "This week" (since Monday), then each month ("October",
 * with the year when it isn't this year's).
 */
internal fun historyGroups(
    entries: List<HistoryEntry>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<String, List<HistoryEntry>>> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
    val month = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)
    val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    return entries.groupBy { entry ->
        val date = Instant.ofEpochMilli(entry.startedAt).atZone(zone)
        when {
            entry.startedAt >= weekStart -> "This week"
            date.year == today.year -> month.format(date)
            else -> monthYear.format(date)
        }
    }.toList()
}
