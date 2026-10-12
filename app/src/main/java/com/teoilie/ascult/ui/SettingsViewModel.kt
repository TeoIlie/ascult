package com.teoilie.ascult.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teoilie.ascult.settings
import com.teoilie.ascult.soniox.Dictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

data class SettingsForm(val apiKey: String, val dictionary: DictionaryText, val dictionaryIsDefault: Boolean)

/** Edits are saved as they are typed; the form keeps its own text so typing is never interrupted. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.settings
    private lateinit var defaultDictionary: Dictionary

    var form by mutableStateOf<SettingsForm?>(null)
        private set

    init {
        viewModelScope.launch {
            defaultDictionary = withContext(Dispatchers.IO) { Dictionary.default(application) }
            val dictionary = settings.dictionary.first()
            form = SettingsForm(
                apiKey = settings.apiKey.first(),
                dictionary = DictionaryText.from(dictionary ?: defaultDictionary),
                dictionaryIsDefault = dictionary == null,
            )
        }
    }

    fun setApiKey(key: String) {
        form = form?.copy(apiKey = key)
        viewModelScope.launch { settings.setApiKey(key.trim()) }
    }

    fun setDictionary(text: DictionaryText) {
        // Edits that match the bundled default are stored as "no override".
        val dictionary = text.toDictionary().takeIf { it != defaultDictionary }
        form = form?.copy(dictionary = text, dictionaryIsDefault = dictionary == null)
        viewModelScope.launch { settings.setDictionary(dictionary) }
    }

    fun resetDictionary() {
        form = form?.copy(dictionary = DictionaryText.from(defaultDictionary), dictionaryIsDefault = true)
        viewModelScope.launch { settings.setDictionary(null) }
    }

    /** JSON in the format of assets/dictionary.json, to copy edits back into the repo. */
    fun dictionaryJson(): String = form?.dictionary?.toDictionary()?.toJson()?.toString(2).orEmpty()
}
