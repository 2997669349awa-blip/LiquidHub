// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 共享的 MediaController：音乐页与 AI 工具（music_play）共用同一个播放会话。

package me.rerere.rikkahub.data.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.MusicPlaybackService
import kotlin.coroutines.resume

object MusicControllerHolder {
    @Volatile
    private var controller: MediaController? = null

    val current: MediaController? get() = controller

    suspend fun ensure(context: Context): MediaController? = withContext(Dispatchers.Main) {
        controller?.let { return@withContext it }
        val app = context.applicationContext
        suspendCancellableCoroutine<MediaController?> { cont ->
            runCatching {
                val token = SessionToken(app, ComponentName(app, MusicPlaybackService::class.java))
                val future = MediaController.Builder(app, token).buildAsync()
                future.addListener({
                    val result = runCatching { future.get() }.getOrNull()
                    controller = result
                    runCatching { cont.resume(result) }
                }, MoreExecutors.directExecutor())
                cont.invokeOnCancellation { runCatching { MediaController.releaseFuture(future) } }
            }.onFailure { cont.resume(null) }
        }
    }

    fun toMediaItem(song: MusicSong): MediaItem {
        val url = "https://music.163.com/song/media/outer/url?id=${song.id}.mp3"
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.name)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .apply { if (song.cover.isNotBlank()) setArtworkUri(Uri.parse(song.cover)) }
                    .build()
            )
            .build()
    }

    suspend fun playQueue(context: Context, songs: List<MusicSong>, index: Int): MediaController? =
        withContext(Dispatchers.Main) {
            val c = ensure(context) ?: return@withContext null
            c.setMediaItems(songs.map { toMediaItem(it) }, index, 0)
            c.prepare()
            c.play()
            c
        }
}
