package dev.offreel.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.LinearProgressIndicator
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.ui.draw.alpha
import dev.offreel.ui.common.DeleteGroupsDialog
import dev.offreel.ui.common.SelectionMark
import dev.offreel.data.GroupSummary
import dev.offreel.data.ReelEntity
import dev.offreel.data.ReelStatus
import dev.offreel.graph
import dev.offreel.ui.common.OffreelMark
import dev.offreel.ui.common.Thumb
import dev.offreel.ui.common.isCaptureServiceEnabled
import dev.offreel.ui.common.openAccessibilitySettings
import dev.offreel.ui.common.openAppDetails
import dev.offreel.ui.theme.Brand
import dev.offreel.ui.theme.LocalOffreelColors
import dev.offreel.ui.theme.WordmarkStyle
import kotlinx.coroutines.launch

private sealed interface GroupDialog {
    data object Create : GroupDialog
    data class Rename(val id: Long, val name: String) : GroupDialog
    data class Delete(val ids: Set<Long>, val names: List<String>, val count: Int) : GroupDialog
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier, onOpenGroup: (Long) -> Unit) {
    val context = LocalContext.current
    val repo = context.graph.repository
    val scope = rememberCoroutineScope()
    val groups by repo.groupSummaries.collectAsStateWithLifecycle(initialValue = null)
    val active by repo.activeReels.collectAsStateWithLifecycle(initialValue = emptyList())
    val failed by repo.failedReels.collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by context.graph.downloads.progress.collectAsStateWithLifecycle()
    var serviceOn by remember { mutableStateOf(isCaptureServiceEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        serviceOn = isCaptureServiceEnabled(context)
        onPauseOrDispose { }
    }
    var dialog by remember { mutableStateOf<GroupDialog?>(null) }
    /** Groups picked for deleting together (long-press a card). Default can't be picked. */
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val selecting = selected.isNotEmpty()
    LaunchedEffect(groups) { selected = selected intersect groups.orEmpty().map { it.id }.toSet() }
    BackHandler(enabled = selecting) { selected = emptySet() }
    fun askDelete(ids: Set<Long>) {
        val picked = groups.orEmpty().filter { it.id in ids && !it.isDefault }
        if (picked.isNotEmpty()) {
            dialog = GroupDialog.Delete(picked.map { it.id }.toSet(), picked.map { it.name }, picked.sumOf { it.reelCount })
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    onClear = { selected = emptySet() },
                    onSelectAll = { selected = groups.orEmpty().filter { !it.isDefault }.map { it.id }.toSet() },
                    onDelete = { askDelete(selected) },
                )
            } else {
                Header(onNewGroup = { dialog = GroupDialog.Create })
            }
        }
        if (!serviceOn) {
            item(span = { GridItemSpan(maxLineSpan) }) { SetupCard() }
        }
        if (active.isNotEmpty() || failed.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SavingNow(
                    active = active, failed = failed, progress = progress,
                    onRetry = { id -> scope.launch { repo.retry(id) } },
                    onRemove = { id -> scope.launch { repo.deleteReel(id) } },
                )
            }
        }
        val list = groups
        if (list != null && list.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(onNewGroup = { dialog = GroupDialog.Create }) }
        }
        if (list != null) {
            items(list, key = { it.id }) { g ->
                GroupCard(
                    group = g,
                    selecting = selecting,
                    isSelected = g.id in selected,
                    onClick = {
                        when {
                            !selecting -> onOpenGroup(g.id)
                            g.isDefault -> {}
                            else -> selected = if (g.id in selected) selected - g.id else selected + g.id
                        }
                    },
                    onLongClick = { if (!g.isDefault) selected = selected + g.id },
                    onRename = { dialog = GroupDialog.Rename(g.id, g.name) },
                    onDelete = { askDelete(setOf(g.id)) },
                )
            }
            if (list.isNotEmpty() && !selecting) {
                item { NewGroupCard(onClick = { dialog = GroupDialog.Create }) }
            }
        }
    }

    when (val d = dialog) {
        GroupDialog.Create -> NameDialog(
            title = "New group", initial = "", confirm = "Create",
            onDismiss = { dialog = null },
            onConfirm = { name -> scope.launch { repo.createGroup(name) }; dialog = null },
        )
        is GroupDialog.Rename -> NameDialog(
            title = "Rename group", initial = d.name, confirm = "Rename",
            onDismiss = { dialog = null },
            onConfirm = { name -> scope.launch { repo.renameGroup(d.id, name) }; dialog = null },
        )
        is GroupDialog.Delete -> DeleteGroupsDialog(
            names = d.names,
            reelCount = d.count,
            onDismiss = { dialog = null },
            onDelete = { keepReels ->
                dialog = null
                selected = emptySet()
                scope.launch {
                    repo.deleteGroups(d.ids, keepReels)
                    Toast.makeText(
                        context,
                        (if (d.ids.size == 1) "Group deleted" else "${d.ids.size} groups deleted") +
                            if (keepReels && d.count > 0) "; reels kept in Default" else "",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
        null -> {}
    }
}

@Composable
private fun Header(onNewGroup: () -> Unit) {
    val extra = LocalOffreelColors.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val word = buildAnnotatedString {
            // On dark backgrounds "off" takes the accent; on light ones Ember is never used for text.
            withStyle(SpanStyle(color = if (extra.isDark) extra.accent else MaterialTheme.colorScheme.onBackground)) { append("off") }
            append("reel")
        }
        Text(word, style = WordmarkStyle, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        IconButton(onClick = onNewGroup) { Icon(Icons.Rounded.Add, contentDescription = "New group") }
    }
}

@Composable
private fun SetupCard() {
    val context = LocalContext.current
    val extra = LocalOffreelColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, extra.line, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OffreelMark(26.dp, ringColor = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Text("Turn on the save button", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "offreel needs Accessibility access to put its button over Instagram and copy the link of the " +
                "reel you're watching. It does nothing while Instagram is closed.",
            style = MaterialTheme.typography.bodyMedium, color = extra.muted,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { openAccessibilitySettings(context) },
                colors = ButtonDefaults.buttonColors(containerColor = extra.accent, contentColor = extra.onAccent),
            ) { Text("Open settings") }
            OutlinedButton(onClick = { openAppDetails(context) }) { Text("App info") }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Installed from a website and the switch is greyed out? In App info, tap ⋮ → Allow restricted settings, then try again.",
            style = MaterialTheme.typography.bodySmall, color = extra.muted,
        )
    }
}

@Composable
private fun EmptyState(onNewGroup: () -> Unit) {
    val extra = LocalOffreelColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OffreelMark(56.dp, ringColor = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))
        Text("No groups yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Make one for each thing you save: Gym, Motivation, Recipes…",
            style = MaterialTheme.typography.bodyMedium, color = extra.muted,
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onNewGroup,
            colors = ButtonDefaults.buttonColors(containerColor = extra.accent, contentColor = extra.onAccent),
        ) { Text("Create a group") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupCard(
    group: GroupSummary,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val extra = LocalOffreelColors.current
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .alpha(if (selecting && group.isDefault) 0.45f else 1f)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.78f).clip(RoundedCornerShape(18.dp))) {
            Thumb(group.coverPath, Modifier.fillMaxSize(), placeholder = MaterialTheme.colorScheme.surfaceContainerHigh)
            if (group.coverPath == null) {
                OffreelMark(36.dp, ringColor = extra.muted, modifier = Modifier.align(Alignment.Center), playColor = extra.muted)
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.6f to Color.Transparent, 1f to Brand.Ink.copy(alpha = 0.55f))
                    )
                )
            }
            if (selecting) {
                if (!group.isDefault) SelectionMark(isSelected, Modifier.align(Alignment.TopStart).padding(10.dp))
                if (isSelected) Box(Modifier.fillMaxSize().border(2.dp, extra.accent, RoundedCornerShape(18.dp)))
            } else {
                Box(Modifier.align(Alignment.TopEnd)) {
                    Box(
                        Modifier
                            .padding(6.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Brand.Ink.copy(alpha = 0.55f))
                            .clickable { menu = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.MoreVert, "Group options", tint = Brand.Cream, modifier = Modifier.size(20.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        if (!group.isDefault) {
                            DropdownMenuItem(
                                text = { Text("Delete group", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onDelete() },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = buildString {
            append(if (group.reelCount == 1) "1 reel" else "${group.reelCount} reels")
            if (group.pendingCount > 0) append(" · ${group.pendingCount} saving")
            if (group.isDefault) append(" · new saves land here")
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "Stop selecting") }
        Text(
            if (count == 1) "1 group selected" else "$count groups selected",
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, "Select all") }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete groups") }
    }
}

@Composable
private fun NewGroupCard(onClick: () -> Unit) {
    val extra = LocalOffreelColors.current
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, extra.line, RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(28.dp), tint = extra.muted)
                Spacer(Modifier.height(6.dp))
                Text("New group", color = extra.muted, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Reels downloading or waiting right now, plus any that failed (with retry). */
@Composable
private fun SavingNow(
    active: List<ReelEntity>,
    failed: List<ReelEntity>,
    progress: Map<Long, Float>,
    onRetry: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val extra = LocalOffreelColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (active.isNotEmpty()) {
            Text(
                "SAVING NOW · ${active.size}", style = MaterialTheme.typography.labelMedium, color = extra.muted,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            active.forEach { reel ->
                val p = progress[reel.id]
                DownloadRow(
                    reel = reel,
                    status = when {
                        p != null -> "Downloading · ${p.toInt()}%"
                        reel.status == ReelStatus.DOWNLOADING -> "Starting…"
                        else -> "Waiting for a connection"
                    },
                ) {
                    if (p != null) {
                        LinearProgressIndicator(
                            progress = { p / 100f }, color = extra.accent, trackColor = extra.line,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                        )
                    } else if (reel.status == ReelStatus.DOWNLOADING) {
                        LinearProgressIndicator(
                            color = extra.accent, trackColor = extra.line,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }
        if (failed.isNotEmpty()) {
            if (active.isNotEmpty()) Spacer(Modifier.height(10.dp))
            Text(
                "COULDN'T SAVE · ${failed.size}", style = MaterialTheme.typography.labelMedium, color = extra.muted,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            failed.take(5).forEach { reel ->
                DownloadRow(reel = reel, status = reel.error ?: "Download failed") {
                    Row {
                        TextButton(onClick = { onRetry(reel.id) }) { Text("Try again", color = MaterialTheme.colorScheme.onSurface) }
                        TextButton(onClick = { onRemove(reel.id) }) { Text("Remove", color = extra.muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(reel: ReelEntity, status: String, below: @Composable () -> Unit) {
    val extra = LocalOffreelColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 36.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { OffreelMark(18.dp, ringColor = extra.muted, playColor = extra.muted) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                reel.author?.let { "@$it" } ?: if (reel.kind == "p") "Post" else "Reel",
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(status, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            below()
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                placeholder = { Text("e.g. Gym") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
