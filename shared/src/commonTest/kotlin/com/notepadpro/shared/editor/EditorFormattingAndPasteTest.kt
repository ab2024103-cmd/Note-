package com.notepadpro.shared.editor

import com.notepadpro.shared.data.settings.SettingsRepository
import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor
import com.notepadpro.shared.domain.model.InlineSpan
import com.notepadpro.shared.domain.model.LineEnding
import com.notepadpro.shared.domain.model.ListType
import com.notepadpro.shared.domain.model.NoteDocument
import com.notepadpro.shared.domain.model.TextCodec
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorFormattingAndPasteTest {
    @Test
    fun colorRespectsSelectedTextInsideOneParagraphAndUndoRestoresIt() = runTest {
        val original = EditorLine.plain("p", "first visual line second visual line third visual line")
        val session = session(original)
        session.applyTextChange("p", original.plainText, 18, 36)
        session.setLineColor(HighlightColor.PINK)
        assertEquals(original.plainText, session.state.value.lines.single().plainText)
        assertNull(session.state.value.lines.single().lineColor)
        assertEquals((18 until 36).toSet(), highlightedOffsets(session.state.value.lines.single()))
        session.undo()
        assertEquals(listOf(original), session.state.value.lines)
        session.redo()
        assertEquals((18 until 36).toSet(), highlightedOffsets(session.state.value.lines.single()))
    }

    @Test
    fun collapsedCaretColorsOnlyTheMeasuredWrappedLine() = runTest {
        val line = EditorLine.plain("p", "first line second line third line")
        val session = session(line)
        session.applyTextChange("p", line.plainText, 15, 15)
        session.onVisualLineChanged("p", 11, 23)
        session.setLineColor(HighlightColor.GREEN)
        assertEquals((11 until 23).toSet(), highlightedOffsets(session.state.value.lines.single()))
        assertEquals(line.plainText, session.state.value.lines.single().plainText)
        assertFalse(session.canMoveToAdjacentParagraph(-1))
        assertFalse(session.canMoveToAdjacentParagraph(1))
    }

    @Test
    fun staleOrUnmeasuredVisualRangesNeverFallBackToTheEntireParagraph() = runTest {
        val line = EditorLine.plain("p", "unmeasured paragraph")
        val session = session(line)
        session.onLineFocused("p")
        session.setLineColor(HighlightColor.YELLOW)
        assertEquals(listOf(line), session.state.value.lines)
        session.onVisualLineChanged("p", 0, 5)
        session.applyTextChange("p", "changed paragraph", 8, 8)
        session.setLineColor(HighlightColor.YELLOW)
        assertTrue(highlightedOffsets(session.state.value.lines.single()).isEmpty())
    }

    @Test
    fun selectingTextAfterAGutterSelectionDoesNotColorTheWholeParagraph() = runTest {
        val line = EditorLine.plain("p", "one two three")
        val session = session(line)
        session.selectSingleLine("p")
        session.applyTextChange("p", line.plainText, 4, 7)
        session.setLineColor(HighlightColor.BLUE)
        assertEquals(setOf(4, 5, 6), highlightedOffsets(session.state.value.lines.single()))
        assertNull(session.state.value.lines.single().lineColor)
    }

    @Test
    fun clearingPartOfAnOldParagraphBackgroundPreservesTheColorOutsideIt() = runTest {
        val line = EditorLine.plain("p", "one two three").copy(lineColor = HighlightColor.PINK)
        val session = session(line)
        session.applyTextChange("p", line.plainText, 4, 7)
        session.setLineColor(null)
        val result = session.state.value.lines.single()
        assertNull(result.lineColor)
        assertEquals((line.plainText.indices - (4 until 7).toSet()).toSet(), highlightedOffsets(result))
        assertTrue(result.spans.filter { it.highlighted }.all { it.highlightColor == HighlightColor.PINK })
    }

    @Test
    fun paragraphColorRemainsAnExplicitSeparateAction() = runTest {
        val session = session(EditorLine.plain("p", "one two three"))
        session.onLineFocused("p")
        session.setParagraphColor(HighlightColor.ORANGE)
        assertEquals(HighlightColor.ORANGE, session.state.value.lines.single().lineColor)
    }

    @Test
    fun pasteKeepsBlankLinesAndNumbersOnlyRealItemsLikeK2() = runTest {
        val session = session(EditorLine.plain("p", ""))
        val text = "first long paragraph\n\n\nsecond paragraph\n\nthird paragraph"
        session.applyTextChange("p", text, text.length, text.length)
        session.selectAllLines()
        session.toggleList(ListType.NUMBER)
        val state = session.state.value
        assertEquals(text, TextCodec.joinLines(state.lines, LineEnding.LF))
        assertEquals(listOf(1, 2, 3), state.lines.mapNotNull { state.numbers[it.id] })
        assertTrue(state.lines.filter { it.plainText.isBlank() }.all { it.listType == ListType.NONE })
    }

    @Test
    fun numberedPasteInTheMiddleKeepsSuffixFormattingAndPlacesCaretAfterPaste() = runTest {
        val original = EditorLine("p", listOf(InlineSpan("before "), InlineSpan("after", true, HighlightColor.GREEN)), listType = ListType.NUMBER)
        val session = session(original)
        val inserted = "before first\r\n\r\nsecond"
        val text = inserted + "after"
        session.applyTextChange("p", text, inserted.length, inserted.length)
        val state = session.state.value
        assertEquals(listOf("before first", "", "secondafter"), state.lines.map { it.plainText })
        assertEquals(listOf(1, 2), state.lines.mapNotNull { state.numbers[it.id] })
        assertEquals(state.lines.last().id, state.activeLineId)
        assertEquals(Caret(6, 6), state.caret)
        assertEquals("after", state.lines.last().spans.filter { it.highlighted }.joinToString("") { it.text })
        session.undo()
        assertEquals(listOf(original), session.state.value.lines)
        session.redo()
        assertEquals(state.lines, session.state.value.lines)
    }

    @Test
    fun pasteNormalizesCrLfAndLoneCrWithoutLosingLeadingOrTrailingBlankLines() = runTest {
        val session = session(EditorLine.plain("p", ""))
        val text = "\r\nA\rB\r\n\r\n"
        session.applyTextChange("p", text, text.length, text.length)
        assertEquals(listOf("", "A", "B", "", ""), session.state.value.lines.map { it.plainText })
        assertEquals("\nA\nB\n\n", docText(session.state.value.lines))
    }

    @Test
    fun enterReplacesTheSelectionAndPreservesTheRightHandText() = runTest {
        val session = session(EditorLine.plain("p", "abSELECTyz"))
        session.applyTextChange("p", "abSELECTyz", 2, 8)
        session.insertLineBreak("p")
        assertEquals(listOf("ab", "yz"), session.state.value.lines.map { it.plainText })
        assertEquals(Caret(0, 0), session.state.value.caret)
    }

    @Test
    fun enterShowsTheNextNumberAndAnEmptyItemExitsTheList() = runTest {
        val session = session(EditorLine.plain("p", "first").copy(listType = ListType.NUMBER))
        session.applyTextChange("p", "first", 5, 5)
        session.insertLineBreak("p")
        val second = session.state.value.lines.last()
        assertEquals(2, session.state.value.numbers[second.id])
        session.insertLineBreak(second.id)
        assertEquals(2, session.state.value.lines.size)
        assertEquals(ListType.NONE, session.state.value.lines.last().listType)
        assertNull(session.state.value.numbers[second.id])
    }

    @Test
    fun batchListToggleAppliesOneConsistentTypeToMixedRows() = runTest {
        val session = session(
            EditorLine.plain("a", "one").copy(listType = ListType.NUMBER),
            EditorLine.plain("b", "two"), EditorLine.plain("blank", "")
        )
        session.selectAllLines()
        session.toggleList(ListType.NUMBER)
        assertEquals(listOf(ListType.NUMBER, ListType.NUMBER, ListType.NONE), session.state.value.lines.map { it.listType })
        session.selectAllLines()
        session.toggleList(ListType.NUMBER)
        assertTrue(session.state.value.lines.all { it.listType == ListType.NONE })
    }

    @Test
    fun numberingResumesParentCountersAndIgnoresLegacyEmptyNumberedRows() {
        fun item(id: String, indent: Int = 0, text: String = id) = EditorLine.plain(id, text).copy(listType = ListType.NUMBER, indent = indent)
        val lines = listOf(item("a"), item("blank", text = ""), item("b", 1), item("c", 1), item("d"), item("e", 1), item("f"))
        assertEquals(listOf(1, 1, 2, 2, 1, 3), lines.mapNotNull { computeNumbers(lines)[it.id] })
        assertNull(computeNumbers(lines)["blank"])
        assertEquals(2, computeNumbers(lines, "blank")["blank"])
    }

    @Test
    fun pureLineSplitUsesTheSuffixNotTheEmptyMiddleOfTheSpanClip() {
        val line = EditorLine("p", listOf(InlineSpan("abcd", true, HighlightColor.BLUE)))
        val (left, right) = splitLineAt(line, 2)
        assertEquals("ab", left.plainText)
        assertEquals("cd", right.plainText)
        assertTrue(right.spans.single().highlighted)
    }

    private fun TestScope.session(vararg lines: EditorLine) = EditorSession(
        scope = backgroundScope,
        settings = SettingsRepository(MapSettings(), Json),
        initial = NoteDocument(lines = lines.toList()),
        ioDispatcher = StandardTestDispatcher(testScheduler),
        onPersist = { 1L }
    )

    private fun highlightedOffsets(line: EditorLine): Set<Int> {
        val result = mutableSetOf<Int>()
        var offset = 0
        for (span in line.spans) {
            if (span.highlighted) result.addAll(offset until offset + span.text.length)
            offset += span.text.length
        }
        return result
    }
}
