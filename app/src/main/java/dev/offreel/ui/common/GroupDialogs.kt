package dev.offreel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.offreel.ui.theme.Brand
import dev.offreel.ui.theme.LocalOffreelColors

/**
 * Confirms deleting one or more groups and asks what happens to their reels: keep them (they go
 * to Default) or delete them from the phone. Reels that are also in another group always stay.
 */
@Composable
fun DeleteGroupsDialog(
    names: List<String>,
    reelCount: Int,
    onDismiss: () -> Unit,
    onDelete: (keepReels: Boolean) -> Unit,
) {
    val title = if (names.size == 1) "Delete \"${names.first()}\"?" else "Delete ${names.size} groups?"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (reelCount == 0) {
                    Text(if (names.size == 1) "The group is empty." else "These groups are empty.")
                } else {
                    val videos = if (reelCount == 1) "1 video" else "$reelCount videos"
                    Text(
                        "${if (names.size == 1) "It has" else "They have"} $videos inside. Delete only the " +
                            "${if (names.size == 1) "group" else "groups"}, or the videos too? " +
                            "Videos that are also in another group stay there either way."
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { onDelete(true) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text(
                            if (names.size == 1) "Delete only the group (videos move to Default)"
                            else "Delete only the groups (videos move to Default)",
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onDelete(false) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(if (names.size == 1) "Delete the group and its videos" else "Delete the groups and their videos") }
                }
            }
        },
        confirmButton = {
            if (reelCount == 0) {
                TextButton(onClick = { onDelete(false) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The round tick shown on items while selecting several. */
@Composable
fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val extra = LocalOffreelColors.current
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) extra.accent else Brand.Ink.copy(alpha = 0.35f))
            .border(2.dp, if (selected) extra.accent else Brand.Cream, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = extra.onAccent, modifier = Modifier.size(16.dp))
    }
}
