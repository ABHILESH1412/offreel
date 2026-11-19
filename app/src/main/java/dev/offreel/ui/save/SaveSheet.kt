package dev.offreel.ui.save

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.offreel.data.GroupEntity
import dev.offreel.data.InstagramLink
import dev.offreel.data.SaveResult
import dev.offreel.ui.common.OffreelMark
import dev.offreel.ui.theme.Brand
import dev.offreel.ui.theme.LocalOffreelColors

sealed interface LinkState {
    data object Loading : LinkState
    data class Ready(val link: InstagramLink) : LinkState
    data class Failed(val message: String, val canRetry: Boolean) : LinkState
}

/** State of one save, shared by the overlay over Instagram and the share-sheet activity. */
class SaveUiState(initial: LinkState = LinkState.Loading, canType: Boolean = false) {
    var link by mutableStateOf(initial)
    var author by mutableStateOf<String?>(null)
    /** Set once the reel is saved (to Default, or to the group picked while the link was loading). */
    var saved by mutableStateOf<SaveResult?>(null)
    /** A group tapped before the link arrived; the reel is saved straight there. */
    var pendingGroupId by mutableStateOf<Long?>(null)
    /** A move in progress, then its result ("Moved to Gym"). */
    var movingTo by mutableStateOf<Long?>(null)
    var doneMessage by mutableStateOf<String?>(null)
    /** The user closed the sheet while the link was loading: finish quietly, then close. */
    var closing by mutableStateOf(false)
    /** The user started something (typing a group name): don't auto-close. */
    var keepOpen by mutableStateOf(false)
    /** Typing needs keyboard focus, which the overlay only takes once the link is copied. */
    var canType by mutableStateOf(canType)
}

/**
 * The sheet shown after tapping the offreel button. The reel is saved to Default as soon as its
 * link is known; tapping a group moves it there. Closing never throws a captured reel away.
 * The backdrop is opaque so Instagram's own share sheet, opened underneath, stays out of sight.
 */
@Composable
fun SaveSheet(
    state: SaveUiState,
    /** A still of the reel taken when the button was tapped; drawn behind the sheet instead of black. */
    backdrop: ImageBitmap? = null,
    /** Null while still loading, so "New group" doesn't flash open before the list arrives. */
    groups: List<GroupEntity>?,
    lastGroupId: Long?,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val extra = LocalOffreelColors.current
    val noRipple = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize()) {
        // The still, placed where it was on screen (this window may start below the status bar).
        val view = LocalView.current
        // Lighter while "Saving reel…" shows, so the reel stays recognisable behind the label.
        val dim by animateFloatAsState(if (state.closing) 0.4f else 0.62f, label = "dim")
        Box(
            Modifier
                .fillMaxSize()
                .background(Brand.Ink)
                .drawBehind {
                    if (backdrop != null) {
                        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                        drawImage(backdrop, topLeft = Offset(-loc[0].toFloat(), -loc[1].toFloat()))
                        drawRect(Brand.Ink.copy(alpha = dim))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos -> Log.i("offreel.sheet", "backdrop touched at $pos"); onDismiss() }
                }
                .semantics {
                    onClick(label = "Close") { Log.i("offreel.sheet", "backdrop clicked by an accessibility service"); onDismiss(); true }
                }
        )
        if (state.closing) {
            // Closed while the link was still loading. The screen comes back in under a second;
            // until then say what's happening, so a briefly frozen reel doesn't look broken.
            Row(
                Modifier
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Brand.Surface.copy(alpha = 0.92f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = extra.accent)
                Spacer(Modifier.width(12.dp))
                Text("Saving reel…", style = MaterialTheme.typography.titleMedium, color = Brand.Cream)
            }
            return@Box
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(interactionSource = noRipple, indication = null) { }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(extra.line)
            )
            Spacer(Modifier.height(16.dp))
            Header(state, onRetry)
            Spacer(Modifier.height(16.dp))

            AnimatedContent(targetState = state.doneMessage, label = "done") { done ->
                if (done != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(extra.accent), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, tint = extra.onAccent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(done, style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Column {
                        if (groups != null) {
                            val choices = groups.filter { it.id != state.saved?.groupId }
                            if (choices.isNotEmpty()) {
                                Text(
                                    if (state.saved != null) "Move to" else "Save to",
                                    style = MaterialTheme.typography.labelLarge, color = extra.muted,
                                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                                )
                            }
                            GroupList(state, choices, lastGroupId, onPick)
                            NewGroupRow(state, onCreate = onCreate)
                        } else {
                            Spacer(Modifier.height(120.dp))
                        }
                        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                            Text(if (state.saved != null) "Done" else "Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: SaveUiState, onRetry: () -> Unit) {
    val extra = LocalOffreelColors.current
    val saved = state.saved
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (saved != null) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(extra.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = extra.onAccent, modifier = Modifier.size(18.dp))
            }
        } else {
            OffreelMark(28.dp, ringColor = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(saved?.message() ?: "Save to offreel", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when (val l = state.link) {
                LinkState.Loading -> "Getting the link…"
                is LinkState.Ready -> when {
                    saved != null && !saved.existed -> "Downloading. Tap a group to move it there."
                    else -> state.author?.let { "Reel by $it" } ?: if (l.link.kind == "p") "Post" else "Reel"
                }
                is LinkState.Failed -> l.message
            }
            Text(
                sub, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.link is LinkState.Failed) MaterialTheme.colorScheme.error else extra.muted,
            )
        }
        when (val l = state.link) {
            LinkState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = extra.accent)
            is LinkState.Failed -> if (l.canRetry) {
                TextButton(onClick = onRetry) { Text("Try again", color = MaterialTheme.colorScheme.onSurface) }
            } else {
                Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            }
            is LinkState.Ready -> {}
        }
    }
}

@Composable
private fun GroupList(state: SaveUiState, groups: List<GroupEntity>, lastGroupId: Long?, onPick: (Long) -> Unit) {
    val extra = LocalOffreelColors.current
    val ordered = remember(groups, lastGroupId) {
        groups.sortedWith(compareByDescending<GroupEntity> { it.id == lastGroupId }.thenByDescending { it.isDefault })
    }
    val busy = state.pendingGroupId != null || state.movingTo != null
    LazyColumn(Modifier.heightIn(max = 300.dp)) {
        items(ordered, key = { it.id }) { g ->
            val working = state.pendingGroupId == g.id || state.movingTo == g.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = !busy && state.link !is LinkState.Failed) { onPick(g.id) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) { Text(g.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (working && state.link == LinkState.Loading) {
                        Text("Saving here as soon as the link is ready", style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    } else if (g.id == lastGroupId) {
                        Text("Last used", style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    }
                }
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = extra.accent)
            }
        }
    }
}

@Composable
private fun NewGroupRow(state: SaveUiState, onCreate: (String) -> Unit) {
    val extra = LocalOffreelColors.current
    var open by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val busy = state.pendingGroupId != null || state.movingTo != null

    if (!open) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = !busy) { open = true; state.keepOpen = true }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, null) }
            Spacer(Modifier.width(14.dp))
            Text("New group", style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    LaunchedEffect(open, state.canType) {
        if (open && state.canType) runCatching { focus.requestFocus() }
    }
    val submit = { if (name.isNotBlank() && state.canType && !busy) onCreate(name.trim()) }
    Column(Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            enabled = state.canType && !busy,
            singleLine = true,
            placeholder = { Text(if (state.canType) "Group name, e.g. Gym" else "One moment, getting the link…") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                unfocusedBorderColor = extra.line,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = submit,
            enabled = name.isNotBlank() && state.canType && !busy,
            colors = ButtonDefaults.buttonColors(containerColor = extra.accent, contentColor = extra.onAccent),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (state.saved != null) "Create and move" else "Create and save", fontWeight = FontWeight.SemiBold) }
    }
}
