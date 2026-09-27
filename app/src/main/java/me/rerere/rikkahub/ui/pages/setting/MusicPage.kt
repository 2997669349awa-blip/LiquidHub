// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 音乐页：接入网易云逆向 Web 接口（扫码/手机号登录、搜索、完整播放、歌词、喜欢、VIP）。

package me.rerere.rikkahub.ui.pages.setting

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.music.MusicSong
import me.rerere.rikkahub.data.music.MusicUser
import me.rerere.rikkahub.data.music.NeteaseApi
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun MusicPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val cookieFile = remember { File(context.filesDir, "music_cookie.txt") }

    var user by remember { mutableStateOf<MusicUser?>(null) }
    var query by remember { mutableStateOf("") }
    var songs by remember { mutableStateOf<List<MusicSong>>(emptyList()) }
    var likes by remember { mutableStateOf<List<MusicSong>>(emptyList()) }
    var tab by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nowPlaying by remember { mutableStateOf<String?>(null) }
    var lyrics by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // 读取已保存的登录 Cookie
    LaunchedEffect(Unit) {
        runCatching {
            if (cookieFile.exists()) NeteaseApi.cookie = cookieFile.readText().trim()
        }
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
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.play()
            nowPlaying = "${item.name} - ${item.artist}"
            lyrics = null
            val lrc = withContext(Dispatchers.IO) { runCatching { NeteaseApi.lyrics(item.id) }.getOrNull() }
            lyrics = lrc?.takeIf { it.isNotBlank() }
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
            val u = withContext(Dispatchers.IO) { runCatching { NeteaseApi.account() }.getOrNull() }
            user = u
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
                        TextButton(onClick = { showQr = true }) { Text("扫码登录") }
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
            nowPlaying?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("正在播放：$it", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    lyrics?.let { lrc -> TextButton(onClick = { lyrics = lrc }) { Text("歌词") } }
                }
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

    if (showQr) {
        QrLoginDialog(
            onDismiss = { showQr = false },
            onLoggedIn = { showQr = false; afterLogin() },
        )
    }
    if (showPhone) {
        PhoneLoginDialog(
            onDismiss = { showPhone = false },
            onLoggedIn = { showPhone = false; afterLogin() },
        )
    }
    lyrics?.let { lrc ->
        AlertDialog(
            onDismissRequest = { lyrics = null },
            title = { Text("歌词") },
            text = {
                Text(
                    text = lrc,
                    modifier = Modifier.height(360.dp),
                )
            },
            confirmButton = { TextButton(onClick = { lyrics = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun QrLoginDialog(onDismiss: () -> Unit, onLoggedIn: () -> Unit) {
    var unikey by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }

    LaunchedEffect(Unit) {
        val key = withContext(Dispatchers.IO) { runCatching { NeteaseApi.qrCreate() }.getOrNull() }
        unikey = key
        if (key == null) {
            status = "获取二维码失败"
            return@LaunchedEffect
        }
        qrBitmap = withContext(Dispatchers.IO) { runCatching { fetchBitmap("https://music.163.com/login?codekey=$key") }.getOrNull() }
        status = "请用网易云 APP 扫码"
        repeat(120) {
            delay(2000)
            val code = withContext(Dispatchers.IO) { runCatching { NeteaseApi.qrPoll(key) }.getOrDefault(-1) }
            when (code) {
                800 -> status = "二维码已过期，请关闭重试"
                801 -> status = "已扫码，等待确认"
                802 -> status = "已取消"
                803 -> {
                    status = "登录成功"
                    onLoggedIn()
                    return@LaunchedEffect
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码登录") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                qrBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "二维码", modifier = Modifier.size(220.dp)) }
                Text(status, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
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
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
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

private fun fetchBitmap(url: String): android.graphics.Bitmap? {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LiquidHub/0.1")
        setRequestProperty("Referer", "https://music.163.com/")
    }
    return try {
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    } finally {
        conn.disconnect()
    }
}
