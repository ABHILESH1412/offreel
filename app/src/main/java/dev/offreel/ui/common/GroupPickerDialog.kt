package dev.offreel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.offreel.graph
import dev.offreel.ui.theme.LocalOffreelColors
import kotlinx.coroutines.launch

/**
 * "Move to…" / "Add to…" picker: every group except [excludeGroupIds], plus "New group".
 * Calls [onPick] with the chosen (or newly created) group's id.
 */
@Composable
fun GroupPickerDialog(
    title: String,
    excludeGroupIds: Set<Long>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val context = LocalContext.current
    val repo = context.graph.repository
    val scope = rememberCoroutineScope()
    val extra = LocalOffreelColors.current
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                val choices = groups.filter { it.id !in excludeGroupIds }
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(choices, key = { it.id }) { g ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(g.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) { Text(g.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium) }
                            Spacer(Modifier.width(12.dp))
                            Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (creating) {
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                        placeholder = { Text("New group name") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { creating = true }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Add, null) }
                        Spacer(Modifier.width(12.dp))
                        Text("New group", style = MaterialTheme.typography.titleMedium, color = extra.muted)
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { scope.launch { onPick(repo.createGroup(name)) } },
                ) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
