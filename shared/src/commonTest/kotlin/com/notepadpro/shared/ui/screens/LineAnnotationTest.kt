package com.notepadpro.shared.ui.screens

import androidx.compose.ui.graphics.Color
import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor
import com.notepadpro.shared.domain.model.InlineSpan
import kotlin.test.Test
import kotlin.test.assertEquals

class LineAnnotationTest {
    @Test
    fun aOneCharacterFindMatchIsNotDropped() {
        val annotated = buildLineAnnotation(EditorLine.plain("p", "abc"), listOf(2 until 3), Color.Yellow)
        val style = annotated.spanStyles.single()
        assertEquals(2, style.start)
        assertEquals(3, style.end)
    }

    @Test
    fun theLastCharacterOfEveryFindMatchIsIncluded() {
        val annotated = buildLineAnnotation(EditorLine.plain("p", "hello"), listOf(0 until 5), Color.Yellow)
        assertEquals(5, annotated.spanStyles.single().end)
    }

    @Test
    fun inlineFormattingProducesAnnotationsEvenWhenThePlainTextIsUnchanged() {
        val line = EditorLine("p", listOf(InlineSpan("before "), InlineSpan("selected", true, HighlightColor.GREEN), InlineSpan(" after")))
        val annotated = buildLineAnnotation(line, emptyList(), Color.Yellow)
        assertEquals(line.plainText, annotated.text)
        assertEquals(7, annotated.spanStyles.single().start)
        assertEquals(15, annotated.spanStyles.single().end)
    }
}
