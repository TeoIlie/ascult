package com.teoilie.ascult.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teoilie.ascult.app
import com.teoilie.ascult.settings.ThemeMode
import com.teoilie.ascult.soniox.Dictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The dictionary as editable text: one entry per line. */
data class DictionaryText(
    val terms: String = "",
    val translationTerms: String = "",
    val general: String = "",
    val text: String = "",
) {
    fun toDictionary() = Dictionary(
        general = general.pairs(':'),
        text = text.trim(),
        terms = terms.lines().map { it.trim() }.filter { it.isNotEmpty() },
        translationTerms = translationTerms.pairs('='),
    )

    companion object {
        fun from(dictionary: Dictionary) = DictionaryText(
            terms = dictionary.terms.joinToString("\n"),
            translationTerms = dictionary.translationTerms.joinToString("\n") { (source, target) -> "$source = $target" },
            general = dictionary.general.joinToString("\n") { (key, value) -> "$key: $value" },
            text = dictionary.text,
        )
    }
}

/** "a = b" lines to pairs, skipping lines without both sides. */
private fun String.pairs(separator: Char): List<Pair<String, String>> = lines().mapNotNull { line ->
    val left = line.substringBefore(separator, "").trim()
    val right = line.substringAfter(separator, "").trim()
    if (left.isEmpty() || right.isEmpty()) null else left to right
}

data class SettingsForm(
    val apiKey: String,
    val anthropicApiKey: String,
    val dictionary: DictionaryText,
    val dictionaryIsDefault: Boolean,
    /** The bundled dictionary, for Reset and for recognising edits that match it. */
    val defaultDictionary: Dictionary,
) {
    /** Where the dictionary in use comes from, as shown in Settings and on the dictionary screen. */
    val dictionarySource: String get() = if (dictionaryIsDefault) "Bundled default" else "Edited in the app"
}

/** Edits are saved as they are typed; the form keeps its own text so typing is never interrupted. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.app.settings

    var form by mutableStateOf<SettingsForm?>(null)
        private set

    val theme: StateFlow<ThemeMode> =
        settings.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setTheme(mode) }
    }

    init {
        viewModelScope.launch {
            val defaultDictionary = withContext(Dispatchers.IO) { Dictionary.default(application) }
            val dictionary = settings.dictionary.first()
            form = SettingsForm(
                apiKey = settings.apiKey.first(),
                anthropicApiKey = settings.anthropicApiKey.first(),
                dictionary = DictionaryText.from(dictionary ?: defaultDictionary),
                dictionaryIsDefault = dictionary == null,
                defaultDictionary = defaultDictionary,
            )
        }
    }

    fun setApiKey(key: String) {
        form = form?.copy(apiKey = key)
        viewModelScope.launch { settings.setApiKey(key.trim()) }
    }

    fun setAnthropicApiKey(key: String) {
        form = form?.copy(anthropicApiKey = key)
        viewModelScope.launch { settings.setAnthropicApiKey(key.trim()) }
    }

    fun setDictionary(text: DictionaryText) {
        val current = form ?: return
        // Edits that match the bundled default are stored as "no override".
        val dictionary = text.toDictionary().takeIf { it != current.defaultDictionary }
        form = current.copy(dictionary = text, dictionaryIsDefault = dictionary == null)
        viewModelScope.launch { settings.setDictionary(dictionary) }
    }

    fun resetDictionary() {
        val current = form ?: return
        form = current.copy(dictionary = DictionaryText.from(current.defaultDictionary), dictionaryIsDefault = true)
        viewModelScope.launch { settings.setDictionary(null) }
    }

    /** JSON in the format of assets/dictionary.json, to copy edits back into the repo. */
    fun dictionaryJson(): String = form?.dictionary?.toDictionary()?.toJson()?.toString(2).orEmpty()
}
