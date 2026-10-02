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
import me.rerere.rikkahub.data.music.MusicSession
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
            val songs = searchSongs(query, keyword.trim())
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
        description = "Play a song on NetEase Cloud Music (网易云音乐) on the device. " +
            "TWO ways to use it: " +
            "(1) ONE-SHOT (preferred, saves steps): pass `keyword` — and `artist` if you know it — " +
            "and the best-matching song plays immediately; no separate music_search call needed. " +
            "(2) pass an `id` returned by music_search. " +
            "Use the one-shot form whenever you already know the title (and artist). " +
            "Playback appears in the notification bar with media controls.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("keyword", buildJsonObject {
                        put("type", "string")
                        put("description", "Song title (+ extra keywords). Use this to play directly without searching first.")
                    })
                    put("artist", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional artist name; include it whenever known for a better match.")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Song id returned by music_search (alternative to keyword)")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional song title for display")
                    })
                    put("cover", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional album cover URL for display")
                    })
                },
                required = emptyList()
            )
        },
        execute = {
            val keyword = it.jsonObject["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val artistArg = it.jsonObject["artist"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            var id = it.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            var name = it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            var artist = artistArg
            var cover = it.jsonObject["cover"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val query = listOf(keyword, artistArg).filter { it.isNotBlank() }.joinToString(" ")

            // 只给了关键词：先搜一次，取最匹配的那首直接播放（一步到位）
            val autoTop = if (id.isBlank() && keyword.isNotBlank()) {
                searchSongs(query, keyword).firstOrNull()
            } else {
                null
            }
            val song: MusicSong? = if (id.isBlank() && autoTop == null) {
                null
            } else {
                if (id.isBlank()) {
                    val top = autoTop!!
                    id = top.id
                    if (name.isBlank()) name = top.name
                    if (artist.isBlank()) artist = top.artist
                    if (cover.isBlank()) cover = top.cover
                }
                MusicSong(
                    id = id,
                    name = name.ifBlank { "Song" },
                    artist = artist,
                    album = "",
                    cover = cover,
                )
            }
            val payload = if (song == null) {
                buildJsonObject {
                    put("ok", false)
                    put("error", "找不到匹配的歌曲，请换个关键词重试")
                    if (query.isNotBlank()) put("query", query)
                }
            } else {
                val controller = runCatching {
                    MusicControllerHolder.playQueue(MusicToolKoin.context, listOf(song), 0)
                }.getOrNull()
                buildJsonObject {
                    put("ok", controller != null)
                    if (controller == null) {
                        put("error", "Could not connect to the music player session")
                    } else {
                        put("playing", "${song.name} - ${song.artist}".trim().trimStart('-').trim())
                    }
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
)

/**
 * 依次尝试多个关键词，返回第一个有结果列表的搜索结果。
 * 会在搜索前确保网易云登录 Cookie 已加载，让 AI 搜索与手动搜索走同一账号身份。
 */
private suspend fun searchSongs(vararg queries: String): List<MusicSong> {
    MusicSession.ensure(MusicToolKoin.context)
    val tries = queries.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    for (q in tries) {
        val list = withContext(Dispatchers.IO) {
            runCatching { NeteaseApi.search(q) }.getOrDefault(emptyList())
        }
        if (list.isNotEmpty()) return list
    }
    return emptyList()
}
