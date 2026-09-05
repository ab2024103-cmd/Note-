package com.notepadpro.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import com.notepadpro.shared.generated.resources.Res
import com.notepadpro.shared.generated.resources.notepad_pro
import org.jetbrains.compose.resources.painterResource

/** Shared, tint-free brand artwork for the UI and desktop window/taskbar. */
@Composable
fun notePadProIconPainter(): Painter = painterResource(Res.drawable.notepad_pro)

/**
 * Leave [contentDescription] null beside the app name to avoid a duplicate
 * accessibility announcement. Supply a label when the logo stands alone.
 */
@Composable
fun NotePadProLogo(modifier: Modifier = Modifier, contentDescription: String? = null) {
    Image(
        painter = notePadProIconPainter(),
        contentDescription = contentDescription,
        modifier = modifier
    )
}
