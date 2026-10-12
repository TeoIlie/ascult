package com.teoilie.ascult.soniox

import android.content.Context
import org.json.JSONObject

/** Text received so far for one language: [final] only grows; [provisional] is replaced on every response. */
data class Transcript(val final: String = "", val provisional: String = "") {
    fun update(tokens: List<Token>) = Transcript(
        final = final + tokens.filter { it.isFinal }.joinToString("") { it.text },
        provisional = tokens.filterNot { it.isFinal }.joinToString("") { it.text },
    )
}

/** The text of one Start→Stop run, labelled with the audio settings it used. */
data class Segment(val label: String, val romanian: Transcript = Transcript(), val english: Transcript = Transcript())

/** The default dictionary (Soniox `context`), shared with tools/stream_test.py. */
fun loadDictionary(context: Context): JSONObject =
    context.assets.open("dictionary.json").bufferedReader().use { JSONObject(it.readText()) }
