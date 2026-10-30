package com.teoilie.ascult.translation

import com.teoilie.ascult.soniox.Dictionary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClaudeClientTest {
    @Test
    fun `request replays earlier turns as alternating messages, then the new Romanian in tags, with caching on`() {
        val json = requestJson(ClaudeModel("claude-sonnet-9", "Claude Sonnet 9"), "Translate.", listOf(Turn("Amin.", "Amen.")), "Doamne miluiește.")

        assertEquals("claude-sonnet-9", json.getString("model"))
        assertEquals("disabled", json.getJSONObject("thinking").getString("type"))
        assertFalse(json.has("output_config"))
        assertEquals("Translate.", json.getString("system"))
        assertEquals("ephemeral", json.getJSONObject("cache_control").getString("type"))
        val messages = json.getJSONArray("messages")
        assertEquals(
            listOf(
                "user" to "<romanian>Amin.</romanian>",
                "assistant" to "Amen.",
                "user" to "<romanian>Doamne miluiește.</romanian>",
            ),
            (0 until messages.length()).map { messages.getJSONObject(it).let { m -> m.getString("role") to m.getString("content") } },
        )
    }

    @Test
    fun `latest model is the family's most recently released, whatever the list order`() {
        val json = JSONObject(
            """
            {"data": [
              {"id": "claude-sonnet-4-6", "line": "sonnet", "display_name": "Claude Sonnet 4.6", "created_at": "2026-02-17T00:00:00Z"},
              {"id": "claude-opus-5", "line": "opus", "display_name": "Claude Opus 5", "created_at": "2026-09-01T00:00:00Z"},
              {"id": "claude-sonnet-5", "line": "sonnet", "display_name": "Claude Sonnet 5", "created_at": "2026-06-29T00:00:00Z"},
              {"id": "claude-haiku-4-5-20251001", "display_name": "Claude Haiku 4.5", "created_at": "2025-10-15T00:00:00Z"},
              {"id": "claude-3-5-haiku-20241022", "display_name": "Claude Haiku 3.5", "created_at": "2024-10-22T00:00:00Z"}
            ]}
            """,
        )
        assertEquals(ClaudeModel("claude-sonnet-5", "Claude Sonnet 5"), parseLatestModel(json, "sonnet"))
        assertEquals("by id when there is no line", "claude-haiku-4-5-20251001", parseLatestModel(json, "haiku")?.id)
        assertNull(parseLatestModel(json, "fable"))
        assertNull(parseLatestModel(JSONObject(), "haiku"))
    }

    @Test
    fun `thinking is turned off the way each model allows`() {
        fun model(thinking: String, effort: String = "{}") = parseLatestModel(
            JSONObject("""{"data": [{"id": "claude-sonnet-9", "line": "sonnet", "capabilities": {"thinking": $thinking, "effort": $effort}}]}"""),
            "sonnet",
        )!!
        fun types(disabled: Boolean, betweenTools: Boolean) =
            """{"supported": true, "types": {"adaptive": {"supported": true}, "disabled": {"supported": $disabled}, "between_tools": {"supported": $betweenTools}}}"""
        val lowEffort = """{"supported": true, "low": {"supported": true}}"""

        assertEquals("disabled", model(types(disabled = true, betweenTools = false)).thinkingOff)
        assertEquals("between_tools", model(types(disabled = false, betweenTools = true)).thinkingOff)

        val alwaysThinks = model(types(disabled = false, betweenTools = false), lowEffort)
        assertNull(alwaysThinks.thinkingOff)
        assertTrue(alwaysThinks.lowEffort)
        val request = requestJson(alwaysThinks, "Translate.", emptyList(), "Amin.")
        assertFalse(request.has("thinking"))
        assertEquals("low", request.getJSONObject("output_config").getString("effort"))

        val noThinking = model("""{"supported": false}""")
        assertNull(noThinking.thinkingOff)
        assertFalse(noThinking.lowEffort)
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
