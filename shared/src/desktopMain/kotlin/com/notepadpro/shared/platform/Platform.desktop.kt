package com.notepadpro.shared.platform

import com.notepadpro.shared.data.db.NoteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.resume
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual object AppDispatchers {
    actual val main = Dispatchers.Main
    actual val io = Dispatchers.IO
    actual val default = Dispatchers.Default
}

actual fun appCoreScope(): CoroutineScope = CoroutineScope(SupervisorJob() + AppDispatchers.main)

actual object ClipboardBridge {
    actual fun copy(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    actual fun read(): String? = runCatching {
        val clip = Toolkit.getDefaultToolkit().systemClipboard
        if (clip.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) {
            clip.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        } else null
    }.getOrNull()
}

/** Desktop picker using the AWT FileDialog (invoked on the EDT). */
actual object FilePickerBridge {
    actual suspend fun pickOpenFile(): PickedFile? {
        val result = kotlinx.coroutines.suspendCancellableCoroutine<PickedFile?> { cont ->
            javax.swing.SwingUtilities.invokeLater {
                try {
                    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Open note", java.awt.FileDialog.LOAD)
                    dialog.isVisible = true
                    val file = dialog.file?.let { java.io.File(dialog.directory, it) }
                    if (cont.isActive) {
                        if (file != null && file.isFile) {
                            cont.resume(
                                PickedFile(
                                    displayName = file.absolutePath,
                                    readText = { file.readText(Charsets.UTF_8) },
                                    writeText = { file.writeText(it, Charsets.UTF_8) }
                                )
                            ) {}
                        } else cont.resume(null) {}
                    }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        }
        return result
    }

    actual suspend fun pickSaveLocation(suggestedName: String): PickedFile? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            javax.swing.SwingUtilities.invokeLater {
                try {
                    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Save note as", java.awt.FileDialog.SAVE)
                    dialog.file = suggestedName
                    dialog.isVisible = true
                    val file = dialog.file?.let { java.io.File(dialog.directory, it) }
                    if (cont.isActive) {
                        if (file != null) {
                            cont.resume(
                                PickedFile(
                                    displayName = file.absolutePath,
                                    readText = { file.readText(Charsets.UTF_8) },
                                    writeText = { file.writeText(it, Charsets.UTF_8) }
                                )
                            ) {}
                        } else cont.resume(null) {}
                    }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        }
}

actual object FileIO {
    actual fun readText(path: String): String = File(path).readText(Charsets.UTF_8)

    actual fun writeText(path: String, content: String) {
        File(path).parentFile?.mkdirs()
        File(path).writeText(content, Charsets.UTF_8)
    }
}

actual object PlatformInfo {
    actual fun isLowMemoryDevice(): Boolean = Runtime.getRuntime().maxMemory() < 160L * 1024 * 1024

    actual fun recommendedUndoHistoryLimit(): Int = if (isLowMemoryDevice()) 30 else 100

    actual fun animationsEnabled(): Boolean = true

    actual fun isDesktop(): Boolean = true
}

actual fun randomLineId(): String = UUID.randomUUID().toString()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

/**
 * Desktop DB location: %APPDATA%/NotePadPro on Windows, ~/.notepadpro elsewhere.
 * jpackage installers are per-user, so APPDATA always exists for the app user.
 */
actual fun createDatabaseDriver(): SqlDriver {
    val base = (System.getenv("APPDATA") ?: System.getProperty("user.home"))
    val dir = File(base, if (System.getenv("APPDATA") != null) "NotePadPro" else ".notepadpro")
    dir.mkdirs()
    val url = "jdbc:sqlite:" + File(dir, DB_FILE_NAME).absolutePath
    val driver = JdbcSqliteDriver(url)
    val exists = driver.executeQuery(
        identifier = null,
        sql = "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='note'",
        mapper = { cursor -> if (cursor.next()) cursor.getLong(0) else 0L },
        parameters = 0
    ) > 0L
    if (!exists) NoteDatabase.Schema.create(driver)
    return driver
}
