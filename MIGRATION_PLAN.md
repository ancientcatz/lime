# Migration Plan

## Goal

Modernize `ancientcatz/Orange-NG` (an Xposed module that pins screen orientation per app) so that:

1. It targets the modern libxposed API `io.github.libxposed:api:102.0.0` instead of the legacy
   `de.robv.android.xposed:api:82`.
2. It runs correctly under NPatch (an LSPosed fork) in Local mode.
3. The original per-app orientation behavior is preserved exactly.
4. The build toolchain is upgraded to a mutually compatible, supported combination that can consume
   libxposed API 102 and (if needed later) the NPatch Remote API SDK.
5. A GitHub Actions CI/CD pipeline builds, validates, signs, and publishes both debug and release
   APKs as a draft GitHub Release for every pushed implementation commit.
6. Every commit is monitored through to a final workflow conclusion, and actionable warnings are
   fixed rather than suppressed.

## Phase breakdown

### Phase 1 — Inspection (DONE)

Inspected every file under the repository. Key findings are captured in `API_MIGRATION.md`.

### Phase 2 — Migrate to libxposed API 102.0.0

- Add `compileOnly("io.github.libxposed:api:102.0.0")` and `implementation("io.github.libxposed:service:102.0.0")`.
- Remove `compileOnly("de.robv.android.xposed:api:82")` and the `:sources` variant.
- Delete `app/src/main/assets/xposed_init`.
- Create `app/src/main/resources/META-INF/xposed/{java_init.list,module.prop,scope.list}`.
- Replace `lsposed.orange.hook.Hook` (legacy `IXposedHookZygoteInit` + `IXposedHookLoadPackage`)
  with `lsposed.orange.hook.OrangeModule` extending `io.github.libxposed.api.XposedModule`.
  - Move the `Activity.attach` hook into `onPackageLoaded` using `hook(method).intercept { chain -> ... }`.
  - Skip the module's own application ID (preserves legacy behavior).
  - Look up the per-app orientation via `getRemotePreferences("orange_config")` instead of `XSharedPreferences`.
  - Remove the `MainFragment.isModuleActive` Java-level hook. In the modern API the module is not
    injected into its own settings process (scope is `com.seleuco.mame4droid` only), so the legacy
    hook never actually ran in production. The modern equivalent is `App.mService != null`, where
    `mService` is the `XposedService` binder acquired via `XposedServiceHelper.registerListener()`
    in the module settings process.
- Update `SharedConfig`:
  - `Fetcher` (hooked process) reads from a `SharedPreferences` instance passed in by the module
    (which obtained it via `XposedInterface.getRemotePreferences("orange_config")`).
  - `Provider` (settings process) writes through `XposedService.getRemotePreferences("orange_config")`
    when the service binder is available, and falls back to local storage when NPatch is unavailable
    so the UI keeps working as a configuration editor even outside the framework.
  - On first run after upgrade, migrate the legacy `configs.properties` file (if present) into the
    remote preferences so existing users keep their per-app orientation settings.
- Update `AndroidManifest.xml`:
  - Remove `xposedmodule`, `xposedminversion`, `xposeddescription`, `xposedscope` meta-data.
  - Remove the `de.robv.android.xposed.category.MODULE_SETTINGS` category from the activity
    intent-filter (NPatch auto-detects the launcher activity; the legacy category is unused by the
    modern API).
  - Keep the launcher activity-alias and the `QUERY_ALL_PACKAGES` permission (the app list needs it).
- Apply the official R8 rules from the libxposed README:
  ```
  -dontwarn io.github.libxposed.annotation.**
  -adaptresourcefilecontents META-INF/xposed/java_init.list
  -keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
      public <init>();
  }
  ```

### Phase 3 — NPatch Remote API integration decision (DONE — decision: NOT NEEDED initially)

Per the NPatch Remote API README itself: "Modules should prefer the standard libxposed
`XposedServiceHelper.registerListener(...)` path and use `NPatchRemoteClient` only when standard
service delivery is unavailable." NPatch is an LSPosed fork and supports the standard libxposed
service contract. We therefore use the standard path (`io.github.libxposed:service:102.0.0`).

If device testing later shows that NPatch does not deliver the XposedService binder to the settings
process, we will revisit and add `npatch-remote-api-v1.0.0-release.aar` under `app/libs/` and
integrate `NPatchRemoteClient` as a fallback. That decision and any future integration will be
documented in `API_MIGRATION.md`.

### Phase 4 — Modernize the build toolchain

Adopt the same versions validated by the official libxposed example (so dependency compatibility is
guaranteed by upstream):

| Component | Old | New |
|-----------|-----|-----|
| AGP | 7.1.3 | 9.2.1 |
| Gradle | 7.3.3 | 9.5.1 |
| Kotlin | 1.9.0 | 2.0.21 |
| compileSdk | 32 | 37 |
| buildToolsVersion | (default) | 37.0.0 |
| Java/Kotlin target | 1.8 | 21 |
| minSdk | 30 | 30 (preserved) |
| targetSdk | 32 | 32 (preserved — changing targetSdk has runtime behavior implications) |
| Version catalog | none | `gradle/libs.versions.toml` |

Other build changes:
- Add `packaging { resources { merges += "META-INF/xposed/*"; excludes += "**" } }` so the
  META-INF/xposed module declaration files are merged rather than duplicated and so other
  duplicate META-INF resources don't break the build.
- Remove the legacy `maven(url = "https://api.xposed.info")` repository (no longer needed; libxposed
  API and service are on Maven Central).
- Move the signing configuration for the release build to be driven by environment variables
  (`ORANGE_KEYSTORE_FILE`, `ORANGE_KEYSTORE_PASSWORD`, `ORANGE_KEY_ALIAS`, `ORANGE_KEY_PASSWORD`)
  so the workflow can sign with a temporary keystore without embedding secrets in Gradle.
- Enable `lint.abortOnError = true` and `checkReleaseBuilds = false` to match the example.

### Phase 5 — GitHub Actions CI/CD

Workflow file: `.github/workflows/build.yml`. Triggers:
- `push` to `feat/npatch-libxposed-102` (full pipeline including signing and draft release).
- `workflow_dispatch` (full pipeline including signing and draft release).
- `pull_request` to `feat/npatch-libxposed-102` (build + lint + test only; NO signing secrets, NO
  draft release — protects against untrusted PR code).

Jobs:
1. `build` — checkout exact commit, setup JDK 21, setup Android SDK (compileSdk 37, build-tools
   37.0.0), validate Gradle wrapper, run `clean`, `lintDebug`, `testDebugUnitTest`, `assembleDebug`,
   `assembleRelease` with signing env vars, verify both APKs exist and are nonempty, verify the
   release APK is signed, upload both APKs as workflow artifacts.
2. `release` — depends on `build`, runs only on push to the working branch, creates or updates a
   draft GitHub Release tagged `ci-<short-sha>` with title `Orange-NG CI Build <short-sha>`,
   attaches both APKs with descriptive names, and includes branch + full SHA + build info in the
   release notes. A rerun for the same commit updates the same draft release rather than creating
   a duplicate.

Permissions are granted at the job level on a least-privilege basis. The `release` job gets
`contents: write`; the `build` job gets only the defaults. Signing secrets are only referenced
inside the `build` job and only when the triggering event is a push or workflow_dispatch (never
for pull_request).

### Phase 6 — Temporary release signing

Generate a temporary keystore locally with `keytool` using strong random passwords. Store it as
`ANDROID_KEYSTORE_BASE64` (Base64-encoded), plus `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_PASSWORD` as GitHub Actions secrets via `gh secret set` (Base64 piped via stdin so
the value never appears in the process list or shell history). Delete the local keystore and a
zeroed password file after the secrets are configured.

The workflow decodes the keystore into a temporary runner file, passes the four values to Gradle
via environment variables, and cleans up the temporary keystore in a `post:` step even on failure.

Caveat documented in `BUILD_AND_CI.md`: this is a temporary development identity. Changing signing
identities later prevents in-place upgrade of an installed APK.

### Phase 7 — Strict Actions monitoring

After every implementation push, use `gh run list`, `gh run view`, and `gh run watch` to follow the
run to a final conclusion. Inspect full logs (not just the green/red status) for warnings,
deprecations, and annotations. Record every run in `ACTIONS_LOG.md`. Fix actionable warnings
rather than suppressing them. Repeat the push → watch → fix cycle until the build is clean and the
final successful run corresponds to the actual final commit.

### Phase 8 — Test and verify

Automated checks performed locally and in CI: `lintDebug`, `testDebugUnitTest`, `assembleDebug`,
`assembleRelease`, APK presence and non-empty, release APK signature verification, APK archive
inspection, application ID and version verification, presence of `META-INF/xposed/java_init.list`,
`module.prop` with `targetApiVersion=102`, `scope.list` containing `com.seleuco.mame4droid`,
R8 has kept the entry class, no `de.robv.android.xposed` references in compiled code, no signing
material in the Git diff or APK.

NPatch-specific checks: confirm `module.prop` `minApiVersion=101` / `targetApiVersion=102` matches
NPatch's expected module recognition, confirm `scope.list` preserves the original target, confirm
the entry class is registered in `java_init.list`.

Manual verification: documented procedure in `TESTING.md` covering installing both APKs, enabling
the module in NPatch, configuring orientation for `com.seleuco.mame4droid`, restarting the target,
confirming the orientation is applied, and confirming behavior when the module is disabled. These
checks require a physical device or emulator with NPatch installed; the automated CI cannot execute
them, so they are explicitly marked as "requires manual verification" in the final report.

### Phase 9 — Final review

Diff the entire implementation branch against `master`. Confirm no accidental behavior changes, no
unnecessary dependencies, no duplicate versions, no incomplete migration, no missing resources, no
incorrect package names, no missing R8 rules, no unnecessary permissions, no sensitive files, no
signing secrets in config, no generated build outputs in Git, no incorrect workflow branch filters,
no CI workflows that can touch `master`, no documentation on the implementation branch, and that
the README accurately describes the modern implementation.

Run the full local validation suite one final time. Push the final implementation commit. Poll the
final workflow run to completion. Inspect the full logs one last time. Only declare done when the
final successful run corresponds to the actual final commit and both APKs are in its draft release.
