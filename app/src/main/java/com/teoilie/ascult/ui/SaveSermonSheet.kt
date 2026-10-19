package com.teoilie.ascult.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.teoilie.ascult.R
import com.teoilie.ascult.session.Phase
import com.teoilie.ascult.session.SessionState
import com.teoilie.ascult.settings.Translator

/**
 * Asked after every Stop: keep the run in History (under a name) or throw it away. It can't be
 * dismissed; Save waits for Soniox and Claude to finish the last sentences.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SaveSermonSheet(state: SessionState, onSave: (title: String) -> Unit, onDelete: () -> Unit) {
    val entry = state.entry ?: return
    var title by rememberSaveable(entry.id) { mutableStateOf(entry.title) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })
    val recordingMb = remember(state.recording, state.phase) { state.recording?.let { (it.length() + 500_000) / 1_000_000 } }
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Save this sermon?", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(R.drawable.ic_schedule, "${entry.minutes} min")
                InfoChip(null, if (entry.translator == Translator.CLAUDE) "Claude translation" else "Soniox translation")
                if (recordingMb != null) InfoChip(R.drawable.ic_graphic_eq, "Recording · $recordingMb MB")
            }
            if (state.phase == Phase.STOPPING) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Finishing the last sentences… Save waits for them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                ) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Delete")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onSave(title) },
                    modifier = Modifier.padding(start = 8.dp),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Save")
                }
            }
        }
    }
}

/** A small read-only fact about the run. */
@Composable
private fun InfoChip(@DrawableRes icon: Int?, text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(painterResource(icon), contentDescription = null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
