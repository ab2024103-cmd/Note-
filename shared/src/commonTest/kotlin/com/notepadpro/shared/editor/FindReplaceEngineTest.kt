package com.notepadpro.shared.editor

import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor
import com.notepadpro.shared.domain.model.InlineSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindReplaceEngineTest {
    @Test
    fun unicodeCaseInsensitiveSearchKeepsOriginalOffsets() {
        val lines = listOf(EditorLine.plain("p", "İ x X"))
        val matches = FindReplaceEngine.findAll(lines, "x", false)
        assertEquals(listOf(2, 4), matches.map { it.start })
        assertEquals(listOf(3, 5), matches.map { it.end })
        assertEquals("İ z z", FindReplaceEngine.replaceAll(lines, "x", "z", false).first.single().plainText)
    }

    @Test
    fun matchingIsLiteralNonOverlappingAndHonorsCase() {
        val lines = listOf(EditorLine.plain("p", "aaAA . aa"))
        assertEquals(2, FindReplaceEngine.findAll(lines, "aa", true).size)
        assertEquals(3, FindReplaceEngine.findAll(lines, "aa", false).size)
        assertEquals(1, FindReplaceEngine.findAll(lines, ".", false).size)
        assertTrue(FindReplaceEngine.findAll(lines, "", false).isEmpty())
    }

    @Test
    fun deletingARepeatedMatchDoesNotDuplicateTheOverlappingPrefixAndSuffix() {
        val lines = listOf(EditorLine.plain("p", "aaaa"))
        val match = FindReplaceEngine.findAll(lines, "aa", true).first()
        assertEquals("aa", FindReplaceEngine.replaceOne(lines, match, "").first.single().plainText)
    }

    @Test
    fun replaceAllPreservesFormattingBetweenSeparateMatches() {
        val line = EditorLine("p", listOf(
            InlineSpan("cat "), InlineSpan("green", true, HighlightColor.GREEN),
            InlineSpan(" cat "), InlineSpan("pink", true, HighlightColor.PINK), InlineSpan(" cat")
        ))
        val (lines, count) = FindReplaceEngine.replaceAll(listOf(line), "cat", "dog", true)
        assertEquals(3, count)
        assertEquals("dog green dog pink dog", lines.single().plainText)
        assertEquals(listOf("green", "pink"), lines.single().spans.filter { it.highlighted }.map { it.text })
        assertEquals(listOf(HighlightColor.GREEN, HighlightColor.PINK), lines.single().spans.filter { it.highlighted }.map { it.highlightColor })
    }

    @Test
    fun aStaleMatchCannotChangeAnEditedOrDifferentParagraph() {
        val line = EditorLine.plain("p", "cat tail")
        val match = FindReplaceEngine.findAll(listOf(line), "cat", true).single()
        val edited = listOf(EditorLine.plain("p", "cat edited tail"))
        assertEquals(edited, FindReplaceEngine.replaceOne(edited, match, "dog").first)
        val other = listOf(EditorLine.plain("other", line.plainText))
        assertEquals(other, FindReplaceEngine.replaceOne(other, match, "dog").first)
    }

    @Test
    fun multilineReplacementCreatesRealRowsAndPreservesTheTail() {
        val lines = listOf(EditorLine.plain("p", "before cat after"))
        val match = FindReplaceEngine.findAll(lines, "cat", true).single()
        val (result, caret) = FindReplaceEngine.replaceOne(lines, match, "first\r\nsecond")
        assertEquals(listOf("before first", "second after"), result.map { it.plainText })
        assertEquals(6, caret)
        assertTrue(result.all { '\n' !in it.plainText && '\r' !in it.plainText })
    }

    @Test
    fun replaceAllDoesNotReprocessTheTextItJustInserted() {
        val (result, count) = FindReplaceEngine.replaceAll(listOf(EditorLine.plain("p", "a a")), "a", "aa", true)
        assertEquals(2, count)
        assertEquals("aa aa", result.single().plainText)
    }
}
