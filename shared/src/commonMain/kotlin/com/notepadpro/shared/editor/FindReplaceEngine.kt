package com.notepadpro.shared.editor

import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.InlineSpan
import com.notepadpro.shared.domain.model.TextCodec

/** Literal, non-overlapping matches in original-text coordinates. End is exclusive. */
data class FindMatch(
    val lineId: String,
    val lineIndex: Int,
    val start: Int,
    val end: Int,
    val lineText: String
)

object FindReplaceEngine {
    fun findAll(lines: List<EditorLine>, query: String, caseSensitive: Boolean): List<FindMatch> {
        if (query.isEmpty()) return emptyList()
        val result = ArrayList<FindMatch>()
        for ((index, line) in lines.withIndex()) {
            val text = line.plainText
            var from = 0
            while (from <= text.length - query.length) {
                // Lowercasing entire strings can change Unicode string lengths
                // and corrupt offsets. Search the original text instead.
                val hit = text.indexOf(query, from, ignoreCase = !caseSensitive)
                if (hit < 0) break
                result.add(FindMatch(line.id, index, hit, hit + query.length, text))
                from = hit + query.length
            }
        }
        return result
    }

    /** Never apply a stale match to a different row or an edited text snapshot. */
    fun replaceOne(
        lines: List<EditorLine>,
        match: FindMatch,
        replacement: String
    ): Pair<List<EditorLine>, Int> {
        val line = lines.getOrNull(match.lineIndex) ?: return lines to 0
        val text = line.plainText
        if (line.id != match.lineId || text != match.lineText ||
            match.start < 0 || match.end <= match.start || match.end > text.length
        ) return lines to 0
        val normalized = TextCodec.normalizeLineEndings(replacement)
        val (before, _, after) = clipSpans(line.spans, match.start, match.end)
        val spans = before + listOf(replacementSpan(line, match.start, match.end, normalized)) + after
        val replacementLines = splitRichLines(line, spans)
        val out = lines.toMutableList()
        out.removeAt(match.lineIndex)
        out.addAll(match.lineIndex, replacementLines)
        val caret = if ('\n' in normalized) normalized.substringAfterLast('\n').length else match.start + normalized.length
        return out to caret
    }

    /** Replace the original matches once; preserve formatting outside each hit. */
    fun replaceAll(
        lines: List<EditorLine>,
        query: String,
        replacement: String,
        caseSensitive: Boolean
    ): Pair<List<EditorLine>, Int> {
        if (query.isEmpty()) return lines to 0
        val normalized = TextCodec.normalizeLineEndings(replacement)
        val out = ArrayList<EditorLine>(lines.size)
        var replaced = 0
        for (line in lines) {
            val text = line.plainText
            val spans = ArrayList<InlineSpan>()
            var cursor = 0
            var hits = 0
            while (cursor <= text.length - query.length) {
                val hit = text.indexOf(query, cursor, ignoreCase = !caseSensitive)
                if (hit < 0) break
                spans.addAll(clipSpans(line.spans, cursor, hit).second)
                if (normalized.isNotEmpty()) spans.add(replacementSpan(line, hit, hit + query.length, normalized))
                cursor = hit + query.length
                hits++
            }
            if (hits == 0) {
                out.add(line)
            } else {
                spans.addAll(clipSpans(line.spans, cursor, text.length).second)
                out.addAll(splitRichLines(line, spans))
                replaced += hits
            }
        }
        return out to replaced
    }
}
