package com.teoilie.ascult.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teoilie.ascult.R
import com.teoilie.ascult.history.HistoryEntry
import com.teoilie.ascult.soniox.toPlainText

/** A saved sermon: its details, the Romanian and English panes, and its recording (if any). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(entryId: String, onBack: () -> Unit, viewModel: HistoryViewModel = viewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val listening by viewModel.listening.collectAsStateWithLifecycle()
    val textScale by viewModel.textScale.collectAsStateWithLifecycle()
    val paneSplit by viewModel.paneSplit.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Missing while History is still loading after a restart, or once deleted.
    val entry = entries.find { it.id == entryId }
    val recording = entry?.let(viewModel::recordingFile)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (entry != null) {
                        Column {
                            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                entry.details(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (entry != null) {
                        IconMenu(R.drawable.ic_more_vert, "More") { close ->
                            @Composable
                            fun Item(label: String, @DrawableRes icon: Int, color: Color = Color.Unspecified, onClick: () -> Unit) =
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
                                    colors = if (color == Color.Unspecified) MenuDefaults.itemColors()
                                    else MenuDefaults.itemColors(textColor = color, leadingIconColor = color),
                                    onClick = {
                                        close()
                                        onClick()
                                    },
                                )
                            Item("Share text", R.drawable.ic_share) { shareText(context, entry) }
                            if (recording != null) {
                                Item("Share recording", R.drawable.ic_graphic_eq) {
                                    val mime = if (recording.extension == "wav") "audio/wav" else "audio/mp4"
                                    shareFile(context, entry.title, recording, mime)
                                }
                            }
                            Item("Rename", R.drawable.ic_edit) { renaming = true }
                            Item("Delete", R.drawable.ic_delete, MaterialTheme.colorScheme.error) {
                                viewModel.delete(listOf(entry.id))
                                onBack()
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            when {
                recording != null -> PlayerBar(recording, enabled = !listening)
                entry != null -> TextOnlyBar(onShare = { shareText(context, entry) })
            }
        },
    ) { padding ->
        if (entry == null) return@Scaffold
        SyncedPanes(
            segments = listOf(entry.segment()),
            showLiveCaption = false,
            textScale = textScale,
            split = paneSplit,
            onSplitChange = viewModel::setPaneSplit,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            live = false,
        )
    }

    if (renaming && entry != null) {
        RenameDialog(
            initial = entry.title,
            onSave = {
                viewModel.rename(entry, it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
}

private fun shareText(context: Context, entry: HistoryEntry) =
    share(context, entry.title, entry.title + "\n\n" + listOf(entry.segment()).toPlainText())

/** In place of the player when recording was off. */
@Composable
private fun TextOnlyBar(onShare: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_notes), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text("Text only", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Recording was off for this sermon",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onShare, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Share")
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(title) }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
