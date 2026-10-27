# Orange-NG

Orange-NG is an [Xposed](https://github.com/libxposed/api) module that lets you specify screen
orientation on a per-app basis. It is useful for apps that need a forced orientation to give the
best experience (for example, forcing MAME4droid into landscape).

This is the NPatch-compatible fork of the original Orange module, migrated to the modern
libxposed API 102.

## Target platform

| Platform        | Min API | Notes |
|:----------------|:-------:|:------|
| Android         | 30      | minSdk preserved from the original. |
| libxposed API   | 101     | `module.prop` declares `minApiVersion=101` and `targetApiVersion=102`. |
| Framework       | NPatch (LSPosed fork) or any libxposed API 102 compatible framework. |

## Module scope

The module ships with `META-INF/xposed/scope.list` containing exactly:

```
com.seleuco.mame4droid
```

This is the original module scope, preserved verbatim. To extend the module to additional apps,
edit `app/src/main/resources/META-INF/xposed/scope.list` (one package name per line) and rebuild.

## How it works

1. The framework (NPatch in Local mode, or any libxposed API 102 compatible framework) loads
   `lsposed.orange.hook.OrangeModule` into every process whose package appears in `scope.list`.
2. `OrangeModule.onPackageLoaded` reads the configured orientation for the loaded package from the
   framework's remote preferences (`getRemotePreferences("orange_config")`).
3. `OrangeModule` installs an `Activity.attach` hook with `ExceptionMode.PROTECTIVE`. After the
   original `attach()` returns, the hook sets `Activity.requestedOrientation` to the configured
   value. If no configuration exists for the package, the hook does nothing — the app's own
   orientation choice wins.
4. The settings UI (launched from the home screen) writes the per-app configuration to a local
   `SharedPreferences`. The custom `App` Application class registers an `XposedServiceHelper`
   listener; when the framework delivers the `XposedService` binder, the local config is mirrored
   into the framework's remote preferences where the hooked process can read it.

This replaces the legacy `XSharedPreferences` + `MODE_WORLD_READABLE` SharedPreferences mechanism,
which has been broken since Android N (targetSdk >= 24) without LSPosed-specific prefs rebinding.

## Building

Requirements:
- JDK 21
- Android SDK with `platforms;android-37` and `build-tools;37.0.0`
- AGP 9.2.1 / Gradle 9.5.1 / Kotlin 2.0.21 (declared in `gradle/libs.versions.toml`)

Local build (debug):
```
./gradlew --warning-mode=all clean assembleDebug
```

Local build (release, unsigned — for local validation only):
```
./gradlew --warning-mode=all assembleRelease
```

Signed release build (requires a keystore provided via environment variables, as the CI does):
```
ORANGE_KEYSTORE_FILE=/path/to/keystore.jks \
ORANGE_KEYSTORE_PASSWORD=... \
ORANGE_KEY_ALIAS=... \
ORANGE_KEY_PASSWORD=... \
./gradlew --warning-mode=all assembleRelease
```

## CI/CD

GitHub Actions workflow at `.github/workflows/build.yml` builds, lints, tests, and signs both
debug and release APKs on every push to `feat/npatch-libxposed-102`. Both APKs are attached to a
draft GitHub Release tagged `ci-<short-sha>` so each commit has its own testable artifacts.

## License

GNU General Public License v3. See [LICENSE](LICENSE).

<!-- Trigger: verify git-cliff compares against the previous ci-* tag -->
