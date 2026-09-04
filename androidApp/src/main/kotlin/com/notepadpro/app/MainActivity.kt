package com.notepadpro.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.notepadpro.shared.AppCore
import com.notepadpro.shared.data.settings.SettingsRepository
import com.notepadpro.shared.platform.AndroidPlatform
import com.notepadpro.shared.ui.NotePadProApp
import kotlinx.coroutines.launch

/**
 * Single Activity hosting the whole Compose UI.
 * configChanges in the manifest prevent recreation on rotation so editor
 * state (caret, scroll, undo stack) survives orientation changes.
 */
class MainActivity : ComponentActivity() {

    private val core: AppCore by lazy {
        AppCore(
            scope = com.notepadpro.shared.platform.appCoreScope(),
            settings = SettingsRepository(
                settings = com.notepadpro.shared.platform.createSettings(),
                json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wire SAF picker launchers + app context BEFORE the UI starts.
        AndroidPlatform.attach(this)

        setContent {
            NotePadProApp(core)
        }
    }

    override fun onStop() {
        super.onStop()
        // Flush any pending debounced autosave when leaving the foreground.
        core.scope.launch {
            runCatching { core.flushAll() }
        }
    }
}
