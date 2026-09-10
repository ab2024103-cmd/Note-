package com.notepadpro.shared.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.notepadpro.shared.domain.model.EditorLine
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse

class ReadingModeImeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun enteringReadingModeHidesTheKeyboardAndTappingTextDoesNotReopenIt() {
        compose.setContent {
            var reading by remember { mutableStateOf(false) }
            MaterialTheme {
                ClearEditingFocus(reading)
                Column {
                    ReadingModeButton(reading) { reading = !reading }
                    EditorLineRow(
                        EditorLine.plain("p", "Read this note without accidental edits or an editing keyboard."),
                        null, true, false, true, 15f, emptyList(), Color.Yellow,
                        remember { FocusRequester() }, { _, _ -> }, {}, null,
                        { _, _, _, _ -> }, {}, {}, {}, {}, readOnly = reading
                    )
                }
            }
        }
        compose.onNodeWithTag("editor-text-p").performClick()
        // The emulator enables show_ime_with_hard_keyboard, so this also proves
        // the test would detect an incorrectly opened editing keyboard.
        compose.waitUntil(10_000) { imeVisible() }
        compose.onNodeWithTag("reading-mode-toggle").performClick()
        compose.waitUntil(10_000) { !imeVisible() }
        compose.onNodeWithTag("editor-text-p").performClick()
        compose.waitForIdle()
        assertFalse(imeVisible())
    }

    private fun imeVisible(): Boolean {
        var visible = false
        compose.runOnUiThread {
            visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }
}
