# Warnings

This file records every warning encountered during the migration, its root cause, the fix applied,
and any warnings that remain unresolved with justification.

## Warning policy

Per the task requirements, warnings are fixed rather than suppressed. We do NOT:
- add blanket `@Suppress` annotations,
- disable lint globally,
- use `continue-on-error` to mask failures,
- silence Gradle deprecations by removing `--warning-mode=all`.

If a warning genuinely originates in an upstream component and cannot reasonably be fixed within
this repository, we document the exact warning, its source, the investigation performed, and
why it remains unresolved.

## Warnings encountered and resolved

### Kotlin deprecation: Fragment menu API (compileDebugKotlin)

**Observed in**: CI run 40449028782, `compileDebugKotlin` task output.

**Warning text**:
```
w: MainFragment.kt:35:9 'fun setHasOptionsMenu(p0: Boolean): Unit' is deprecated. Deprecated in Java.
w: MainFragment.kt:98:18 This declaration overrides a deprecated member but is not marked as deprecated itself. Add the '@Deprecated' annotation or suppress the diagnostic.
w: MainFragment.kt:99:15 'fun onCreateOptionsMenu(p0: Menu, p1: MenuInflater): Unit' is deprecated. Deprecated in Java.
w: MainFragment.kt:115:18 This declaration overrides a deprecated member but is not marked as deprecated itself. Add the '@Deprecated' annotation or suppress the diagnostic.
w: MainFragment.kt:124:34 'fun onOptionsItemSelected(p0: MenuItem): Boolean' is deprecated. Deprecated in Java.
```

**Root cause**: `androidx.fragment:fragment` 1.5 deprecated the entire Fragment menu API
(`setHasOptionsMenu`, `onCreateOptionsMenu`, `onOptionsItemSelected`) in favor of the
`MenuProvider` / `MenuHost` API. The `MenuProvider` interface lives in `androidx.core:core`
(was added in 1.8.0). Orange-NG was using `androidx.fragment:fragment-ktx:1.5.1` (already
bumped from 1.4.1 to fix `ObsoleteLintCustomCheck`), so the deprecation surfaced, but
`androidx.core:core-ktx` was still at 1.7.0, which did not yet have `MenuProvider`.

**Fix applied** (commit `3ceeb1c`):
- Bumped `androidx.core:core-ktx` from `1.7.0` to `1.9.0` in `gradle/libs.versions.toml`
  to get the `MenuProvider` interface. 1.9.0 is binary-compatible with the rest of the
  AndroidX stack already in use (fragment-ktx 1.5.1, appcompat 1.4.1) and compiles cleanly
  against compileSdk 37.
- Rewrote `MainFragment.kt`:
  - Removed the `setHasOptionsMenu(true)` call.
  - Removed the `onCreateOptionsMenu` and `onOptionsItemSelected` overrides.
  - Added a private `MenuProvider` that inflates `R.menu.main` and routes item selection.
  - Registered it via `requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner,
    Lifecycle.State.RESUMED)` in `onViewCreated`. The provider is automatically removed
    when `viewLifecycleOwner` reaches `DESTROYED`, so there is no leak.

**Status**: fixed. CI run 40450657195 (after the fix) shows zero `w:` lines in the entire
run log.

---

### ObsoleteLintCustomCheck (lintDebug)

**Observed in**: local validation, `app/build/reports/lint-results-debug.txt`.

**Warning text**:
```
androidx.fragment:fragment:1.4.1 ships a lint.jar compiled against Lint API 11.
The currently running Lint API is 16 (8.7+). The custom checks are therefore
skipped. This is a known bug fixed in androidx.fragment:fragment:1.5.1 and later.
```

**Root cause**: `androidx.fragment:fragment-ktx:1.4.1` transitively pulls in
`androidx.fragment:fragment:1.4.1`, which ships an obsolete `lint.jar` that modern Lint
cannot load. The custom fragment lint checks (FragmentTagUsage, FragmentAddMenuProvider,
FragmentBackPressedCallback, etc.) are silently skipped.

**Fix applied**: bumped `androidx.fragment:fragment-ktx` from `1.4.1` to `1.5.1` in
`gradle/libs.versions.toml`. The 1.5.1 release ships a `lint.jar` compiled against a modern
Lint API, so the custom checks are now loaded and run. The API surface used by Orange-NG
(`FragmentManager.beginTransaction()`, `FragmentTransaction.replace()`, `Fragment.commit()`,
`viewModels<MainViewModel>()`) is stable across 1.4.x and 1.5.x, so no source changes were
needed.

**Status**: fixed.

---

### ExpiredTargetSdkVersion (lintDebug)

**Observed in**: local validation, `app/build/reports/lint-results-debug.txt`.

**Warning text**:
```
Error: Google Play requires that apps target API level 33 or higher. [ExpiredTargetSdkVersion]
        targetSdk = 32
        ~~~~~~~~~~~~~~
```

**Root cause**: Orange-NG's `targetSdk` is 32 (Android 12L). Google Play requires apps distributed
via the Play Store to target API level 33 or higher. Lint enforces this as an error by default.

**Fix applied**: disabled the `ExpiredTargetSdkVersion` lint check in `app/build.gradle.kts` with
a documented justification. The justification is:
- The task brief explicitly says: "Do not increase target SDK or change runtime behavior without
  investigating the consequences."
- Bumping `targetSdk` to 33+ would change runtime behavior (notification runtime permissions,
  granular media permissions, predictive back gesture, etc.) and is therefore out of scope for
  this migration.
- The module is distributed via GitHub Releases (not Google Play), so the Google Play target-API
  requirement does not apply.
- This is a targeted, justified lint suppression — NOT a global lint disable.

**Status**: resolved (suppressed with justification; not a global suppression).

---

### Node.js 20 deprecation (GitHub Actions)

**Observed in**: CI runs 40447899202, 40448028674, 40448182748, 40448483874.

**Warning text**:
```
Node.js 20 is deprecated. The following actions target Node.js 20 but are being
forced to run on Node.js 24: actions/cache@v4, actions/checkout@v4,
actions/setup-java@v4, actions/upload-artifact@v4, actions/download-artifact@v4.
```

**Root cause**: the v4 versions of these actions were built against Node.js 20, which is being
deprecated on GitHub-hosted runners. The actions still work (they are forced to run on Node.js 24)
but emit a deprecation warning.

**Fix applied**: commit `8084597` upgraded all five actions to Node.js 24-compatible major
versions:
- `actions/checkout` v4 → v6
- `actions/setup-java` v4 → v6
- `actions/cache` v4 → v6
- `actions/upload-artifact` v4 → v7
- `actions/download-artifact` v4 → v8

**Status**: fixed. Run 40449028782 (after the upgrade) shows no Node.js 20 deprecation warnings.

---

### setup-java v4 deprecation (GitHub Actions)

**Observed in**: CI runs 40447899202 through 40448483874.

**Warning text**:
```
setup-java v4 is deprecated and will no longer receive updates. Please migrate
to actions/setup-java@v5.
```

**Root cause**: `actions/setup-java@v4` is end-of-life. The action still works but will not
receive updates.

**Fix applied**: same commit `8084597` upgraded to `actions/setup-java@v6` (the latest stable
major version, which is well past the v5 minimum recommended by the deprecation warning).

**Status**: fixed. Run 40449028782 shows no setup-java deprecation warning.

---

### AndroidGradlePluginVersion / GradleDependency (lintDebug, informational)

**Observed in**: local validation, `app/build/reports/lint-results-debug.txt`.

**Warning text**:
```
Warning: A newer version of Gradle than 9.5.1 is available: 9.8.2 [AndroidGradlePluginVersion]
Warning: A newer version of com.android.application than 9.2.1 is available: 9.4.1 [AndroidGradlePluginVersion]
Warning: A newer version of androidx.core:core-ktx than 1.7.0 is available: 1.19.1 [GradleDependency]
Warning: A newer version of androidx.appcompat:appcompat than 1.4.1 is available: 1.8.0 [GradleDependency]
Warning: A newer version of androidx.fragment:fragment-ktx than 1.5.1 is available: 1.9.1 [GradleDependency]
Warning: A newer version of androidx.preference:preference-ktx than 1.2.0 is available: 1.2.1 [GradleDependency]
Warning: A newer version of androidx.constraintlayout:constraintlayout than 2.1.3 is available: 2.2.2 [GradleDependency]
Warning: A newer version of androidx.recyclerview:recyclerview than 1.2.1 is available: 1.4.0 [GradleDependency]
```

**Root cause**: these are informational "newer version available" warnings, not errors. They
indicate that newer versions of Gradle, AGP, and the AndroidX libraries exist.

**Fix applied**: none. The task brief explicitly says: "Use mutually compatible, supported
toolchain versions. Verify compatibility with the actual dependencies instead of independently
upgrading every version to its latest release." The current versions are:
- AGP 9.2.1 + Gradle 9.5.1 + Kotlin 2.0.21 + compileSdk 37 — these match the official libxposed
  example module exactly, so dependency compatibility is validated by upstream.
- AndroidX libraries (core-ktx 1.7.0, appcompat 1.4.1, fragment-ktx 1.5.1, preference-ktx 1.2.0,
  constraintlayout 2.1.3, recyclerview 1.2.1) — these are the original Orange-NG versions
  (except fragment-ktx which was bumped to 1.5.1 to fix ObsoleteLintCustomCheck). Preserving
  them minimizes behavioral changes per the brief.

**Status**: unresolved (intentional). Upgrading these would require re-validating the entire
app for behavioral compatibility, which is out of scope for this migration. The brief
explicitly permits sticking with mutually-compatible versions rather than chasing the latest
release of every dependency.

---

## Warnings remaining (informational, no action possible)

### ubuntu-latest runner image migration notice

**Observed in**: all CI runs.

**Warning text**:
```
The ubuntu-latest label will migrate to Ubuntu 26 beginning October 19, 2026.
For more information, see https://github.com/actions/runner-images/issues/14748
```

**Root cause**: GitHub is migrating the `ubuntu-latest` runner image from Ubuntu 24.04 to
Ubuntu 26. This is a GitHub-hosted runner image lifecycle announcement, not a workflow
configuration issue.

**Investigation**: the workflow does not pin to a specific Ubuntu version (it uses
`runs-on: ubuntu-latest`), so the migration will happen automatically. The Android SDK setup
step installs `platforms;android-37.0` and `build-tools;37.0.0` via `sdkmanager`, which works
the same on Ubuntu 24.04 and Ubuntu 26. No workflow changes are needed.

**Status**: unresolved (no action possible from the workflow side; this is a runner image
lifecycle announcement).

---

### ExpiredTargetSdkVersion — RESOLVED by bumping targetSdk to 33

**Update (commit 32c0bab)**: The user explicitly requested targeting Android 13+, so
`targetSdk` was bumped from 32 to 33. This satisfies Google Play's minimum target-API
requirement, so the `ExpiredTargetSdkVersion` lint check now passes naturally and the
`disable += "ExpiredTargetSdkVersion"` rule has been removed from `app/build.gradle.kts`.

Runtime behavior implications of targetSdk 33 (all verified safe for this app):
- `POST_NOTIFICATIONS` runtime permission: the app does not post notifications, no impact.
- Granular media permissions: the app does not read media files, no impact.
- Predictive back gesture: opt-in via `android:enableOnBackInvokedCallback`; the app does
  not declare it, so default behavior is preserved.
- `NEARBY_WIFI_DEVICES` / `BODY_SENSORS_BACKGROUND` / `ACTIVITY_RECOGNITION`: not used.

**Status**: resolved (targetSdk 33, lint check passes naturally, no suppression needed).

**Previous status** (for historical reference): the lint check was previously disabled
with a documented justification because targetSdk was intentionally preserved at 32 per
the original migration brief. The user's explicit request to target Android 13+ supersedes
that decision.
