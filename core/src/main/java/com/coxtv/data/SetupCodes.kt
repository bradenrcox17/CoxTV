package com.coxtv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * One-time setup codes: tv.thecoxhome.com ("Set up a TV") shows a code like K7P-2QX, and the
 * app exchanges it with the CoxOnAir stream server for the playlist and guide links, so
 * nobody has to type long URLs with a remote.
 */
object SetupCodes {
    const val SERVER = "https://stream.thecoxhome.com:9443"

    data class Links(val m3u: String, val epg: String, val appGuide: String)

    /** Letters and digits only, upper case ("k7p 2qx" -> "K7P2QX"). */
    fun normalize(code: String) = code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    suspend fun redeem(http: OkHttpClient, code: String): Links = withContext(Dispatchers.IO) {
        val clean = normalize(code)
        if (clean.length != 6) throw IOException("Setup codes have 6 letters and numbers, like K7P-2QX.")
        val request = Request.Builder().url("$SERVER/setup/$clean").header("Cache-Control", "no-store").build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body.string()
            when {
                resp.code == 404 -> throw IOException(
                    "That code didn't work. Codes work once and expire after 10 minutes. Get a new one on tv.thecoxhome.com.",
                )
                resp.code == 429 -> throw IOException("Too many wrong codes. Wait a few minutes and try again.")
                !resp.isSuccessful -> throw IOException("The setup server answered HTTP ${resp.code}. Try again in a moment.")
            }
            val json = JSONObject(body)
            val m3u = json.optString("m3u")
            if (m3u.isBlank()) throw IOException("The setup server didn't send a playlist.")
            Links(m3u, json.optString("epg"), json.optString("app_guide"))
        }
    }
}
