package com.teoilie.ascult.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.teoilie.ascult.settings.AudioSettings
import com.teoilie.ascult.settings.MAX_GAIN_DB
import com.teoilie.ascult.settings.MicSource
import kotlin.math.roundToInt

private const val GAIN_STEP_DB = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsSheet(
    settings: AudioSettings,
    listening: Boolean,
    onChange: ((AudioSettings) -> AudioSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Microphone source", style = MaterialTheme.typography.titleMedium)
            if (listening) {
                Text(
                    "Source and recording changes apply from the next Start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.selectableGroup()) {
                MicSource.entries.forEach { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = settings.source == source,
                                onClick = { onChange { it.copy(source = source) } },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.source == source, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(source.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                source.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("Gain: +${settings.gainDb} dB", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.gainDb.toFloat(),
                onValueChange = { value -> onChange { it.copy(gainDb = value.roundToInt()) } },
                valueRange = 0f..MAX_GAIN_DB.toFloat(),
                steps = MAX_GAIN_DB / GAIN_STEP_DB - 1,
            )

            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.saveRecording,
                        onValueChange = { save -> onChange { it.copy(saveRecording = save) } },
                        role = Role.Switch,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Save recording", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "WAV of exactly what is sent to Soniox, for comparing settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.saveRecording, onCheckedChange = null)
            }
        }
    }
}
