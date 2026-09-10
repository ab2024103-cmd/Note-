package com.notepadpro.shared.ui.screens

import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor
import com.notepadpro.shared.editor.markRange
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentLayoutCacheTest {
    @Test
    fun countsAllThirtyOneTextRowsNotJustVisibleRows() = runTest {
        val lines = List(31) { EditorLine.plain("$it", "one two three") }
        val result = DocumentLayoutCache().measure(lines, { 100 }) { _, _ -> listOf(0, 4, 8) }
        assertEquals(93, result.displayLines)
        assertEquals(DisplayPosition(93, 1), result.position("30", 8))
        assertEquals(DisplayPosition(2, 2), result.position("0", 5))
    }

    @Test
    fun highlightingDoesNotRemeasureOrAddDisplayLines() = runTest {
        val line = EditorLine.plain("p", "one two three")
        val cache = DocumentLayoutCache()
        var measured = 0
        val before = cache.measure(listOf(line), { 100 }) { _, _ -> measured++; listOf(0, 4, 8) }
        val after = cache.measure(listOf(markRange(line, 4, 7, HighlightColor.BLUE)), { 100 }) { _, _ -> measured++; listOf(0, 4, 8) }
        assertEquals(1, measured)
        assertEquals(before, after)
    }

    @Test
    fun widthAndTextChangesInvalidateOnlyTheAffectedCachedMeasurements() = runTest {
        val lines = listOf(EditorLine.plain("a", "one"), EditorLine.plain("b", "two"))
        val cache = DocumentLayoutCache()
        var measured = 0
        cache.measure(lines, { 100 }) { _, _ -> measured++; listOf(0) }
        cache.measure(listOf(lines[0], EditorLine.plain("b", "edited")), { 100 }) { _, _ -> measured++; listOf(0) }
        assertEquals(3, measured)
        cache.measure(lines, { 50 }) { _, _ -> measured++; listOf(0) }
        assertEquals(5, measured)
    }

    @Test
    fun blankRowsStillCountAsOneAndCaretOffsetsAreClamped() = runTest {
        val result = DocumentLayoutCache().measure(listOf(EditorLine.plain("a", "")), { 100 }) { _, _ -> listOf(0) }
        assertEquals(1, result.displayLines)
        assertEquals(DisplayPosition(1, 1), result.position("a", 100))
    }
}
