// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 音乐页：网易云逆向接口 + MediaSession（通知栏控制）+ 全屏播放器 + 滚动歌词。

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Heart
import me.rerere.hugeicons.stroke.MoreHorizontal
import me.rerere.hugeicons.stroke.Next
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Playlist01
import me.rerere.hugeicons.stroke.Previous
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.RepeatOne01
import me.rerere.hugeicons.stroke.Shuffle
import me.rerere.rikkahub.data.music.MusicControllerHolder
import me.rerere.rikkahub.data.music.MusicSong
import me.rerere.rikkahub.data.music.MusicSources
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors

private val BG_PRESETS = listOf(
    0xFF101014, 0xFF14202B, 0xFF1B1230, 0xFF12211A, 0xFF2A1416, 0xFF202020, 0xFF000000,
).map { Color(it) }

/** 一行歌词：原文 + 翻译（无翻译时为空）。 */
private data class LyricLine(
    val time: Long,
    val text: String,
    val translation: String,
)

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

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var query by remember { mutableStateOf("") }
    var songs by remember { mutableStateOf<List<MusicSong>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var likesAvailable by remember { mutableStateOf(false) }
    var likedSongs by remember { mutableStateOf<List<MusicSong>>(emptyList()) }
    var likedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tab by remember { mutableStateOf(0) }

    fun refreshLiked() {
        scope.launch {
            val l = withContext(Dispatchers.IO) { runCatching { MusicSources.liked() }.getOrNull() }.orEmpty()
            likedSongs = l
            likedIds = l.map { it.id }.toSet()
        }
    }

    fun toggleLike(song: MusicSong) {
        val want = song.id !in likedIds
        likedIds = if (want) likedIds + song.id else likedIds - song.id
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { MusicSources.like(song.id, want) }.getOrDefault(false) }
            if (!ok) {
                likedIds = if (want) likedIds - song.id else likedIds + song.id
            } else {
                refreshLiked()
            }
        }
    }

    LaunchedEffect(Unit) {
        controller = MusicControllerHolder.ensure(context)
        likesAvailable = withContext(Dispatchers.IO) { runCatching { MusicSources.supportsLikes() }.getOrDefault(false) }
        if (likesAvailable) refreshLiked()
    }

    val snap = rememberPlayerSnapshot(controller)
    val meta = controller?.currentMediaItem?.mediaMetadata
    val playingCover = meta?.artworkUri?.toString().orEmpty()

    fun doSearch() {
        if (query.isBlank()) return
        loading = true
        error = null
        scope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { MusicSources.search(query) }.getOrNull() }
            loading = false
            songs = list.orEmpty()
        }
    }

    fun play(list: List<MusicSong>, index: Int) {
        showPlayer = true
        scope.launch { controller = MusicControllerHolder.playQueue(context, list, index) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("音乐") },
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
                Button(onClick = { doSearch() }) { Text("搜索") }
            }
            if (likesAvailable) {
                androidx.compose.material3.TabRow(
                    selectedTabIndex = tab,
                    containerColor = Color.Transparent,
                ) {
                    androidx.compose.material3.Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("搜索") })
                    androidx.compose.material3.Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("喜欢") })
                }
            }
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
            val list = if (likesAvailable && tab == 1) likedSongs else songs
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
                            if (likesAvailable) {
                                val liked = item.id in likedIds
                                IconButton(onClick = { toggleLike(item) }) {
                                    Icon(
                                        imageVector = HugeIcons.Heart,
                                        contentDescription = if (liked) "取消喜欢" else "喜欢",
                                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (snap.playing) HugeIcons.Pause else HugeIcons.Play,
                contentDescription = if (snap.playing) "暂停" else "播放",
            )
        }
        IconButton(onClick = onNext) {
            Icon(HugeIcons.Next, contentDescription = "下一首")
        }
    }
}

@Composable
private fun NowPlayingScreen(
    controller: MediaController?,
    snap: PlayerSnapshot,
    onClose: () -> Unit,
) {
    var bg by remember { mutableStateOf(Color(0xFF000000)) }
    var lyrics by remember { mutableStateOf<List<LyricLine>?>(null) }
    var showFullLyrics by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var livePos by remember { mutableStateOf(snap.position) }
    var likeAvailable by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(snap.mediaId) {
        val id = snap.mediaId
        likeAvailable = withContext(Dispatchers.IO) { runCatching { MusicSources.supportsLikes() }.getOrDefault(false) }
        liked = if (likeAvailable && id != null) {
            withContext(Dispatchers.IO) { runCatching { MusicSources.liked() }.getOrDefault(emptyList()) }.any { it.id == id }
        } else false
    }
    LaunchedEffect(snap.mediaId, snap.playing) {
        // 高频取播放位置，让歌词高亮实时跟随（避免 500ms 轮询造成的延迟感）
        while (true) {
            livePos = controller?.currentPosition?.coerceAtLeast(0) ?: snap.position
            delay(80)
        }
    }
    val meta = controller?.currentMediaItem?.mediaMetadata
    val title = meta?.title?.toString().orEmpty().ifBlank { "未知歌曲" }
    val artist = meta?.artist?.toString().orEmpty()
    val cover = meta?.artworkUri?.toString().orEmpty()

    val accent = Color(0xFFFF8A3D)
    val rotation by rememberInfiniteTransition(label = "disc").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(28_000, easing = LinearEasing), RepeatMode.Restart),
        label = "discRotation",
    )

    LaunchedEffect(snap.mediaId) {
        val id = snap.mediaId ?: return@LaunchedEffect
        lyrics = null
        val data = withContext(Dispatchers.IO) { runCatching { MusicSources.lyrics(id) }.getOrNull() }
        lyrics = data?.takeIf { it.lyric.isNotBlank() }
            ?.let { parseLrc(it.lyric, it.translation) }
            ?.takeIf { it.isNotEmpty() }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val dialogView = LocalView.current
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            dialogWindow?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowCompat.getInsetsController(window, dialogView).isAppearanceLightStatusBars = false
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(bg, Color(0xFF070707)))),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 顶栏：收起 / 歌名歌手 / 收藏 / 更多
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(HugeIcons.ArrowDown01, contentDescription = "收起", tint = Color.White)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (artist.isNotBlank()) {
                            Text(
                                artist,
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                    if (likeAvailable) {
                        IconButton(onClick = {
                            val id = snap.mediaId ?: return@IconButton
                            val want = !liked
                            liked = want
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { runCatching { MusicSources.like(id, want) }.getOrDefault(false) }
                                if (!ok) liked = !want
                            }
                        }) {
                            Icon(
                                imageVector = HugeIcons.Heart,
                                contentDescription = if (liked) "取消喜欢" else "喜欢",
                                tint = if (liked) accent else Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(HugeIcons.MoreHorizontal, contentDescription = "更多", tint = Color.White.copy(alpha = 0.85f))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (showFullLyrics) "收起歌词" else "查看完整歌词") },
                                onClick = { showFullLyrics = !showFullLyrics; menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("切换背景色") },
                                onClick = {
                                    bg = BG_PRESETS[(BG_PRESETS.indexOf(bg) + 1).mod(BG_PRESETS.size)]
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }

                // 黑胶唱片
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    VinylDisc(
                        cover = cover,
                        accent = accent,
                        rotation = rotation,
                        modifier = Modifier.fillMaxWidth(0.82f).aspectRatio(1f),
                    )
                }

                // 歌词预览（点按展开完整歌词）
                Box(
                    modifier = Modifier.fillMaxWidth().height(132.dp).clickable { showFullLyrics = true },
                    contentAlignment = Alignment.Center,
                ) {
                    LyricPreview(lyrics = lyrics, position = livePos, accent = accent)
                }

                Spacer(Modifier.height(6.dp))
                val progressFraction by animateFloatAsState(
                    targetValue = if (snap.duration > 0) livePos.toFloat() / snap.duration else 0f,
                    animationSpec = tween(200, easing = LinearEasing),
                    label = "progress",
                )
                Slider(
                    value = progressFraction,
                    onValueChange = { frac -> if (snap.duration > 0) controller?.seekTo((frac * snap.duration).toLong()) },
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                    ),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(livePos), color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(snap.duration), color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { cycleMode(controller, snap) }) {
                        Icon(modeIcon(snap), contentDescription = modeLabel(snap), tint = Color.White.copy(alpha = 0.85f))
                    }
                    IconButton(onClick = { controller?.seekToPreviousMediaItem() }) {
                        Icon(HugeIcons.Previous, contentDescription = "上一首", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { if (snap.playing) controller?.pause() else controller?.play() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (snap.playing) HugeIcons.Pause else HugeIcons.Play,
                            contentDescription = if (snap.playing) "暂停" else "播放",
                            tint = Color(0xFF111111),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                        Icon(HugeIcons.Next, contentDescription = "下一首", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = { showFullLyrics = !showFullLyrics }) {
                        Icon(HugeIcons.Playlist01, contentDescription = "歌词", tint = Color.White.copy(alpha = 0.85f))
                    }
                }
            }

            // 完整歌词浮层
            if (showFullLyrics) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bg.copy(alpha = 0.97f))
                        .clickable { showFullLyrics = false }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    if (lyrics.isNullOrEmpty()) {
                        Text("暂无歌词", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center))
                    } else {
                        LyricsList(lyrics = lyrics!!, position = livePos, accent = accent)
                    }
                    IconButton(
                        onClick = { showFullLyrics = false },
                        modifier = Modifier.align(Alignment.TopStart),
                    ) {
                        Icon(HugeIcons.ArrowDown01, contentDescription = "收起歌词", tint = Color.White)
                    }
                }
            }
        }
    }
}

private fun modeIcon(snap: PlayerSnapshot): ImageVector = when {
    snap.shuffle -> HugeIcons.Shuffle
    snap.repeatMode == Player.REPEAT_MODE_ONE -> HugeIcons.RepeatOne01
    else -> HugeIcons.Repeat
}

/** 黑胶唱片：外圈光晕 + 唱片纹理 + 中心旋转的专辑封面。 */
@Composable
private fun VinylDisc(
    cover: String,
    accent: Color,
    rotation: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.matchParentSize().drawBehind {
                val r = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.5f), accent.copy(alpha = 0.10f), Color.Transparent),
                        radius = r * 1.2f,
                    ),
                    radius = r * 1.2f,
                )
            },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xFF0E0A08))
                .rotate(rotation)
                .drawBehind {
                    val r = size.minDimension / 2f
                    var ring = r * 0.99f
                    while (ring > r * 0.62f) {
                        drawCircle(Color.White.copy(alpha = 0.04f), radius = ring, style = Stroke(1f))
                        ring -= r * 0.035f
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, accent.copy(alpha = 0.32f)),
                            radius = r,
                        ),
                        radius = r,
                        style = Stroke(4f),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(0.64f).clip(CircleShape).background(Color(0xFF201612)),
                contentAlignment = Alignment.Center,
            ) {
                if (cover.isNotBlank()) {
                    AsyncImage(model = cover, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Text("♪", color = Color.White, style = MaterialTheme.typography.displayMedium)
                }
            }
        }
    }
}

/** 唱片下方四行歌词预览：当前行高亮，其余弱化；有翻译时在原文下方显示。 */
@Composable
private fun LyricPreview(
    lyrics: List<LyricLine>?,
    position: Long,
    accent: Color,
) {
    if (lyrics.isNullOrEmpty()) {
        Text("暂无歌词", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val idx = lyrics.indexOfLast { it.time <= position }.coerceAtLeast(0)
    AnimatedContent(
        targetState = idx,
        transitionSpec = {
            val dir = if (targetState >= initialState) 1 else -1
            (slideInVertically { full -> dir * full / 3 } + fadeIn()) togetherWith
                (slideOutVertically { full -> -dir * full / 3 } + fadeOut())
        },
        label = "lyricPreview",
    ) { current ->
        val visible = (current until minOf(current + 4, lyrics.size)).toList()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            visible.forEach { i ->
                val isCurrent = i == current
                val line = lyrics[i]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = line.text,
                        color = if (isCurrent) accent else Color.White.copy(alpha = 0.5f),
                        style = if (isCurrent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (line.translation.isNotBlank()) {
                        Text(
                            text = line.translation,
                            color = if (isCurrent) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.35f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsList(lyrics: List<LyricLine>, position: Long, accent: Color = Color.White) {
    val currentIndex = remember(position, lyrics) {
        lyrics.indexOfLast { it.time <= position }.coerceAtLeast(0)
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(lyrics.indices.toList(), key = { it }) { i ->
            val active = i == currentIndex
            val line = lyrics[i]
            val lineColor by animateColorAsState(
                targetValue = if (active) accent else Color.White.copy(alpha = 0.45f),
                animationSpec = tween(300),
                label = "lyricColor",
            )
            val lineScale by animateFloatAsState(
                targetValue = if (active) 1f else 0.94f,
                animationSpec = tween(300),
                label = "lyricScale",
            )
            Column(
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    scaleX = lineScale
                    scaleY = lineScale
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = line.text,
                    style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = lineColor,
                    textAlign = TextAlign.Center,
                )
                if (line.translation.isNotBlank()) {
                    Text(
                        text = line.translation,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.35f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 解析 LRC（可带翻译），返回按时间排序的歌词行。 */
private fun parseLrc(lrc: String, translation: String = ""): List<LyricLine> {
    val textMap = parseTimedText(lrc)
    val transMap = parseTimedText(translation)
    return textMap.entries
        .sortedBy { it.key }
        .map { (time, text) -> LyricLine(time, text, transMap[time].orEmpty()) }
}

private fun parseTimedText(raw: String): Map<Long, String> {
    val out = LinkedHashMap<Long, String>()
    val tag = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    raw.lineSequence().forEach { line ->
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
            if (text.isNotEmpty()) out[min * 60_000 + sec * 1000 + fracMs] = text
        }
    }
    return out
}
