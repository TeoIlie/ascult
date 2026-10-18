package com.teoilie.ascult.settings

import android.content.Context
import android.media.MediaRecorder
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.teoilie.ascult.soniox.Dictionary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** Android microphone sources worth comparing for a distant speaker; each applies different processing. */
enum class MicSource(val label: String, val description: String, val audioSource: Int) {
    VOICE_RECOGNITION("Voice recognition", "Tuned for speech recognition", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    UNPROCESSED("Unprocessed", "Raw signal, no noise suppression or gain control", MediaRecorder.AudioSource.UNPROCESSED),
    MIC("Mic", "Default microphone processing", MediaRecorder.AudioSource.MIC),
    CAMCORDER("Camcorder", "Video-recording processing, may use another mic", MediaRecorder.AudioSource.CAMCORDER),
}

/** App colours: follow the phone's dark theme setting, or force one. */
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

const val MAX_GAIN_DB = 24

/** Transcript text sizes, as multiples of the default body text size. */
val TEXT_SCALES = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f)

data class AudioSettings(
    val source: MicSource = MicSource.VOICE_RECOGNITION,
    val gainDb: Int = 0,
    val saveRecording: Boolean = false,
) {
    /** "Voice recognition · +12 dB", as shown in the status and segment labels. */
    val summary: String get() = "${source.label} · +$gainDb dB"
}

/** Pane split that gives Romanian and English the same height. */
const val EVEN_SPLIT = 0.5f

private val Context.dataStore by preferencesDataStore("settings")

private val SOURCE = stringPreferencesKey("audio_source")
private val GAIN_DB = intPreferencesKey("gain_db")
private val SAVE_RECORDING = booleanPreferencesKey("save_recording")
private val API_KEY = stringPreferencesKey("soniox_api_key")
private val DICTIONARY = stringPreferencesKey("dictionary_json")
private val TEXT_SCALE = floatPreferencesKey("text_scale")
private val THEME = stringPreferencesKey("theme")
private val PANE_SPLIT = floatPreferencesKey("pane_split")

class SettingsStore(context: Context) {
    private val dataStore = context.dataStore

    val audio: Flow<AudioSettings> = dataStore.data.map { it.toAudioSettings() }

    /** Entered in Settings; stored unencrypted in app-private storage (backups are disabled). */
    val apiKey: Flow<String> = dataStore.data.map { it[API_KEY].orEmpty() }

    suspend fun setApiKey(key: String) {
        dataStore.edit { it[API_KEY] = key }
    }

    /** The in-app dictionary override, or null to use the bundled default. */
    val dictionary: Flow<Dictionary?> = dataStore.data.map { prefs ->
        prefs[DICTIONARY]?.let { json -> runCatching { Dictionary.fromJson(JSONObject(json)) }.getOrNull() }
    }

    suspend fun setDictionary(dictionary: Dictionary?) {
        dataStore.edit { prefs ->
            if (dictionary == null) prefs.remove(DICTIONARY) else prefs[DICTIONARY] = dictionary.toJson().toString()
        }
    }

    val textScale: Flow<Float> = dataStore.data.map { it[TEXT_SCALE] ?: 1f }

    suspend fun setTextScale(scale: Float) {
        dataStore.edit { it[TEXT_SCALE] = scale }
    }

    val theme: Flow<ThemeMode> = dataStore.data.map { it.enum(THEME, ThemeMode.SYSTEM) }

    suspend fun setTheme(mode: ThemeMode) {
        dataStore.edit { it[THEME] = mode.name }
    }

    /** Romanian pane's share of the panes' height: 0 collapses it, 1 collapses English. */
    val paneSplit: Flow<Float> = dataStore.data.map { (it[PANE_SPLIT] ?: EVEN_SPLIT).coerceIn(0f, 1f) }

    suspend fun setPaneSplit(split: Float) {
        dataStore.edit { it[PANE_SPLIT] = split.coerceIn(0f, 1f) }
    }

    suspend fun updateAudio(transform: (AudioSettings) -> AudioSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toAudioSettings())
            prefs[SOURCE] = updated.source.name
            prefs[GAIN_DB] = updated.gainDb.coerceIn(0, MAX_GAIN_DB)
            prefs[SAVE_RECORDING] = updated.saveRecording
        }
    }
}

/** An enum stored by name, or [default] if missing or no longer a valid name. */
private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
    this[key]?.let { name -> enumValues<E>().find { it.name == name } } ?: default

private fun Preferences.toAudioSettings() = AudioSettings(
    source = enum(SOURCE, MicSource.VOICE_RECOGNITION),
    gainDb = (this[GAIN_DB] ?: 0).coerceIn(0, MAX_GAIN_DB),
    saveRecording = this[SAVE_RECORDING] ?: false,
)
