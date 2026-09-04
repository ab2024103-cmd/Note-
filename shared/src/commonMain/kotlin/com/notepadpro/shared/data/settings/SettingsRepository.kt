package com.notepadpro.shared.data.settings

import com.russhwolf.settings.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * App preferences backed by multiplatform-settings
 * (SharedPreferences on Android, java.util.prefs on desktop).
 * All values are kept as strings and versioned with a [Keys] prefix.
 */
class SettingsRepository(private val settings: Settings, private val json: Json) {

    private object Keys {
        const val THEME = "theme"
        const val WORD_WRAP = "word_wrap"
        const val FONT_SIZE = "font_size_sp"
        const val REDUCE_MOTION = "reduce_motion"
        const val RECENT_FILES = "recent_files"
        const val TAB_SESSION = "tab_session"
        const val WINDOW_W = "window_w"
        const val WINDOW_H = "window_h"
        const val HIGHLIGHT_COLOR = "highlight_color"
        const val SHOW_LINE_COLORS = "show_line_colors_v1"
    }

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(settings.getString(Keys.THEME, "SYSTEM")) }
            .getOrDefault(ThemeMode.SYSTEM)
        set(v) = settings.putString(Keys.THEME, v.name)

    var wordWrap: Boolean
        get() = settings.getBoolean(Keys.WORD_WRAP, true)
        set(v) = settings.putBoolean(Keys.WORD_WRAP, v)

    var reduceMotion: Boolean
        get() = settings.getBoolean(Keys.REDUCE_MOTION, false)
        set(v) = settings.putBoolean(Keys.REDUCE_MOTION, v)

    var fontSizeSp: Float
        get() = settings.getFloat(Keys.FONT_SIZE, 15f).coerceIn(10f, 26f)
        set(v) = settings.putFloat(Keys.FONT_SIZE, v.coerceIn(10f, 26f))

    var lastHighlightColor: String
        get() = settings.getString(Keys.HIGHLIGHT_COLOR, "YELLOW")
        set(v) = settings.putString(Keys.HIGHLIGHT_COLOR, v)

    fun getRecentFiles(): List<String> =
        settings.getStringOrNull(Keys.RECENT_FILES)?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()

    /** Recent-files list, most recent first, capped at 10 entries. */
    fun addRecentFile(path: String) {
        val list = (listOf(path) + getRecentFiles().filter { it != path }).take(10)
        settings.putString(Keys.RECENT_FILES, json.encodeToString(list))
    }

    /** Serialized tab session: ordered note ids + active index (JSON). */
    fun getTabSession(): List<Long> =
        settings.getStringOrNull(Keys.TAB_SESSION)?.let {
            runCatching { json.decodeFromString<List<Long>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()

    fun setTabSession(ids: List<Long>) {
        if (ids.isEmpty()) settings.remove(Keys.TAB_SESSION)
        else settings.putString(Keys.TAB_SESSION, json.encodeToString(ids))
    }

    var windowWidth: Int
        get() = settings.getInt(Keys.WINDOW_W, 0)
        set(v) = settings.putInt(Keys.WINDOW_W, v)

    var windowHeight: Int
        get() = settings.getInt(Keys.WINDOW_H, 0)
        set(v) = settings.putInt(Keys.WINDOW_H, v)
}
