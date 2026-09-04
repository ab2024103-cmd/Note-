package com.notepadpro.shared.platform

import com.notepadpro.shared.domain.model.LineEnding
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/** Dispatchers that exist on every platform (Main/IO are not in commonMain). */
expect object AppDispatchers {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

/**
 * Application-wide scope, created lazily per platform (main dispatcher).
 * The app never cancels this scope except at shutdown.
 */
expect fun appCoreScope(): CoroutineScope

/** Clipboard bridge: copy/read plain text. */
expect object ClipboardBridge {
    fun copy(text: String)
    fun read(): String?
}

/** A file the user picked; accessors close over platform handles. */
data class PickedFile(
    val displayName: String,
    val readText: () -> String,
    val writeText: (String) -> Unit
)

/** Native open/save dialogs (AWT FileDialog on desktop, SAF on Android). */
expect object FilePickerBridge {
    suspend fun pickOpenFile(): PickedFile?
    suspend fun pickSaveLocation(suggestedName: String): PickedFile?
}

/** Direct file I/O by path (desktop app-data files; Android internal files). */
expect object FileIO {
    fun readText(path: String): String
    fun writeText(path: String, content: String)
}

expect object PlatformInfo {
    fun isLowMemoryDevice(): Boolean
    fun recommendedUndoHistoryLimit(): Int
    fun animationsEnabled(): Boolean
    fun isDesktop(): Boolean
}

/** Random line id (UUID). */
expect fun randomLineId(): String

/** Current wall time in ms. */
expect fun currentTimeMillis(): Long

/** Locale-style short timestamp for the sidebar. */
expect fun formatTimestamp(epochMs: Long): String
