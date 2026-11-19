package dev.offreel.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.offreel.data.GroupEntity
import dev.offreel.data.ReelStatus
import dev.offreel.graph
import dev.offreel.ui.player.EmptyPlayer
import dev.offreel.ui.player.ReelPager
import dev.offreel.ui.theme.Brand
import dev.offreel.ui.theme.LocalOffreelColors
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Saved videos shuffled, from every group or only the groups picked with the filter button
 * (remembered). The order is fixed by a seed, so a reel that finishes downloading slots in
 * without reshuffling what you're watching. Tap shuffle for a new order.
 */
@Composable
fun FeedScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = context.graph.repository
    val store = context.graph.settings
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    // Ignore filter entries for groups that have since been deleted.
    val filter = settings?.feedGroupIds.orEmpty().filter { id -> groups.any { it.id == id } }.toSet()
    val source = remember(filter) { repo.reelsInGroups(filter) }
    val all by source.collectAsStateWithLifecycle(initialValue = null)
    var seed by rememberSaveable { mutableLongStateOf(Random.nextLong()) }
    var picking by remember { mutableStateOf(false) }
    val reels = remember(all, seed) {
        all.orEmpty()
            .filter { it.status == ReelStatus.READY }
            .sortedBy { java.lang.Long.hashCode((it.id * -7046029254386353131L) xor seed) }
    }

    val title = when {
        filter.isEmpty() -> "Feed"
        filter.size == 1 -> "Feed · ${groups.first { it.id in filter }.name}"
        else -> "Feed · ${filter.size} groups"
    }
    val buttons: @Composable () -> Unit = {
        val extra = LocalOffreelColors.current
        IconButton(onClick = { picking = true }) {
            Icon(
                Icons.Rounded.FilterList, contentDescription = "Choose groups",
                tint = if (filter.isEmpty()) Brand.Cream else extra.accent,
            )
        }
        IconButton(onClick = { seed = Random.nextLong() }) {
            Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle again", tint = Brand.Cream)
        }
    }

    if (all != null) {
        if (reels.isEmpty()) {
            EmptyPlayer(
                title = title, modifier = modifier, onBack = null, topEnd = buttons,
                message = if (filter.isEmpty()) {
                    "Your saved reels from every group play here, shuffled. Save a few from Instagram first."
                } else {
                    "No downloaded reels in the groups you picked. Tap the filter button to choose others."
                },
            )
        } else {
            key(seed, filter) {
                ReelPager(reels = reels, modifier = modifier, title = title, topEnd = buttons)
            }
        }
    }

    if (picking) {
        GroupFilterDialog(
            groups = groups,
            initial = filter,
            onDismiss = { picking = false },
            onApply = { picked ->
                picking = false
                scope.launch { store.setFeedGroups(picked) }
            },
        )
    }
}

@Composable
private fun GroupFilterDialog(
    groups: List<GroupEntity>,
    initial: Set<Long>,
    onDismiss: () -> Unit,
    onApply: (Set<Long>) -> Unit,
) {
    val extra = LocalOffreelColors.current
    var picked by remember { mutableStateOf(initial) }
    val colors = CheckboxDefaults.colors(checkedColor = extra.accent, checkmarkColor = extra.onAccent)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play from") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item {
                    FilterRow("All groups", checked = picked.isEmpty(), colors = colors) { picked = emptySet() }
                    HorizontalDivider(color = extra.line, modifier = Modifier.padding(vertical = 4.dp))
                }
                items(groups, key = { it.id }) { g ->
                    FilterRow(g.name, checked = g.id in picked, colors = colors) {
                        picked = if (g.id in picked) picked - g.id else picked + g.id
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(picked) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FilterRow(label: String, checked: Boolean, colors: androidx.compose.material3.CheckboxColors, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, colors = colors)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
