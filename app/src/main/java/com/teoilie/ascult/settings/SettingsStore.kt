package com.teoilie.ascult.settings

import android.content.Context
import android.media.MediaRecorder
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Android microphone sources worth comparing for a distant speaker; each applies different processing. */
enum class MicSource(val label: String, val description: String, val audioSource: Int) {
    VOICE_RECOGNITION("Voice recognition", "Tuned for speech recognition", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    UNPROCESSED("Unprocessed", "Raw signal, no noise suppression or gain control", MediaRecorder.AudioSource.UNPROCESSED),
    MIC("Mic", "Default microphone processing", MediaRecorder.AudioSource.MIC),
    CAMCORDER("Camcorder", "Video-recording processing, may use another mic", MediaRecorder.AudioSource.CAMCORDER),
}

const val MAX_GAIN_DB = 24

data class AudioSettings(
    val source: MicSource = MicSource.VOICE_RECOGNITION,
    val gainDb: Int = 0,
    val saveRecording: Boolean = false,
)

private val Context.dataStore by preferencesDataStore("settings")

private val SOURCE = stringPreferencesKey("audio_source")
private val GAIN_DB = intPreferencesKey("gain_db")
private val SAVE_RECORDING = booleanPreferencesKey("save_recording")

class SettingsStore(context: Context) {
    private val dataStore = context.dataStore

    val audio: Flow<AudioSettings> = dataStore.data.map { it.toAudioSettings() }

    suspend fun updateAudio(transform: (AudioSettings) -> AudioSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toAudioSettings())
            prefs[SOURCE] = updated.source.name
            prefs[GAIN_DB] = updated.gainDb.coerceIn(0, MAX_GAIN_DB)
            prefs[SAVE_RECORDING] = updated.saveRecording
        }
    }
}

private fun Preferences.toAudioSettings() = AudioSettings(
    source = this[SOURCE]?.let { name -> MicSource.entries.find { it.name == name } } ?: MicSource.VOICE_RECOGNITION,
    gainDb = (this[GAIN_DB] ?: 0).coerceIn(0, MAX_GAIN_DB),
    saveRecording = this[SAVE_RECORDING] ?: false,
)
