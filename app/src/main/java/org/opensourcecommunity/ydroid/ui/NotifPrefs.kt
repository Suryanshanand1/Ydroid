package org.opensourcecommunity.ydroid.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

private const val PREFS = "ydroid_settings"
private const val KEY_NOTIF_ASKED = "notif_asked"

fun isNotifGranted(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(
        ctx, android.Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

fun notifAsked(ctx: Context): Boolean {
    return try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTIF_ASKED, false)
    } catch (_: Exception) { true }
}

fun setNotifAsked(ctx: Context) {
    try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
    } catch (_: Exception) { }
}
