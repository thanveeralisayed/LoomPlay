package com.loomplay.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.LibrarySortOrder
import com.loomplay.core.model.MpvHwDecMode
import com.loomplay.core.model.NetworkStreamEntry
import com.loomplay.core.model.SubtitleStyleConfig
import com.loomplay.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "loomplay_settings")

@Serializable
private data class SerializedStreamEntry(
    val url: String,
    val title: String = "",
    val headers: Map<String, String> = emptyMap(),
    val preferredEngine: String = EnginePreference.AUTO.name,
)

data class UserPreferences(
    val defaultEngine: EnginePreference = EnginePreference.AUTO,
    val autoFallbackEnabled: Boolean = true,
    val mpvHwDecMode: MpvHwDecMode = MpvHwDecMode.AUTO,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val amoledDarkMode: Boolean = false,
    val dynamicColorEnabled: Boolean = false,
    val showContinueWatching: Boolean = true,
    val minimalInterface: Boolean = true,
    val defaultPlaybackSpeed: Float = 1.0f,
    val seekStepSeconds: Int = 10,
    val longPressBoostSpeed: Float = 2.0f,
    val pinchToZoomEnabled: Boolean = true,
    val swipeGesturesEnabled: Boolean = true,
    val horizontalSeekGestureEnabled: Boolean = true,
    val pipAutoEnter: Boolean = true,
    val backgroundAudioEnabled: Boolean = false,
    val autoPlayNextEnabled: Boolean = true,
    val defaultSubtitleDelayMs: Long = 0L,
    val autoDetectSidecarSubtitles: Boolean = true,
    val subtitleFontSizeSp: Float = 20f,
    val subtitleOutlineEnabled: Boolean = true,
    val subtitleBgOpacity: Float = 0.45f,
    val subtitleMarginPercent: Int = 8,
    val mpvAssOverrideEnabled: Boolean = false,
    val mpvCustomConfig: String = "",
    val librarySortOrder: LibrarySortOrder = LibrarySortOrder.DATE_ADDED,
    val savedStreams: List<NetworkStreamEntry> = emptyList(),
) {
    val subtitleStyleConfig: SubtitleStyleConfig
        get() = SubtitleStyleConfig(
            fontSizeSp = subtitleFontSizeSp,
            outlineEnabled = subtitleOutlineEnabled,
            backgroundOpacity = subtitleBgOpacity,
            verticalMarginPercent = subtitleMarginPercent,
            mpvAssOverride = mpvAssOverrideEnabled,
        )
}

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val DEFAULT_ENGINE = stringPreferencesKey("default_engine")
        val AUTO_FALLBACK = booleanPreferencesKey("auto_fallback")
        val MPV_HWDEC = stringPreferencesKey("mpv_hwdec")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AMOLED_DARK_MODE = booleanPreferencesKey("amoled_dark_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SHOW_CONTINUE_WATCHING = booleanPreferencesKey("show_continue_watching")
        val MINIMAL_INTERFACE = booleanPreferencesKey("minimal_interface")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val SEEK_STEP_SECONDS = intPreferencesKey("seek_step_seconds")
        val LONG_PRESS_BOOST = floatPreferencesKey("long_press_boost")
        val PINCH_TO_ZOOM = booleanPreferencesKey("pinch_to_zoom")
        val SWIPE_GESTURES = booleanPreferencesKey("swipe_gestures")
        val HORIZONTAL_SEEK_GESTURE = booleanPreferencesKey("horizontal_seek_gesture")
        val PIP_AUTO_ENTER = booleanPreferencesKey("pip_auto_enter")
        val BACKGROUND_AUDIO_ENABLED = booleanPreferencesKey("background_audio_enabled")
        val LEGACY_BACKGROUND_MODE = stringPreferencesKey("background_playback_mode")
        val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        val SUBTITLE_DELAY_MS = longPreferencesKey("subtitle_delay_ms")
        val AUTO_DETECT_SIDECARS = booleanPreferencesKey("auto_detect_sidecars")
        val SUBTITLE_FONT_SIZE_SP = floatPreferencesKey("subtitle_font_size_sp")
        val SUBTITLE_OUTLINE = booleanPreferencesKey("subtitle_outline")
        val SUBTITLE_BG_OPACITY = floatPreferencesKey("subtitle_bg_opacity")
        val SUBTITLE_MARGIN_PERCENT = intPreferencesKey("subtitle_margin_percent")
        val MPV_ASS_OVERRIDE = booleanPreferencesKey("mpv_ass_override")
        val MPV_CUSTOM_CONFIG = stringPreferencesKey("mpv_custom_config")
        val LIBRARY_SORT_ORDER = stringPreferencesKey("library_sort_order")
        val LEGACY_RECENT_STREAM_URLS = stringPreferencesKey("recent_stream_urls")
        val SAVED_STREAMS_JSON = stringPreferencesKey("saved_streams_json")
    }

    val userPreferences: Flow<UserPreferences> = context.userPrefsDataStore.data.map { prefs ->
        val legacyUrls = prefs[Keys.LEGACY_RECENT_STREAM_URLS]
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val parsedStreams = prefs[Keys.SAVED_STREAMS_JSON]?.let { encoded ->
            runCatching {
                json.decodeFromString<List<SerializedStreamEntry>>(encoded).map {
                    NetworkStreamEntry(
                        url = it.url,
                        title = it.title,
                        headers = it.headers,
                        preferredEngine = runCatching { EnginePreference.valueOf(it.preferredEngine) }
                            .getOrDefault(EnginePreference.AUTO),
                    )
                }
            }.getOrNull()
        } ?: legacyUrls.map { NetworkStreamEntry(url = it) }

        val bgEnabled = prefs[Keys.BACKGROUND_AUDIO_ENABLED]
            ?: (prefs[Keys.LEGACY_BACKGROUND_MODE] == "ALWAYS")

        UserPreferences(
            defaultEngine = prefs[Keys.DEFAULT_ENGINE]?.let {
                runCatching { EnginePreference.valueOf(it) }.getOrNull()
            } ?: EnginePreference.AUTO,
            autoFallbackEnabled = prefs[Keys.AUTO_FALLBACK] ?: true,
            mpvHwDecMode = prefs[Keys.MPV_HWDEC]?.let {
                runCatching { MpvHwDecMode.valueOf(it) }.getOrNull()
            } ?: MpvHwDecMode.AUTO,
            themeMode = prefs[Keys.THEME_MODE]?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrNull()
            } ?: ThemeMode.DARK,
            amoledDarkMode = prefs[Keys.AMOLED_DARK_MODE] ?: false,
            dynamicColorEnabled = prefs[Keys.DYNAMIC_COLOR] ?: false,
            showContinueWatching = prefs[Keys.SHOW_CONTINUE_WATCHING] ?: true,
            minimalInterface = prefs[Keys.MINIMAL_INTERFACE] ?: true,
            defaultPlaybackSpeed = (prefs[Keys.DEFAULT_SPEED] ?: 1.0f).coerceIn(0.25f, 3.0f),
            seekStepSeconds = prefs[Keys.SEEK_STEP_SECONDS] ?: 10,
            longPressBoostSpeed = (prefs[Keys.LONG_PRESS_BOOST] ?: 2.0f).coerceIn(1.25f, 4.0f),
            pinchToZoomEnabled = prefs[Keys.PINCH_TO_ZOOM] ?: true,
            swipeGesturesEnabled = prefs[Keys.SWIPE_GESTURES] ?: true,
            horizontalSeekGestureEnabled = prefs[Keys.HORIZONTAL_SEEK_GESTURE] ?: true,
            pipAutoEnter = prefs[Keys.PIP_AUTO_ENTER] ?: true,
            backgroundAudioEnabled = bgEnabled,
            autoPlayNextEnabled = prefs[Keys.AUTO_PLAY_NEXT] ?: true,
            defaultSubtitleDelayMs = prefs[Keys.SUBTITLE_DELAY_MS] ?: 0L,
            autoDetectSidecarSubtitles = prefs[Keys.AUTO_DETECT_SIDECARS] ?: true,
            subtitleFontSizeSp = (prefs[Keys.SUBTITLE_FONT_SIZE_SP] ?: 20f).coerceIn(12f, 36f),
            subtitleOutlineEnabled = prefs[Keys.SUBTITLE_OUTLINE] ?: true,
            subtitleBgOpacity = (prefs[Keys.SUBTITLE_BG_OPACITY] ?: 0.45f).coerceIn(0f, 1f),
            subtitleMarginPercent = (prefs[Keys.SUBTITLE_MARGIN_PERCENT] ?: 8).coerceIn(2, 20),
            mpvAssOverrideEnabled = prefs[Keys.MPV_ASS_OVERRIDE] ?: false,
            mpvCustomConfig = prefs[Keys.MPV_CUSTOM_CONFIG] ?: "",
            librarySortOrder = prefs[Keys.LIBRARY_SORT_ORDER]?.let {
                runCatching { LibrarySortOrder.valueOf(it) }.getOrNull()
            } ?: LibrarySortOrder.DATE_ADDED,
            savedStreams = parsedStreams,
        )
    }

    suspend fun setDefaultEngine(engine: EnginePreference) {
        context.userPrefsDataStore.edit { it[Keys.DEFAULT_ENGINE] = engine.name }
    }

    suspend fun setMpvHwDecMode(mode: MpvHwDecMode) {
        context.userPrefsDataStore.edit { it[Keys.MPV_HWDEC] = mode.name }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.userPrefsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setAmoledDarkMode(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.AMOLED_DARK_MODE] = enabled }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setShowContinueWatching(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.SHOW_CONTINUE_WATCHING] = enabled }
    }

    suspend fun setMinimalInterface(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.MINIMAL_INTERFACE] = enabled }
    }

    suspend fun setSwipeGesturesEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.SWIPE_GESTURES] = enabled }
    }

    suspend fun setHorizontalSeekGestureEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.HORIZONTAL_SEEK_GESTURE] = enabled }
    }

    suspend fun setAutoDetectSidecarSubtitles(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.AUTO_DETECT_SIDECARS] = enabled }
    }

    suspend fun setSubtitleMarginPercent(marginPercent: Int) {
        context.userPrefsDataStore.edit { it[Keys.SUBTITLE_MARGIN_PERCENT] = marginPercent.coerceIn(2, 20) }
    }

    suspend fun setAutoFallbackEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.AUTO_FALLBACK] = enabled }
    }

    suspend fun setDefaultPlaybackSpeed(speed: Float) {
        context.userPrefsDataStore.edit { it[Keys.DEFAULT_SPEED] = speed.coerceIn(0.25f, 3.0f) }
    }

    suspend fun setDefaultSubtitleDelayMs(delayMs: Long) {
        context.userPrefsDataStore.edit { it[Keys.SUBTITLE_DELAY_MS] = delayMs.coerceIn(-10_000L, 10_000L) }
    }

    suspend fun setSeekStepSeconds(seconds: Int) {
        context.userPrefsDataStore.edit { it[Keys.SEEK_STEP_SECONDS] = seconds }
    }

    suspend fun setLongPressBoostSpeed(speed: Float) {
        context.userPrefsDataStore.edit { it[Keys.LONG_PRESS_BOOST] = speed }
    }

    suspend fun setPinchToZoomEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.PINCH_TO_ZOOM] = enabled }
    }

    suspend fun setPipAutoEnter(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.PIP_AUTO_ENTER] = enabled }
    }

    suspend fun setBackgroundAudioEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit {
            it[Keys.BACKGROUND_AUDIO_ENABLED] = enabled
            it.remove(Keys.LEGACY_BACKGROUND_MODE)
        }
    }

    suspend fun setAutoPlayNextEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.AUTO_PLAY_NEXT] = enabled }
    }

    suspend fun setSubtitleFontSizeSp(sizeSp: Float) {
        context.userPrefsDataStore.edit { it[Keys.SUBTITLE_FONT_SIZE_SP] = sizeSp.coerceIn(12f, 36f) }
    }

    suspend fun setSubtitleOutlineEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.SUBTITLE_OUTLINE] = enabled }
    }

    suspend fun setSubtitleBgOpacity(opacity: Float) {
        context.userPrefsDataStore.edit { it[Keys.SUBTITLE_BG_OPACITY] = opacity.coerceIn(0f, 1f) }
    }

    suspend fun setMpvAssOverrideEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.MPV_ASS_OVERRIDE] = enabled }
    }

    suspend fun setMpvCustomConfig(config: String) {
        context.userPrefsDataStore.edit { it[Keys.MPV_CUSTOM_CONFIG] = config }
    }

    suspend fun setLibrarySortOrder(order: LibrarySortOrder) {
        context.userPrefsDataStore.edit { it[Keys.LIBRARY_SORT_ORDER] = order.name }
    }

    suspend fun saveNetworkStream(
        url: String,
        title: String = "",
        headers: Map<String, String> = emptyMap(),
        preferredEngine: EnginePreference = EnginePreference.AUTO,
    ) {
        val cleanedUrl = url.trim()
        if (cleanedUrl.isEmpty()) return
        val cleanedHeaders = headers
            .mapKeys { it.key.trim() }
            .mapValues { it.value.trim() }
            .filterKeys { it.isNotEmpty() }
            .filterValues { it.isNotEmpty() }

        context.userPrefsDataStore.edit { prefs ->
            val existing = prefs[Keys.SAVED_STREAMS_JSON]?.let { encoded ->
                runCatching { json.decodeFromString<List<SerializedStreamEntry>>(encoded) }.getOrNull()
            } ?: emptyList()
            val newEntry = SerializedStreamEntry(
                url = cleanedUrl,
                title = title.trim(),
                headers = cleanedHeaders,
                preferredEngine = preferredEngine.name,
            )
            val updatedStreams = (listOf(newEntry) + existing.filterNot { it.url == cleanedUrl }).take(15)
            prefs[Keys.SAVED_STREAMS_JSON] = json.encodeToString(updatedStreams)
            prefs.remove(Keys.LEGACY_RECENT_STREAM_URLS)
        }
    }

    suspend fun removeNetworkStream(url: String) {
        val cleaned = url.trim()
        context.userPrefsDataStore.edit { prefs ->
            val existing = prefs[Keys.SAVED_STREAMS_JSON]?.let { encoded ->
                runCatching { json.decodeFromString<List<SerializedStreamEntry>>(encoded) }.getOrNull()
            } ?: emptyList()
            prefs[Keys.SAVED_STREAMS_JSON] = json.encodeToString(existing.filterNot { it.url == cleaned })
            prefs.remove(Keys.LEGACY_RECENT_STREAM_URLS)
        }
    }

    suspend fun clearNetworkStreams() {
        context.userPrefsDataStore.edit { prefs ->
            prefs.remove(Keys.LEGACY_RECENT_STREAM_URLS)
            prefs.remove(Keys.SAVED_STREAMS_JSON)
        }
    }
}
