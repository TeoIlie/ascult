package com.teoilie.ascult.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.teoilie.ascult.settings.AudioSettings
import com.teoilie.ascult.settings.MAX_GAIN_DB
import com.teoilie.ascult.settings.MicSource
import com.teoilie.ascult.settings.Translator
import kotlin.math.roundToInt

private const val GAIN_STEP_DB = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsSheet(
    settings: AudioSettings,
    listening: Boolean,
    /** Whether an Anthropic API key is available, which Claude translation needs. */
    claudeKeySet: Boolean,
    onChange: ((AudioSettings) -> AudioSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SettingsColumn(Modifier.fillMaxWidth()) {
            SettingsGroup(
                "Microphone source",
                note = if (listening) "Source, recording and translation changes apply from the next Start." else null,
            ) {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    MicSource.entries.forEachIndexed { index, source ->
                        SegmentedListItem(
                            selected = settings.source == source,
                            onClick = { onChange { it.copy(source = source) } },
                            shapes = ListItemDefaults.segmentedShapes(index, MicSource.entries.size),
                            leadingContent = { RadioButton(selected = settings.source == source, onClick = null) },
                            supportingContent = { Text(source.description) },
                        ) { Text(source.label) }
                    }
                }
            }

            SettingsGroup("Gain: +${settings.gainDb} dB") {
                Slider(
                    value = settings.gainDb.toFloat(),
                    onValueChange = { value -> onChange { it.copy(gainDb = value.roundToInt()) } },
                    valueRange = 0f..MAX_GAIN_DB.toFloat(),
                    steps = MAX_GAIN_DB / GAIN_STEP_DB - 1,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            SettingsGroup("Translation") {
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(0, 2),
                    supportingContent = {
                        Text(
                            when {
                                settings.translator == Translator.SONIOX -> "Live from Soniox, a few words at a time"
                                claudeKeySet -> "Each finished sentence, with the whole sermon as context"
                                else -> "Add an Anthropic API key in Settings"
                            },
                        )
                    },
                ) { Text("English by") }
                SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(1, 2)) {
                    ConnectedToggleGroup(Translator.entries, settings.translator, Translator::label) { translator ->
                        onChange { it.copy(translator = translator) }
                    }
                }
            }

            SettingsGroup("Recording") {
                SegmentedListItem(
                    checked = settings.saveRecording,
                    onCheckedChange = { save -> onChange { it.copy(saveRecording = save) } },
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    trailingContent = { Switch(checked = settings.saveRecording, onCheckedChange = null) },
                    supportingContent = { Text("WAV of exactly what is sent to Soniox, for comparing settings") },
                ) { Text("Save recording") }
            }
        }
    }
}
