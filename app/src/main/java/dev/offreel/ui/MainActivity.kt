package dev.offreel.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.offreel.graph
import dev.offreel.update.UpdateWorker
import dev.offreel.update.Updates
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dev.offreel.ui.feed.FeedScreen
import dev.offreel.ui.home.HomeScreen
import dev.offreel.ui.group.GroupScreen
import dev.offreel.ui.settings.SettingsScreen
import dev.offreel.ui.theme.LocalOffreelColors
import dev.offreel.ui.theme.OffreelTheme
import dev.offreel.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        Updates.appVisible = true
        lifecycleScope.launch { UpdateWorker.checkSoon(this@MainActivity, graph.settings.current()) }
    }

    override fun onPause() {
        Updates.appVisible = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            OffreelTheme(settings?.theme ?: ThemeMode.DARK) {
                OffreelRoot()
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.GridView),
    FEED("Feed", Icons.Rounded.Shuffle),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
private fun OffreelRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var openGroup by rememberSaveable { mutableStateOf<Long?>(null) }

    openGroup?.let { id ->
        BackHandler { openGroup = null }
        GroupScreen(groupId = id, onBack = { openGroup = null })
        return
    }
    BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }

    val extra = LocalOffreelColors.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onBackground,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unselectedIconColor = extra.muted,
                            unselectedTextColor = extra.muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val mod = Modifier.padding(bottom = padding.calculateBottomPadding())
        when (tab) {
            Tab.HOME -> HomeScreen(modifier = mod, onOpenGroup = { openGroup = it })
            Tab.FEED -> FeedScreen(modifier = mod)
            Tab.SETTINGS -> SettingsScreen(modifier = mod)
        }
    }
}
