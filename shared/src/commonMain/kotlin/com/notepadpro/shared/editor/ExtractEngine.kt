package com.notepadpro.shared.editor

import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor

/** Extract colored text, not uncolored neighbours from the same stored paragraph. */
object ExtractEngine {
    data class ExtractOptions(
        val colors: Set<HighlightColor>,
        val groupByColor: Boolean,
        val includeLineColors: Boolean = true,
        val includeInlineHighlights: Boolean = true
    )

    fun extract(lines: List<EditorLine>, options: ExtractOptions): String {
        if (options.colors.isEmpty()) return ""
        if (!options.groupByColor) return collectRuns(lines, options, options.colors).joinToString("\n")
        return HighlightColor.entries.filter { it in options.colors }.mapNotNull { color ->
            val runs = collectRuns(lines, options, setOf(color))
            if (runs.isEmpty()) null else "===== ${color.display} =====\n" + runs.joinToString("\n")
        }.joinToString("\n\n")
    }

    private fun collectRuns(
        lines: List<EditorLine>,
        options: ExtractOptions,
        colors: Set<HighlightColor>
    ): List<String> {
        val runs = ArrayList<String>()
        for (line in lines) {
            // Explicitly colored blank paragraphs are real selected line breaks.
            if (line.spans.all { it.text.isEmpty() } && options.includeLineColors && line.lineColor != null && line.lineColor in colors) {
                runs.add("")
                continue
            }
            val selected = StringBuilder()
            fun finishRun() {
                if (selected.isNotEmpty()) {
                    runs.add(selected.toString())
                    selected.clear()
                }
            }
            for (span in line.spans) {
                if (span.text.isEmpty()) continue
                // Inline marks cover a paragraph background. Extract the color
                // actually visible, rather than including the marked text twice.
                val color = when {
                    span.highlighted -> if (options.includeInlineHighlights) span.highlightColor else null
                    options.includeLineColors -> line.lineColor
                    else -> null
                }
                if (color != null && color in colors) selected.append(span.text) else finishRun()
            }
            finishRun()
        }
        return runs
    }
}
