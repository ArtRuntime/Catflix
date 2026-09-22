package com.alex.catflix.settings

import android.content.Context
import androidx.core.content.edit


class AppSettings(context: Context) {

    private val prefs =
        context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_ADBLOCK, value) }


    var autoPip: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PIP, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PIP, value) }


    var backgroundPlayback: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND, false)
        set(value) = prefs.edit { putBoolean(KEY_BACKGROUND, value) }


    var lastUrl: String
        get() = prefs.getString(KEY_LAST_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LAST_URL, value) }


    var darkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK_MODE, true)
        set(value) = prefs.edit { putBoolean(KEY_DARK_MODE, value) }


    var textZoom: Int
        get() = prefs.getInt(KEY_TEXT_ZOOM, 100).coerceIn(50, 200)
        set(value) = prefs.edit { putInt(KEY_TEXT_ZOOM, value.coerceIn(50, 200)) }


    var swipeZoom: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_ZOOM, false)
        set(value) = prefs.edit { putBoolean(KEY_SWIPE_ZOOM, value) }

    /** Keep the screen awake whenever the app is open (not just in playback). */
    var keepAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, false)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_AWAKE, value) }


    var fabX: Float
        get() = prefs.getFloat(KEY_FAB_X, 1f).coerceIn(0f, 1f)
        set(value) = prefs.edit { putFloat(KEY_FAB_X, value.coerceIn(0f, 1f)) }

    var fabY: Float
        get() = prefs.getFloat(KEY_FAB_Y, 1f).coerceIn(0f, 1f)
        set(value) = prefs.edit { putFloat(KEY_FAB_Y, value.coerceIn(0f, 1f)) }


    var volumeBoost: Int
        get() = prefs.getInt(KEY_VOLUME_BOOST, 100).coerceIn(100, 5000)
        set(value) = prefs.edit { putInt(KEY_VOLUME_BOOST, value.coerceIn(100, 5000)) }


    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, true)
        set(value) = prefs.edit { putBoolean(KEY_DESKTOP_MODE, value) }

    companion object {
        private const val PREFS_NAME = "catflix_site_settings"
        private const val KEY_ADBLOCK = "adblock_enabled"
        private const val KEY_AUTO_PIP = "auto_pip"
        private const val KEY_BACKGROUND = "background_playback"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_TEXT_ZOOM = "text_zoom"
        private const val KEY_SWIPE_ZOOM = "swipe_zoom"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_FAB_X = "fab_x"
        private const val KEY_FAB_Y = "fab_y"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_VOLUME_BOOST = "volume_boost"
    }
}
