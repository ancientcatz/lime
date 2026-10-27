package io.github.ancientcatz.lime.hook

import android.app.Activity
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.ancientcatz.lime.BuildConfig
import io.github.ancientcatz.lime.SharedConfig
import io.github.ancientcatz.lime.model.mapActivityOrientation

/**
 * Modern libxposed API 102 entry point for Lime.
 *
 * Replaces the legacy [de.robv.android.xposed.IXposedHookZygoteInit] +
 * [de.robv.android.xposed.IXposedHookLoadPackage] implementation. The framework instantiates this
 * class once per module generation per process. The framework then calls
 * [XposedInterfaceWrapper.attachFramework] to inject the [XposedInterface] base, after which the
 * lifecycle callbacks ([onModuleLoaded], [onPackageLoaded], ...) fire.
 *
 * Behavior preserved from the legacy implementation:
 *  - The module's own settings application (`io.github.ancientcatz.lime`) is never hooked.
 *  - The hook fires on `Activity.attach` (after the original method returns) so the Activity is
 *    fully constructed when we mutate `requestedOrientation`.
 *  - If no configuration exists for the current package, the hook does nothing (the app's own
 *    orientation choice wins).
 *  - Orientation values are mapped through [mapActivityOrientation] exactly as before.
 *
 * Behavior change vs. legacy:
 *  - The legacy `MainFragment.isModuleActive` Java-level hook is removed. In the modern API the
 *    module is never injected into its own settings process (scope is configurable via
 *    NPatch), so that hook never actually ran in production. The modern equivalent — checking
 *    `App.mService != null` — is implemented in [io.github.ancientcatz.lime.ui.main.MainFragment].
 *  - Settings IPC uses [XposedInterface.getRemotePreferences] (read-only in hooked processes)
 *    instead of the legacy `XSharedPreferences` + `MODE_WORLD_READABLE` SharedPreferences, which
 *    has been broken since Android N (targetSdk >= 24).
 */
class LimeModule : XposedModule() {

    companion object {
        private const val TAG = "Lime"
        private const val REMOTE_PREFS_NAME = "lime_config"
    }

    /**
     * Lazy SharedPreferences view over the framework-provided remote preferences. Read-only inside
     * hooked processes. The first access can throw if the framework does not support remote
     * preferences (PROP_CAP_REMOTE not set); we capture that and degrade gracefully.
     */
    private val configFetcher: SharedConfig.Fetcher? by lazy {
        try {
            val prefs = getRemotePreferences(REMOTE_PREFS_NAME)
            SharedConfig.Fetcher(prefs)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Remote preferences unavailable; orientation hook will be inactive", t)
            null
        }
    }

    /**
     * Package name -> configured orientation ordinal. Populated in [onPackageLoaded] so the
     * Activity.attach hook can do a cheap lookup instead of re-reading SharedPreferences on every
     * activity creation.
     */
    private val orientationByPackage = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Volatile
    private var hookInstalled = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "onModuleLoaded: process=${param.processName} isSystemServer=${param.isSystemServer}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // Skip the module's own settings UI process — we never hook ourselves.
        if (param.packageName == BuildConfig.APPLICATION_ID) return
        // Pre-fetch the configured orientation (if any) for this package.
        val orientation = configFetcher?.findConfigApp(param.packageName)?.orientation ?: return
        orientationByPackage[param.packageName] = orientation
        log(Log.DEBUG, TAG, "onPackageLoaded: ${param.packageName} -> orientation $orientation")
        installActivityAttachHook()
    }

    /**
     * Installs the Activity.attach hook exactly once per process. Uses
     * [XposedInterface.ExceptionMode.PROTECTIVE] so any exception thrown by our hooker is caught
     * and logged by the framework rather than crashing the target app.
     */
    private fun installActivityAttachHook() {
        if (hookInstalled) return
        synchronized(this) {
            if (hookInstalled) return
            try {
                val attached = Activity::class.java.declaredMethods.filter { it.name == "attach" }
                if (attached.isEmpty()) {
                    log(Log.WARN, TAG, "Activity.attach method not found; cannot install hook")
                    return
                }
                attached.forEach { method ->
                    hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            // Call the original attach() first so the Activity is fully constructed.
                            chain.proceed()
                            val activity = chain.thisObject as? Activity ?: return@intercept null
                            val packageName = activity.packageName
                            if (packageName == BuildConfig.APPLICATION_ID) return@intercept null
                            val orientation = orientationByPackage[packageName]
                                ?: return@intercept null
                            val mapped = mapActivityOrientation(orientation)
                            log(Log.DEBUG, TAG, "hook ${activity.javaClass.name}: set orientation to $mapped")
                            activity.requestedOrientation = mapped
                            // For void methods, return null per the API contract.
                            null
                        }
                }
                hookInstalled = true
                log(Log.INFO, TAG, "Activity.attach hook installed (${attached.size} overload(s))")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to install Activity.attach hook", t)
            }
        }
    }
}
