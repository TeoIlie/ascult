package com.teoilie.ascult.soniox

import com.teoilie.ascult.audio.SAMPLE_RATE
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

private const val URL = "wss://stt-rt.soniox.com/transcribe-websocket"
private const val MODEL = "stt-rt-v5"

/** A connection that misses a pong for this long fails, so a silently stalled network triggers a reconnect. */
private const val PING_INTERVAL_SECONDS = 10L

/** One piece of text from Soniox: Romanian original or English translation, final or still provisional. */
data class Token(val text: String, val isFinal: Boolean, val isTranslation: Boolean)

/** An error reported by Soniox; timeouts, rate limits and server errors are worth retrying. */
class SonioxException(val code: Int, message: String) : Exception("Soniox error $code: $message") {
    val retryable get() = code == 408 || code == 429 || code >= 500
}

/** Streams audio to the Soniox real-time API; see CLAUDE.md for the protocol. */
class SonioxClient(
    private val http: OkHttpClient = OkHttpClient.Builder().pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS).build(),
) {

    /**
     * Sends [audio] (16 kHz mono PCM16 frames) and emits the tokens of each response. When [audio]
     * completes, the stream is ended and the flow completes once Soniox has finalised every token.
     */
    fun transcribe(apiKey: String, dictionary: Dictionary, audio: Flow<ShortArray>): Flow<List<Token>> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val response = JSONObject(text)
                if (response.has("error_code")) {
                    val error = SonioxException(response.optInt("error_code"), response.optString("error_message"))
                    this@callbackFlow.close(error)
                    return
                }
                trySend(parseTokens(response.optJSONArray("tokens")))
                if (response.optBoolean("finished")) this@callbackFlow.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@callbackFlow.close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@callbackFlow.close()
            }
        }

        val webSocket = http.newWebSocket(Request.Builder().url(URL).build(), listener)
        webSocket.send(config(apiKey, dictionary).toString())
        launch {
            audio.collect { frame -> webSocket.send(frame.toPcmBytes()) }
            webSocket.send("") // end of audio: Soniox finalises the remaining tokens, then sends "finished"
        }
        awaitClose { webSocket.cancel() }
    }.buffer(Channel.UNLIMITED)

    // Mirrored by build_config() in tools/stream_test.py; keep the two in sync.
    private fun config(apiKey: String, dictionary: Dictionary) = JSONObject().apply {
        put("api_key", apiKey)
        put("model", MODEL)
        put("audio_format", "pcm_s16le")
        put("sample_rate", SAMPLE_RATE)
        put("num_channels", 1)
        put("language_hints", JSONArray(listOf("ro")))
        put("translation", JSONObject().put("type", "one_way").put("target_language", "en"))
        put("context", dictionary.toJson())
    }

    private fun parseTokens(tokens: JSONArray?): List<Token> = buildList {
        if (tokens == null) return@buildList
        for (i in 0 until tokens.length()) {
            val token = tokens.getJSONObject(i)
            add(
                Token(
                    text = token.getString("text"),
                    isFinal = token.optBoolean("is_final"),
                    // "original" and "none" are both source-language text.
                    isTranslation = token.optString("translation_status") == "translation",
                ),
            )
        }
    }
}

private fun ShortArray.toPcmBytes(): ByteString {
    val buffer = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
    buffer.asShortBuffer().put(this)
    return buffer.array().toByteString()
}
