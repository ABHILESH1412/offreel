package dev.offreel.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.offreel.data.GroupEntity
import dev.offreel.data.InstagramLink
import dev.offreel.data.LinkParser
import dev.offreel.graph
import dev.offreel.ui.save.LinkState
import dev.offreel.ui.save.SaveSheet
import dev.offreel.ui.save.SaveUiState
import dev.offreel.ui.theme.OffreelTheme
import dev.offreel.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Backup way in: Instagram → Share → offreel. Works without the accessibility service too.
 * The reel is saved to Default at once; tapping a group moves it there.
 */
class ShareActivity : ComponentActivity() {
    private lateinit var link: InstagramLink
    private lateinit var state: SaveUiState

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val text = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        link = LinkParser.parse(text) ?: run {
            Toast.makeText(this, "No Instagram reel or post link found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        state = SaveUiState(LinkState.Ready(link), canType = true)
        lifecycleScope.launch {
            state.saved = runCatching { graph.repository.save(link) }.getOrNull()
            if (state.saved == null) {
                Toast.makeText(this@ShareActivity, "Couldn't save this one", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val groups: List<GroupEntity>? by graph.repository.groups.collectAsStateWithLifecycle(initialValue = null)
            OffreelTheme(settings?.theme ?: ThemeMode.DARK) {
                SaveSheet(
                    state = state,
                    groups = groups,
                    lastGroupId = settings?.lastGroupId,
                    onPick = ::move,
                    onCreate = { name -> lifecycleScope.launch { move(graph.repository.createGroup(name)) } },
                    onRetry = {},
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun move(groupId: Long) {
        val saved = state.saved ?: return
        if (state.movingTo != null) return
        state.movingTo = groupId
        lifecycleScope.launch {
            val name = graph.repository.moveToGroup(saved.reelId, if (saved.existed) null else saved.groupId, groupId)
            graph.settings.setLastGroup(groupId)
            state.doneMessage = if (saved.existed) "Added to $name" else "Moved to $name"
            delay(900)
            finish()
        }
    }
}
