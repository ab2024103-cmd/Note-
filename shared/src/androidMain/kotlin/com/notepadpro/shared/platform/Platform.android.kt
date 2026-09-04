package com.notepadpro.shared.platform

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.notepadpro.shared.data.db.NoteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * Android platform hooks. [attach] must be called from the Activity's
 * onCreate before any picker is used.
 */
object AndroidPlatform {
    lateinit var appContext: Context
        private set

    private lateinit var openLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createLauncher: ActivityResultLauncher<String>
    private var pendingOpen: CompletableDeferred<PickedFile?>? = null
    private var pendingCreate: CompletableDeferred<PickedFile?>? = null

    val isAttached: Boolean get() = ::appContext.isInitialized

    fun attach(activity: ComponentActivity) {
        if (::appContext.isInitialized) return
        appContext = activity.applicationContext

        openLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val pending = pendingOpen
            pendingOpen = null
            if (pending != null && uri != null) {
                pending.complete(toPickedFile(uri))
            } else {
                pending?.complete(null)
            }
        }
        createLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val pending = pendingCreate
            pendingCreate = null
            if (pending != null && uri != null) {
                pending.complete(toPickedFile(uri))
            } else {
                pending?.complete(null)
            }
        }
    }

    private fun toPickedFile(uri: Uri): PickedFile {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "note.txt"
        val cr = appContext.contentResolver
        return PickedFile(
            displayName = uri.toString(),
            readText = {
                cr.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            },
            writeText = { content ->
                cr.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                    ?: error("Cannot write to $name")
            }
        )
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

actual object AppDispatchers {
    actual val main = Dispatchers.Main
    actual val io = Dispatchers.IO
    actual val default = Dispatchers.Default
}

actual fun appCoreScope(): CoroutineScope = CoroutineScope(SupervisorJob() + AppDispatchers.main)

@SuppressLint("ServiceCast")
private fun clipboard(): ClipboardManager? {
    val ctx = AndroidPlatform.appContext
    return ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
}

actual object ClipboardBridge {
    actual fun copy(text: String) {
        runCatching {
            clipboard()?.setPrimaryClip(ClipData.newPlainText("notepadpro", text))
        }
    }

    actual fun read(): String? = runCatching {
        clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(AndroidPlatform.appContext)?.toString()
    }.getOrNull()
}

actual object FilePickerBridge {
    actual suspend fun pickOpenFile(): PickedFile? {
        if (!AndroidPlatform.isAttached) return null
        val d = CompletableDeferred<PickedFile?>()
        AndroidPlatform.pendingOpen = d
        AndroidPlatform.openLauncher.launch(arrayOf("*/*"))
        return d.await()
    }

    actual suspend fun pickSaveLocation(suggestedName: String): PickedFile? {
        if (!AndroidPlatform.isAttached) return null
        val d = CompletableDeferred<PickedFile?>()
        AndroidPlatform.pendingCreate = d
        AndroidPlatform.createLauncher.launch(suggestedName)
        return d.await()
    }
}

actual object FileIO {
    actual fun readText(path: String): String = File(path).readText(Charsets.UTF_8)

    actual fun writeText(path: String, content: String) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
    }
}

actual object PlatformInfo {
    actual fun isLowMemoryDevice(): Boolean {
        if (!AndroidPlatform.isAttached) return true
        val am = AndroidPlatform.appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice ?: true
        val memoryClass = am?.memoryClass ?: 64
        return isLowRam || memoryClass <= 96
    }

    actual fun recommendedUndoHistoryLimit(): Int = if (isLowMemoryDevice()) 30 else 100

    actual fun animationsEnabled(): Boolean {
        if (!AndroidPlatform.isAttached) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Settings.Global.getFloat(
                AndroidPlatform.appContext.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        } else true
    }

    actual fun isDesktop(): Boolean = false
}

actual fun randomLineId(): String = UUID.randomUUID().toString()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

actual fun createDatabaseDriver(): SqlDriver {
    val ctx = AndroidPlatform.appContext
    val dbFile = ctx.getDatabasePath(DB_FILE_NAME)
    dbFile.parentFile?.mkdirs()
    return AndroidSqliteDriver(NoteDatabase.Schema, ctx, dbFile.absolutePath)
}
