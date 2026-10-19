package com.teoilie.ascult.translation

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val URL = "https://api.anthropic.com/v1/messages"
private const val API_VERSION = "2023-06-01"
const val CLAUDE_MODEL = "claude-haiku-4-5"

/** Far more than a sentence or two of English needs; only a runaway reply would reach it. */
private const val MAX_TOKENS = 2048

/** A reply normally takes a second or two; a stalled request is retried rather than waited on. */
private const val CALL_TIMEOUT_SECONDS = 30L

/** One exchange of the conversation: a piece of Romanian and Claude's English for it. */
data class Turn(val romanian: String, val english: String)

/** Token counts of one request, for checking that caching works and what a sermon costs. */
data class Usage(val input: Int, val cacheRead: Int, val cacheCreation: Int, val output: Int)

data class Reply(val text: String, val usage: Usage)

/** An error response from the Claude API; rate limits, overload and server errors are worth retrying. */
class ClaudeException(val status: Int, val type: String, message: String) : Exception("Claude error $status ($type): $message") {
    val retryable get() = status == 408 || status == 429 || status >= 500
}

/**
 * Calls the Claude Messages API over plain HTTP: the app needs only text in and text out, which
 * doesn't justify the official SDK's size (see CLAUDE.md).
 */
class ClaudeClient(
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build(),
) {
    /** Sends the conversation so far ([turns]) plus [next], and returns Claude's reply to [next]. */
    suspend fun reply(apiKey: String, system: String, turns: List<Turn>, next: String): Reply {
        val request = Request.Builder()
            .url(URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .post(requestJson(system, turns, next).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val (status, body) = execute(http.newCall(request))
        // A proxy or gateway may answer with HTML; that is an error response, not a broken reply.
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (status !in 200..299) throw parseError(status, json ?: JSONObject())
        return parseReply(json ?: throw IOException("Unreadable reply from Claude"))
    }

    /** Runs [call] off the main thread; cancelling the coroutine cancels the request. */
    private suspend fun execute(call: Call): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { it.code to it.body.string() } }
                result.onSuccess { continuation.resume(it) }.onFailure { continuation.resumeWithException(it) }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        })
    }
}

/**
 * The request body: [system], then the earlier turns as alternating user and assistant messages,
 * then [next]. The conversation only grows at the end, so automatic prompt caching reuses all of it.
 * The Romanian is wrapped in `<romanian>` tags so Claude treats it as text to translate: unwrapped,
 * a first sentence like "Is it working?" was answered instead of translated.
 */
internal fun requestJson(system: String, turns: List<Turn>, next: String): JSONObject {
    val messages = JSONArray()
    for (turn in turns) {
        messages.put(message("user", romanian(turn.romanian)))
        messages.put(message("assistant", turn.english))
    }
    messages.put(message("user", romanian(next)))
    return JSONObject()
        .put("model", CLAUDE_MODEL)
        .put("max_tokens", MAX_TOKENS)
        .put("system", system)
        .put("messages", messages)
        .put("cache_control", JSONObject().put("type", "ephemeral"))
}

private fun romanian(text: String) = "<romanian>$text</romanian>"

private fun message(role: String, text: String) = JSONObject().put("role", role).put("content", text)

/** The reply's text blocks joined, and its token counts. */
internal fun parseReply(json: JSONObject): Reply {
    val content = json.optJSONArray("content") ?: JSONArray()
    val text = (0 until content.length())
        .map { content.getJSONObject(it) }
        .filter { it.optString("type") == "text" }
        .joinToString("") { it.optString("text") }
    val usage = json.optJSONObject("usage") ?: JSONObject()
    return Reply(
        text = text.trim(),
        usage = Usage(
            input = usage.optInt("input_tokens"),
            cacheRead = usage.optInt("cache_read_input_tokens"),
            cacheCreation = usage.optInt("cache_creation_input_tokens"),
            output = usage.optInt("output_tokens"),
        ),
    )
}

internal fun parseError(status: Int, json: JSONObject): ClaudeException {
    val error = json.optJSONObject("error") ?: JSONObject()
    return ClaudeException(status, error.optString("type", "unknown"), error.optString("message", "no details"))
}
