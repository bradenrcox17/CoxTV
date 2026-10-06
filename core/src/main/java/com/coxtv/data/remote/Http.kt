package com.coxtv.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object Http {
    const val USER_AGENT = "CoxTV/1.0 (Linux; Android) ExoPlayer"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }
}

suspend fun OkHttpClient.getString(url: HttpUrl): String = withContext(Dispatchers.IO) {
    newCall(Request.Builder().url(url).build()).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from ${url.host}")
        resp.body.string()
    }
}

suspend fun <T> OkHttpClient.getStream(url: String, block: (InputStream) -> T): T = withContext(Dispatchers.IO) {
    newCall(Request.Builder().url(url).build()).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} loading $url")
        resp.body.byteStream().use(block)
    }
}

suspend fun OkHttpClient.download(url: String, target: File): File =
    getStream(url) { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) }; target }

/** Opens a file, transparently gunzipping it if it starts with the gzip magic bytes. */
fun openMaybeGzip(file: File): InputStream {
    val buffered = BufferedInputStream(file.inputStream(), 64 * 1024)
    buffered.mark(2)
    val b1 = buffered.read()
    val b2 = buffered.read()
    buffered.reset()
    return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024) else buffered
}
