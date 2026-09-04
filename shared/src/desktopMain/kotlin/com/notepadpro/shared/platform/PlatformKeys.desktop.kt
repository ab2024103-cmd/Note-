package com.notepadpro.shared.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.awt.event.KeyEvent as AwtKeyEvent

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
    if (e.key.value <= 0) return null
    return PlatformKeyEvent(
        key = mapDesktopKeyCode(e.key.value),
        ctrl = e.isCtrlPressed,
        shift = e.isShiftPressed,
        alt = e.isAltPressed,
        isDown = e.type == KeyEventType.KeyDown
    )
}

/**
 * Desktop Key.value carries the AWT virtual-key code, which is ASCII-based
 * for letters/digits and VK_* for control keys.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun mapDesktopKeyCode(code: Int): CommonKey {
    // A..Z: VK_A..VK_Z (65..90); CommonKey order: UNKNOWN(0), A..Z(1..26)
    if (code in AwtKeyEvent.VK_A..AwtKeyEvent.VK_Z) {
        return CommonKey.values()[code - AwtKeyEvent.VK_A + 1]
    }
    // 0..9: VK_0..VK_9 (48..57); CommonKey: DIGIT_0 starts at index 27
    if (code in AwtKeyEvent.VK_0..AwtKeyEvent.VK_9) {
        return CommonKey.values()[27 + (code - AwtKeyEvent.VK_0)]
    }
    return when (code) {
        AwtKeyEvent.VK_ENTER -> CommonKey.ENTER
        AwtKeyEvent.VK_TAB -> CommonKey.TAB
        AwtKeyEvent.VK_BACK_SPACE -> CommonKey.BACKSPACE
        AwtKeyEvent.VK_DELETE -> CommonKey.DELETE
        AwtKeyEvent.VK_UP -> CommonKey.ARROW_UP
        AwtKeyEvent.VK_DOWN -> CommonKey.ARROW_DOWN
        AwtKeyEvent.VK_LEFT -> CommonKey.ARROW_LEFT
        AwtKeyEvent.VK_RIGHT -> CommonKey.ARROW_RIGHT
        AwtKeyEvent.VK_HOME -> CommonKey.HOME
        AwtKeyEvent.VK_END -> CommonKey.END
        AwtKeyEvent.VK_PAGE_UP -> CommonKey.PAGE_UP
        AwtKeyEvent.VK_PAGE_DOWN -> CommonKey.PAGE_DOWN
        AwtKeyEvent.VK_ESCAPE -> CommonKey.ESCAPE
        AwtKeyEvent.VK_MINUS -> CommonKey.MINUS
        AwtKeyEvent.VK_EQUALS -> CommonKey.EQUALS
        AwtKeyEvent.VK_SPACE -> CommonKey.SPACE
        else -> CommonKey.UNKNOWN
    }
}
