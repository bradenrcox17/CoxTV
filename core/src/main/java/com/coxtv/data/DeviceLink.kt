package com.coxtv.data

import android.util.Log
import com.coxtv.data.db.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * The link a setup code makes between this app and the CoxOnAir stream server:
 *  - channels play through the server, so TVs watching the same channel share one provider
 *    connection (with each other and with tv.thecoxhome.com);
 *  - TVs take commands (play a channel, channel up/down, previous, stop) sent from the phone
 *    app or the web page;
 *  - the phone app lists the TVs and sends them commands.
 */
class DeviceLink(private val settings: SettingsStore, http: OkHttpClient) {
    /** Long polls hold the request ~25 s, so they need a longer read timeout than the default. */
    private val http = http.newBuilder().readTimeout(40, TimeUnit.SECONDS).build()

    data class Command(val n: Long, val cmd: String, val channelId: String, val channelName: String, val from: String)
    data class Tv(val id: String, val name: String, val kind: String, val online: Boolean, val playing: String)

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    /** Commands for this TV (only while [listen] runs). */
    val commands: SharedFlow<Command> = _commands

    /** What this TV is playing, reported to the remote (channel name, server channel id). */
    @Volatile var playing: Pair<String, String>? = null

    suspend fun token(): String? = settings.deviceToken().takeIf { it.isNotBlank() }

    /** Stream URL through the server, or null to play the provider link directly. */
    suspend fun streamUrl(channel: Channel): String? {
        val token = token() ?: return null
        if (!settings.useServer.first()) return null
        val id = serverId(channel.streamUrl) ?: return null
        return "${SetupCodes.SERVER}/d/$token/live/$id.m3u8"
    }

    /** Tells the server this device stopped watching, so the channel's connection frees up sooner. */
    suspend fun leave() {
        val token = token() ?: return
        playing = null
        runCatching { post("/d/$token/leave", "{}") }
    }

    /** Long-polls for commands until cancelled (run it while the app is in the foreground). */
    suspend fun listen() {
        var since = 0L
        var failures = 0
        while (coroutineContext.isActive) {
            val token = token()
            if (token == null) {
                delay(30_000)
                continue
            }
            try {
                val now = playing
                val url = "${SetupCodes.SERVER}/d/$token/wait".toHttpUrl().newBuilder()
                    .addQueryParameter("since", since.toString())
                    .addQueryParameter("ch", now?.first.orEmpty())
                    .addQueryParameter("id", now?.second.orEmpty())
                    .build()
                val body = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (resp.code == 401) throw Unlinked()
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        resp.body.string()
                    }
                }
                failures = 0
                val json = JSONObject(body)
                val list = json.optJSONArray("commands") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val c = list.getJSONObject(i)
                    val n = c.optLong("n")
                    if (n <= since) continue
                    since = n
                    _commands.emit(Command(n, c.optString("cmd"), c.optString("id"), c.optString("name"), c.optString("from")))
                }
                json.optString("name").takeIf { it.isNotBlank() }?.let { settings.setDeviceName(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Unlinked) {
                Log.i("CoxTV", "This device was unlinked on tv.thecoxhome.com")
                settings.setDeviceLink("", "")
            } catch (e: Exception) {
                failures++
                delay((5_000L * failures).coerceAtMost(60_000))
            }
        }
    }

    private class Unlinked : IOException("unlinked")

    /** TVs that can be controlled (phone remote). */
    suspend fun tvs(): List<Tv> {
        val token = token() ?: return emptyList()
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("${SetupCodes.SERVER}/d/$token/devices").build()).execute().use { resp ->
                if (resp.code == 401) throw IOException("This phone was unlinked. Set it up again with a code from tv.thecoxhome.com.")
                if (!resp.isSuccessful) throw IOException("The stream server answered HTTP ${resp.code}.")
                resp.body.string()
            }
        }
        val list = JSONArray(body)
        return List(list.length()) { i ->
            val d = list.getJSONObject(i)
            Tv(d.optString("id"), d.optString("name"), d.optString("kind"), d.optBoolean("online"), d.optString("playing"))
        }
    }

    /** Sends a command ("play" with a channel, "up", "down", "prev", "stop") to a TV. */
    suspend fun send(tv: Tv, cmd: String, channel: Channel? = null) {
        val token = token() ?: throw IOException("Not linked. Set up with a code from tv.thecoxhome.com.")
        val body = JSONObject().put("to", tv.id).put("cmd", cmd)
        if (channel != null) {
            body.put("id", serverId(channel.streamUrl) ?: throw IOException("This channel can't be sent to a TV."))
        }
        post("/d/$token/send", body.toString())
    }

    private suspend fun post(path: String, json: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(SetupCodes.SERVER + path)
            .post(json.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(resp.body.string()).optString("message") }.getOrNull()
                throw IOException(msg?.takeIf { it.isNotBlank() } ?: "The stream server answered HTTP ${resp.code}.")
            }
        }
    }

    companion object {
        private val ID_RE = Regex("""/(\d+)(?:\.[A-Za-z0-9]+)?$""")

        /** The stream server's id for a channel: the provider stream number at the end of its link. */
        fun serverId(streamUrl: String): String? =
            runCatching { streamUrl.toHttpUrl().encodedPath }.getOrNull()?.let { ID_RE.find(it)?.groupValues?.get(1) }
    }
}
