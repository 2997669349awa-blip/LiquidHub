// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// AI tools for music: search NetEase Cloud Music and start playback via the media session.

package me.rerere.rikkahub.data.ai.tools

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.music.NeteaseApi
import me.rerere.rikkahub.service.MusicPlaybackService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.coroutines.resume

private object MusicToolKoin : KoinComponent {
    val context: Context get() = get()
}

fun createMusicTools(): List<Tool> = listOf(
    Tool(
        name = "music_search",
        description = "Search songs on NetEase Cloud Music (网易云音乐). " +
            "Use this whenever the user wants to find, listen to, or talk about music. " +
            "Return matching songs with id, name, artist and album. " +
            "Prefer this over browsing the web. To play a result, call music_play with its id.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("keyword", buildJsonObject {
                        put("type", "string")
                        put("description", "Song title, artist, or keywords to search for")
                    })
                },
                required = listOf("keyword")
            )
        },
        execute = {
            val keyword = it.jsonObject["keyword"]?.jsonPrimitive?.contentOrNull
                ?: error("keyword is required")
            val songs = withContext(Dispatchers.IO) {
                runCatching { NeteaseApi.search(keyword) }.getOrDefault(emptyList())
            }
            val arr = buildJsonArray {
                songs.forEach { s ->
                    add(buildJsonObject {
                        put("id", s.id)
                        put("name", s.name)
                        put("artist", s.artist)
                        put("album", s.album)
                    })
                }
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("count", songs.size)
                        put("songs", arr)
                        put(
                            "note",
                            "Call music_play with a song id to start playback on the device."
                        )
                    }.toString()
                )
            )
        }
    ),
    Tool(
        name = "music_play",
        description = "Play a NetEase Cloud Music song by its id (from music_search) on the device. " +
            "Playback appears in the notification bar with media controls.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Song id returned by music_search")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional song title for display")
                    })
                    put("artist", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional artist for display")
                    })
                },
                required = listOf("id")
            )
        },
        execute = {
            val id = it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
            val name = it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val artist = it.jsonObject["artist"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val context = MusicToolKoin.context
            val url = withContext(Dispatchers.IO) {
                runCatching { NeteaseApi.songUrl(id) }.getOrNull()
            } ?: "https://music.163.com/song/media/outer/url?id=$id.mp3"
            val controller = awaitController(context)
            if (controller == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", false)
                            put("error", "Could not connect to the music player session")
                        }.toString()
                    )
                )
            }
            runCatching {
                controller.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaId(id)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(name.ifBlank { "Song" })
                                .setArtist(artist)
                                .build()
                        )
                        .build()
                )
                controller.prepare()
                controller.play()
            }
            runCatching { controller.release() }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("playing", "$name - $artist".trim().trimStart('-').trim())
                    }.toString()
                )
            )
        }
    ),
)

private suspend fun awaitController(context: Context): MediaController? =
    suspendCancellableCoroutine { cont ->
        runCatching {
            val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({
                runCatching { cont.resume(future.get()) }.onFailure { cont.resume(null) }
            }, MoreExecutors.directExecutor())
            cont.invokeOnCancellation { runCatching { MediaController.releaseFuture(future) } }
        }.onFailure { cont.resume(null) }
    }
