package com.notepadpro.shared.domain.model

import kotlinx.serialization.Serializable

/**
 * Colors that can be used for whole-line backgrounds (data-color analog)
 * and for inline highlight spans (data-inline-highlight analog).
 * Exactly six, per the master spec.
 */
@Serializable
enum class HighlightColor(val display: String) {
    YELLOW("Yellow"),
    GREEN("Green"),
    PINK("Pink"),
    BLUE("Blue"),
    ORANGE("Orange"),
    PURPLE("Purple")
}

@Serializable
enum class ListType { NONE, BULLET, NUMBER, CHECK }

@Serializable
enum class LineEnding { LF, CRLF }

/**
 * One ordered run of plain text within an [EditorLine], optionally carrying
 * an inline highlight (the replacement for <span data-inline-highlight>).
 */
@Serializable
data class InlineSpan(
    val text: String,
    val highlighted: Boolean = false,
    val highlightColor: HighlightColor = HighlightColor.YELLOW
)

/**
 * The line-model rich editor's unit of content.
 * Direct replacement for an .editor-line <div> plus its data-* attributes:
 *  - lineColor   -> data-color
 *  - listType    -> data-list-type
 *  - indent      -> data-indent
 *  - checked     -> data-checked
 * There is intentionally NO HTML/DOM in this model: rendering happens from
 * this data structure only (memory stays proportional to note size).
 */
@Serializable
data class EditorLine(
    val id: String,
    val spans: List<InlineSpan> = emptyList(),
    val lineColor: HighlightColor? = null,
    val listType: ListType = ListType.NONE,
    val indent: Int = 0,
    val checked: Boolean = false
) {
    val plainText: String
        get() {
            if (spans.size == 1) return spans[0].text // fast path
            val sb = StringBuilder()
            for (s in spans) sb.append(s.text)
            return sb.toString()
        }

    val isEmptyLine: Boolean get() = plainText.isEmpty()

    companion object {
        fun plain(id: String, text: String): EditorLine =
            EditorLine(id = id, spans = if (text.isEmpty()) emptyList() else listOf(InlineSpan(text)))
    }
}

/**
 * In-memory + persisted representation of one note document.
 * serialized to `lines_json` for storage (no HTML is ever rendered/stored).
 */
@Serializable
data class NoteDocument(
    val id: Long? = null,
    val title: String = "",
    val lines: List<EditorLine> = emptyList(),
    val isPinned: Boolean = false,
    val sourcePath: String? = null,
    val lineEnding: LineEnding = LineEnding.LF,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L
)

/** Lightweight summary row used by the sidebar list. */
data class NoteRow(
    val id: Long,
    val title: String,
    val preview: String,
    val isPinned: Boolean,
    val modifiedAt: Long,
    val createdAt: Long
)
