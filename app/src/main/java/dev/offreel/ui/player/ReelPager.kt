package dev.offreel.ui.player

import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.offreel.R
import dev.offreel.data.GalleryExport
import dev.offreel.data.ReelEntity
import dev.offreel.data.ReelStatus
import dev.offreel.graph
import dev.offreel.ui.common.GroupPickerDialog
import dev.offreel.ui.common.OffreelMark
import dev.offreel.ui.common.isInstagramHandle
import dev.offreel.ui.common.openInstagramProfile
import dev.offreel.ui.common.shareVideos
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import dev.offreel.ui.common.Thumb
import dev.offreel.ui.common.openInInstagram
import dev.offreel.ui.theme.Brand
import dev.offreel.ui.theme.LocalOffreelColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Full-screen, swipe-up video player over [reels]. One ExoPlayer is shared by every page. */
@Composable
fun ReelPager(
    reels: List<ReelEntity>,
    modifier: Modifier = Modifier,
    title: String? = null,
    groupId: Long? = null,
    initialPage: Int = 0,
    /** Full-screen use: keep the seek bar and captions clear of the system gesture bar. */
    avoidNavigationBar: Boolean = false,
    onBack: (() -> Unit)? = null,
    topEnd: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var userPaused by remember { mutableStateOf(false) }
    var muted by rememberSaveable { mutableStateOf(false) }
    /** Holding the video plays it at 2x until the finger lifts. */
    var fast by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!userPaused) player.play() }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    val pager = rememberPagerState(initialPage = initialPage) { reels.size }
    // With gesture navigation and a hidden pill the nav-bar inset is 0, but the bottom-edge swipe still
    // exists; the mandatory gesture inset covers that strip.
    val bottomInset = if (avoidNavigationBar) {
        WindowInsets.navigationBars.union(WindowInsets.mandatorySystemGestures).asPaddingValues().calculateBottomPadding()
    } else 0.dp
    val current = reels.getOrNull(pager.settledPage)
    val currentFile = current?.takeIf { it.status == ReelStatus.READY }?.videoPath?.let(::File)?.takeIf { it.exists() }
    LaunchedEffect(current?.id, currentFile?.path) {
        userPaused = false
        fast = false
        player.setPlaybackSpeed(1f)
        if (currentFile != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(currentFile)))
            player.prepare()
            player.play()
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(current?.id) {
        while (true) {
            val d = player.duration
            progress = if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(100)
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pager,
            key = { reels[it].id },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val reel = reels[page]
            val isCurrent = page == pager.settledPage
            ReelPage(
                reel = reel,
                player = if (isCurrent && currentFile != null) player else null,
                isPlaying = isPlaying,
                progress = if (isCurrent) progress else 0f,
                muted = muted,
                groupId = groupId,
                onTogglePlay = {
                    if (player.isPlaying) { player.pause(); userPaused = true } else { player.play(); userPaused = false }
                },
                onToggleMute = { muted = !muted },
                onHoldStart = { player.setPlaybackSpeed(2f); fast = true },
                onHoldEnd = { if (fast) { player.setPlaybackSpeed(1f); fast = false } },
                onSeek = { fraction -> if (player.duration > 0) player.seekTo((player.duration * fraction).toLong()) },
                durationMs = { player.duration.coerceAtLeast(0) },
                bottomInset = bottomInset,
            )
        }

        AnimatedVisibility(
            visible = fast, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 56.dp),
        ) {
            Text(
                "2× speed", color = Brand.Cream, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.background(Brand.Ink.copy(alpha = 0.65f), CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        // A soft fade so the title and buttons stay readable over bright videos.
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Brush.verticalGradient(listOf(Brand.Ink.copy(alpha = 0.6f), Color.Transparent)))
        )
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Brand.Cream) }
            } else Spacer(Modifier.width(12.dp))
            if (title != null) {
                Text(title, color = Brand.Cream, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Spacer(Modifier.weight(1f))
            topEnd()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ReelPage(
    reel: ReelEntity,
    player: Player?,
    isPlaying: Boolean,
    progress: Float,
    muted: Boolean,
    groupId: Long?,
    onTogglePlay: () -> Unit,
    onToggleMute: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onSeek: (Float) -> Unit,
    durationMs: () -> Long,
    bottomInset: Dp,
) {
    val extra = LocalOffreelColors.current
    Box(Modifier.fillMaxSize()) {
        if (reel.status == ReelStatus.READY) {
            Box(
                Modifier.fillMaxSize().pointerInput(player) {
                    if (player == null) return@pointerInput
                    detectTapGestures(
                        onTap = { onTogglePlay() },
                        onLongPress = { onHoldStart() },
                        // Called on every press; when it ends (lift, or the pager takes over a swipe) drop back to 1x.
                        onPress = { tryAwaitRelease(); onHoldEnd() },
                    )
                }
            ) {
                Thumb(reel.thumbPath, Modifier.fillMaxSize(), placeholder = Color.Black, contentScale = ContentScale.Fit)
                if (player != null) {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).also { it.player = player }
                        },
                        update = { it.player = player },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AnimatedVisibility(
                    visible = player != null && !isPlaying,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Brand.Ink.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        OffreelMark(44.dp, ringColor = Color.Transparent)
                    }
                }
            }
        } else {
            StatusPage(reel)
        }

        // Bottom: creator and caption over a soft gradient; progress in the accent colour.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Brand.Ink.copy(alpha = 0.7f))))
                .padding(start = 16.dp, end = 72.dp, top = 40.dp, bottom = 14.dp + bottomInset)
        ) {
            reel.author?.let { author ->
                val context = LocalContext.current
                // Tap the @handle to open the creator's profile in Instagram (or the reel, if we only know a display name).
                // A pill, so it reads as a button: opens the creator's profile in Instagram.
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(Brand.Cream.copy(alpha = 0.14f))
                        .border(1.dp, Brand.Cream.copy(alpha = 0.28f), CircleShape)
                        .clickable {
                            if (isInstagramHandle(author)) openInstagramProfile(context, author) else openInInstagram(context, reel.url)
                        }
                        .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "@$author", color = Brand.Cream, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open in Instagram",
                        tint = Brand.Cream.copy(alpha = 0.8f), modifier = Modifier.size(14.dp),
                    )
                }
            }
            reel.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                var expanded by remember(reel.id) { mutableStateOf(false) }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.heightIn(max = if (expanded) 220.dp else 60.dp)) {
                    // Tap the caption to open it, tap again to close it (long captions scroll while open).
                    val toggle = Modifier.clickable(interactionSource = null, indication = null) { expanded = !expanded }
                    if (expanded) {
                        LazyColumn {
                            item {
                                Text(
                                    caption, color = Brand.Cream.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
                                    modifier = toggle,
                                )
                            }
                        }
                    } else {
                        Text(
                            caption, color = Brand.Cream.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = toggle,
                        )
                    }
                }
            }
        }
        if (player != null) {
            SeekBar(
                progress = progress, durationMs = durationMs, onSeek = onSeek,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = bottomInset),
            )
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 28.dp + bottomInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (reel.status == ReelStatus.READY) {
                IconButton(onClick = onToggleMute) {
                    Icon(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (muted) "Unmute" else "Mute", tint = Brand.Cream)
                }
                val context = LocalContext.current
                IconButton(onClick = { shareVideos(context, listOf(reel)) }) {
                    Icon(Icons.Rounded.Share, "Share video", tint = Brand.Cream)
                }
            }
            ReelMenu(reel, groupId)
        }
    }
}

/**
 * The progress line at the bottom. Tap to jump, or drag to scrub: while dragging it thickens,
 * shows a handle and the time, and the video jumps there when you let go.
 */
@Composable
private fun SeekBar(progress: Float, durationMs: () -> Long, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val extra = LocalOffreelColors.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragFraction else progress
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (dragging) {
            val total = durationMs()
            Text(
                "${formatMs((total * dragFraction).toLong())} / ${formatMs(total)}",
                color = Brand.Cream, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp).background(Brand.Ink.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(Unit) {
                    detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = { onSeek(dragFraction); dragging = false },
                        onDragCancel = { dragging = false },
                    )
                },
            contentAlignment = Alignment.BottomStart,
        ) {
            val barHeight = if (dragging) 4.dp else 2.dp
            Box(Modifier.fillMaxWidth().height(barHeight).background(Brand.Cream.copy(alpha = 0.22f)))
            Box(Modifier.fillMaxWidth(shown).height(barHeight).background(extra.accent))
            if (dragging) {
                Box(
                    Modifier
                        .offset(x = maxWidth * shown - 7.dp, y = 5.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(extra.accent)
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun StatusPage(reel: ReelEntity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val extra = LocalOffreelColors.current
    Box(Modifier.fillMaxSize().background(Brand.Ink), contentAlignment = Alignment.Center) {
        Thumb(reel.thumbPath, Modifier.fillMaxSize(), placeholder = Brand.Ink, contentScale = ContentScale.Fit)
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (reel.status) {
                ReelStatus.FAILED -> {
                    Text("Couldn't save the video", color = Brand.Cream, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(reel.error ?: "Download failed", color = Brand.Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { scope.launch { context.graph.repository.retry(reel.id) } },
                            colors = ButtonDefaults.buttonColors(containerColor = extra.accent, contentColor = extra.onAccent),
                        ) { Text("Try again") }
                        OutlinedButton(onClick = { openInInstagram(context, reel.url) }) {
                            Text("Open in Instagram", color = Brand.Cream)
                        }
                    }
                }
                else -> {
                    CircularProgressIndicator(color = extra.accent)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (reel.status == ReelStatus.DOWNLOADING) "Saving to your phone…" else "Waiting to download…",
                        color = Brand.Cream, style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReelMenu(reel: ReelEntity, groupId: Long?) {
    val context = LocalContext.current
    val repo = context.graph.repository
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }   // "move" or "add"
    var confirmDelete by remember { mutableStateOf(false) }
    var inGroups by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(reel.id, picker) { inGroups = repo.groupIdsOf(reel.id).toSet() }
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Brand.Cream) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (groupId != null) {
                DropdownMenuItem(text = { Text("Move to…") }, onClick = { open = false; picker = "move" })
            }
            DropdownMenuItem(text = { Text("Also add to…") }, onClick = { open = false; picker = "add" })
            DropdownMenuItem(text = { Text("Share video") }, onClick = { open = false; shareVideos(context, listOf(reel)) })
            DropdownMenuItem(text = { Text("Save to gallery") }, onClick = {
                open = false; scope.launch { toast(GalleryExport.saveAll(context, listOf(reel))) }
            })
            DropdownMenuItem(text = { Text("Open in Instagram") }, onClick = { open = false; openInInstagram(context, reel.url) })
            if (groupId != null) {
                DropdownMenuItem(text = { Text("Remove from this group") }, onClick = {
                    open = false; scope.launch { repo.removeFromGroup(reel.id, groupId) }
                })
            }
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; confirmDelete = true },
            )
        }
    }
    when (picker) {
        "move" -> GroupPickerDialog(
            title = "Move to",
            excludeGroupIds = inGroups,
            onDismiss = { picker = null },
            onPick = { to ->
                picker = null
                scope.launch { toast("Moved to ${repo.moveManyToGroup(listOf(reel.id), groupId!!, to)}") }
            },
        )
        "add" -> GroupPickerDialog(
            title = "Also add to",
            excludeGroupIds = inGroups,
            onDismiss = { picker = null },
            onPick = { to ->
                picker = null
                scope.launch { repo.addToGroup(reel.id, to); toast("Added to another group") }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this reel?") },
            text = { Text("It's removed from every group and the video is deleted from your phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; scope.launch { repo.deleteReel(reel.id) } }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun EmptyPlayer(
    title: String,
    message: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    topEnd: @Composable () -> Unit = {},
) {
    val extra = LocalOffreelColors.current
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp).weight(1f))
            topEnd()
        }
        Column(Modifier.align(Alignment.Center).padding(40.dp).fillMaxHeight(0.5f), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            OffreelMark(48.dp, ringColor = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = extra.muted)
        }
    }
}
