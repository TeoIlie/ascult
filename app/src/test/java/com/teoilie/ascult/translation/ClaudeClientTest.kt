package com.teoilie.ascult.translation

import com.teoilie.ascult.soniox.Dictionary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClaudeClientTest {
    @Test
    fun `request replays earlier turns as alternating messages, then the new Romanian, with caching on`() {
        val json = requestJson("Translate.", listOf(Turn("Amin.", "Amen.")), "Doamne miluiește.")

        assertEquals(CLAUDE_MODEL, json.getString("model"))
        assertEquals("Translate.", json.getString("system"))
        assertEquals("ephemeral", json.getJSONObject("cache_control").getString("type"))
        val messages = json.getJSONArray("messages")
        assertEquals(
            listOf("user" to "Amin.", "assistant" to "Amen.", "user" to "Doamne miluiește."),
            (0 until messages.length()).map { messages.getJSONObject(it).let { m -> m.getString("role") to m.getString("content") } },
        )
    }

    @Test
    fun `reply joins its text blocks and reads the token counts`() {
        val json = JSONObject(
            """
            {"content": [{"type": "text", "text": "Lord, "}, {"type": "text", "text": "have mercy. "}],
             "usage": {"input_tokens": 12, "cache_read_input_tokens": 5000, "cache_creation_input_tokens": 40, "output_tokens": 6}}
            """,
        )
        assertEquals(Reply("Lord, have mercy.", Usage(input = 12, cacheRead = 5000, cacheCreation = 40, output = 6)), parseReply(json))
    }

    @Test
    fun `errors keep their type, and only temporary ones are retryable`() {
        val body = JSONObject("""{"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key"}}""")
        val auth = parseError(401, body)
        assertEquals("authentication_error", auth.type)
        assertEquals("Claude error 401 (authentication_error): invalid x-api-key", auth.message)
        assertFalse(auth.retryable)

        assertTrue(parseError(529, JSONObject()).retryable)
        assertTrue(parseError(429, JSONObject()).retryable)
        assertFalse(parseError(400, JSONObject()).retryable)
    }

    @Test
    fun `system prompt adds the setting and preferred renderings from the bundled dictionary`() {
        // Unit tests run with app/ as the working directory.
        val instructions = File("src/main/assets/claude_translation_prompt.txt").readText()
        val dictionary = Dictionary.fromJson(JSONObject(File("src/main/assets/dictionary.json").readText()))

        val prompt = systemPrompt(instructions, dictionary)

        assertTrue(prompt.startsWith(instructions.trim()))
        assertTrue(prompt.contains("- domain: Eastern Orthodox Christianity"))
        assertTrue(prompt.contains("- Maica Domnului → the Mother of God"))
        assertFalse("recognition-only text is left out", prompt.contains(dictionary.text.take(40)))
    }

    @Test
    fun `system prompt without a dictionary is just the instructions`() {
        assertEquals("Translate.", systemPrompt("Translate.\n", Dictionary()))
    }
}
