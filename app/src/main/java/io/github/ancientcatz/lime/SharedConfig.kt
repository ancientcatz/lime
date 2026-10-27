package io.github.ancientcatz.lime

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.ancientcatz.lime.model.ConfigApp
import java.io.File
import java.util.Properties

internal const val KEY_CONFIG_APPS = "config_apps"
internal const val DEFAULT_JSON_EMPTY_ARRAY = "[]"

/**
 * Shared configuration access for Lime.
 *
 * Two concrete subclasses:
 *  - [Fetcher] is used inside hooked target processes. It reads from a
 *    [SharedPreferences] obtained via [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 *    (read-only inside hooked processes).
 *  - [Provider] is used inside the module's own settings app. It writes to a local MODE_PRIVATE
 *    [SharedPreferences] (the same one [androidx.preference.PreferenceManager] uses by default),
 *    then triggers [App.syncRemoteConfig] so the change is mirrored into the framework's remote
 *    preferences where the hooked process can read it.
 *
 * Legacy compatibility: on first run after upgrade, [Provider] migrates the contents of the
 * pre-migration `<dataDir>/configs.properties` file (the old Properties-based storage that
 * existed because `MODE_WORLD_READABLE` has been broken since Android N when targetSdk >= 24)
 * into the local SharedPreferences, then deletes the legacy file so we don't carry two sources of
 * truth forward.
 */
sealed class SharedConfig {

    protected val configApps by lazy {
        Json.decodeFromString<List<ConfigApp>>(getConfigAppsJSON())
            .associateBy { it.packageName }
            .toMutableMap()
    }

    protected abstract fun getConfigAppsJSON(): String

    fun findConfigApp(packageName: String) = configApps[packageName]

    /**
     * Read-only config view used inside hooked target processes. The [SharedPreferences] instance
     * is supplied by [io.github.ancientcatz.lime.hook.LimeModule] from
     * [io.github.libxposed.api.XposedInterface.getRemotePreferences].
     */
    class Fetcher(private val prefs: SharedPreferences) : SharedConfig() {
        override fun getConfigAppsJSON(): String =
            prefs.getString(KEY_CONFIG_APPS, DEFAULT_JSON_EMPTY_ARRAY) ?: DEFAULT_JSON_EMPTY_ARRAY
    }

    /**
     * Read/write config view used inside the module's own settings app. Writes go to the local
     * MODE_PRIVATE SharedPreferences (which [androidx.preference.PreferenceManager] also uses),
     * then [App.syncRemoteConfig] is triggered to mirror the change into the framework's remote
     * preferences for the hooked process to read.
     */
    class Provider(context: Context) : SharedConfig() {

        private val prefs: SharedPreferences =
            context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

        private val keyHideIcon = context.getString(R.string.key_setting_hide_icon)
        private val keyShowSystemApps = context.getString(R.string.key_setting_show_system_apps)

        init {
            migrateLegacyPropertiesFile(context)
        }

        /**
         * One-shot migration from the pre-migration `<dataDir>/configs.properties` Properties file
         * into the local SharedPreferences. Deletes the legacy file on success so we don't run
         * this again. Safe to call on every [Provider] construction: it's a no-op once the legacy
         * file is gone.
         */
        private fun migrateLegacyPropertiesFile(context: Context) {
            val legacyFile = File(context.dataDir, "configs.properties")
            if (!legacyFile.exists()) return
            try {
                val properties = Properties().apply { legacyFile.inputStream().use { load(it) } }
                val legacyJson = properties.getProperty(KEY_CONFIG_APPS, DEFAULT_JSON_EMPTY_ARRAY)
                val legacyHideIcon = properties.getProperty(keyHideIcon, "false").toBoolean()
                val legacyShowSystemApps = properties.getProperty(keyShowSystemApps, "false").toBoolean()
                prefs.edit {
                    // Only write entries that aren't already present in the local prefs, so we don't
                    // overwrite a configuration the user has already touched in the new app.
                    if (prefs.getString(KEY_CONFIG_APPS, null) == null) {
                        putString(KEY_CONFIG_APPS, legacyJson)
                    }
                    if (!prefs.contains(keyHideIcon)) {
                        putBoolean(keyHideIcon, legacyHideIcon)
                    }
                    if (!prefs.contains(keyShowSystemApps)) {
                        putBoolean(keyShowSystemApps, legacyShowSystemApps)
                    }
                }
                if (legacyFile.delete()) {
                    Log.i("LimeConfig", "Migrated legacy configs.properties into SharedPreferences; legacy file deleted")
                } else {
                    Log.w("LimeConfig", "Migrated legacy configs.properties but failed to delete the legacy file")
                }
            } catch (t: Throwable) {
                Log.e("LimeConfig", "Failed to migrate legacy configs.properties; leaving it in place", t)
            }
        }

        override fun getConfigAppsJSON(): String =
            prefs.getString(KEY_CONFIG_APPS, DEFAULT_JSON_EMPTY_ARRAY) ?: DEFAULT_JSON_EMPTY_ARRAY

        /**
         * Persist a generic preference change. Called by
         * [io.github.ancientcatz.lime.ui.main.MainViewModel.onSharedPreferenceChanged]. Triggers a remote
         * sync so the change is visible to the hooked process.
         */
        fun savePref(key: String) {
            // The local SharedPreferences has already been updated by the framework's
            // PreferenceFragmentCompat; we only need to mirror it to the remote store.
            App.syncRemoteConfig()
        }

        fun addConfigApp(packageName: String, orientation: Int) {
            configApps[packageName] = ConfigApp(packageName, orientation)
            saveConfigApps()
        }

        fun removeConfigApp(packageName: String) {
            configApps.remove(packageName)
            saveConfigApps()
        }

        private fun saveConfigApps() {
            val json = Json.encodeToString(configApps.values.toList())
            prefs.edit { putString(KEY_CONFIG_APPS, json) }
            App.syncRemoteConfig()
        }
    }
}
