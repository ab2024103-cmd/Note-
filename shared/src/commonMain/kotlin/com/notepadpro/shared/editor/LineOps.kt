package com.notepadpro.shared.editor

import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor
import com.notepadpro.shared.domain.model.InlineSpan
import com.notepadpro.shared.domain.model.ListType
import com.notepadpro.shared.platform.randomLineId

/**
 * Pure, immutable operations on [EditorLine] / [InlineSpan].
 * Everything here is UI-free, so it is unit-testable and cheap to reason about.
 */

/** Cuts the line's spans at [pos] (0..len). The right half keeps the same
 *  highlight/color attributes (used when splitting a line with Enter). */
fun splitLineAt(line: EditorLine, pos: Int): Pair<EditorLine, EditorLine> {
    val text = line.plainText
    val p = pos.coerceIn(0, text.length)
    val (left, right) = clipSpans(line.spans, p, p)
    return line.copy(
        spans = dropEmpty(left),
        id = line.id
    ) to line.copy(
        id = randomLineId(),
        spans = dropEmpty(right)
    )
}

/**
 * Clips [spans] into (before, inside, after) pieces for the plain-text range
 * [start, end). Span colors are preserved on all pieces.
 */
fun clipSpans(
    spans: List<InlineSpan>,
    start: Int,
    end: Int
): Triple<List<InlineSpan>, List<InlineSpan>, List<InlineSpan>> {
    var offset = 0
    val before = ArrayList<InlineSpan>()
    val inside = ArrayList<InlineSpan>()
    val after = ArrayList<InlineSpan>()
    for (span in spans) {
        val spanStart = offset
        val spanEnd = offset + span.text.length
        offset = spanEnd
        if (span.text.isEmpty()) continue
        val relStart = (start - spanStart).coerceIn(0, span.text.length)
        val relEnd = (end - spanStart).coerceIn(0, span.text.length)
        if (relStart > 0) before.add(copySpan(span, span.text.substring(0, relStart)))
        if (relEnd > relStart) inside.add(copySpan(span, span.text.substring(relStart, relEnd)))
        if (relEnd < span.text.length) after.add(copySpan(span, span.text.substring(relEnd)))
    }
    return Triple(before, inside, after)
}

private fun copySpan(span: InlineSpan, text: String): InlineSpan =
    if (text == span.text) span else InlineSpan(text, span.highlighted, span.highlightColor)

private fun dropEmpty(spans: List<InlineSpan>): List<InlineSpan> =
    if (spans.any { it.text.isEmpty() }) spans.filter { it.text.isNotEmpty() } else spans

/** True if the range [start,end) contains no span entirely - unused helper. */
@Suppress("unused")
private fun emptyRange(spans: List<InlineSpan>, start: Int, end: Int): Boolean =
    clipSpans(spans, start, end).second.isEmpty()

/**
 * Rebuilds the span list of a line whose plain text changed from [oldText]
 * to [newText]. [prefixLen] is the length of the unchanged prefix and
 * [suffixLen] the length of the unchanged suffix (computed by diffing the
 * two plain texts). The typed/inserted middle region inherits the highlight
 * of the span at the caret position, which is what editors do when you keep
 * typing inside a highlighted run.
 */
fun remapSpansForEdit(
    oldSpans: List<InlineSpan>,
    oldText: String,
    newText: String,
    prefixLen: Int,
    suffixLen: Int
): List<InlineSpan> {
    val removedStart = prefixLen
    val removedEnd = oldText.length - suffixLen

    val (keptPrefix, inside, keptSuffix) = clipSpans(oldSpans, removedStart, removedEnd)

    // Inherit highlight from the span at the caret (char before it), or from
    // the replaced region when it was highlighted (select+type keeps color).
    var inherited: Pair<HighlightColor, Boolean>? = null
    val caretIdx = prefixLen - 1
    if (caretIdx >= 0) {
        var off = 0
        for (span in oldSpans) {
            val end = off + span.text.length
            if (caretIdx < end && span.text.isNotEmpty()) {
                if (span.highlighted) inherited = span.highlightColor to true
                break
            }
            off = end
        }
    }
    if (inherited == null) {
        val firstInside = inside.firstOrNull()
        if (firstInside != null && firstInside.highlighted) {
            inherited = firstInside.highlightColor to true
        }
    }

    val middleText = newText.substring(prefixLen, newText.length - suffixLen)
    val middle: List<InlineSpan> = if (middleText.isEmpty()) emptyList()
    else {
        val (color, highlighted) = inherited ?: (HighlightColor.YELLOW to false)
        listOf(InlineSpan(middleText, highlighted, color))
    }

    val result = ArrayList<InlineSpan>(keptPrefix.size + middle.size + keptSuffix.size)
    result.addAll(keptPrefix)
    result.addAll(middle)
    result.addAll(keptSuffix)
    return dropEmpty(result)
}

/**
 * Marks the plain-text range [start,end) as highlighted with [color]
 * (the "Mark" action). Splits existing spans at the boundaries first.
 */
fun markRange(line: EditorLine, start: Int, end: Int, color: HighlightColor): EditorLine {
    val s = start.coerceIn(0, line.plainText.length)
    val e = end.coerceIn(s, line.plainText.length)
    if (e <= s) return line
    val (before, inside, after) = clipSpans(line.spans, s, e)
    val painted = inside.map { InlineSpan(it.text, true, color) }
    val merged = ArrayList<InlineSpan>(before.size + painted.size + after.size)
    merged.addAll(before)
    merged.addAll(painted)
    merged.addAll(after)
    return line.copy(spans = dropEmpty(merged))
}

/** Clears inline highlights inside the range (used by "Remove highlight"). */
fun clearMarkRange(line: EditorLine, start: Int, end: Int): EditorLine {
    val s = start.coerceIn(0, line.plainText.length)
    val e = end.coerceIn(s, line.plainText.length)
    val (before, inside, after) = clipSpans(line.spans, s, e)
    val cleared = inside.map { InlineSpan(it.text, false, it.highlightColor) }
    val merged = ArrayList<InlineSpan>(before.size + cleared.size + after.size)
    merged.addAll(before)
    merged.addAll(cleared)
    merged.addAll(after)
    return line.copy(spans = dropEmpty(merged))
}

/** True when the line contains at least one highlighted span. */
fun hasAnyHighlight(line: EditorLine): Boolean = line.spans.any { it.highlighted }

/** Toggles list type: applying the same type again removes it. */
fun toggleListType(line: EditorLine, type: ListType): EditorLine = when {
    type == ListType.NONE -> line.copy(listType = ListType.NONE)
    line.listType == type -> line.copy(listType = ListType.NONE, checked = false)
    else -> line.copy(listType = type, indent = if (type != ListType.NONE) line.indent.coerceAtLeast(0) else 0, checked = false)
}

fun setLineColor(line: EditorLine, color: HighlightColor?): EditorLine = line.copy(lineColor = color)

fun changeIndent(line: EditorLine, delta: Int): EditorLine {
    if (line.listType == ListType.NONE) return line
    return line.copy(indent = (line.indent + delta).coerceIn(0, 6))
}

fun setChecked(line: EditorLine, checked: Boolean): EditorLine =
    if (line.listType != ListType.CHECK) line else line.copy(checked = checked)

/**
 * Recomputes the display numbers for NUMBER list lines.
 * A fresh numbering run starts whenever the list context is interrupted
 * (non-number line, or indent level change); nested runs restart at 1.
 */
fun computeNumbers(lines: List<EditorLine>): Map<String, Int> {
    val out = HashMap<String, Int>()
    val counters = HashMap<Int, Int>()
    var lastNumbered = false
    var lastIndent = -1
    for (line in lines) {
        if (line.listType == ListType.NUMBER && line.plainText.isNotBlank()) {
            val i = line.indent
            if (lastNumbered && lastIndent == i) {
                counters[i] = counters.getOrDefault(i, 0) + 1
            } else {
                counters.clear()
                counters[i] = 1
            }
            out[line.id] = counters[i]!!
            lastNumbered = true
            lastIndent = i
        } else {
            lastNumbered = false
        }
    }
    return out
}

/** Total plain characters of a document (used by word count). */
fun docText(lines: List<EditorLine>): String {
    if (lines.isEmpty()) return ""
    val sb = StringBuilder()
    for (line in lines) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(line.plainText)
    }
    return sb.toString()
}

fun countWords(text: String): Int {
    if (text.isBlank()) return 0
    var words = 0
    var inWord = false
    for (c in text) {
        val isLetter = c.isLetterOrDigit() || c == '\'' || c == '-'
        if (isLetter) {
            if (!inWord) {
                words++
                inWord = true
            }
        } else {
            inWord = false
        }
    }
    return words
}

/** Merges `next` into `prev` (Backspace at start of a line). */
fun mergeLines(prev: EditorLine, next: EditorLine): EditorLine =
    prev.copy(spans = dropEmpty(prev.spans + next.spans))

/** Longest common prefix length of two strings. */
fun commonPrefixLen(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    var i = 0
    while (i < n && a[i] == b[i]) i++
    return i
}

/** Longest common suffix length of two strings (without overlapping prefix). */
fun commonSuffixLen(a: String, b: String): Int {
    var i = 0
    val max = minOf(a.length, b.length)
    while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
    return i
}
