package org.opensourcecommunity.ydroid.downloader

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.io.File

object Cookies {
    private const val TAG = "Cookies"

    fun file(ctx: Context): File = File(ctx.filesDir, "cookies.txt")

    fun hasCookies(ctx: Context): Boolean {
        return try {
            val f = file(ctx)
            f.exists() && f.length() > 64
        } catch (_: Exception) { false }
    }

    fun lastSaved(ctx: Context): Long {
        return try { file(ctx).lastModified() } catch (_: Exception) { 0L }
    }

    /**
     * Read WebView cookies for YouTube + Google accounts and write a Netscape
     * cookies.txt that yt-dlp understands. Returns number of cookies saved.
     */
    fun saveFromWebView(ctx: Context): Result<Int> {
        return try {
            val cm = CookieManager.getInstance()
            val merged = linkedMapOf<String, Pair<String, String>>() // name -> (value, domain)
            // (rawCookieHeader, domain)
            listOf(
                "https://www.youtube.com" to ".youtube.com",
                "https://m.youtube.com" to ".youtube.com",
                "https://accounts.google.com" to ".google.com",
            ).forEach { (url, domain) ->
                val raw = try { cm.getCookie(url) } catch (_: Exception) { null }
                raw?.split(";")?.forEach { part ->
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size == 2 && kv[0].isNotBlank()) {
                        merged[kv[0].trim()] = kv[1].trim() to domain
                    }
                }
            }
            if (merged.isEmpty()) {
                return Result.failure(Exception("No cookies found — sign in first, then tap Save"))
            }
            val exp = (System.currentTimeMillis() / 1000) + 365L * 24 * 3600
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            merged.forEach { (name, vd) ->
                val (value, domain) = vd
                val secure = if (name.startsWith("__Secure-") || name == "SID" || name == "HSID" || name == "SSID") "TRUE" else "FALSE"
                sb.append("$domain\tTRUE\t/\t$secure\t$exp\t$name\t$value\n")
            }
            file(ctx).writeText(sb.toString())
            Result.success(merged.size)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            Result.failure(e)
        }
    }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) { }
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Exception) { }
    }
}

