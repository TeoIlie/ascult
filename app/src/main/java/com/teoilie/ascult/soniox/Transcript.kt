package com.teoilie.ascult.soniox

/** Text received so far for one language: [final] only grows; [provisional] is replaced on every response. */
data class Transcript(val final: String = "", val provisional: String = "") {
    fun update(tokens: List<Token>) = Transcript(
        final = final + tokens.filter { it.isFinal }.joinToString("") { it.text },
        provisional = tokens.filterNot { it.isFinal }.joinToString("") { it.text },
    )
}

/** The text of one Start→Stop run, labelled with the audio settings it used. */
data class Segment(val label: String, val romanian: Transcript = Transcript(), val english: Transcript = Transcript())

/** Applies one Soniox response to the current (last) segment, routing original and translated text. */
fun List<Segment>.withTokens(tokens: List<Token>): List<Segment> {
    val current = last()
    return dropLast(1) + current.copy(
        romanian = current.romanian.update(tokens.filterNot { it.isTranslation }),
        english = current.english.update(tokens.filter { it.isTranslation }),
    )
}

/** Drops the current segment's provisional text, e.g. when its connection is lost. */
fun List<Segment>.withoutProvisional(): List<Segment> {
    val current = last()
    return dropLast(1) + current.copy(
        romanian = Transcript(current.romanian.final),
        english = Transcript(current.english.final),
    )
}

/** Plain-text transcript for sharing and saving: each segment's label, then its Romanian and English. */
fun List<Segment>.toPlainText(): String = joinToString("\n\n") { segment ->
    val romanian = (segment.romanian.final + segment.romanian.provisional).trim()
    val english = (segment.english.final + segment.english.provisional).trim()
    "[${segment.label}]\nRO: $romanian\nEN: $english"
}
