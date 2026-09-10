package com.notepadpro.shared.editor

import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor
import com.notepadpro.shared.domain.model.InlineSpan
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtractEngineTest {
    private val blue = HighlightColor.BLUE
    private val pink = HighlightColor.PINK
    private fun options(vararg colors: HighlightColor, grouped: Boolean = false) =
        ExtractEngine.ExtractOptions(colors.toSet(), grouped)

    @Test
    fun mobileScreenshotCaseExtractsOnlyTheBlueExceptionNameNotTheRestOfItsParagraph() {
        val line = EditorLine("log", listOf(
            InlineSpan("exception: "),
            InlineSpan("java.lang.NoClassDefFoundError:", true, blue),
            InlineSpan(" com.example.transfer.engine.TransferEngine"),
        ))
        assertEquals("java.lang.NoClassDefFoundError:", ExtractEngine.extract(listOf(line), options(blue)))
    }

    @Test
    fun highlightingAVisibleLineDoesNotExtractAdjacentWrappedLines() {
        val text = "unselected before selected display line unselected after"
        val original = EditorLine.plain("p", text)
        val selected = "selected display line"
        val start = text.indexOf(selected)
        val highlighted = markRange(original, start, start + selected.length, blue)
        assertEquals(selected, ExtractEngine.extract(listOf(highlighted), options(blue)))
        assertEquals(text, highlighted.plainText)
    }

    @Test
    fun adjacentSelectedSpansJoinWithoutInventingLineBreaks() {
        val line = EditorLine("p", listOf(InlineSpan("first ", true, blue), InlineSpan("second", true, pink)))
        assertEquals("first second", ExtractEngine.extract(listOf(line), options(blue, pink)))
    }

    @Test
    fun separateHighlightsStaySeparateWithoutIncludingTheGap() {
        val line = EditorLine("p", listOf(InlineSpan("first", true, blue), InlineSpan(" DO NOT COPY "), InlineSpan("second", true, blue)))
        assertEquals("first\nsecond", ExtractEngine.extract(listOf(line), options(blue)))
    }

    @Test
    fun groupByColorIncludesEveryColorInTheSameParagraph() {
        val line = EditorLine("p", listOf(InlineSpan("pink", true, pink), InlineSpan(" gap "), InlineSpan("blue", true, blue)))
        assertEquals("===== Pink =====\npink\n\n===== Blue =====\nblue",
            ExtractEngine.extract(listOf(line), options(blue, pink, grouped = true)))
    }

    @Test
    fun anExplicitWholeParagraphColorStillExtractsItsText() {
        val line = EditorLine.plain("p", "whole paragraph").copy(lineColor = blue)
        assertEquals(line.plainText, ExtractEngine.extract(listOf(line), options(blue)))
    }

    @Test
    fun inlineColorOverridesParagraphColorWithoutDuplicates() {
        val line = EditorLine("p", listOf(InlineSpan("pink "), InlineSpan("blue", true, blue)), lineColor = pink)
        assertEquals("pink ", ExtractEngine.extract(listOf(line), options(pink)))
        assertEquals("blue", ExtractEngine.extract(listOf(line), options(blue)))
        assertEquals("pink blue", ExtractEngine.extract(listOf(line), options(pink, blue)))
    }

    @Test
    fun explicitlyColoredBlankParagraphsAndSelectedWhitespaceArePreserved() {
        val lines = listOf("  first  ", "", "last\t").mapIndexed { index, text ->
            EditorLine.plain("$index", text).copy(lineColor = blue)
        }
        assertEquals("  first  \n\nlast\t", ExtractEngine.extract(lines, options(blue)))
    }

    @Test
    fun disablingInlineExtractionDoesNotRevealTheHiddenParagraphColor() {
        val line = EditorLine("p", listOf(InlineSpan("visible pink "), InlineSpan("blue", true, blue)), lineColor = pink)
        assertEquals("visible pink ", ExtractEngine.extract(listOf(line), options(pink).copy(includeInlineHighlights = false)))
    }

    @Test
    fun flagsAndUnhighlightedDefaultColorDoNotCreateFalseHits() {
        val plain = EditorLine.plain("p", "plain")
        assertEquals("", ExtractEngine.extract(listOf(plain), options(HighlightColor.YELLOW)))
        val colored = plain.copy(lineColor = blue)
        assertEquals("", ExtractEngine.extract(listOf(colored), options(blue).copy(includeLineColors = false)))
        val marked = markRange(plain, 0, 5, blue)
        assertEquals("", ExtractEngine.extract(listOf(marked), options(blue).copy(includeInlineHighlights = false)))
    }
}
