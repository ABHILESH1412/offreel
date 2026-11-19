package dev.offreel.ui.group

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.offreel.data.GalleryExport
import dev.offreel.data.ReelEntity
import dev.offreel.data.ReelStatus
import dev.offreel.graph
import dev.offreel.ui.common.GroupPickerDialog
import dev.offreel.ui.common.DeleteGroupsDialog
import dev.offreel.ui.common.OffreelMark
import dev.offreel.ui.common.SelectionMark
import dev.offreel.ui.home.NameDialog
import dev.offreel.ui.common.Thumb
import dev.offreel.ui.common.openInInstagram
import dev.offreel.ui.player.EmptyPlayer
import dev.offreel.ui.player.ReelPager
import dev.offreel.ui.theme.Brand
import dev.offreel.ui.theme.LocalOffreelColors
import kotlinx.coroutines.launch

private sealed interface GroupAction {
    data class Move(val ids: Set<Long>) : GroupAction
    data class AddTo(val ids: Set<Long>) : GroupAction
    data class Delete(val ids: Set<Long>) : GroupAction
}

/**
 * A group's reels as a grid or a list (the user's choice, remembered). Tap plays from that reel.
 * Long-press starts selecting several; every reel also has a ⋮ menu (move, add, remove, delete).
 */
@Composable
fun GroupScreen(groupId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.graph.repository
    val store = context.graph.settings
    val scope = rememberCoroutineScope()
    val group by repo.group(groupId).collectAsStateWithLifecycle(initialValue = null)
    val reels by repo.reelsIn(groupId).collectAsStateWithLifecycle(initialValue = null)
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = null)
    var playingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var action by remember { mutableStateOf<GroupAction?>(null) }
    var groupMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deletingGroup by remember { mutableStateOf(false) }

    val list = reels ?: return
    val title = group?.name ?: ""
    val selecting = selected.isNotEmpty()
    LaunchedEffect(list) { selected = selected intersect list.map { it.id }.toSet() }   // drop deleted/moved ones

    playingIndex?.let { start ->
        BackHandler { playingIndex = null }
        if (list.isEmpty()) { playingIndex = null; return@let }
        ReelPager(
            reels = list,
            initialPage = start.coerceIn(0, list.lastIndex),
            title = title,
            groupId = groupId,
            avoidNavigationBar = true,
            onBack = { playingIndex = null },
        )
        return
    }
    BackHandler(enabled = selecting) { selected = emptySet() }

    if (list.isEmpty()) {
        EmptyPlayer(
            title = title, onBack = onBack,
            message = "Nothing here yet. Open Instagram, tap the offreel button on a reel and move it to this group.",
        )
        return
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    val onTap = { i: Int, reel: ReelEntity ->
        if (selecting) selected = if (reel.id in selected) selected - reel.id else selected + reel.id
        else playingIndex = i
    }
    val onLongPress = { reel: ReelEntity -> selected = selected + reel.id }
    val menu = ReelMenuActions(
        openInInstagram = { openInInstagram(context, it.url) },
        move = { action = GroupAction.Move(setOf(it.id)) },
        addTo = { action = GroupAction.AddTo(setOf(it.id)) },
        removeHere = { r -> scope.launch { repo.removeFromGroup(r.id, groupId); toast("Removed from $title") } },
        delete = { action = GroupAction.Delete(setOf(it.id)) },
        saveToGallery = { r -> scope.launch { toast(GalleryExport.saveAll(context, listOf(r))) } },
    )

    val grid = settings?.groupGrid ?: true
    val extra = LocalOffreelColors.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.statusBars)) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, "Stop selecting") }
                Text("${selected.size} selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { selected = list.map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Select all") }
                IconButton(onClick = {
                    val picked = list.filter { it.id in selected }
                    selected = emptySet()
                    scope.launch { toast(GalleryExport.saveAll(context, picked)) }
                }) { Icon(Icons.Rounded.SaveAlt, "Save to gallery") }
                IconButton(onClick = { action = GroupAction.Move(selected) }) {
                    Icon(Icons.AutoMirrored.Rounded.DriveFileMove, "Move to another group")
                }
                IconButton(onClick = { action = GroupAction.Delete(selected) }) { Icon(Icons.Rounded.Delete, "Delete") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (list.size == 1) "1 reel · hold to select" else "${list.size} reels · hold to select",
                        style = MaterialTheme.typography.bodySmall, color = extra.muted,
                    )
                }
                IconButton(onClick = { scope.launch { store.setGroupGrid(!grid) } }) {
                    Icon(
                        if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                        contentDescription = if (grid) "Show as list" else "Show as grid",
                    )
                }
                Box {
                    IconButton(onClick = { groupMenu = true }) { Icon(Icons.Rounded.MoreVert, "Group options") }
                    DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                        DropdownMenuItem(text = { Text("Select reels") }, onClick = {
                            groupMenu = false; list.firstOrNull()?.let { selected = setOf(it.id) }
                        })
                        DropdownMenuItem(text = { Text("Rename group") }, onClick = { groupMenu = false; renaming = true })
                        if (group?.isDefault == false) {
                            DropdownMenuItem(
                                text = { Text("Delete group", color = MaterialTheme.colorScheme.error) },
                                onClick = { groupMenu = false; deletingGroup = true },
                            )
                        }
                    }
                }
            }
        }

        val bottom = WindowInsets.navigationBars
        if (grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().windowInsetsPadding(bottom),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(list, key = { _, r -> r.id }) { i, reel ->
                    GridTile(
                        reel = reel, selecting = selecting, isSelected = reel.id in selected,
                        onClick = { onTap(i, reel) }, onLongClick = { onLongPress(reel) }, menu = menu,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(bottom), contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(list, key = { _, r -> r.id }) { i, reel ->
                    ListRow(
                        reel = reel, selecting = selecting, isSelected = reel.id in selected,
                        onClick = { onTap(i, reel) }, onLongClick = { onLongPress(reel) }, menu = menu,
                    )
                }
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = "Rename group", initial = title, confirm = "Rename",
            onDismiss = { renaming = false },
            onConfirm = { name -> renaming = false; scope.launch { repo.renameGroup(groupId, name) } },
        )
    }
    if (deletingGroup) {
        DeleteGroupsDialog(
            names = listOf(title),
            reelCount = list.size,
            onDismiss = { deletingGroup = false },
            onDelete = { keepReels ->
                deletingGroup = false
                scope.launch {
                    repo.deleteGroup(groupId, keepReels)
                    toast(if (keepReels && list.isNotEmpty()) "Group deleted; reels kept in Default" else "Group deleted")
                    onBack()
                }
            },
        )
    }

    when (val a = action) {
        is GroupAction.Move -> GroupPickerDialog(
            title = if (a.ids.size == 1) "Move to" else "Move ${a.ids.size} reels to",
            excludeGroupIds = setOf(groupId),
            onDismiss = { action = null },
            onPick = { to ->
                action = null
                selected = emptySet()
                scope.launch { toast("Moved to ${repo.moveManyToGroup(a.ids, groupId, to)}") }
            },
        )
        is GroupAction.AddTo -> GroupPickerDialog(
            title = "Also add to",
            excludeGroupIds = setOf(groupId),
            onDismiss = { action = null },
            onPick = { to ->
                action = null
                scope.launch {
                    a.ids.forEach { repo.addToGroup(it, to) }
                    toast("Added to another group")
                }
            },
        )
        is GroupAction.Delete -> AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(if (a.ids.size == 1) "Delete this reel?" else "Delete ${a.ids.size} reels?") },
            text = { Text("They're removed from every group and the videos are deleted from your phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    action = null
                    selected = emptySet()
                    scope.launch {
                        repo.deleteReels(a.ids)
                        toast(if (a.ids.size == 1) "Deleted" else "Deleted ${a.ids.size} reels")
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
        )
        null -> {}
    }
}

private class ReelMenuActions(
    val openInInstagram: (ReelEntity) -> Unit,
    val move: (ReelEntity) -> Unit,
    val addTo: (ReelEntity) -> Unit,
    val removeHere: (ReelEntity) -> Unit,
    val delete: (ReelEntity) -> Unit,
    val saveToGallery: (ReelEntity) -> Unit,
)

@Composable
private fun ReelMenuButton(reel: ReelEntity, menu: ReelMenuActions, onImage: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (onImage) {
            // Small and on a dark disc so it reads over any thumbnail.
            Box(
                Modifier
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Brand.Ink.copy(alpha = 0.55f))
                    .combinedClickable(onClick = { open = true }),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.MoreVert, "More", tint = Brand.Cream, modifier = Modifier.size(18.dp)) }
        } else {
            IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More") }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Move to…") }, onClick = { open = false; menu.move(reel) })
            DropdownMenuItem(text = { Text("Also add to…") }, onClick = { open = false; menu.addTo(reel) })
            DropdownMenuItem(text = { Text("Save to gallery") }, onClick = { open = false; menu.saveToGallery(reel) })
            DropdownMenuItem(text = { Text("Open in Instagram") }, onClick = { open = false; menu.openInInstagram(reel) })
            DropdownMenuItem(text = { Text("Remove from this group") }, onClick = { open = false; menu.removeHere(reel) })
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; menu.delete(reel) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(
    reel: ReelEntity, selecting: Boolean, isSelected: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, menu: ReelMenuActions,
) {
    val extra = LocalOffreelColors.current
    Box(
        Modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Thumb(reel.thumbPath, Modifier.fillMaxSize(), placeholder = MaterialTheme.colorScheme.surfaceContainerHigh)
        StatusBadge(reel, Modifier.align(Alignment.Center))
        reel.durationSec?.takeIf { reel.status == ReelStatus.READY }?.let {
            Text(
                formatDuration(it), color = Brand.Cream, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Brand.Ink.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (selecting) {
            if (isSelected) Box(Modifier.fillMaxSize().background(Brand.Ink.copy(alpha = 0.35f)))
            SelectionMark(isSelected, Modifier.align(Alignment.TopStart).padding(6.dp))
        } else {
            Box(Modifier.align(Alignment.TopEnd)) { ReelMenuButton(reel, menu, onImage = true) }
        }
        if (isSelected) Box(Modifier.fillMaxSize().border(2.dp, extra.accent, RoundedCornerShape(4.dp)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    reel: ReelEntity, selecting: Boolean, isSelected: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, menu: ReelMenuActions,
) {
    val extra = LocalOffreelColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            SelectionMark(isSelected)
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.size(width = 60.dp, height = 96.dp).clip(RoundedCornerShape(10.dp))) {
            Thumb(reel.thumbPath, Modifier.fillMaxSize(), placeholder = MaterialTheme.colorScheme.surfaceContainerHigh)
            StatusBadge(reel, Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                reel.author?.let { "@$it" } ?: if (reel.kind == "p") "Post" else "Reel",
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            reel.caption?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Text(statusLine(reel), style = MaterialTheme.typography.labelSmall, color = extra.muted)
        }
        if (!selecting) ReelMenuButton(reel, menu, onImage = false)
    }
}

@Composable
private fun StatusBadge(reel: ReelEntity, modifier: Modifier) {
    val extra = LocalOffreelColors.current
    when (reel.status) {
        ReelStatus.READY -> if (reel.thumbPath == null) OffreelMark(24.dp, ringColor = extra.muted, modifier = modifier)
        ReelStatus.FAILED -> Icon(Icons.Rounded.ErrorOutline, "Couldn't save", tint = Brand.Cream, modifier = modifier)
        else -> CircularProgressIndicator(modifier.size(22.dp), strokeWidth = 2.dp, color = extra.accent)
    }
}

private fun statusLine(reel: ReelEntity): String = when (reel.status) {
    ReelStatus.READY -> reel.durationSec?.let { formatDuration(it) } ?: "Saved"
    ReelStatus.FAILED -> reel.error ?: "Couldn't save"
    ReelStatus.DOWNLOADING -> "Downloading…"
    else -> "Waiting to download"
}

fun formatDuration(seconds: Double): String {
    val s = seconds.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
