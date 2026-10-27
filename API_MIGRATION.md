# Legacy API Inventory → Modern libxposed API 102 Replacements

## Legacy entry-point registration

| Legacy | Modern replacement |
|--------|--------------------|
| `app/src/main/assets/xposed_init` containing `lsposed.orange.hook.Hook` | `app/src/main/resources/META-INF/xposed/java_init.list` containing `lsposed.orange.hook.OrangeModule` |
| AndroidManifest `<meta-data android:name="xposedmodule" android:value="true" />` | Removed. Replaced by `META-INF/xposed/module.prop`. |
| AndroidManifest `<meta-data android:name="xposedminversion" android:value="93" />` | `module.prop` field `minApiVersion=101`. |
| (no equivalent) | `module.prop` field `targetApiVersion=102`. |
| AndroidManifest `<meta-data android:name="xposeddescription" android:value="@string/module_description" />` | `module.prop` field `description=Specifying orientation per app`. |
| AndroidManifest `<meta-data android:name="xposedscope" android:resource="@array/module_scope" />` | `META-INF/xposed/scope.list` containing `com.seleuco.mame4droid` (one package per line). |
| `<category android:name="de.robv.android.xposed.category.MODULE_SETTINGS" />` on the settings activity | Removed. NPatch auto-detects the launcher activity for module settings. |
| `compileOnly("de.robv.android.xposed:api:82")` and `:82:sources` | `compileOnly("io.github.libxposed:api:102.0.0")` (module side) + `implementation("io.github.libxposed:service:102.0.0")` (settings-app side). |
| `maven(url = "https://api.xposed.info")` in `settings.gradle.kts` | Removed. libxposed artifacts are on Maven Central. |

## Legacy hooking API → Modern hooking API

| Legacy (de.robv.android.xposed) | Modern (io.github.libxposed.api) |
|----------------------------------|----------------------------------|
| `class Hook : IXposedHookZygoteInit, IXposedHookLoadPackage` | `class OrangeModule : XposedModule()` |
| `override fun initZygote(startupParam: StartupParam)` | `override fun onModuleLoaded(param: ModuleLoadedParam)` — called once per process the module is loaded into. |
| `override fun handleLoadPackage(lpparam: LoadPackageParam)` | `override fun onPackageLoaded(param: PackageLoadedParam)` — `param.packageName`, `param.defaultClassLoader`, `param.isFirstPackage`. |
| `XposedBridge.hookAllMethods(Activity::class.java, "attach", XC_MethodHook)` | `hook(Method).intercept { chain -> ... }` — find the `Activity.attach` method via reflection, then install a single intercept. |
| `object : XC_MethodHook() { override fun afterHookedMethod(param: MethodHookParam) { ... } }` | `.intercept { chain -> chain.proceed(); /* after */ }` — proceed first, then mutate `thisObject.requestedOrientation`. |
| `param.thisObject as Activity` | `chain.thisObject as Activity` |
| `AndroidAppHelper.currentApplication()` | `param.packageName` (we already know which package is loaded; we look up its config directly) |
| `XposedBridge.log("$TAG: $msg")` | `log(Log.DEBUG, TAG, msg)` |
| `XposedHelpers.findAndHookMethod(className, classLoader, methodName, XC_MethodReplacement)` | `hook(Method).intercept { _ -> return@intercept ... }` — but this specific use case (forcing `MainFragment.isModuleActive` to `true`) is **removed** because the modern module is never injected into its own settings process (scope does not include the module's own package). Modern "module active" detection uses `App.mService != null`. |

## Legacy settings IPC → Modern settings IPC

| Legacy | Modern replacement |
|--------|--------------------|
| `SharedConfig.Provider` writes `MODE_WORLD_READABLE` SharedPreferences | Writes through `XposedService.getRemotePreferences("orange_config")` (when the service binder is available via `XposedServiceHelper.registerListener()` in the module's `Application`). Falls back to local private prefs when NPatch is unavailable, so the UI keeps working as a configuration editor. |
| `SharedConfig.Fetcher` reads `XSharedPreferences(applicationId)` | Receives a `SharedPreferences` instance obtained from `XposedInterface.getRemotePreferences("orange_config")` inside the module's `onPackageLoaded`. |
| `<dataDir>/configs.properties` (Properties file mirroring prefs) | Migrated to remote preferences on first run after upgrade. File is then deleted so we don't carry two sources of truth. |

The legacy `MODE_WORLD_READABLE` SharedPreferences mechanism is **already broken** in Orange-NG
(targetSdk 32, `MODE_WORLD_READABLE` throws `SecurityException` since Android N when targetSdk >= 24).
The legacy code catches the exception and falls back to `MODE_PRIVATE`, which means the hooked process
cannot read the config without LSPosed-specific prefs rebinding. The modern remote-preferences path
fixes this properly.

## Legacy hook behavior to preserve

1. **Scope**: only `com.seleuco.mame4droid` (preserved in `scope.list`).
2. **Filter**: skip the module's own application ID (`lsposed.orange`) so the hook never runs in the
   settings process.
3. **Trigger**: install the hook on `Activity.attach` so it fires for every activity of every
   scoped process.
4. **Action**: look up the current package's configured orientation; if present, set
   `activity.requestedOrientation` to the mapped value.
5. **Mapping** (preserved verbatim from `model/Orientation.kt`):
   - `UNSPECIFIED` → `SCREEN_ORIENTATION_UNSPECIFIED`
   - `PORTRAIT` → `SCREEN_ORIENTATION_PORTRAIT`
   - `LANDSCAPE` → `SCREEN_ORIENTATION_LANDSCAPE`
   - `REVERSE_LANDSCAPE` → `SCREEN_ORIENTATION_REVERSE_LANDSCAPE`
   - `SENSOR_LANDSCAPE` → `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`
   - `SENSOR` → `SCREEN_ORIENTATION_SENSOR`
6. **Missing config**: do nothing (do not override the app's own orientation choice).
7. **Error handling**: wrap the hook body in try/catch so a missing method or a classloader issue
   does not crash the target process. Log via `log(Log.ERROR, TAG, msg, throwable)`.

## NPatch Remote API integration decision

**Decision**: do NOT add `npatch-remote-api-v1.0.0-release.aar` initially.

**Rationale**:
- NPatch is a fork of LSPosed and supports the standard libxposed service contract.
- The NPatch Remote API README explicitly states: "Modules should prefer the standard libxposed
  `XposedServiceHelper.registerListener(...)` path and use `NPatchRemoteClient` only when standard
  service delivery is unavailable."
- The standard `io.github.libxposed:service:102.0.0` library gives us `XposedServiceHelper`,
  `XposedService`, `getRemotePreferences(...)`, and `openRemoteFile(...)` — everything Orange-NG
  needs for settings IPC.
- Adding the NPatch SDK preemptively would couple us to a vendor-specific fallback path that we do
  not yet need.

**Re-evaluation trigger**: if device testing under NPatch Local mode shows that
`XposedServiceHelper.registerListener()` does not deliver a binder to the settings process, we will
add `npatch-remote-api-v1.0.0-release.aar` under `app/libs/` and integrate `NPatchRemoteClient` as
a fallback, mirroring the pattern shown in the NPatch Remote API README. That decision will be
recorded here if it happens.

## R8 / ProGuard rules

Apply the official libxposed rules verbatim (from
https://github.com/libxposed/api/blob/master/README.md):

```proguard
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
```

Plus the project-specific keep for the data model used by kotlinx-serialization:

```proguard
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class lsposed.orange.model.**$$serializer { *; }
-keepclassmembers class lsposed.orange.model.** {
    *** Companion;
}
-keepclasseswithmembers class lsposed.orange.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
```

The existing `-keep class lsposed.orange.** { *; }` rule is replaced by the more targeted rules
above so R8 can still optimize the non-model classes (matching the example's
`isMinifyEnabled = true` + `isShrinkResources = true` release configuration).
