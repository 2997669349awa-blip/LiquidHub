// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 共享的 MediaController：音乐页与 AI 工具（music_play）共用同一个播放会话。
// 队列先用可预测的 outer URL 占位，再在切歌时解析出真实播放地址替换，保证能播放。

package me.rerere.rikkahub.data.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.MusicPlaybackService
import kotlin.coroutines.resume

object MusicControllerHolder {
    @Volatile
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var resolverAttached = false

    val current: MediaController? get() = controller

    fun placeholderUrl(id: String) = "https://music.163.com/song/media/outer/url?id=$id.mp3"

    suspend fun ensure(context: Context): MediaController? = withContext(Dispatchers.Main) {
        controller?.let { return@withContext it }
        val app = context.applicationContext
        val c = suspendCancellableCoroutine<MediaController?> { cont ->
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
        if (c != null) attachResolver(c)
        c
    }

    private fun mediaMetadata(song: MusicSong): MediaMetadata = MediaMetadata.Builder()
        .setTitle(song.name)
        .setArtist(song.artist)
        .setAlbumTitle(song.album)
        .apply { if (song.cover.isNotBlank()) setArtworkUri(Uri.parse(song.cover)) }
        .build()

    fun toMediaItem(song: MusicSong): MediaItem = MediaItem.Builder()
        .setUri(placeholderUrl(song.id))
        .setMediaId(song.id)
        .setMediaMetadata(mediaMetadata(song))
        .build()

    private fun replaceItemWithResolved(c: MediaController, id: String, url: String, metadata: MediaMetadata?) {
        val idx = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == id } ?: return
        val existing = c.getMediaItemAt(idx).localConfiguration?.uri?.toString().orEmpty()
        if (existing == url) return
        val builder = MediaItem.Builder()
            .setUri(url)
            .setMediaId(id)
        if (metadata != null) builder.setMediaMetadata(metadata)
        c.replaceMediaItem(idx, builder.build())
        if (idx == c.currentMediaItemIndex) c.prepare()
    }

    private fun attachResolver(c: MediaController) {
        if (resolverAttached) return
        resolverAttached = true
        c.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                resolve(c, mediaItem)
            }
        })
    }

    /** 把占位 outer URL 解析成真实可播放地址。 */
    fun resolve(c: MediaController, item: MediaItem?) {
        val id = item?.mediaId ?: return
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        if (!uri.contains("/song/media/outer/")) return
        scope.launch {
            val resolved = runCatching { MusicSources.songUrl(id) }.getOrNull()
            if (resolved.isNullOrBlank()) return@launch
            withContext(Dispatchers.Main) {
                runCatching { replaceItemWithResolved(c, id, resolved, item.mediaMetadata) }
            }
        }
    }

    /** 用真实播放地址构造 MediaItem。 */
    private fun resolvedItem(song: MusicSong, url: String): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setMediaId(song.id)
        .setMediaMetadata(mediaMetadata(song))
        .build()

    suspend fun playQueue(context: Context, songs: List<MusicSong>, index: Int): MediaController? =
        withContext(Dispatchers.Main) {
            val c = ensure(context) ?: return@withContext null
            val clicked = songs.getOrNull(index)
            val resolvedUrl = clicked?.let {
                withContext(Dispatchers.IO) { runCatching { MusicSources.songUrl(it.id) }.getOrNull() }
            }
            val items = songs.mapIndexed { i, s ->
                if (i == index && !resolvedUrl.isNullOrBlank()) resolvedItem(s, resolvedUrl) else toMediaItem(s)
            }
            c.setMediaItems(items, index, 0)
            c.prepare()
            c.play()
            resolve(c, c.currentMediaItem)
            c
        }
}
