package com.notepadpro.shared.editor

import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor

/**
 * Extract-by-color: produces plain text of every line that carries one of
 * the selected colors as whole-line color or as an inline highlight.
 * With [groupByColor], output is grouped under color headers.
 * The result is rendered in a read-only preview, recomputed only when the
 * checkbox set or the document revision changes (derivedStateOf at the UI).
 */
object ExtractEngine {

    data class ExtractOptions(
        val colors: Set<HighlightColor>,
        val groupByColor: Boolean,
        val includeLineColors: Boolean = true,
        val includeInlineHighlights: Boolean = true
    )

    private data class Hit(val line: EditorLine, val color: HighlightColor, val viaLine: Boolean)

    fun extract(lines: List<EditorLine>, options: ExtractOptions): String {
        if (options.colors.isEmpty()) return ""
        val hits = ArrayList<Hit>()
        for (line in lines) {
            if (line.plainText.isEmpty()) continue
            if (options.includeLineColors && line.lineColor != null && line.lineColor in options.colors) {
                hits.add(Hit(line, line.lineColor!!, viaLine = true))
            } else if (options.includeInlineHighlights) {
                for (span in line.spans) {
                    if (span.highlighted && span.highlightColor in options.colors) {
                        hits.add(Hit(line, span.highlightColor, viaLine = false))
                        break
                    }
                }
            }
        }
        if (hits.isEmpty()) return ""

        val sb = StringBuilder()
        if (!options.groupByColor) {
            for (hit in hits) {
                sb.append(hit.line.plainText).append('\n')
            }
        } else {
            for (color in HighlightColor.entries) {
                if (color !in options.colors) continue
                val group = hits.filter { it.color == color }
                if (group.isEmpty()) continue
                sb.append("===== ").append(color.display).append(" =====\n")
                for (hit in group) {
                    sb.append(hit.line.plainText).append('\n')
                }
                sb.append('\n')
            }
        }
        return sb.toString().trimEnd('\n')
    }
}
