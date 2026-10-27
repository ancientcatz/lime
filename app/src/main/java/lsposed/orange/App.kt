package lsposed.orange

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lsposed.orange.model.ConfigApp
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.Volatile

/**
 * Orange-NG Application entry point.
 *
 * Registers itself as an [XposedServiceHelper.OnServiceListener] so the module's settings process
 * can acquire the [XposedService] binder from the framework (NPatch in Local mode, or LSPosed).
 *
 * The [XposedService] binder is the modern, framework-agnostic replacement for the legacy
 * `XSharedPreferences` + `MODE_WORLD_READABLE` SharedPreferences combination (which has been
 * broken since Android N when targetSdk >= 24). When the binder is available, settings written in
 * this process are mirrored into the framework's remote preferences under the group name
 * [REMOTE_PREFS_NAME], where [lsposed.orange.hook.OrangeModule] reads them inside hooked target
 * processes via [io.github.libxposed.api.XposedInterface.getRemotePreferences].
 *
 * Per the libxposed/service documentation, the [XposedProvider] inside this library is Direct Boot
 * aware. We therefore keep `onCreate` and the service listener callbacks safe to run before the
 * user unlocks the device: we only touch the (already-existing) local SharedPreferences and the
 * (framework-managed) remote preferences, never credential-protected storage directly.
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        const val TAG = "OrangeApp"
        const val REMOTE_PREFS_NAME = "orange_config"
        const val KEY_CONFIG_APPS = "config_apps"
        internal const val DEFAULT_JSON_EMPTY_ARRAY = "[]"

        @Volatile
        @JvmStatic
        var mService: XposedService? = null
            private set

        @Volatile
        @JvmStatic
        private var appInstance: App? = null

        private val serviceStateListeners = CopyOnWriteArraySet<ServiceStateListener>()

        private fun dispatchServiceState(listener: ServiceStateListener, service: XposedService?) {
            if (serviceStateListeners.contains(listener)) {
                listener.onServiceStateChanged(service)
            }
        }

        @JvmStatic
        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            serviceStateListeners.add(listener)
            if (notifyImmediately) {
                dispatchServiceState(listener, mService)
            }
        }

        @JvmStatic
        fun removeServiceStateListener(listener: ServiceStateListener) {
            serviceStateListeners.remove(listener)
        }

        /**
         * Push the local SharedPreferences into the framework's remote preferences so the hooked
         * target process can read the configuration via
         * [io.github.libxposed.api.XposedInterface.getRemotePreferences]. Safe to call from any
         * thread; no-op if the service binder is not currently held.
         */
        @JvmStatic
        fun syncRemoteConfig() {
            val service = mService ?: return
            val app = appInstance ?: return
            try {
                val remotePrefs = service.getRemotePreferences(REMOTE_PREFS_NAME)
                val localPrefs = app.getSharedPreferences("${app.packageName}_preferences", MODE_PRIVATE)
                val localConfig = localPrefs.getString(KEY_CONFIG_APPS, DEFAULT_JSON_EMPTY_ARRAY)
                    ?: DEFAULT_JSON_EMPTY_ARRAY
                // Validate JSON before writing — we don't want to push a corrupt value into remote prefs.
                val parsed = runCatching {
                    Json.decodeFromString<List<ConfigApp>>(localConfig)
                }.getOrNull() ?: run {
                    Log.e(TAG, "syncRemoteConfig: local config JSON is invalid; pushing empty array instead")
                    emptyList()
                }
                val normalized = Json.encodeToString(parsed)
                remotePrefs.edit()
                    .putString(KEY_CONFIG_APPS, normalized)
                    .apply()
                Log.d(TAG, "syncRemoteConfig: pushed ${parsed.size} config entries to remote prefs")
            } catch (t: Throwable) {
                Log.e(TAG, "syncRemoteConfig: failed to sync local prefs to remote prefs", t)
            }
        }
    }

    private fun notifyServiceStateChanged(service: XposedService?) {
        for (listener in serviceStateListeners) {
            dispatchServiceState(listener, service)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appInstance = this
        Log.i(TAG, "onCreate: registering XposedServiceHelper listener")
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Log.i(TAG, "onServiceBind: framework=${service.frameworkName} api=${service.apiVersion}")
        mService = service
        notifyServiceStateChanged(mService)
        // Push the local config into the framework's remote store so the hooked process can read it.
        syncRemoteConfig()
    }

    override fun onServiceDied(service: XposedService) {
        Log.w(TAG, "onServiceDied: framework service binder died")
        mService = null
        notifyServiceStateChanged(mService)
    }

    override fun onTerminate() {
        super.onTerminate()
        appInstance = null
    }

    /**
     * Notified whenever the [XposedService] binder is acquired or lost. Used by
     * [lsposed.orange.ui.main.MainFragment] to refresh the "module active" indicator in the title.
     */
    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }
}
