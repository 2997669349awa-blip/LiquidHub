// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 音乐页：网易云逆向接口 + MediaSession（通知栏控制）+ 滚动歌词。

package me.rerere.rikkahub.ui.pages.setting

import android.content.ComponentName
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.music.MusicSong
import me.rerere.rikkahub.data.music.MusicUser
import me.rerere.rikkahub.data.music.NeteaseApi
import me.rerere.rikkahub.service.MusicPlaybackService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import java.io.File

private fun buildController(context: Context, onReady: (MediaController?) -> Unit) {
    runCatching {
        val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ onReady(runCatching { future.get() }.getOrNull()) }, MoreExecutors.directExecutor())
    }.onFailure { onReady(null) }
}

@Composable
fun MusicPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val cookieFile = remember { File(context.filesDir, "music_cookie.txt") }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var user by remember { mutableStateOf<MusicUser?>(null) }
    var query by remember { mutableStateOf("") }
    var songs by remember { mutableStateOf<List<MusicSong>>(emptyList()) }
    var likes by remember { mutableStateOf<List<MusicSong>>(emptyList()) }
    var tab by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nowPlaying by remember { mutableStateOf<String?>(null) }
    var lyrics by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    var showWebLogin by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        buildController(context) { controller = it }
        onDispose { runCatching { controller?.release() } }
    }

    LaunchedEffect(Unit) {
        runCatching { if (cookieFile.exists()) NeteaseApi.cookie = cookieFile.readText().trim() }
        if (NeteaseApi.cookie.isNotBlank()) {
            withContext(Dispatchers.IO) { runCatching { NeteaseApi.account() } }.getOrNull()?.let {
                user = it
                withContext(Dispatchers.IO) { runCatching { NeteaseApi.likeList(it.uid) } }.getOrNull()?.let { l -> likes = l }
            }
        }
    }

    fun refreshLikes() {
        val uid = user?.uid ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { NeteaseApi.likeList(uid) } }.getOrNull()?.let { likes = it }
        }
    }

    fun doSearch() {
        if (query.isBlank()) return
        loading = true
        error = null
        scope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { NeteaseApi.search(query) }.getOrNull() }
            loading = false
            if (list == null) error = "搜索失败（网络或被限制）" else songs = list
        }
    }

    fun play(item: MusicSong) {
        scope.launch {
            val url = withContext(Dispatchers.IO) { runCatching { NeteaseApi.songUrl(item.id) }.getOrNull() }
                ?: "https://music.163.com/song/media/outer/url?id=${item.id}.mp3"
            controller?.run {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                play()
            }
            nowPlaying = "${item.name} - ${item.artist}"
            lyrics = null
            val lrc = withContext(Dispatchers.IO) { runCatching { NeteaseApi.lyrics(item.id) }.getOrNull() }
            lyrics = lrc?.takeIf { it.isNotBlank() }?.let { parseLrc(it) }
        }
    }

    fun toggleLike(item: MusicSong) {
        val liked = likes.any { it.id == item.id }
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { NeteaseApi.like(item.id, !liked) } }
            refreshLikes()
        }
    }

    fun afterLogin() {
        runCatching { cookieFile.writeText(NeteaseApi.cookie) }
        scope.launch {
            user = withContext(Dispatchers.IO) { runCatching { NeteaseApi.account() }.getOrNull() }
            refreshLikes()
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("音乐（网易云）") },
                navigationIcon = { BackButton() },
                actions = {
                    if (user == null) {
                        TextButton(onClick = { showWebLogin = true }) { Text("网页登录") }
                        TextButton(onClick = { showPhone = true }) { Text("手机号") }
                    } else {
                        Text(
                            (if (user!!.vip) "VIP · " else "") + user!!.nickname,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        TextButton(onClick = {
                            NeteaseApi.cookie = ""
                            cookieFile.delete()
                            user = null
                            likes = emptyList()
                        }) { Text("退出") }
                    }
                },
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
                Button(onClick = { doSearch() }) { Text("搜索") }
            }
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
            if (nowPlaying != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("正在播放：$nowPlaying", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (lyrics != null) {
                        TextButton(onClick = { }) { Text("") }
                    }
                }
                LyricsBar(controller = controller, lyrics = lyrics)
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("搜索") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("喜欢") })
            }
            val list = if (tab == 0) songs else likes
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(list, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { play(item) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${item.artist} · ${item.album}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { toggleLike(item) }) {
                            Text(if (likes.any { it.id == item.id }) "♥" else "♡")
                        }
                    }
                }
            }
        }
    }

    if (showWebLogin) {
        WebLoginDialog(onDismiss = { showWebLogin = false }, onLoggedIn = { showWebLogin = false; afterLogin() })
    }
    if (showPhone) {
        PhoneLoginDialog(onDismiss = { showPhone = false }, onLoggedIn = { showPhone = false; afterLogin() })
    }
}

/** 滚动歌词：高亮当前行并自动滚动。 */
@Composable
private fun LyricsBar(controller: MediaController?, lyrics: List<Pair<Long, String>>?) {
    if (lyrics.isNullOrEmpty()) return
    var position by remember { mutableStateOf(0L) }
    LaunchedEffect(controller, lyrics) {
        while (true) {
            position = controller?.currentPosition ?: 0L
            delay(300)
        }
    }
    val currentIndex = remember(position, lyrics) {
        lyrics.indexOfLast { it.first <= position }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        runCatching { listState.animateScrollToItem(currentIndex.coerceIn(0, lyrics.lastIndex)) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 12.dp),
    ) {
        items(lyrics.indices.toList(), key = { it }) { i ->
            val active = i == currentIndex
            Text(
                text = lyrics[i].second,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

/** 解析 LRC：返回 (毫秒, 文本) 列表。 */
private fun parseLrc(lrc: String): List<Pair<Long, String>> {
    val out = mutableListOf<Pair<Long, String>>()
    val tag = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    lrc.lineSequence().forEach { line ->
        tag.findAll(line).forEach { m ->
            val min = m.groupValues[1].toLongOrNull() ?: return@forEach
            val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
            val fracStr = m.groupValues[3]
            val fracMs = when (fracStr.length) {
                0 -> 0L
                1 -> (fracStr.toLongOrNull() ?: 0) * 100
                2 -> (fracStr.toLongOrNull() ?: 0) * 10
                else -> fracStr.toLongOrNull() ?: 0
            }
            val text = line.substringAfterLast(']').trim()
            if (text.isNotEmpty()) out += (min * 60_000 + sec * 1000 + fracMs) to text
        }
    }
    return out.sortedBy { it.first }
}

@Composable
private fun WebLoginDialog(onDismiss: () -> Unit, onLoggedIn: () -> Unit) {
    var status by remember { mutableStateOf("在下方网页登录（扫码/账号），完成后点“我已登录，抓取”") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网易云网页登录") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl("https://music.163.com/#/login")
                        }
                    },
                )
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cookie = CookieManager.getInstance().getCookie("https://music.163.com").orEmpty()
                if (cookie.contains("MUSIC_U=")) {
                    NeteaseApi.cookie = cookie
                    runCatching { CookieManager.getInstance().flush() }
                    onLoggedIn()
                } else {
                    status = "还没检测到登录，请先在网页里完成登录"
                }
            }) { Text("我已登录，抓取") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PhoneLoginDialog(onDismiss: () -> Unit, onLoggedIn: () -> Unit) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手机号登录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = phone, onValueChange = { phone = it }, singleLine = true, label = { Text("手机号") })
                OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true, label = { Text("密码") })
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { NeteaseApi.cellphoneLogin(phone, password) }.getOrDefault(false) }
                    if (ok) onLoggedIn() else message = "登录失败（可能需验证码/密码错误）"
                }
            }) { Text("登录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
