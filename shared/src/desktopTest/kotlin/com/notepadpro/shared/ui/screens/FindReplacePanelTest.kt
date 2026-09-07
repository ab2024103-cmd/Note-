package com.notepadpro.shared.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.notepadpro.shared.FindUiState
import com.notepadpro.shared.editor.FindMatch
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FindReplacePanelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun referenceControlsAreVisibleAndBothFieldsAndButtonsWork() {
        var state by mutableStateOf(FindUiState(query = "cat", matches = listOf(FindMatch("p", 0, 0, 3, "cat"))))
        var visible by mutableStateOf(true)
        var previous = 0
        var next = 0
        var replaced = 0
        var all = 0
        var outside = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(800.dp, 600.dp)) {
                    TextButton(onClick = { outside++ }) { Text("Editor outside panel") }
                    if (visible) FloatingFindReplacePanel(
                        state, false, false, 1,
                        onQuery = { state = state.copy(query = it) }, onReplacement = { state = state.copy(replaceQuery = it) },
                        onMatchCase = { state = state.copy(caseSensitive = it) },
                        onPrevious = { previous++ }, onNext = { next++ }, onReplace = { replaced++ }, onReplaceAll = { all++ },
                        onClose = { visible = false }
                    )
                }
            }
        }
        compose.onNodeWithText("Find & Replace").assertIsDisplayed()
        compose.onNodeWithContentDescription("Find text").assertIsFocused().performTextReplacement("dog")
        compose.onNodeWithContentDescription("Replacement text").performTextReplacement("lion")
        compose.onNodeWithText("1 / 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous match").performClick()
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.onNodeWithText("Replace").performClick()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithText("Match case").performClick()
        compose.onNodeWithText("Editor outside panel").performClick()
        compose.runOnIdle {
            assertEquals("dog", state.query)
            assertEquals("lion", state.replaceQuery)
            assertTrue(state.caseSensitive)
            assertEquals(listOf(1, 1, 1, 1, 1), listOf(previous, next, replaced, all, outside))
            state = state.copy(matches = emptyList())
        }
        compose.onNodeWithText("0 / 0").assertIsDisplayed()
        compose.onNodeWithText("Replace").assertIsNotEnabled()
        compose.onNodeWithText("All").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close Find & Replace").performClick()
        compose.onNodeWithText("Find & Replace").assertDoesNotExist()
    }

    @Test
    fun panelCanBeDraggedAndStaysInsideANarrowEditor() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(340.dp, 300.dp).testTag("viewport")) {
                    FloatingFindReplacePanel(FindUiState(), false, false, 1,
                        {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        val before = compose.onNodeWithTag("find-replace-panel").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("find-replace-drag-handle").performTouchInput {
            swipe(center, center + Offset(100f, 70f), 300)
        }
        val after = compose.onNodeWithTag("find-replace-panel").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(after.top > before.top)
        assertTrue(after.left >= viewport.left && after.right <= viewport.right)
        assertTrue(after.top >= viewport.top && after.bottom <= viewport.bottom)
        compose.onNodeWithContentDescription("Replacement text").assertIsDisplayed()
        compose.onNodeWithText("Match case").assertIsDisplayed()
    }

    @Test
    fun enterShiftEnterAndEscapeWorkWithoutMovingFocusIntoTheEditor() {
        var next = 0
        var previous = 0
        var closed = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(800.dp, 600.dp)) {
                    FloatingFindReplacePanel(FindUiState(), false, false, 1,
                        {}, {}, {}, { previous++ }, { next++ }, {}, {}, { closed = true })
                }
            }
        }
        val find = compose.onNodeWithContentDescription("Find text").assertIsFocused()
        find.performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        find.performKeyInput {
            keyDown(Key.ShiftLeft); keyDown(Key.Enter); keyUp(Key.Enter); keyUp(Key.ShiftLeft)
        }
        find.assertIsFocused()
        find.performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        compose.runOnIdle {
            assertEquals(1, next)
            assertEquals(1, previous)
            assertTrue(closed)
        }
    }
}
