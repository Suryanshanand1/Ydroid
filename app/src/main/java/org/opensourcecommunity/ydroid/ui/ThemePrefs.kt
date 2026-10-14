package org.opensourcecommunity.ydroid.ui

import android.content.Context

enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
}

private const val PREFS = "ydroid_settings"
private const val KEY_THEME = "theme_mode"

fun loadThemeMode(ctx: Context): ThemeMode {
    return try {
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, ThemeMode.SYSTEM.name)
        ThemeMode.valueOf(name ?: ThemeMode.SYSTEM.name)
    } catch (_: Exception) {
        ThemeMode.SYSTEM
    }
}

fun saveThemeMode(ctx: Context, mode: ThemeMode) {
    try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, mode.name).apply()
    } catch (_: Exception) { }
}

