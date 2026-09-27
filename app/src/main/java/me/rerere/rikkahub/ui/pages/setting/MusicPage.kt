// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 音乐页：网易云搜索 + 播放（登录/VIP 需要额外服务，后续版本补）。

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private data class MusicItem(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
)

private val json = Json { ignoreUnknownKeys = true }

@Composable
fun MusicPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MusicItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nowPlaying by remember { mutableStateOf<String?>(null) }

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    fun search() {
        if (query.isBlank()) return
        loading = true
        error = null
        scope.launch {
            val list = runCatching { withContext(Dispatchers.IO) { neteaseSearch(query) } }.getOrNull()
            loading = false
            if (list == null) {
                error = "搜索失败（网络或被限制）"
            } else {
                results = list
            }
        }
    }

    fun play(item: MusicItem) {
        val url = "https://music.163.com/song/media/outer/url?id=${item.id}.mp3"
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
        nowPlaying = "${item.name} - ${item.artist}"
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("音乐（网易云）") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("搜索歌曲/歌手") },
                )
                Button(onClick = { search() }) { Text("搜索") }
            }
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
            nowPlaying?.let {
                Text("正在播放：$it", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp))
            }
            Text(
                text = "说明：登录 / VIP / 喜欢 / 歌词需要网易云账号接口（后续版本接入）。当前可直接搜索并试听可播放的歌曲。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(results, key = { it.id }) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { play(item) }
                            .padding(8.dp),
                    ) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${item.artist} · ${item.album}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun neteaseSearch(keyword: String): List<MusicItem> {
    val url = URL("https://music.163.com/api/search/get/web?s=${URLEncoder.encode(keyword, "UTF-8")}&type=1&limit=20&offset=0")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LiquidHub/0.1")
        setRequestProperty("Referer", "https://music.163.com/")
    }
    try {
        if (conn.responseCode !in 200..299) return emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val songs = json.parseToJsonElement(body).jsonObject["result"]?.jsonObject?.get("songs")?.jsonArray ?: return emptyList()
        return songs.mapNotNull { s ->
            val o = s.jsonObject
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.content ?: ""
            val artist = o["artists"]?.jsonArray?.joinToString("/") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }.orEmpty()
            val album = o["album"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
            MusicItem(id, name, artist, album)
        }
    } finally {
        conn.disconnect()
    }
}
