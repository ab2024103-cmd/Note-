package com.notepadpro.shared.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.notepadpro.shared.FindUiState
import com.notepadpro.shared.data.settings.SettingsRepository
import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor
import com.notepadpro.shared.domain.model.LineEnding
import com.notepadpro.shared.domain.model.ListType
import com.notepadpro.shared.domain.model.NoteDocument
import com.notepadpro.shared.domain.model.TextCodec
import com.notepadpro.shared.editor.EditorSession
import com.notepadpro.shared.editor.ExtractEngine
import com.notepadpro.shared.platform.AppDispatchers
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compiled and executed on BOTH desktop and the Android API 23 emulator. */
@OptIn(ExperimentalTestApi::class)
class ReadingAndLayoutUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private lateinit var session: EditorSession
    private var width by mutableStateOf(320.dp)
    private var font by mutableStateOf(10f)
    private var wrap by mutableStateOf(true)
    private var reading by mutableStateOf(false)
    private var metrics by mutableStateOf<DocumentLayout?>(null)
    private val selected = "java.lang.NoClassDefFoundError:"
    private val paragraph = "exception: $selected com.example.transfer.engine.TransferEngine.clearFinished(TransferEngine.kt:194) followed by unselected text from the same long paragraph."
    private val pasted = List(31) { paragraph }.joinToString("\n")

    @After
    fun cleanup() {
        if (::session.isInitialized) session.dispose()
        scope.cancel()
    }

    @Test
    fun pastedThirtyOneTextLinesCountAllWrappedLinesIncludingOffScreenRows() {
        editor()
        val actual = firstLayout()
        assertTrue(actual.lineCount > 1)
        compose.runOnIdle {
            assertEquals(31, session.state.value.lines.size)
            assertEquals(31 * actual.lineCount, metrics?.displayLines)
            assertEquals(pasted, TextCodec.joinLines(session.state.value.lines, LineEnding.LF))
        }
        compose.onNodeWithTag("line-count").assertIsDisplayed()
        compose.onNodeWithText("${actual.lineCount * 31} display lines · 31 text lines").assertIsDisplayed()
    }

    @Test
    fun widthFontAndWrapChangesUpdateTheDisplayCountWithoutChangingTheNote() {
        editor()
        val initial = metrics!!.displayLines
        compose.runOnIdle { width = 220.dp }
        compose.waitUntil(10_000) { metrics?.displayLines?.let { it > initial } == true }
        assertEquals(31 * firstLayout().lineCount, metrics!!.displayLines)
        val narrow = metrics!!.displayLines
        compose.runOnIdle { font = 18f }
        compose.waitUntil(10_000) { metrics?.displayLines?.let { it > narrow } == true }
        assertEquals(31 * firstLayout().lineCount, metrics!!.displayLines)
        compose.runOnIdle { wrap = false }
        compose.waitUntil(10_000) { metrics?.displayLines == 31 }
        compose.runOnIdle { assertEquals(pasted, TextCodec.joinLines(session.state.value.lines, LineEnding.LF)) }
    }

    @Test
    fun wrapTogglesKeepFocusSelectionAndTextAcrossRowRecreation() {
        editor()
        val start = paragraph.indexOf(selected)
        val range = TextRange(start, start + selected.length)
        val field = compose.onNodeWithTag("editor-text-p")
        field.performClick().performTextInputSelection(range)
        compose.runOnIdle { wrap = false }
        compose.waitUntil(10_000) { metrics?.displayLines == 31 }
        field.assertIsFocused()
        assertEquals(range, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.runOnIdle { wrap = true }
        compose.waitUntil(10_000) { metrics?.displayLines?.let { it > 31 } == true }
        field.assertIsFocused()
        assertEquals(range, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.runOnIdle { assertEquals(pasted, TextCodec.joinLines(session.state.value.lines, LineEnding.LF)) }
        assertEquals(31 * firstLayout().lineCount, metrics!!.displayLines)
    }

    @Test
    fun colorMenuDoesNotAddTextOrDisplayLinesAndExtractsOnlyTheSelectedText() {
        editor()
        val before = metrics!!.displayLines
        val start = paragraph.indexOf(selected)
        compose.onNodeWithTag("editor-text-p").performClick().performTextInputSelection(TextRange(start, start + selected.length))
        compose.onNodeWithText("Color").performClick()
        compose.onNodeWithText("Blue").performClick()
        compose.waitUntil(10_000) { metrics != null }
        compose.runOnIdle {
            val lines = session.state.value.lines
            assertEquals(pasted, TextCodec.joinLines(lines, LineEnding.LF))
            assertEquals(31, lines.size)
            assertEquals(before, metrics?.displayLines)
            assertEquals(selected, ExtractEngine.extract(lines, ExtractEngine.ExtractOptions(setOf(HighlightColor.BLUE), false)))
        }
        assertEquals(before, 31 * firstLayout().lineCount)
        val annotation = compose.onNodeWithTag("editor-text-p").fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals(start, annotation.spanStyles.single().start)
        assertEquals(start + selected.length, annotation.spanStyles.single().end)
    }

    @Test
    fun readingModeBlocksTypingAndCheckTogglesAndCanReturnToEditing() {
        editor()
        compose.runOnIdle {
            session.replaceAllLinesExternal(session.state.value.lines.mapIndexed { index, line ->
                if (index == 0) line.copy(listType = ListType.CHECK) else line
            })
        }
        compose.onNodeWithTag("reading-mode-toggle").performClick()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        val field = compose.onNodeWithTag("editor-text-p").performClick()
        field.assert(!hasSetTextAction())
        field.performTextInputSelection(TextRange(0, 9))
        field.performKeyInput {
            keyDown(Key.A); keyUp(Key.A)
            keyDown(Key.Enter); keyUp(Key.Enter)
            keyDown(Key.Backspace); keyUp(Key.Backspace)
            keyDown(Key.CtrlLeft); keyDown(Key.V); keyUp(Key.V); keyUp(Key.CtrlLeft)
        }
        compose.onNodeWithTag("line-gutter-p").performClick()
        compose.runOnIdle {
            assertEquals(pasted, TextCodec.joinLines(session.state.value.lines, LineEnding.LF))
            assertFalse(session.state.value.lines.first().checked)
            assertTrue(session.isReadOnly)
        }
        compose.onNodeWithTag("reading-mode-toggle").performClick()
        field.assert(hasSetTextAction()).performTextReplacement("edited")
        compose.runOnIdle { assertEquals("edited", session.state.value.lines.first().plainText) }
    }

    @Test
    fun readingModeKeepsFindAvailableWithoutReplacementControls() {
        var state by mutableStateOf(FindUiState())
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(300.dp)) {
                    FloatingFindReplacePanel(state, false, true, 1,
                        onQuery = { state = state.copy(query = it) }, onReplacement = { error("Read-only replacement") },
                        onMatchCase = { state = state.copy(caseSensitive = it) },
                        onPrevious = {}, onNext = {}, onReplace = { error("Read-only replacement") },
                        onReplaceAll = { error("Read-only replacement") }, onClose = {}, readOnly = true)
                }
            }
        }
        compose.onNodeWithText("Find · Reading mode").assertIsDisplayed()
        compose.onNodeWithContentDescription("Find text").assertIsFocused().performTextReplacement("exception")
        compose.onNodeWithContentDescription("Replacement text").assertDoesNotExist()
        compose.onNodeWithText("Replace").assertDoesNotExist()
        compose.onNodeWithText("All").assertDoesNotExist()
        compose.runOnIdle { assertEquals("exception", state.query) }
    }

    private fun firstLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("editor-text-p").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun editor() {
        session = EditorSession(scope, SettingsRepository(MapSettings(), Json),
            NoteDocument(lines = listOf(EditorLine.plain("p", ""))), onPersist = { 1L })
        compose.setContent {
            // Fractional density/font scaling catches pixel rounding differences
            // between the real row and off-screen line measurement.
            CompositionLocalProvider(LocalDensity provides Density(1.25f, 1.1f)) {
                MaterialTheme {
                    val state by session.state.collectAsState()
                    val status by session.saveStatus.collectAsState()
                    var widthPx by remember { mutableStateOf(0) }
                    val layout = rememberDocumentLayout(state, widthPx, font, wrap)
                    SideEffect { metrics = layout }
                    ClearEditingFocus(reading)
                    Column(Modifier.width(width)) {
                        Row {
                            ReadingModeButton(reading) {
                                reading = !reading
                                session.setReadOnly(reading)
                            }
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { menu = true }) { Text("Color") }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(onClick = { session.setLineColor(HighlightColor.BLUE); menu = false }) { Text("Blue") }
                                }
                            }
                        }
                        LazyColumn(Modifier.height(170.dp).onSizeChanged { widthPx = it.width }) {
                            items(state.lines, key = { it.id }) { line ->
                                EditorLineRow(
                                    line, state.numbers[line.id], state.activeLineId == line.id, false, wrap, font,
                                    emptyList(), Color.Yellow, remember(line.id) { FocusRequester() }, { _, _ -> }, {}, null,
                                    session::applyTextChange, session::onLineFocused, session::toggleChecked, session::selectSingleLine,
                                    {}, session::onVisualLineChanged, readOnly = reading
                                )
                            }
                        }
                        StatusBar(state, status, font, false, layout, reading)
                    }
                }
            }
        }
        compose.onNodeWithTag("editor-text-p").performTextReplacement(pasted)
        compose.waitUntil(10_000) { metrics?.lines?.size == 31 }
    }
}
