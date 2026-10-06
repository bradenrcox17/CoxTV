package com.coxtv.data.remote

import android.util.JsonReader
import android.util.JsonToken
import android.util.MalformedJsonException
import com.coxtv.data.db.ChannelEntity
import java.io.InputStreamReader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class XtreamClient(
    private val http: OkHttpClient,
    server: String,
    private val user: String,
    private val pass: String,
) {
    private val base: HttpUrl = try {
        normalizeServer(server).toHttpUrl()
    } catch (e: IllegalArgumentException) {
        throw IOException("Invalid server URL: $server")
    }

    private fun api(action: String? = null): HttpUrl = base.newBuilder()
        .addPathSegment("player_api.php")
        .addQueryParameter("username", user)
        .addQueryParameter("password", pass)
        .apply { if (action != null) addQueryParameter("action", action) }
        .build()

    suspend fun authenticate() {
        val body = http.getString(api())
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("Server did not return an Xtream Codes response")
        }
        val info = json.optJSONObject("user_info") ?: throw IOException("Server did not return account info")
        if (info.optInt("auth", 0) != 1) throw IOException("Login failed: check username and password")
        val status = info.optString("status", "Active")
        if (!status.equals("Active", ignoreCase = true)) throw IOException("Account status: $status")
    }

    suspend fun liveChannels(): List<ChannelEntity> {
        val categories = HashMap<String, String>()
        runCatching { JSONArray(http.getString(api("get_live_categories"))) }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                categories[o.optString("category_id")] = o.optString("category_name").ifBlank { "Uncategorized" }
            }
        }
        // Streamed with JsonReader: big providers return 20,000+ channels (10+ MB of JSON),
        // which would need several times that in memory as a parsed JSONArray.
        val out = ArrayList<ChannelEntity>()
        try {
            http.getStream(api("get_live_streams").toString()) { input ->
                JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                    r.beginArray()
                    while (r.hasNext()) {
                        readStream(r, categories, out.size)?.let { out += it }
                    }
                    r.endArray()
                }
            }
        } catch (e: IllegalStateException) {
            throw IOException("Could not read the live channel list")
        } catch (e: MalformedJsonException) {
            throw IOException("Could not read the live channel list")
        }
        return out
    }

    private fun readStream(r: JsonReader, categories: Map<String, String>, index: Int): ChannelEntity? {
        var streamId: String? = null
        var num: String? = null
        var name: String? = null
        var icon: String? = null
        var epg: String? = null
        var categoryId: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "stream_id" -> streamId = r.nextScalar()
                "num" -> num = r.nextScalar()
                "name" -> name = r.nextScalar()
                "stream_icon" -> icon = r.nextScalar()
                "epg_channel_id" -> epg = r.nextScalar()
                "category_id" -> categoryId = r.nextScalar()
                else -> r.skipValue()
            }
        }
        r.endObject()
        val id = streamId?.takeIf { it.isNotBlank() } ?: return null
        return ChannelEntity(
            id = "x:$id",
            sortOrder = index,
            number = num?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: (index + 1),
            name = name?.trim()?.ifBlank { null } ?: "Channel $id",
            logo = icon?.trim()?.ifBlank { null },
            groupName = categories[categoryId] ?: "Uncategorized",
            streamUrl = streamUrl(id),
            epgIdRaw = epg?.trim()?.ifBlank { null },
            epgId = epg?.trim()?.ifBlank { null },
        )
    }

    /** Next value as a string whether the provider sent a string, number or boolean; null for JSON null. */
    private fun JsonReader.nextScalar(): String? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); null }
    }

    fun streamUrl(streamId: String): String = base.newBuilder()
        .addPathSegment("live")
        .addPathSegment(user)
        .addPathSegment(pass)
        .addPathSegment("$streamId.ts")
        .build()
        .toString()

    fun epgUrl(): String = base.newBuilder()
        .addPathSegment("xmltv.php")
        .addQueryParameter("username", user)
        .addQueryParameter("password", pass)
        .build()
        .toString()

    companion object {
        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            return s.removeSuffix("/player_api.php").removeSuffix("/get.php").trimEnd('/')
        }
    }
}

private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
