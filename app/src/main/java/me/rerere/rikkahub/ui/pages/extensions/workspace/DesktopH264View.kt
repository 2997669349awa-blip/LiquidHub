// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 用 ExoPlayer（Media3）硬解播放 rootfs 里 ffmpeg 输出的 H.264(MPEG-TS) 桌面流。

package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.view.ViewGroup

@OptIn(UnstableApi::class)
@Composable
fun DesktopH264View(
    url: String,
    modifier: Modifier = Modifier,
    onState: (String) -> Unit = {},
    onFps: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val player = remember {
        // 低延迟缓冲，缩短首帧/重连时间
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(800, 2000, 400, 800)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(Unit) {
        var frames = 0
        var lastAt = System.currentTimeMillis()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                onState(
                    when (state) {
                        Player.STATE_READY -> "H.264 已就绪"
                        Player.STATE_BUFFERING -> "H.264 缓冲中…"
                        Player.STATE_ENDED -> "H.264 结束"
                        else -> "H.264 连接中…"
                    }
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                onState("H.264 错误：${error.message ?: error.errorCodeName}")
            }
        }
        player.addListener(listener)
        player.setVideoFrameMetadataListener { _, _, _, _ ->
            frames++
            val now = System.currentTimeMillis()
            if (now - lastAt >= 1000) {
                onFps(frames)
                frames = 0
                lastAt = now
            }
        }
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                this.player = player
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
    )
}
