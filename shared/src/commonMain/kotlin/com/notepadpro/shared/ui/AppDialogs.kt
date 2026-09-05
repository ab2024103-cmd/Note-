package com.notepadpro.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.AlertDialog
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notepadpro.shared.AppCore
import com.notepadpro.shared.UiPrefs
import com.notepadpro.shared.data.settings.ThemeMode
import com.notepadpro.shared.platform.PlatformInfo
import kotlin.math.roundToInt

/** Preferences dialog: theme, wrap, motion, font size (spec section 10). */
@Composable
fun SettingsDialog(core: AppCore, prefs: UiPrefs) {
    AlertDialog(
        onDismissRequest = { core.setSettingsOpen(false) },
        title = { Text("Settings", fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Theme", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThemeChoice("Light", ThemeMode.LIGHT, prefs.themeMode) { core.setThemeMode(ThemeMode.LIGHT) }
                    ThemeChoice("Dark", ThemeMode.DARK, prefs.themeMode) { core.setThemeMode(ThemeMode.DARK) }
                    ThemeChoice("System", ThemeMode.SYSTEM, prefs.themeMode) { core.setThemeMode(ThemeMode.SYSTEM) }
                }

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = prefs.wordWrap, onCheckedChange = { core.setWordWrap(it) })
                    Text("Word wrap", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = prefs.reduceMotion, onCheckedChange = { core.setReduceMotion(it) })
                    Text("Reduce motion (no panel slide/fade)", fontSize = 13.sp)
                }

                Spacer(Modifier.height(4.dp))
                Text("Font size", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { core.setFontSize(-1f) }, enabled = prefs.fontSizeSp > 10f) { Text("A−") }
                    Text("${prefs.fontSizeSp.roundToInt()} sp", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    TextButton(onClick = { core.setFontSize(+1f) }, enabled = prefs.fontSizeSp < 26f) { Text("A+") }
                }

                if (PlatformInfo.isLowMemoryDevice()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This device has limited memory — undo depth is automatically capped and very large notes may be slower.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { core.setSettingsOpen(false) }) { Text("Done") }
        }
    )
}

@Composable
private fun ThemeChoice(label: String, mode: ThemeMode, current: ThemeMode, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onPick)
    ) {
        RadioButton(selected = current == mode, onClick = onPick)
        Text(label, fontSize = 13.sp)
    }
}

/** About dialog (spec section 10). */
@Composable
fun AboutDialog(core: AppCore) {
    AlertDialog(
        onDismissRequest = { core.setAboutOpen(false) },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NotePadProLogo(modifier = Modifier.size(40.dp))
                Text("NotePad Pro")
            }
        },
        text = {
            Column {
                Text("Version 1.0.0", fontSize = 13.sp)
                Text(
                    "A lightweight, offline rich-text notepad.\n" +
                        "Built with Kotlin Multiplatform + Compose Multiplatform.\n" +
                        "Your notes never leave this device.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { core.setAboutOpen(false) }) { Text("OK") }
        }
    )
}
