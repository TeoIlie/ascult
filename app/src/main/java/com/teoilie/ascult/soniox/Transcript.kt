package com.teoilie.ascult.soniox

/** Text received so far for one language: [final] only grows; [provisional] is replaced on every response. */
data class Transcript(val final: String = "", val provisional: String = "") {
    fun update(tokens: List<Token>) = Transcript(
        final = final + tokens.filter { it.isFinal }.joinToString("") { it.text },
        provisional = tokens.filterNot { it.isFinal }.joinToString("") { it.text },
    )
}

/**
 * Claude's English for a run in Claude mode: [english] translates the run's final Romanian up to
 * offset [translatedUpTo]. [failed] means Claude stopped translating, so Soniox's English is shown instead.
 */
data class ClaudeText(val english: String = "", val translatedUpTo: Int = 0, val failed: Boolean = false)

/**
 * The text of one Start→Stop run, labelled with the audio settings it used. [english] is always
 * Soniox's translation; [claude] is set for runs translated by Claude.
 */
data class Segment(
    val label: String,
    val romanian: Transcript = Transcript(),
    val english: Transcript = Transcript(),
    val claude: ClaudeText? = null,
) {
    /** The English to show: Claude's while it is translating this run, otherwise Soniox's. */
    val shownEnglish: Transcript get() = if (claude != null && !claude.failed) Transcript(claude.english) else english
}

/** Applies one Soniox response to the current (last) segment, routing original and translated text. */
fun List<Segment>.withTokens(tokens: List<Token>): List<Segment> {
    val current = last()
    return dropLast(1) + current.copy(
        romanian = current.romanian.update(tokens.filterNot { it.isTranslation }),
        english = current.english.update(tokens.filter { it.isTranslation }),
    )
}

/** Appends Claude's translation of the current segment's Romanian up to [upTo]. */
fun List<Segment>.withClaudeEnglish(english: String, upTo: Int): List<Segment> {
    val current = last()
    val claude = current.claude ?: ClaudeText()
    val text = if (claude.english.isEmpty()) english else "${claude.english} $english"
    return dropLast(1) + current.copy(claude = claude.copy(english = text, translatedUpTo = upTo))
}

/** Marks the current segment's Claude translation as given up, so Soniox's English shows instead. */
fun List<Segment>.withClaudeFailed(): List<Segment> {
    val current = last()
    return dropLast(1) + current.copy(claude = (current.claude ?: ClaudeText()).copy(failed = true))
}

/** Drops the current segment's provisional text, e.g. when its connection is lost. */
fun List<Segment>.withoutProvisional(): List<Segment> {
    val current = last()
    return dropLast(1) + current.copy(
        romanian = Transcript(current.romanian.final),
        english = Transcript(current.english.final),
    )
}

/**
 * Plain-text transcript for sharing and saving: each segment's label, then its Romanian and English.
 * Claude runs keep Soniox's English too, as a record of anything Claude did not translate.
 */
fun List<Segment>.toPlainText(): String = joinToString("\n\n") { segment ->
    val romanian = segment.romanian.text()
    val soniox = segment.english.text()
    val claude = segment.claude
    if (claude == null) {
        "[${segment.label}]\nRO: $romanian\nEN: $soniox"
    } else {
        "[${segment.label}]\nRO: $romanian\nEN: ${claude.english}\nEN (Soniox): $soniox"
    }
}

/** All the text so far, final and provisional, without surrounding spaces. */
internal fun Transcript.text() = (final + provisional).trim()
