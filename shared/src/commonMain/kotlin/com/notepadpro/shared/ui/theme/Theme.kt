package com.notepadpro.shared.ui.theme

import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.notepadpro.shared.domain.model.HighlightColor

/**
 * Exactly two themes exist in this codebase: Light and Dark.
 * Glass / Clay / Neumorphism / Liquid were deliberately dropped (they were
 * CPU/GPU-costly in the original stack; only two themes are permitted).
 */
object AppTheme {
    val Light: Colors = lightColors(
        primary = Color(0xFF3F51B5),
        primaryVariant = Color(0xFF303F9F),
        secondary = Color(0xFF00897B),
        secondaryVariant = Color(0xFF00695C),
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        error = Color(0xFFD32F2F),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color(0xFF1B1B1B),
        onSurface = Color(0xFF1B1B1B),
        onError = Color.White
    )

    val Dark: Colors = darkColors(
        primary = Color(0xFF9FA8DA),
        primaryVariant = Color(0xFF5C6BC0),
        secondary = Color(0xFF80CBC4),
        secondaryVariant = Color(0xFF4DB6AC),
        background = Color(0xFF121212),
        surface = Color(0xFF1E1E1E),
        error = Color(0xFFEF5350),
        onPrimary = Color(0xFF121212),
        onSecondary = Color(0xFF121212),
        onBackground = Color(0xFFECECEC),
        onSurface = Color(0xFFECECEC),
        onError = Color(0xFF121212)
    )
}

@Composable
fun NotePadProTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colors = if (darkTheme) AppTheme.Dark else AppTheme.Light,
        typography = AppTypography,
        content = content
    )
}

private val AppTypography = Typography()

/**
 * Pastel marker palette used for both inline highlights and whole-line
 * colors. Highlighted runs always render with dark text so the marker
 * stays readable in both themes (like a real highlighter pen).
 */
object Markers {
    /** Highlighter-pen backgrounds - the same in both themes. */
    private val spanPalette = mapOf(
        HighlightColor.YELLOW to Color(0xFFFFF176),
        HighlightColor.GREEN to Color(0xFFA5D6A7),
        HighlightColor.PINK to Color(0xFFF48FB1),
        HighlightColor.BLUE to Color(0xFF90CAF9),
        HighlightColor.ORANGE to Color(0xFFFFCC80),
        HighlightColor.PURPLE to Color(0xFFCE93D8)
    )

    /** Deep, still readable versions used for whole-line washes in dark theme. */
    private val darkPalette = mapOf(
        HighlightColor.YELLOW to Color(0xFF6D5800),
        HighlightColor.GREEN to Color(0xFF1B5E20),
        HighlightColor.PINK to Color(0xFF880E4F),
        HighlightColor.BLUE to Color(0xFF0D47A1),
        HighlightColor.ORANGE to Color(0xFF6D2F00),
        HighlightColor.PURPLE to Color(0xFF4A148C)
    )

    /** Marker background for inline highlight spans (readable both themes). */
    fun spanBackground(color: HighlightColor): Color =
        spanPalette[color] ?: Color(0xFFFFF176)

    /** Whole-line wash painted under a colored line. */
    fun lineWash(color: HighlightColor, darkTheme: Boolean): Color {
        val base = (if (darkTheme) darkPalette else spanPalette)[color] ?: spanPalette[color]!!
        return if (darkTheme) base.copy(alpha = 0.55f) else base.copy(alpha = 0.30f)
    }

    /** Solid accent for the 4dp left edge of colored lines. */
    fun accent(color: HighlightColor, darkTheme: Boolean): Color {
        val base = if (darkTheme) spanPalette[color] else darkPalette[color]
        return base ?: spanPalette[color]!!
    }

    /** Text color painted on highlighted spans: always dark for contrast. */
    val markText: Color = Color(0xFF1B1B1B)

    fun solid(color: HighlightColor): Color = spanPalette[color] ?: Color(0xFFFFF176)
}
