package com.notepadpro.shared.data.settings

import android.content.Context
import com.notepadpro.shared.platform.AndroidPlatform
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/** Android settings backend: SharedPreferences. */
actual fun createSettings(): Settings {
    val ctx = AndroidPlatform.appContext
    return SharedPreferencesSettings(
        ctx.getSharedPreferences("notepadpro_prefs", Context.MODE_PRIVATE)
    )
}
