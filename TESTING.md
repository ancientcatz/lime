# Testing

This document describes the automated checks that are executed in CI and locally, the NPatch-specific
checks, and the manual verification procedure that requires a device or emulator.

## Automated checks (executed in CI on every push and pull request)

| Check | Tool | Pass criterion |
|-------|------|----------------|
| Gradle configuration valid | `./gradlew --version` | exits 0 |
| Clean build | `./gradlew --warning-mode=all clean` | exits 0, no actionable deprecation warnings |
| Lint (debug) | `./gradlew --warning-mode=all lintDebug` | exits 0, no actionable warnings |
| Unit tests | `./gradlew --warning-mode=all testDebugUnitTest` | exits 0, all tests pass |
| Debug APK | `./gradlew --warning-mode=all assembleDebug` | `app/build/outputs/apk/debug/*.apk` exists and is non-empty |
| Release APK | `./gradlew --warning-mode=all assembleRelease` (with signing env vars on push) | `app/build/outputs/apk/release/*.apk` exists and is non-empty |
| Release APK signed | `apksigner verify --verbose <release.apk>` | `Verified using v1 scheme (JAR signing): false` and `Verified using v2 scheme (APK Signature Scheme v2): true` (or higher) |
| APK archive integrity | `unzip -l <apk>` | lists entries without error |
| Application ID | `aapt2 dump packagename <apk>` (or `aapt dump badging`) | `lsposed.orange` |
| Version | `aapt2 dump badging <apk> | grep versionName` | matches `build.gradle.kts` `versionName` |
| Module entry registered | `unzip -p <apk> META-INF/xposed/java_init.list` | contains `lsposed.orange.hook.OrangeModule` |
| `module.prop` present | `unzip -p <apk> META-INF/xposed/module.prop` | contains `minApiVersion=101` and `targetApiVersion=102` |
| `scope.list` preserves target | `unzip -p <apk> META-INF/xposed/scope.list` | contains `com.seleuco.mame4droid` |
| R8 kept entry class | `aapt2 dump --file resources.pb <apk>` plus `unzip -p <apk> classes.dex \| strings \| grep -c OrangeModule` | entry class name survives minification |
| No legacy Xposed refs in compiled code | `unzip -p <apk> classes*.dex \| strings \| grep -c 'de/robv/android/xposed'` | 0 (or only harmless historical references in resource strings) |
| No signing material in Git diff | `git diff --name-only master..HEAD` | no `.jks`, `.keystore`, `.p12`, `.pem`, `.key` files |
| No signing material in APK | `unzip -l <apk> \| grep -E '\.(jks\|keystore\|p12\|pem\|key)$'` | empty |
| Both APKs in workflow artifacts | `gh run view <run-id> --json artifacts` | two artifacts with the expected names |
| Both APKs in draft release | `gh release view ci-<short-sha> --json assets` | two assets with the expected names |

## NPatch-specific checks (executed as part of the automated inspection)

- `module.prop` declares `minApiVersion=101` and `targetApiVersion=102` — NPatch uses these to
  decide whether to load the module.
- `scope.list` is non-empty and contains exactly `com.seleuco.mame4droid`.
- `java_init.list` contains exactly one fully-qualified class name with no leading/trailing
  whitespace.
- The entry class declared in `java_init.list` extends `io.github.libxposed.api.XposedModule` and
  is reachable in `classes*.dex` after R8 minification.
- The release APK does not contain `de.robv.android.xposed` classes packaged as runtime
  dependencies (the API jar must remain `compileOnly`).
- The settings application declares a launcher activity so NPatch can offer "Open settings" for the
  module.

## Manual verification procedure (requires a device or emulator with NPatch installed)

These checks cannot be executed by CI. The final report will explicitly state which of these were
executed and which still require manual verification.

### Setup

1. Install NPatch Manager on a device or emulator running Android 11 (API 30) or higher.
2. Enable NPatch's module-loading mechanism (Zygote injection or equivalent) per NPatch's
   documentation.
3. Install the debug APK: `adb install orange-ng-v<version>-debug.apk`.
4. Install the release APK on a separate device (or after uninstalling the debug build):
   `adb install orange-ng-v<version>-release.apk`.
5. Open NPatch Manager, enable the Orange-NG module, set its scope to include
   `com.seleuco.mame4droid` (this should be the default since `scope.list` declares it).
6. Reboot the device or restart the target process so NPatch injects the module.

### Test cases

| # | Action | Expected result |
|---|--------|-----------------|
| 1 | Open Orange-NG settings app | The app launches, shows the per-app list, title shows "Orange" without "Not Active" suffix (module is active). |
| 2 | Tap on "MAME4droid" in the app list | A dialog appears with the orientation options. |
| 3 | Select "Landscape" | The list updates to show "L" next to MAME4droid. |
| 4 | Open MAME4droid | The app launches in landscape orientation, regardless of the device's current orientation. |
| 5 | Change the setting to "Portrait" | The list updates to show "P". |
| 6 | Force-stop MAME4droid and reopen it | The app launches in portrait orientation. |
| 7 | Change the setting back to "Unspecified" | The list no longer shows an orientation label for MAME4droid. |
| 8 | Force-stop MAME4droid and reopen it | The app launches in its own default orientation. |
| 9 | Open a different scoped app (if any are added) | The configured orientation is applied. |
| 10 | Disable the Orange-NG module in NPatch, force-stop MAME4droid, reopen it | The app launches in its own default orientation (module is inactive). |
| 11 | Configure an orientation for an app that is NOT in the scope | The configuration is saved but has no effect at runtime (the module is never loaded into that process). |
| 12 | Check `adb logcat` for the `Orange` tag | The module logs `onPackageLoaded: com.seleuco.mame4droid` and `hook <activity class>` lines. No errors. |

### NPatch Remote API fallback check (only if standard service delivery fails)

If `App.mService` is `null` even after NPatch is enabled and the device is rebooted, the standard
libxposed service binder is not being delivered to the settings process. In that case:

1. Record the symptom in `STATUS.md` and `WARNINGS.md`.
2. Add `npatch-remote-api-v1.0.0-release.aar` under `app/libs/`.
3. Add the AAR as a dependency: `implementation(files("libs/npatch-remote-api-v1.0.0-release.aar"))`
   plus `implementation("io.github.libxposed:interface:102.0.0")`.
4. Add a fallback in `SharedConfig.Provider` that uses `NPatchRemoteClient.connectAsync()` when
   `App.mService` is null.
5. Re-run the test cases above and confirm settings now flow to the hooked process.

This fallback is **not** implemented initially. See `API_MIGRATION.md` for the rationale.
