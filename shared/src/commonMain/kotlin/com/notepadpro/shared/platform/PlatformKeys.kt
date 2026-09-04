package com.notepadpro.shared.platform

import androidx.compose.runtime.Composable

/**
 * Normalized physical-key description used to implement the shared
 * keyboard shortcut map (Ctrl+N/O/S..., Enter/Backspace line navigation)
 * from common code. Each platform translates its own KeyEvent into this.
 */
enum class CommonKey {
    UNKNOWN,
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,
    ENTER, TAB, BACKSPACE, DELETE,
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    HOME, END, PAGE_UP, PAGE_DOWN,
    MINUS, EQUALS,
    SPACE, ESCAPE
}

data class PlatformKeyEvent(
    val key: CommonKey,
    val ctrl: Boolean,
    val shift: Boolean,
    val alt: Boolean,
    val isDown: Boolean
)

/**
 * Composable that installs platform keyboard interception around [content].
 * The [onKey] handler runs during the preview/capture phase, before focused
 * text fields consume the event; return true to consume the event.
 * KeyDown events are delivered (platform repeats included).
 */
expect @Composable
fun PlatformKeyScope(
    enabled: Boolean,
    onKey: (PlatformKeyEvent) -> Boolean,
    content: @Composable () -> Unit
)

/**
 * Platform back button hook: Android = system back, desktop = no-op.
 */
expect @Composable
fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
