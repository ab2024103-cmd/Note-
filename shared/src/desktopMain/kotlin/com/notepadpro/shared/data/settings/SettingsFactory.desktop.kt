package com.notepadpro.shared.data.settings

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

/** Desktop settings backend: java.util.prefs (Windows registry / file). */
actual fun createSettings(): Settings =
    PreferencesSettings(Preferences.userRoot().node("notepadpro"))
