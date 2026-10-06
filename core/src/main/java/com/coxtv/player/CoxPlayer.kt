@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package com.coxtv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.coxtv.data.db.Channel
import com.coxtv.data.remote.Http

/** ExoPlayer tuned for live IPTV: decoder fallback, short start buffer, provider user agent. */
fun buildLivePlayer(context: Context): ExoPlayer {
    val dataSource = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT)
    val renderers = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
        .build()
    return ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource))
        .setLoadControl(loadControl)
        .build()
        .apply {
            playWhenReady = true
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
}

fun ExoPlayer.playChannel(channel: Channel) {
    val item = MediaItem.Builder()
        .setUri(channel.streamUrl)
        .setMediaId(channel.id)
        .apply { if (channel.streamUrl.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .build()
    setMediaItem(item)
    prepare()
    play()
}
