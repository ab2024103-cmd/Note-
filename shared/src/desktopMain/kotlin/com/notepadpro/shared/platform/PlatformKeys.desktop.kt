package com.notepadpro.shared.platform

import androidx.compose.runtime.Composable
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
import androidx.compose.foundation.layout.Box

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
            translated?.let { latestOnKey(it) } ?: false
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
    Key.Number0 -> CommonKey.DIGIT_0
    Key.Number1 -> CommonKey.DIGIT_1
    Key.Number2 -> CommonKey.DIGIT_2
    Key.Number3 -> CommonKey.DIGIT_3
    Key.Number4 -> CommonKey.DIGIT_4
    Key.Number5 -> CommonKey.DIGIT_5
    Key.Number6 -> CommonKey.DIGIT_6
    Key.Number7 -> CommonKey.DIGIT_7
    Key.Number8 -> CommonKey.DIGIT_8
    Key.Number9 -> CommonKey.DIGIT_9
    Key.Enter -> CommonKey.ENTER
    Key.Tab -> CommonKey.TAB
    Key.Backspace -> CommonKey.BACKSPACE
    Key.Delete -> CommonKey.DELETE
    Key.DirectionUp -> CommonKey.ARROW_UP
    Key.DirectionDown -> CommonKey.ARROW_DOWN
    Key.DirectionLeft -> CommonKey.ARROW_LEFT
    Key.DirectionRight -> CommonKey.ARROW_RIGHT
    Key.Home -> CommonKey.HOME
    Key.End -> CommonKey.END
    Key.PageUp -> CommonKey.PAGE_UP
    Key.PageDown -> CommonKey.PAGE_DOWN
    Key.Escape -> CommonKey.ESCAPE
    Key.Minus -> CommonKey.MINUS
    Key.Equals -> CommonKey.EQUALS
    Key.Spacebar -> CommonKey.SPACE
    else -> CommonKey.UNKNOWN
}
