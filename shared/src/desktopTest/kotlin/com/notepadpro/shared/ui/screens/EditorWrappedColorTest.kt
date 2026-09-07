package com.notepadpro.shared.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.notepadpro.shared.data.settings.SettingsRepository
import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor
import com.notepadpro.shared.domain.model.NoteDocument
import com.notepadpro.shared.editor.EditorSession
import com.notepadpro.shared.platform.AppDispatchers
import com.notepadpro.shared.ui.theme.Markers
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EditorWrappedColorTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val sessions = mutableListOf<EditorSession>()
    private val text = "A long paragraph with several wrapped display lines. Coloring one of these lines must leave the rest of the paragraph untouched."

    @After
    fun cleanup() {
        sessions.forEach { it.dispose() }
        scope.cancel()
    }

    @Test
    fun toolbarColorPaintsOnlyTheSelectedVisualLineAndRedrawsImmediately() {
        val session = editor()
        val field = compose.onNode(hasSetTextAction()).performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.lineCount > 2)
        val start = layout.getLineStart(1)
        val end = layout.getLineEnd(1)
        field.performTextInputSelection(TextRange(start, end))
        compose.onNodeWithText("Color selection").performClick()
        compose.runOnIdle {
            assertEquals(text, session.state.value.lines.single().plainText)
            assertNull(session.state.value.lines.single().lineColor)
        }
        val annotation = field.fetchSemanticsNode().config[SemanticsProperties.EditableText]
        val marked = annotation.spanStyles.filter { it.item.background == Markers.spanBackground(HighlightColor.YELLOW) }
        assertEquals(1, marked.size)
        assertEquals(start, marked.single().start)
        assertEquals(end, marked.single().end)
        assertEquals(TextRange(start, end), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    @Test
    fun collapsedCaretColorsOnlyItsWrappedDisplayLine() {
        editor()
        val field = compose.onNode(hasSetTextAction()).performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val start = layout.getLineStart(1)
        val end = layout.getLineEnd(1)
        field.performTextInputSelection(TextRange(start + 2))
        compose.onNodeWithText("Color selection").performClick()
        val marked = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].spanStyles
            .filter { it.item.background == Markers.spanBackground(HighlightColor.YELLOW) }
        assertEquals(start, marked.single().start)
        assertEquals(end, marked.single().end)
    }

    @Test
    fun aRealCaretMoveAfterSelectingTextDoesNotKeepTheOldSelection() {
        editor()
        val field = compose.onNode(hasSetTextAction()).performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        field.performTextInputSelection(TextRange(layout.getLineStart(1), layout.getLineEnd(1)))
        val start = layout.getLineStart(2)
        val end = layout.getLineEnd(2)
        field.performTextInputSelection(TextRange(start + 2))
        compose.onNodeWithText("Color selection").performClick()
        val marked = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].spanStyles
            .filter { it.item.background == Markers.spanBackground(HighlightColor.YELLOW) }
        assertEquals(start, marked.single().start)
        assertEquals(end, marked.single().end)
        assertEquals(TextRange(start + 2), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    private fun editor(): EditorSession {
        val session = EditorSession(scope, SettingsRepository(MapSettings(), Json),
            NoteDocument(lines = listOf(EditorLine.plain("p", text))), onPersist = { 1L })
        sessions += session
        compose.setContent {
            val state by session.state.collectAsState()
            MaterialTheme {
                Column(Modifier.width(260.dp)) {
                    EditorLineRow(
                        line = state.lines.single(), number = null, isActiveRow = state.activeLineId == "p",
                        darkTheme = false, wordWrap = true, fontSizeSp = 15f,
                        findRanges = emptyList(), findColor = Color.Yellow,
                        focusRequester = remember { FocusRequester() }, registerFocus = { _, _ -> }, unregisterFocus = {},
                        caretToApply = state.focusRequest?.second,
                        onTextChange = session::applyTextChange, onRowFocused = session::onLineFocused,
                        onToggleCheck = {}, onSelectLine = session::selectSingleLine,
                        onCaretApplied = { session.consumeFocusRequest() }, onVisualLineChanged = session::onVisualLineChanged
                    )
                    TextButton(onClick = { session.setLineColor(HighlightColor.YELLOW) }) { Text("Color selection") }
                }
            }
        }
        return session
    }
}
