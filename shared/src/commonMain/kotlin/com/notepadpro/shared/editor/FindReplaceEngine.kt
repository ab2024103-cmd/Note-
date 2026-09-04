package com.notepadpro.shared.editor

import com.notepadpro.shared.domain.model.EditorLine

/**
 * Find & Replace over the line model. Plain-text search, one line at a time
 * (matches never span lines), so results map 1:1 onto editor lines and
 * scrolling/highlighting stays trivial and cheap.
 */
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
        val q = if (caseSensitive) query else query.lowercase()
        val result = ArrayList<FindMatch>()
        var index = 0
        for (line in lines) {
            val text = line.plainText
            if (text.isNotEmpty()) {
                val hay = if (caseSensitive) text else text.lowercase()
                var from = 0
                while (true) {
                    val hit = hay.indexOf(q, from)
                    if (hit < 0) break
                    result.add(FindMatch(line.id, index, hit, hit + q.length, text))
                    from = hit + q.length
                    if (from >= hay.length) break
                }
            }
            index++
        }
        return result
    }

    /**
     * Replaces the given single match inside [lines], returning the new line
     * list plus the caret position of the replacement in that line.
     */
    fun replaceOne(
        lines: List<EditorLine>,
        match: FindMatch,
        replacement: String,
        caseSensitive: Boolean
    ): Pair<List<EditorLine>, Int> {
        val line = lines.getOrNull(match.lineIndex) ?: return lines to 0
        val text = line.plainText
        if (match.start < 0 || match.end > text.length) return lines to 0
        val newText = text.substring(0, match.start) + replacement + text.substring(match.end)
        val newSpans = remapSpansForEdit(
            line.spans, text, newText,
            commonPrefixLen(text, newText), commonSuffixLen(text, newText)
        )
        val newLine = line.copy(spans = newSpans)
        val out = lines.toMutableList()
        out[match.lineIndex] = newLine
        return out to (match.start + replacement.length)
    }

    /** Replaces every match, returns the new lines and the number replaced. */
    fun replaceAll(
        lines: List<EditorLine>,
        query: String,
        replacement: String,
        caseSensitive: Boolean
    ): Pair<List<EditorLine>, Int> {
        val q = if (caseSensitive) query else query.lowercase()
        var replaced = 0
        val out = lines.toMutableList()
        for (i in out.indices) {
            val line = out[i]
            val text = line.plainText
            if (text.isEmpty() || q.isEmpty()) continue
            val h = if (caseSensitive) text else text.lowercase()

            // Count matches first so we can skip untouched lines cheaply.
            var found = 0
            var pos = 0
            while (true) {
                val hit = h.indexOf(q, pos)
                if (hit < 0) break
                found++
                pos = hit + q.length
            }
            if (found == 0) continue

            val builder = StringBuilder(text.length + replacement.length * found)
            var cursor = 0
            var searchFrom = 0
            while (true) {
                val hit = h.indexOf(q, searchFrom)
                if (hit < 0) break
                builder.append(text, cursor, hit)
                builder.append(replacement)
                cursor = hit + q.length
                searchFrom = cursor
            }
            builder.append(text, cursor, text.length)
            val newText = builder.toString()
            replaced += found
            val newSpans = remapSpansForEdit(
                line.spans, text, newText,
                commonPrefixLen(text, newText), commonSuffixLen(text, newText)
            )
            out[i] = line.copy(spans = newSpans)
        }
        return out to replaced
    }
}
