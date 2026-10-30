package com.teoilie.ascult.translation

import android.content.Context
import android.util.Log
import com.teoilie.ascult.LOG_TAG
import com.teoilie.ascult.soniox.Dictionary

/** Claude's English for a piece of Romanian, and the name of the model that wrote it. */
data class Translation(val english: String, val model: String)

/**
 * Translates a sermon piece by piece as one conversation with Claude, so each piece is translated
 * with everything said before it. Only successful exchanges are kept: a failed piece is simply sent
 * again (with whatever followed it) and the conversation keeps alternating.
 *
 * The model is the newest in [family], looked up with the first piece (so a failed lookup is retried
 * like a failed translation) and kept for the whole sermon, which also keeps its cache.
 */
class ClaudeTranslator(
    private val client: ClaudeClient,
    private val apiKey: String,
    private val system: String,
    private val family: String,
) {
    private val turns = mutableListOf<Turn>()
    private var model: ClaudeModel? = null

    suspend fun translate(romanian: String): Translation {
        val model = model ?: resolveModel()
        val reply = client.reply(apiKey, model, system, turns, romanian)
        turns += Turn(romanian, reply.text)
        with(reply.usage) {
            Log.i(LOG_TAG, "Claude turn ${turns.size}: input $input, cache read $cacheRead, cache write $cacheCreation, output $output")
        }
        return Translation(reply.text, model.name)
    }

    private suspend fun resolveModel(): ClaudeModel {
        val found = client.latestModel(apiKey, family)
            ?: throw ClaudeException(404, "not_found_error", "no Claude ${family.replaceFirstChar { it.uppercase() }} model available")
        Log.i(LOG_TAG, "Claude model: ${found.id}, thinking ${found.thinkingOff ?: if (found.lowEffort) "low effort" else "default"}")
        model = found
        return found
    }

    companion object {
        /** The instructions in assets/claude_translation_prompt.txt; read off the main thread. */
        fun instructions(context: Context): String =
            context.assets.open("claude_translation_prompt.txt").bufferedReader().use { it.readText() }
    }
}

/**
 * [instructions] followed by the parts of [dictionary] that help translation: the setting
 * (`general`) and the preferred English renderings (`translation_terms`). The recognition terms
 * and `text` only help Soniox hear the Romanian, so they are left out.
 */
fun systemPrompt(instructions: String, dictionary: Dictionary): String = buildString {
    append(instructions.trim())
    if (dictionary.general.isNotEmpty()) {
        append("\n\nThe setting:\n")
        dictionary.general.joinTo(this, "\n") { (key, value) -> "- $key: $value" }
    }
    if (dictionary.translationTerms.isNotEmpty()) {
        append("\n\nPreferred renderings:\n")
        dictionary.translationTerms.joinTo(this, "\n") { (source, target) -> "- $source → $target" }
    }
}
