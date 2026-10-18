package com.teoilie.ascult.translation

/** Sentences this short (e.g. "De aceea.") wait to go with the next sentence, whose meaning they need. */
private const val MIN_WORDS = 4

/** Final text this long without a sentence end is sent anyway (about 15 s of speech). */
private const val MAX_WORDS = 40

private const val SENTENCE_ENDS = ".?!…"

/** Closing quotes and brackets that belong to the sentence before them. */
private const val CLOSERS = "\"'”’»)]"

/** Finished Romanian to translate: [text] ends at offset [end] of the final transcript. */
data class Chunk(val text: String, val end: Int)

/**
 * The next piece of [final] (the segment's final Romanian) after offset [from] to send to Claude,
 * or null to wait for more text. Sends up to the last sentence end, holding back trailing sentences
 * under [MIN_WORDS] words; cuts a run-on of more than [MAX_WORDS] words after its last comma (or
 * before its last word, which may still be incomplete). [flush] sends everything left, for Stop.
 */
fun nextChunk(final: String, from: Int, flush: Boolean = false): Chunk? {
    val pending = final.substring(from)
    if (pending.isBlank()) return null
    if (flush) return chunk(final, from, final.length)

    sentenceEnd(pending)?.let { return chunk(final, from, from + it) }
    if (words(pending) <= MAX_WORDS) return null

    val comma = pending.lastIndexOf(',') + 1
    if (comma > 0 && words(pending.substring(0, comma)) >= MIN_WORDS) return chunk(final, from, from + comma)
    return chunk(final, from, from + pending.trimEnd().indexOfLast { it.isWhitespace() }.coerceAtLeast(0))
}

/** End (exclusive) of the last sentence in [text] with at least [MIN_WORDS] words, or null. */
private fun sentenceEnd(text: String): Int? {
    var sentenceStart = 0
    var end: Int? = null
    var i = 0
    while (i < text.length) {
        if (text[i] in SENTENCE_ENDS) {
            var j = i + 1
            while (j < text.length && (text[j] in SENTENCE_ENDS || text[j] in CLOSERS)) j++
            if (words(text.substring(sentenceStart, j)) >= MIN_WORDS) end = j
            sentenceStart = j
            i = j
        } else {
            i++
        }
    }
    return end
}

private fun chunk(final: String, from: Int, end: Int): Chunk? =
    final.substring(from, end).trim().takeIf { it.isNotEmpty() }?.let { Chunk(it, end) }

private fun words(text: String): Int = text.split(' ', '\n', '\t').count { it.isNotBlank() }
