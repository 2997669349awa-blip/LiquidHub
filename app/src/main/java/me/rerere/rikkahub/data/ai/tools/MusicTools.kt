// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// AI tools for music: search NetEase Cloud Music and start playback via the media session.

package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
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
import me.rerere.rikkahub.data.music.MusicControllerHolder
import me.rerere.rikkahub.data.music.MusicSong
import me.rerere.rikkahub.data.music.NeteaseApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

private object MusicToolKoin : KoinComponent {
    val context: android.content.Context get() = get()
}

fun createMusicTools(): List<Tool> = listOf(
    Tool(
        name = "music_search",
        description = "Search songs on NetEase Cloud Music (网易云音乐). " +
            "Use this whenever the user wants to find, listen to, or talk about music. " +
            "IMPORTANT: always search with rich keywords, never a bare title. " +
            "Put the song title in `keyword` and, when you know it, the artist name in `artist` " +
            "(e.g. keyword=\"晴天\", artist=\"周杰伦\"; or keyword=\"人类的山寨品 原版\"). " +
            "If the user only gave a title, still add any distinctive word you know (artist, version, album). " +
            "Results are ranked by relevance to the keywords; the best match is first. " +
            "If the expected song is missing or the results look unrelated, retry with different/more keywords. " +
            "Returns matching songs with id, name, artist and album. To play a result, call music_play with its id.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("keyword", buildJsonObject {
                        put("type", "string")
                        put("description", "Song title plus any extra keywords (version, album, etc.)")
                    })
                    put("artist", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional artist name to narrow the search. Include it whenever known.")
                    })
                },
                required = listOf("keyword")
            )
        },
        execute = {
            val keyword = it.jsonObject["keyword"]?.jsonPrimitive?.contentOrNull
                ?: error("keyword is required")
            val artist = it.jsonObject["artist"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val query = listOf(keyword.trim(), artist).filter { it.isNotBlank() }.joinToString(" ")
            val songs = withContext(Dispatchers.IO) {
                runCatching { NeteaseApi.search(query) }.getOrDefault(emptyList())
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
                        put("query", query)
                        put("count", songs.size)
                        put("songs", arr)
                        put(
                            "note",
                            "Results are ranked by relevance. Pick the song whose title and artist match the request; " +
                                "if none match, call music_search again with different keywords. " +
                                "Then call music_play with the chosen id."
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
                    put("cover", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional album cover URL for display")
                    })
                },
                required = listOf("id")
            )
        },
        execute = {
            val id = it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
            val name = it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Song" }
            val artist = it.jsonObject["artist"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val cover = it.jsonObject["cover"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val song = MusicSong(id = id, name = name, artist = artist, album = "", cover = cover)
            val controller = runCatching {
                MusicControllerHolder.playQueue(MusicToolKoin.context, listOf(song), 0)
            }.getOrNull()
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", controller != null)
                        if (controller == null) {
                            put("error", "Could not connect to the music player session")
                        } else {
                            put("playing", "$name - $artist".trim().trimStart('-').trim())
                        }
                    }.toString()
                )
            )
        }
    ),
)
