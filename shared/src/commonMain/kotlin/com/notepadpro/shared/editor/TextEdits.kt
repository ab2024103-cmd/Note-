package com.notepadpro.shared.editor

import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.InlineSpan
import com.notepadpro.shared.domain.model.ListType
import com.notepadpro.shared.platform.randomLineId

/** Split rich text at real newlines, never at layout/word-wrap boundaries. */
internal fun splitRichLines(template: EditorLine, spans: List<InlineSpan>): List<EditorLine> {
    val rows = mutableListOf(mutableListOf<InlineSpan>())
    for (span in spans) {
        val parts = span.text.split('\n')
        for ((index, part) in parts.withIndex()) {
            if (part.isNotEmpty()) rows.last().add(span.copy(text = part))
            if (index < parts.lastIndex) rows.add(mutableListOf())
        }
    }
    return rows.mapIndexed { index, row ->
        template.copy(
            id = if (index == 0) template.id else randomLineId(),
            spans = row,
            // Pasted blank separators aren't list items. A final empty row can
            // remain the active list placeholder so the user can keep typing.
            listType = if (rows.size > 1 && index < rows.lastIndex && row.all { it.text.isBlank() }) {
                ListType.NONE
            } else template.listType,
            checked = index == 0 && template.checked
        )
    }
}

/** Formatting for replacement text comes from the replaced run, not its neighbours. */
internal fun replacementSpan(line: EditorLine, start: Int, end: Int, text: String): InlineSpan {
    val (before, inside, _) = clipSpans(line.spans, start, end)
    return (inside.firstOrNull() ?: before.lastOrNull() ?: InlineSpan("")).copy(text = text)
}
