package com.notepadpro.shared.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Desktop key scope: Compose desktop dispatches events through the same pipeline. */
@Composable
actual fun PlatformKeyScope(
    enabled: Boolean,
    onKey: (PlatformKeyEvent) -> Boolean,
    content: @Composable () -> Unit
) {
    val latestOnKey by rememberUpdatedState(onKey)
    val latestEnabled by rememberUpdatedState(enabled)
    Box(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (!latestEnabled) return@onPreviewKeyEvent false
            val translated = translateDesktop(event)
            if (translated != null) latestOnKey(translated) else false
        }
    ) {
        content()
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No system back button on desktop; Escape is handled by the shortcut map.
}

private fun translateDesktop(e: KeyEvent): PlatformKeyEvent? {
    if (e.key == Key.Unknown) return null
    return PlatformKeyEvent(
        key = mapDesktopKey(e.key),
        ctrl = e.isCtrlPressed,
        shift = e.isShiftPressed,
        alt = e.isAltPressed,
        isDown = e.type == KeyEventType.KeyDown
    )
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun mapDesktopKey(key: Key): CommonKey = when (key) {
    Key.A -> CommonKey.A
    Key.B -> CommonKey.B
    Key.C -> CommonKey.C
    Key.D -> CommonKey.D
    Key.E -> CommonKey.E
    Key.F -> CommonKey.F
    Key.G -> CommonKey.G
    Key.H -> CommonKey.H
    Key.I -> CommonKey.I
    Key.J -> CommonKey.J
    Key.K -> CommonKey.K
    Key.L -> CommonKey.L
    Key.M -> CommonKey.M
    Key.N -> CommonKey.N
    Key.O -> CommonKey.O
    Key.P -> CommonKey.P
    Key.Q -> CommonKey.Q
    Key.R -> CommonKey.R
    Key.S -> CommonKey.S
    Key.T -> CommonKey.T
    Key.U -> CommonKey.U
    Key.V -> CommonKey.V
    Key.W -> CommonKey.W
    Key.X -> CommonKey.X
    Key.Y -> CommonKey.Y
    Key.Z -> CommonKey.Z
    Key.Zero -> CommonKey.DIGIT_0
    Key.One -> CommonKey.DIGIT_1
    Key.Two -> CommonKey.DIGIT_2
    Key.Three -> CommonKey.DIGIT_3
    Key.Four -> CommonKey.DIGIT_4
    Key.Five -> CommonKey.DIGIT_5
    Key.Six -> CommonKey.DIGIT_6
    Key.Seven -> CommonKey.DIGIT_7
    Key.Eight -> CommonKey.DIGIT_8
    Key.Nine -> CommonKey.DIGIT_9
    Key.Enter -> CommonKey.ENTER
    Key.Tab -> CommonKey.TAB
    Key.Backspace -> CommonKey.BACKSPACE
    Key.Delete -> CommonKey.DELETE
    Key.DirectionUp -> CommonKey.ARROW_UP
    Key.DirectionDown -> CommonKey.ARROW_DOWN
    Key.DirectionLeft -> CommonKey.ARROW_LEFT
    Key.DirectionRight -> CommonKey.ARROW_RIGHT
    Key.Home -> CommonKey.HOME
    Key.MoveEnd -> CommonKey.END
    Key.PageUp -> CommonKey.PAGE_UP
    Key.PageDown -> CommonKey.PAGE_DOWN
    Key.Escape -> CommonKey.ESCAPE
    Key.Minus -> CommonKey.MINUS
    Key.Equals -> CommonKey.EQUALS
    Key.Spacebar -> CommonKey.SPACE
    else -> CommonKey.UNKNOWN
}
