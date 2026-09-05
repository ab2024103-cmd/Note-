package com.notepadpro.shared.ui.screens

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.editor.Caret
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorLineRowFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun caretRequestOnFirstCompositionFocusesTheTextField() {
        val line = EditorLine.plain("first", "hello")
        var focusedLine: String? = null
        var applied = 0
        compose.setContent {
            MaterialTheme {
                TestRow(
                    line = line,
                    caret = Caret(2, 2),
                    onFocused = { focusedLine = it },
                    onCaretApplied = { applied++ }
                )
            }
        }

        compose.onNode(hasSetTextAction())
            .assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2)))
        compose.runOnIdle {
            assertEquals(line.id, focusedLine)
            assertEquals(1, applied)
        }
    }

    @Test
    fun registeredRequesterCanFocusTheFieldAfterComposition() {
        val line = EditorLine.plain("first", "hello")
        val registry = mutableMapOf<String, FocusRequester>()
        compose.setContent {
            MaterialTheme {
                TestRow(line = line, registry = registry)
            }
        }

        // No caret request means no automatic focus/soft keyboard on Android.
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            // This is the same path used for desktop startup focus.
            registry.getValue(line.id).requestFocus()
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun pendingCaretCanFocusALazyRowWhenItIsComposedAfterScrolling() {
        val lines = List(100) { EditorLine.plain("line-$it", "Line $it") }
        val target = lines.last()
        var pending by mutableStateOf<Pair<String, Caret>?>(null)
        var applied = 0
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.height(80.dp).testTag("lines")) {
                    items(lines, key = { it.id }) { line ->
                        TestRow(
                            line = line,
                            caret = pending?.takeIf { it.first == line.id }?.second,
                            onCaretApplied = {
                                pending = null
                                applied++
                            }
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(target.plainText).assertDoesNotExist()
        compose.runOnIdle { pending = target.id to Caret(3, 3) }
        compose.onNodeWithTag("lines").performScrollToIndex(lines.lastIndex)
        compose.onNodeWithText(target.plainText)
            .assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        compose.runOnIdle {
            assertNull(pending)
            assertEquals(1, applied)
        }
    }

    @Composable
    private fun TestRow(
        line: EditorLine,
        caret: Caret? = null,
        registry: MutableMap<String, FocusRequester> = remember { mutableMapOf() },
        onFocused: (String) -> Unit = {},
        onCaretApplied: () -> Unit = {}
    ) {
        EditorLineRow(
            line = line,
            number = null,
            isActiveRow = false,
            darkTheme = false,
            wordWrap = true,
            fontSizeSp = 15f,
            findRanges = emptyList(),
            findColor = Color.Yellow,
            focusRequester = remember(line.id) { FocusRequester() },
            registerFocus = { id, requester -> registry[id] = requester },
            unregisterFocus = { id -> registry.remove(id) },
            caretToApply = caret,
            onTextChange = { _, _, _, _ -> },
            onRowFocused = onFocused,
            onToggleCheck = {},
            onSelectLine = {},
            onCaretApplied = onCaretApplied
        )
    }
}
