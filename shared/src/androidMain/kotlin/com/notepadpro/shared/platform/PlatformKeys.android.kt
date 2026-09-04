package com.notepadpro.shared.platform

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.view.KeyEvent as AndroidKeyEvent

/**
 * Android: hardware-keyboard (Chromebook / BT keyboards) support through the
 * same compose key pipeline as desktop. Soft keyboards are unaffected.
 */
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
            val translated = translateAndroid(event)
            if (translated != null) latestOnKey(translated) else false
        }
    ) {
        content()
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

/**
 * Maps a compose key event onto the shared key model. On Android,
 * [androidx.compose.ui.input.key.Key.value] carries the Android key code.
 */
private fun translateAndroid(e: KeyEvent): PlatformKeyEvent? {
    val code = e.key.value
    if (code <= 0) return null
    val mapped = mapAndroidKeyCode(code) ?: return null
    return PlatformKeyEvent(
        key = mapped,
        ctrl = e.isCtrlPressed,
        shift = e.isShiftPressed,
        alt = e.isAltPressed,
        isDown = e.type == KeyEventType.KeyDown
    )
}

/** A..Z, 0..9 and navigation keys. Returns null for anything unmapped. */
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun mapAndroidKeyCode(code: Int): CommonKey? {
    // CommonKey order: UNKNOWN(0), A..Z(1..26), DIGIT_0..DIGIT_9(27..36), ...
    val a = AndroidKeyEvent.KEYCODE_A // 29
    if (code in a..a + 25) return CommonKey.values()[code - a + 1]
    val n0 = AndroidKeyEvent.KEYCODE_0 // 7
    if (code in n0..n0 + 9) return CommonKey.values()[27 + (code - n0)]
    return when (code) {
        AndroidKeyEvent.KEYCODE_ENTER -> CommonKey.ENTER
        AndroidKeyEvent.KEYCODE_TAB -> CommonKey.TAB
        AndroidKeyEvent.KEYCODE_DEL -> CommonKey.BACKSPACE
        AndroidKeyEvent.KEYCODE_FORWARD_DEL -> CommonKey.DELETE
        AndroidKeyEvent.KEYCODE_DPAD_UP -> CommonKey.ARROW_UP
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> CommonKey.ARROW_DOWN
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> CommonKey.ARROW_LEFT
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> CommonKey.ARROW_RIGHT
        AndroidKeyEvent.KEYCODE_MOVE_HOME -> CommonKey.HOME
        AndroidKeyEvent.KEYCODE_MOVE_END -> CommonKey.END
        AndroidKeyEvent.KEYCODE_PAGE_UP -> CommonKey.PAGE_UP
        AndroidKeyEvent.KEYCODE_PAGE_DOWN -> CommonKey.PAGE_DOWN
        AndroidKeyEvent.KEYCODE_ESCAPE -> CommonKey.ESCAPE
        AndroidKeyEvent.KEYCODE_MINUS -> CommonKey.MINUS
        AndroidKeyEvent.KEYCODE_EQUALS -> CommonKey.EQUALS
        AndroidKeyEvent.KEYCODE_SPACE -> CommonKey.SPACE
        else -> null
    }
}
