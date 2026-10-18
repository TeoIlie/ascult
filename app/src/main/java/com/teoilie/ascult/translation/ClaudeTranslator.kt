package com.teoilie.ascult.translation

import android.content.Context
import android.util.Log
import com.teoilie.ascult.soniox.Dictionary

private const val TAG = "Ascult"

/**
 * Translates a sermon piece by piece as one conversation with Claude, so each piece is translated
 * with everything said before it. Only successful exchanges are kept: a failed piece is simply sent
 * again (with whatever followed it) and the conversation keeps alternating.
 */
class ClaudeTranslator(
    private val client: ClaudeClient,
    val apiKey: String,
    val dictionary: Dictionary,
    private val system: String,
) {
    private val turns = mutableListOf<Turn>()

    suspend fun translate(romanian: String): String {
        val reply = client.reply(apiKey, system, turns, romanian)
        turns += Turn(romanian, reply.text)
        with(reply.usage) {
            Log.i(TAG, "Claude turn ${turns.size}: input $input, cache read $cacheRead, cache write $cacheCreation, output $output")
        }
        return reply.text
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
