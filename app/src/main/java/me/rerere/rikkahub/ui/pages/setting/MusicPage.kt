// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 音乐页：网易云逆向接口 + MediaSession（通知栏控制）+ 全屏播放器 + 滚动歌词。

package me.rerere.rikkahub.ui.pages.setting

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.music.MusicControllerHolder
import me.rerere.rikkahub.data.music.MusicSong
import me.rerere.rikkahub.data.music.MusicUser
import me.rerere.rikkahub.data.music.NeteaseApi
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import java.io.File

private val BG_PRESETS = listOf(
    0xFF101014, 0xFF14202B, 0xFF1B1230, 0xFF12211A, 0xFF2A1416, 0xFF202020, 0xFF000000,
).map { Color(it) }

private data class PlayerSnapshot(
    val mediaId: String? = null,
    val position: Long = 0,
    val duration: Long = 0,
    val playing: Boolean = false,
    val hasItem: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
)

@Composable
private fun rememberPlayerSnapshot(controller: MediaController?): PlayerSnapshot {
    var snap by remember { mutableStateOf(PlayerSnapshot()) }
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            snap = PlayerSnapshot(
                mediaId = c.currentMediaItem?.mediaId,
                position = c.currentPosition.coerceAtLeast(0),
                duration = c.duration.coerceAtLeast(0),
                playing = c.isPlaying,
                hasItem = c.currentMediaItem != null,
                repeatMode = c.repeatMode,
                shuffle = c.shuffleModeEnabled,
            )
            delay(500)
        }
    }
    return snap
}

private fun modeLabel(snap: PlayerSnapshot): String = when {
    snap.shuffle -> "随机"
    snap.repeatMode == Player.REPEAT_MODE_ONE -> "单曲"
    snap.repeatMode == Player.REPEAT_MODE_ALL -> "列表"
    else -> "顺序"
}

private fun cycleMode(controller: MediaController?, snap: PlayerSnapshot) {
    controller ?: return
    when {
        snap.shuffle -> {
            controller.shuffleModeEnabled = false
            controller.repeatMode = Player.REPEAT_MODE_ONE
        }
        snap.repeatMode == Player.REPEAT_MODE_ONE -> {
            controller.shuffleModeEnabled = false
            controller.repeatMode = Player.REPEAT_MODE_OFF
        }
        else -> {
            controller.shuffleModeEnabled = true
            controller.repeatMode = Player.REPEAT_MODE_ALL
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
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
    var showPlayer by remember { mutableStateOf(false) }
    var showWebLogin by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        controller = MusicControllerHolder.ensure(context)
        runCatching { if (cookieFile.exists()) NeteaseApi.cookie = cookieFile.readText().trim() }
        if (NeteaseApi.cookie.isNotBlank()) {
            withContext(Dispatchers.IO) { runCatching { NeteaseApi.account() } }.getOrNull()?.let {
                user = it
                withContext(Dispatchers.IO) { runCatching { NeteaseApi.likeList(it.uid) } }.getOrNull()?.let { l -> likes = l }
            }
        }
    }

    val snap = rememberPlayerSnapshot(controller)
    val meta = controller?.currentMediaItem?.mediaMetadata
    val playingCover = meta?.artworkUri?.toString().orEmpty()

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

    fun play(list: List<MusicSong>, index: Int) {
        showPlayer = true
        scope.launch { controller = MusicControllerHolder.playQueue(context, list, index) }
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
                    val u = user
                    if (u == null) {
                        TextButton(onClick = { showWebLogin = true }) { Text("网页登录") }
                        TextButton(onClick = { showPhone = true }) { Text("手机号") }
                    } else {
                        Text(
                            (if (u.vip) "VIP · " else "") + u.nickname,
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
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("搜索") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("喜欢") })
            }
            val list = if (tab == 0) songs else likes
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(list, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { play(list, list.indexOf(item)) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                if (item.cover.isNotBlank()) {
                                    AsyncImage(model = item.cover, contentDescription = null, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Spacer(Modifier.size(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${item.artist} · ${item.album}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { toggleLike(item) }) {
                                Text(if (likes.any { it.id == item.id }) "♥" else "♡")
                            }
                        }
                    }
                }
            }
            if (snap.hasItem) {
                MiniPlayer(
                    cover = playingCover,
                    title = meta?.title?.toString().orEmpty(),
                    artist = meta?.artist?.toString().orEmpty(),
                    snap = snap,
                    onPlayPause = { if (snap.playing) controller?.pause() else controller?.play() },
                    onNext = { controller?.seekToNextMediaItem() },
                    onOpen = { showPlayer = true },
                )
            }
        }
    }

    if (showPlayer && snap.hasItem) {
        NowPlayingScreen(
            controller = controller,
            snap = snap,
            onClose = { showPlayer = false },
        )
    }

    if (showWebLogin) {
        WebLoginDialog(onDismiss = { showWebLogin = false }, onLoggedIn = { showWebLogin = false; afterLogin() })
    }
    if (showPhone) {
        PhoneLoginDialog(onDismiss = { showPhone = false }, onLoggedIn = { showPhone = false; afterLogin() })
    }
}

@Composable
private fun MiniPlayer(
    cover: String,
    title: String,
    artist: String,
    snap: PlayerSnapshot,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface)) {
            if (cover.isNotBlank()) AsyncImage(model = cover, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title.ifBlank { "正在播放" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onPlayPause) { Text(if (snap.playing) "⏸" else "▶") }
        IconButton(onClick = onNext) { Text("⏭") }
    }
}

@Composable
private fun NowPlayingScreen(
    controller: MediaController?,
    snap: PlayerSnapshot,
    onClose: () -> Unit,
) {
    var bg by remember { mutableStateOf(BG_PRESETS.first()) }
    var lyrics by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    val meta = controller?.currentMediaItem?.mediaMetadata
    val title = meta?.title?.toString().orEmpty().ifBlank { "未知歌曲" }
    val artist = meta?.artist?.toString().orEmpty()
    val cover = meta?.artworkUri?.toString().orEmpty()

    LaunchedEffect(snap.mediaId) {
        val id = snap.mediaId ?: return@LaunchedEffect
        lyrics = null
        val lrc = withContext(Dispatchers.IO) { runCatching { NeteaseApi.lyrics(id) }.getOrNull() }
        lyrics = lrc?.takeIf { it.isNotBlank() }?.let { parseLrc(it) }?.takeIf { it.isNotEmpty() }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(bg).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Text("⌄", color = Color.White) }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BG_PRESETS.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                .clickable { bg = c },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                if (cover.isNotBlank()) {
                    AsyncImage(model = cover, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Text("♪", color = Color.White, style = MaterialTheme.typography.displayLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artist.isNotBlank()) {
                Text(artist, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }

            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (lyrics == null) {
                    Text(
                        "暂无歌词",
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LyricsList(lyrics = lyrics!!, position = snap.position)
                }
            }

            Text(
                "${formatTime(snap.position)}   ${formatTime(snap.duration)}",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(
                value = if (snap.duration > 0) snap.position.toFloat() / snap.duration else 0f,
                onValueChange = { frac -> if (snap.duration > 0) controller?.seekTo((frac * snap.duration).toLong()) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { controller?.seekToPreviousMediaItem() }) { Text("⏮", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
                IconButton(onClick = { if (snap.playing) controller?.pause() else controller?.play() }) {
                    Text(if (snap.playing) "⏸" else "▶", color = Color.White, style = MaterialTheme.typography.displaySmall)
                }
                IconButton(onClick = { controller?.seekToNextMediaItem() }) { Text("⏭", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
            }
            TextButton(onClick = { cycleMode(controller, snap) }) {
                Text("播放模式：${modeLabel(snap)}", color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun LyricsList(lyrics: List<Pair<Long, String>>, position: Long) {
    val currentIndex = remember(position, lyrics) {
        lyrics.indexOfLast { it.first <= position }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        runCatching { listState.animateScrollToItem(currentIndex.coerceIn(0, lyrics.lastIndex), scrollOffset = -120) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 80.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(lyrics.indices.toList(), key = { it }) { i ->
            val active = i == currentIndex
            Text(
                text = lyrics[i].second,
                style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) Color.White else Color.White.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
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
