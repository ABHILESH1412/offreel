package dev.offreel.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.offreel.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class Quality(val label: String, val shortSide: Int?) {
    DATA_SAVER("720p", 720),
    HD("1080p", 1080),
    BEST("Best", null),
}

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val quality: Quality = Quality.HD,
    val wifiOnly: Boolean = false,
    val buttonEnabled: Boolean = true,
    /** Floating button position: vertical fraction of the screen and which edge it sits on. */
    val buttonY: Float = 0.40f,
    val buttonOnLeft: Boolean = false,
    /** The group picked last time; long-pressing the button saves straight there. */
    val lastGroupId: Long? = null,
    val ytdlpUpdatedAt: Long = 0,
    /** Group screen layout: grid of thumbnails, or a list with captions. */
    val groupGrid: Boolean = true,
    /** Copy every finished download into the gallery (Movies/offreel) too. */
    val autoGallery: Boolean = false,
    /** Groups the Feed plays from; empty means all groups. */
    val feedGroupIds: Set<Long> = emptySet(),
    /** Download and install new GitHub releases automatically. */
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,
)

class SettingsStore(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val quality = stringPreferencesKey("quality")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val buttonEnabled = booleanPreferencesKey("button_enabled")
        val buttonY = floatPreferencesKey("button_y")
        val buttonOnLeft = booleanPreferencesKey("button_left")
        val lastGroupId = longPreferencesKey("last_group")
        val ytdlpUpdatedAt = longPreferencesKey("ytdlp_updated_at")
        val groupGrid = booleanPreferencesKey("group_grid")
        val autoGallery = booleanPreferencesKey("auto_gallery")
        val feedGroups = stringPreferencesKey("feed_groups")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings() = Settings(
        theme = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
        quality = this[K.quality]?.let { runCatching { Quality.valueOf(it) }.getOrNull() } ?: Quality.HD,
        wifiOnly = this[K.wifiOnly] ?: false,
        buttonEnabled = this[K.buttonEnabled] ?: true,
        buttonY = this[K.buttonY] ?: 0.40f,
        buttonOnLeft = this[K.buttonOnLeft] ?: false,
        lastGroupId = this[K.lastGroupId],
        ytdlpUpdatedAt = this[K.ytdlpUpdatedAt] ?: 0,
        groupGrid = this[K.groupGrid] ?: true,
        autoGallery = this[K.autoGallery] ?: false,
        feedGroupIds = this[K.feedGroups]?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet(),
        autoUpdate = this[K.autoUpdate] ?: true,
        lastUpdateCheck = this[K.lastUpdateCheck] ?: 0,
    )

    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setQuality(v: Quality) = context.dataStore.edit { it[K.quality] = v.name }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[K.wifiOnly] = v }
    suspend fun setButtonEnabled(v: Boolean) = context.dataStore.edit { it[K.buttonEnabled] = v }
    suspend fun setButtonPosition(y: Float, onLeft: Boolean) = context.dataStore.edit {
        it[K.buttonY] = y.coerceIn(0.08f, 0.85f)
        it[K.buttonOnLeft] = onLeft
    }
    suspend fun resetButtonPosition() = context.dataStore.edit {
        it.remove(K.buttonY); it.remove(K.buttonOnLeft)
    }
    suspend fun setLastGroup(id: Long) = context.dataStore.edit { it[K.lastGroupId] = id }
    suspend fun setAutoUpdate(v: Boolean) = context.dataStore.edit { it[K.autoUpdate] = v }
    suspend fun setLastUpdateCheck(t: Long) = context.dataStore.edit { it[K.lastUpdateCheck] = t }
    suspend fun setAutoGallery(v: Boolean) = context.dataStore.edit { it[K.autoGallery] = v }
    suspend fun setFeedGroups(ids: Set<Long>) = context.dataStore.edit { it[K.feedGroups] = ids.joinToString(",") }
    suspend fun setGroupGrid(v: Boolean) = context.dataStore.edit { it[K.groupGrid] = v }
    suspend fun setYtdlpUpdatedAt(t: Long) = context.dataStore.edit { it[K.ytdlpUpdatedAt] = t }
}
