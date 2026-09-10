package com.notepadpro.shared.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.editor.DocState
import com.notepadpro.shared.platform.currentTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

// The renderer and the off-screen counter must use the very same geometry.
internal val EditorStartPadding = 6.dp
internal val EditorEndPadding = 8.dp
internal fun editorGutterWidth(line: EditorLine, number: Int?, fontSizeSp: Float): Dp =
    (maxOf(36f, ((number?.toString()?.length ?: 1) + 1) * fontSizeSp * 0.65f) + line.indent.coerceAtLeast(0) * 14).dp

internal fun editorTextWidth(widthPx: Int, line: EditorLine, number: Int?, fontSizeSp: Float, density: Density): Int =
    with(density) {
        (widthPx - EditorStartPadding.roundToPx() - EditorEndPadding.roundToPx() -
            editorGutterWidth(line, number, fontSizeSp).roundToPx()).coerceAtLeast(1)
    }

internal data class DisplayPosition(val line: Int, val column: Int)
internal data class LineLayout(val starts: List<Int>, val textLength: Int, val firstDisplayLine: Int)
internal data class DocumentLayout(val lines: Map<String, LineLayout>, val displayLines: Int) {
    fun position(lineId: String?, offset: Int): DisplayPosition? {
        val line = lineId?.let { lines[it] } ?: return null
        val position = offset.coerceIn(0, line.textLength)
        val result = line.starts.binarySearch(position)
        val index = if (result >= 0) result else (-result - 2).coerceAtLeast(0)
        return DisplayPosition(line.firstDisplayLine + index, position - line.starts[index] + 1)
    }
}

/** Keep only break offsets, not thousands of heavyweight TextLayoutResults. */
internal class DocumentLayoutCache {
    private data class Entry(val line: EditorLine, val text: String, val width: Int, val starts: List<Int>)
    private val entries = HashMap<String, Entry>()

    suspend fun measure(
        lines: List<EditorLine>,
        width: (EditorLine) -> Int,
        measureStarts: (String, Int) -> List<Int>
    ): DocumentLayout {
        val result = LinkedHashMap<String, LineLayout>(lines.size)
        var nextLine = 1
        var batchStart = currentTimeMillis()
        for (line in lines) {
            coroutineContext.ensureActive()
            val cached = entries[line.id]
            val text = if (cached != null && cached.line === line) cached.text else line.plainText
            val textWidth = width(line)
            val starts = if (cached != null && cached.text == text && cached.width == textWidth) cached.starts
                else measureStarts(text, textWidth).ifEmpty { listOf(0) }
            entries[line.id] = Entry(line, text, textWidth, starts)
            result[line.id] = LineLayout(starts, text.length, nextLine)
            nextLine += starts.size
            // Let input/scrolling/frames run while counting large pasted notes.
            // Font resolution and TextMeasurer stay on the composition thread.
            if (currentTimeMillis() - batchStart >= 8) {
                delay(1)
                batchStart = currentTimeMillis()
            }
        }
        entries.keys.retainAll(result.keys)
        return DocumentLayout(result, nextLine - 1)
    }
}

@Composable
internal fun rememberDocumentLayout(doc: DocState, widthPx: Int, fontSizeSp: Float, wordWrap: Boolean): DocumentLayout? {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val cache = remember(fontSizeSp, wordWrap, density.density, density.fontScale, direction, measurer) { DocumentLayoutCache() }
    var layout by remember { mutableStateOf<DocumentLayout?>(null) }
    var current by remember { mutableStateOf(false) }
    LaunchedEffect(doc.lines, doc.numbers, widthPx, cache) {
        current = false
        if (widthPx <= 0) return@LaunchedEffect
        layout = cache.measure(
            doc.lines,
            width = { editorTextWidth(widthPx, it, doc.numbers[it.id], fontSizeSp, density) },
            measureStarts = { text, width ->
                if (!wordWrap || text.isEmpty()) listOf(0) else {
                    val measured = measurer.measure(text, style = TextStyle(fontSize = fontSizeSp.sp),
                        softWrap = true, constraints = Constraints(maxWidth = width))
                    List(measured.lineCount) { measured.getLineStart(it) }
                }
            }
        )
        current = true
    }
    return layout.takeIf { current }
}
