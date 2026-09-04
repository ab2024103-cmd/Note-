package com.notepadpro.shared.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notepadpro.shared.AppCore
import com.notepadpro.shared.UiPrefs
import com.notepadpro.shared.data.settings.ThemeMode
import com.notepadpro.shared.platform.PlatformInfo
import kotlin.math.roundToInt

@Composable
internal fun SettingsDialog(core: AppCore, prefs: UiPrefs) {
    AlertDialog(
        onDismissRequest = { core.setSettingsOpen(false) },
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Theme (two themes only: Light / Dark)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = prefs.themeMode == mode,
                            onClick = { core.setThemeMode(mode) }
                        )
                        Text(
                            when (mode) {
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                                ThemeMode.SYSTEM -> "Follow system"
                            },
                            fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = prefs.wordWrap, onCheckedChange = { core.setWordWrap(it) })
                    Text("Wrap long lines", fontSize = 13.sp)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = prefs.reduceMotion, onCheckedChange = { core.setReduceMotion(it) })
                    Text("Reduce motion (disable panel animations)", fontSize = 13.sp)
                }
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Font size  ", fontSize = 13.sp)
                    TextButton(onClick = { core.setFontSize(-1f) }) { Text("−", fontSize = 16.sp) }
                    Text("${prefs.fontSizeSp.roundToInt()} pt  (${((prefs.fontSizeSp / 15f) * 100f).roundToInt()}%)", fontSize = 13.sp)
                    TextButton(onClick = { core.setFontSize(+1f) }) { Text("+", fontSize = 16.sp) }
                }
                Spacer(Modifier.size(8.dp))
                Text("Device class", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    if (PlatformInfo.isLowMemoryDevice()) "Low-memory device: shorter undo history, longer autosave interval, no busy animations."
                    else "Standard device",
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { core.setSettingsOpen(false) }) { Text("Done") }
        }
    )
}

@Composable
internal fun AboutDialog(core: AppCore) {
    AlertDialog(
        onDismissRequest = { core.setAboutOpen(false) },
        title = { Text("NotePad Pro") },
        text = {
            Column {
                Text("Version 1.0.0", fontSize = 13.sp)
                Spacer(Modifier.width(1.dp))
                Text(
                    "Kotlin Multiplatform + Compose Multiplatform.\n" +
                        "Offline-first. Notes are stored in a local SQLite database\n" +
                        "(notepad-pro.sqlite3); documents stay in memory as a line model,\n" +
                        "never as HTML.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { core.setAboutOpen(false) }) { Text("OK") }
        }
    )
}
