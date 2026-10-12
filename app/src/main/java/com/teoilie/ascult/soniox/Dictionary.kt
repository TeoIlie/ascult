package com.teoilie.ascult.soniox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Custom vocabulary sent to Soniox as `context`. The default ships in assets/dictionary.json (shared
 * with tools/stream_test.py); edits made in the app are stored as an override in settings.
 */
data class Dictionary(
    val general: List<Pair<String, String>> = emptyList(),
    val text: String = "",
    val terms: List<String> = emptyList(),
    val translationTerms: List<Pair<String, String>> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("general", JSONArray(general.map { (key, value) -> JSONObject().put("key", key).put("value", value) }))
        put("text", text)
        put("terms", JSONArray(terms))
        put(
            "translation_terms",
            JSONArray(translationTerms.map { (source, target) -> JSONObject().put("source", source).put("target", target) }),
        )
    }

    companion object {
        fun fromJson(json: JSONObject) = Dictionary(
            general = json.optJSONArray("general").objects().map { it.getString("key") to it.getString("value") },
            text = json.optString("text"),
            terms = json.optJSONArray("terms").strings(),
            translationTerms = json.optJSONArray("translation_terms").objects()
                .map { it.getString("source") to it.getString("target") },
        )

        fun default(context: Context): Dictionary =
            context.assets.open("dictionary.json").bufferedReader().use { fromJson(JSONObject(it.readText())) }
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else List(length()) { getJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else List(length()) { getString(it) }
