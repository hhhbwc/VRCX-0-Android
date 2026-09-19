package com.vrcx0.android.data

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * The live [AppSettings.Values], readable from any composable.
 *
 * A CompositionLocal rather than parameters because a preference like "hide
 * the device label" is consumed three screens deep, and threading it through
 * every signature would touch files that have no other reason to know.
 * [VrcxRoot] provides the collected value; the default is the data class's own
 * defaults, so previews and tests need no provider.
 */
val LocalAppSettings = staticCompositionLocalOf { AppSettings.Values() }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings"
)

/** How the app chooses between light and dark. */
enum class ThemeMode(val label: String, val id: String) {
    SYSTEM("跟随系统", "system"),
    LIGHT("浅色", "light"),
    DARK("深色", "dark");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }

    /** The dark-theme decision this mode makes for the current system setting. */
    @Composable
    fun isDark(): Boolean = when (this) {
        SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        LIGHT -> false
        DARK -> true
    }
}

/**
 * Preferences that belong to **this device**, not to the account or the server.
 *
 * These are deliberately kept out of the server config store. They describe how
 * this phone should render things -- a tablet and a phone on the same account
 * can reasonably disagree -- and, more importantly, every switch exposed here is
 * one the app actually applies. A settings screen that persists a value nothing
 * reads is worse than no switch at all.
 *
 * Contrast with `ConfigRepository`, which reads the server-side store the
 * desktop also uses. That store's appearance keys are read by the desktop, so
 * offering them here would set a value with no effect on Android.
 */
class AppSettings(private val context: Context) {

    data class Values(
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        /** Material You wallpaper colours (Android 12+). */
        val dynamicColor: Boolean = true,
        /**
         * Drops list entrance/layout animation.
         *
         * Not a cosmetic preference: on the 90 Hz render budget measured here,
         * transitions are what make scrolling feel heavy, so this is the honest
         * escape hatch for a device that struggles.
         */
        val reducedMotion: Boolean = false,
        /** Show the raw `wrld_...:instance` string next to the world name. */
        val showInstanceId: Boolean = false,
        /**
         * Show `username` instead of `displayName` everywhere a person appears.
         *
         * The point is being able to use the app in public without broadcasting
         * who you are talking to -- display names are the recognisable form.
         */
        val hideNicknames: Boolean = false,
        /** Omit events that happened in a private instance from the feed. */
        val hidePrivateFromFeed: Boolean = false,
        /** Hide the platform ("PC"/"Android") on feed rows. */
        val hideDevicesFromFeed: Boolean = false,
        /** "3 分钟前" instead of a clock time. */
        val relativeFeedTime: Boolean = true,
        /** UI language. */
        val language: AppLanguage = AppLanguage.ZH,
        /**
         * IANA zone id used to render absolute timestamps. Empty means "follow
         * the system" -- which is right for most people and costs nothing.
         */
        val timeZoneId: String = "",
        /** GitHub `owner/repo` whose releases carry the Android APK. */
        val updateRepo: String = com.vrcx0.android.update.UpdateChecker.DEFAULT_REPO,
        /** A release the user dismissed with "never remind me". */
        val ignoredUpdateTag: String = ""
    )

    val values: Flow<Values> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val defaults = Values()
            Values(
                themeMode = ThemeMode.fromId(prefs[KEY_THEME_MODE]),
                dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: defaults.dynamicColor,
                reducedMotion = prefs[KEY_REDUCED_MOTION] ?: defaults.reducedMotion,
                showInstanceId = prefs[KEY_SHOW_INSTANCE_ID] ?: defaults.showInstanceId,
                hideNicknames = prefs[KEY_HIDE_NICKNAMES] ?: defaults.hideNicknames,
                hidePrivateFromFeed = prefs[KEY_HIDE_PRIVATE] ?: defaults.hidePrivateFromFeed,
                hideDevicesFromFeed = prefs[KEY_HIDE_DEVICES] ?: defaults.hideDevicesFromFeed,
                relativeFeedTime = prefs[KEY_RELATIVE_TIME] ?: defaults.relativeFeedTime,
                language = AppLanguage.fromId(prefs[KEY_LANGUAGE]),
                timeZoneId = prefs[KEY_TIME_ZONE] ?: defaults.timeZoneId,
                updateRepo = prefs[KEY_UPDATE_REPO] ?: defaults.updateRepo,
                ignoredUpdateTag = prefs[KEY_IGNORED_UPDATE] ?: defaults.ignoredUpdateTag
            )
        }

    suspend fun setLanguage(language: AppLanguage) =
        context.settingsDataStore.edit { it[KEY_LANGUAGE] = language.id }

    suspend fun setTimeZoneId(zoneId: String) =
        context.settingsDataStore.edit { it[KEY_TIME_ZONE] = zoneId }

    suspend fun setIgnoredUpdateTag(tag: String) =
        context.settingsDataStore.edit { it[KEY_IGNORED_UPDATE] = tag }

    suspend fun setThemeMode(mode: ThemeMode) =
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = mode.id }

    suspend fun setDynamicColor(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }

    suspend fun setReducedMotion(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_REDUCED_MOTION] = enabled }

    suspend fun setShowInstanceId(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_SHOW_INSTANCE_ID] = enabled }

    suspend fun setHideNicknames(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_HIDE_NICKNAMES] = enabled }

    suspend fun setHidePrivateFromFeed(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_HIDE_PRIVATE] = enabled }

    suspend fun setHideDevicesFromFeed(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_HIDE_DEVICES] = enabled }

    suspend fun setRelativeFeedTime(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_RELATIVE_TIME] = enabled }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val KEY_SHOW_INSTANCE_ID = booleanPreferencesKey("show_instance_id")
        val KEY_HIDE_NICKNAMES = booleanPreferencesKey("hide_nicknames")
        val KEY_HIDE_PRIVATE = booleanPreferencesKey("hide_private_from_feed")
        val KEY_HIDE_DEVICES = booleanPreferencesKey("hide_devices_from_feed")
        val KEY_RELATIVE_TIME = booleanPreferencesKey("relative_feed_time")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_TIME_ZONE = stringPreferencesKey("time_zone_id")
        val KEY_UPDATE_REPO = stringPreferencesKey("update_repo")
        val KEY_IGNORED_UPDATE = stringPreferencesKey("ignored_update_tag")
    }
}

/** UI language. The [Strings] object keyed by this carries the translations. */
enum class AppLanguage(val label: String, val id: String) {
    ZH("中文", "zh"),
    EN("English", "en"),
    RU("Русский", "ru");

    companion object {
        fun fromId(id: String?): AppLanguage = entries.firstOrNull { it.id == id } ?: ZH
    }
}

/**
 * The name to show for a person, honouring the "hide nicknames" preference.
 *
 * Lives here rather than in each screen so the rule cannot drift: everywhere a
 * person is rendered, display name is the default and username is the opt-out.
 * A record with no username falls back to the display name rather than showing
 * nothing.
 */
fun displayNameFor(
    displayName: String?,
    username: String?,
    hideNicknames: Boolean
): String {
    val display = displayName.orEmpty().trim()
    val user = username.orEmpty().trim()
    if (!hideNicknames) return display.ifBlank { user }
    return user.ifBlank { display }
}
