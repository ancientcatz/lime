# GitHub Actions Run Log

This file is a chronological record of every GitHub Actions run that is part of the migration.
Each entry records: run ID, triggering commit SHA, trigger event, conclusion, jobs, warnings
investigated, and any fixes pushed.

## Run 40447899202

- **Commit**: `8398753d8dc045046f085bbcace9c4bb22f47b90` (`8398753`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push`
- **Triggered at**: 2026-10-27T04:06:34Z
- **Conclusion**: `failure`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/40447899202

### Jobs
- `build`: failure (15s) — failed at "Set up Android SDK" step.
- `release`: skipped (build failed).

### Logs inspected
- `--log-failed` output showed the `android-actions/setup-android@v3` action mishandling the
  multi-line `packages` input: it joined `platforms;android-37` and `build-tools;37.0.0` with a
  newline and passed the result to `sdkmanager` as a single package name, causing
  `Failed to find package 'platforms;android-37\nbuild-tools;37.0.0'`.

### Warnings found
- `Node.js 20 is deprecated` for actions/checkout@v4, actions/setup-java@v4, android-actions/setup-android@v3.
- `setup-java v4 is deprecated. Please migrate to actions/setup-java@v5.`
- `ubuntu-latest will migrate to Ubuntu 26 beginning October 19, 2026` (informational).

### Fixes applied
- Commit `a194cc4`: replaced `android-actions/setup-android@v3` with a direct `sdkmanager` call
  that passes the three packages as separate arguments. The ubuntu-latest runner ships with
  Android SDK cmdline-tools pre-installed at `/usr/local/lib/android/sdk`, so the third-party
  action was unnecessary.

### Draft release
- None (build failed before reaching the release job).

---

## Run 40448028674

- **Commit**: `a194cc4...` (`a194cc4`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push`
- **Triggered at**: 2026-10-27T04:07:56Z
- **Conclusion**: `failure`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/40448028674

### Jobs
- `build`: failure (20s) — failed at "Set up Android SDK components" step.
- `release`: skipped (build failed).

### Logs inspected
- `--log-failed` output showed `Warning: Failed to find package 'platforms;android-37'`. The
  sdkmanager repository publishes the Android 37 platform as `platforms;android-37.0` (with the
  `.0` suffix), not the unsuffixed `platforms;android-37`.

### Warnings found
- Same Node.js 20 / setup-java v4 / ubuntu-latest deprecation notices as the previous run.

### Fixes applied
- Commit `2871a2f`: changed the package name to `platforms;android-37.0` (with the `.0` suffix)
  to match what the sdkmanager repository actually publishes. This matches the local validation
  environment, where `platforms;android-37.0` was also the correct name.

### Draft release
- None (build failed before reaching the release job).

---

## Run 40448182748

- **Commit**: `2871a2f...` (`2871a2f`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push`
- **Triggered at**: 2026-10-27T04:09:34Z
- **Conclusion**: `failure`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/40448182748

### Jobs
- `build`: failure (57s) — SDK setup passed; failed at "Clean" step.
- `release`: skipped (build failed).

### Logs inspected
- `--log-failed` output showed `Cannot convert '' to File` at app/build.gradle.kts line 39
  (the `storeFile = file(keystoreFilePath)` line inside the release signingConfig block). The
  workflow env vars `ORANGE_KEYSTORE_FILE` etc. were set to `${{ secrets.ORANGE_KEYSTORE_FILE }}`
  etc., but those secrets do not exist (the actual secrets are `ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). The empty-string
  values were then passed to Gradle, where `providers.environmentVariable(...).orNull` returned
  the empty string (not null), so the `if (keystoreFilePath != null && ...)` check passed and
  `file("")` crashed.

### Warnings found
- Same Node.js 20 / setup-java v4 / ubuntu-latest deprecation notices as the previous run.

### Fixes applied
- Commit `e1d0c2a`: exposed the four `ANDROID_*` secrets at the job level; the decode step
  (which runs only on push/workflow_dispatch) decodes `ANDROID_KEYSTORE_BASE64` to a temp file
  and sets the four `ORANGE_*` env vars that Gradle reads. Also hardened the Gradle
  signing-config block to treat both null AND empty-string env vars as "not set" (via
  `takeIf { isNotEmpty() }`), so a workflow that exposes a secret name but not its value
  falls back to an unsigned release build rather than crashing.

### Draft release
- None (build failed before reaching the release job).

---

## Run 40448483874

- **Commit**: `e1d0c2ad6bef7a0993e8249506bba1615cbbf4b1` (`e1d0c2a`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push`
- **Triggered at**: 2026-10-27T04:12:48Z
- **Conclusion**: `success`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/40448483874

### Jobs
- `build`: success (4m22s) — all 18 steps passed.
- `release`: success (11s) — draft release created with both APKs.

### Logs inspected
- Full step-by-step logs inspected. Build produced:
  - `app/build/outputs/apk/debug/app-debug.apk` (9.5 MB)
  - `app/build/outputs/apk/release/app-release.apk` (1.4 MB, signed with v3 scheme)
- Release APK signature verified by `apksigner verify --verbose`:
  `Verifies`, `Verified using v3 scheme (APK Signature Scheme v3): true`.
- Both APKs staged with predictable names and uploaded as workflow artifact `apks-e1d0c2a...`.
- Draft release `ci-e1d0c2ad6bef` created with both APKs attached.

### Warnings found
- `Node.js 20 is deprecated` for actions/cache@v4, actions/checkout@v4, actions/setup-java@v4,
  actions/upload-artifact@v4, actions/download-artifact@v4 (in the release job).
- `setup-java v4 is deprecated. Please migrate to actions/setup-java@v5.`
- `ubuntu-latest will migrate to Ubuntu 26` (informational).

### Fixes applied
- Commit `8084597`: upgraded all actions to Node.js 24-compatible versions
  (checkout v6, setup-java v6, cache v6, upload-artifact v7, download-artifact v8).

### Draft release
- Tag: `ci-e1d0c2ad6bef`
- URL: https://github.com/ancientcatz/Orange-NG/releases (draft, not published)
- Assets:
  - `orange-ng-ve1d0c2ad6bef7a0993e8249506bba1615cbbf4b1-debug.apk` (9,985,885 bytes)
  - `orange-ng-ve1d0c2ad6bef7a0993e8249506bba1615cbbf4b1-release.apk` (1,470,385 bytes)

---

## Run 40449028782

- **Commit**: `80845972943ca939e9ef95dea83f73be112979b4` (`8084597`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push`
- **Triggered at**: 2026-10-27T04:18:51Z
- **Conclusion**: `success`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/40449028782

### Jobs
- `build`: success (2m59s) — all 18 steps passed (faster than the previous run because the
  Gradle cache was warm).
- `release`: success (9s) — draft release created with both APKs.

### Logs inspected
- Full step-by-step logs inspected. Build produced:
  - `app/build/outputs/apk/debug/app-debug.apk` (9.5 MB)
  - `app/build/outputs/apk/release/app-release.apk` (1.4 MB, signed with v3 scheme)
- Release APK signature verified by `apksigner verify --verbose`:
  `Verifies`, `Verified using v3 scheme (APK Signature Scheme v3): true`.
- Both APKs staged with predictable names and uploaded as workflow artifact `apks-8084597...`.
- Draft release `ci-80845972943c` created with both APKs attached.

### Warnings found
- Only the informational `ubuntu-latest will migrate to Ubuntu 26 beginning October 19, 2026`
  notice (no action possible from the workflow side; this is a GitHub-hosted runner image
  lifecycle announcement). All Node.js 20 deprecation warnings are GONE after the action
  upgrades. The `setup-java v4 is deprecated` warning is also GONE (now using v6).

### Fixes applied
- None needed. This is the final successful run.

### Draft release
- Tag: `ci-80845972943c`
- URL: https://github.com/ancientcatz/Orange-NG/releases (draft, not published)
- Assets:
  - `orange-ng-v80845972943ca939e9ef95dea83f73be112979b4-debug.apk` (9,985,885 bytes)
  - `orange-ng-v80845972943ca939e9ef95dea83f73be112979b4-release.apk` (1,470,384 bytes)

---

## Summary

| Run ID | Commit | Conclusion | Duration | Draft release |
|--------|--------|------------|----------|---------------|
| 40447899202 | 8398753 | failure | 19s | — |
| 40448028674 | a194cc4 | failure | 23s | — |
| 40448182748 | 2871a2f | failure | 1m1s | — |
| 40448483874 | e1d0c2a | success | 4m33s | ci-e1d0c2ad6bef |
| 40449028782 | 8084597 | success | 3m8s | ci-80845972943c |

The final successful run (40449028782) corresponds to the actual final implementation commit
(8084597). Both APKs are attached to its draft release. No actionable warnings remain.
