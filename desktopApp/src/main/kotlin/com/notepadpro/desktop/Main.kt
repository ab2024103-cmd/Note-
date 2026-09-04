package com.notepadpro.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.notepadpro.shared.AppCore
import com.notepadpro.shared.data.settings.SettingsRepository
import com.notepadpro.shared.platform.appCoreScope
import com.notepadpro.shared.platform.createSettings
import com.notepadpro.shared.ui.NotePadProApp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Dimension
import kotlin.math.roundToInt

/**
 * Windows/Linux/macOS entry point.
 * - window size is restored from settings and has the 560x460 minimum
 * - on close, pending DB autosaves are flushed synchronously
 */
fun main() = application {
    val core = remember {
        AppCore(
            scope = appCoreScope(),
            settings = SettingsRepository(createSettings(), Json { ignoreUnknownKeys = true })
        )
    }
    val settings = core.settings
    val windowState = rememberWindowState(
        width = settings.windowWidth.takeIf { it >= 560 }?.dp ?: 1040.dp,
        height = settings.windowHeight.takeIf { it >= 460 }?.dp ?: 720.dp
    )

    Window(
        onCloseRequest = {
            windowState.size?.let {
                settings.windowWidth = it.width.value.roundToInt()
                settings.windowHeight = it.height.value.roundToInt()
            }
            // Flush debounced autosaves + persist the tab session.
            runBlocking { core.flushAll() }
            exitApplication()
        },
        state = windowState,
        title = "NotePad Pro",
        resizable = true
    ) {
        window.minimumSize = Dimension(560, 460)
        NotePadProApp(core)
    }
}
