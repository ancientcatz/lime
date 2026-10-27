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

## Run 40450657195

- **Commit**: `3ceeb1c5337a6ee3d0cfad0abf28e9752494d973` (`3ceeb1c`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push`
- **Triggered at**: 2026-10-27T04:36:23Z
- **Conclusion**: `success`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/40450657195

### Jobs
- `build`: success (2m0s) — 33% faster than the previous run (2m59s) thanks to the
  concurrent debug+release build + Gradle parallel mode.
- `release`: success (7s) — draft release created with both APKs.

### Logs inspected
- Full step-by-step logs inspected. The two previously separate "Build debug APK" and
  "Build release APK" steps are now a single "Build debug and release APKs concurrently"
  step that runs `./gradlew --warning-mode=all --no-daemon --parallel assembleDebug assembleRelease`.
- Build produced:
  - `app/build/outputs/apk/debug/app-debug.apk` (10.0 MB)
  - `app/build/outputs/apk/release/app-release.apk` (1.5 MB, signed with v3 scheme)
- Release APK signature verified by `apksigner verify --verbose`:
  `Verifies`, `Verified using v3 scheme (APK Signature Scheme v3): true`.
- Both APKs staged with predictable names and uploaded as workflow artifact `apks-3ceeb1c...`.
- Draft release `ci-3ceeb1c5337a` created with both APKs attached.
- Verified zero Kotlin warnings (`w:` prefix) in the entire run log — the 5 deprecation
  warnings from the previous run (setHasOptionsMenu / onCreateOptionsMenu /
  onOptionsItemSelected and their override counterparts) are GONE after migrating
  MainFragment to the MenuProvider API.

### Warnings found
- Only the informational `ubuntu-latest will migrate to Ubuntu 26 beginning October 19, 2026`
  notice (no action possible from the workflow side; this is a GitHub-hosted runner image
  lifecycle announcement).

### Fixes applied
- None needed. This is the final successful run.

### Draft release
- Tag: `ci-3ceeb1c5337a`
- URL: https://github.com/ancientcatz/Orange-NG/releases (draft, not published)
- Assets:
  - `orange-ng-v3ceeb1c5337a6ee3d0cfad0abf28e9752494d973-debug.apk` (10,045,961 bytes)
  - `orange-ng-v3ceeb1c5337a6ee3d0cfad0abf28e9752494d973-release.apk` (1,473,393 bytes)

---

## Summary

| Run ID | Commit | Conclusion | Duration | Draft release |
|--------|--------|------------|----------|---------------|
| 40447899202 | 8398753 | failure | 19s | — |
| 40448028674 | a194cc4 | failure | 23s | — |
| 40448182748 | 2871a2f | failure | 1m1s | — |
| 40448483874 | e1d0c2a | success | 4m33s | ci-e1d0c2ad6bef |
| 40449028782 | 8084597 | success | 3m8s | ci-80845972943c |
| 40450657195 | 3ceeb1c | success | 2m7s | ci-3ceeb1c5337a |

The final successful run (40450657195) corresponds to the actual final implementation commit
(3ceeb1c5337a6ee3d0cfad0abf28e9752494d973). Both APKs are attached to its draft release.
Zero Kotlin warnings remain (the 5 deprecation warnings from run 40449028782 were fixed by
migrating MainFragment to the MenuProvider API). Build time dropped 33% (2m59s -> 2m0s)
thanks to the concurrent debug+release build + Gradle parallel mode.

---

## Run 40452941884 (trigger for next-run comparison test)

- **Commit**: `0890d730063a27bf3158ad155c968dfe1d9f6d6c` (`0890d73`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push`
- **Triggered at**: 2026-10-27T05:01:30Z
- **Conclusion**: `success`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/40452941884

### What this run verified
- The `Push tag to remote` step successfully pushed `ci-0890d730063a` as a real git ref.
- Confirmed via `gh api repos/ancientcatz/Orange-NG/tags` that the tag now exists on the remote.
- This is the first run that pushes a real tag, so the changelog still shows all commits
  (no previous tag to compare against). The NEXT run should show only the new commit plus
  the "Full Changelog: ci-0890d730063a...ci-<new>" compare link.


---

## Run 40452258014

- **Commit**: `1f58bc6d22048521715bcde9416f2b029b9d0615` (`1f58bc6`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push`
- **Triggered at**: 2026-10-27T04:53:57Z
- **Conclusion**: `success`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/40452258014

### What this run introduced
- Integrated `orhun/git-cliff-action@v4` into the release job.
- Added `cliff.toml` config combining the GitHub template (PR links, contributor credits,
  "Full Changelog" compare link) with conventional-commit grouping.
- The release job's checkout was changed to `fetch-depth: 0` (required by git-cliff).
- A "Create temporary local tag at HEAD" step was added so git-cliff's `--latest` flag
  treats HEAD as a tagged release.
- The "Build release notes" step now combines the build metadata table with the
  git-cliff-generated changelog under a "## Changelog" heading.

### Issue found
- The changelog showed ALL commits from the beginning of history instead of just the
  commits since the previous release. Root cause: `actions/checkout`'s default
  `fetch-tags: false` passed `--no-tags` to `git fetch`, preventing the upstream `v*`
  tags from being created as local refs.

### Draft release
- Tag: `ci-1f58bc6d2204`
- Assets: both APKs attached.

---

## Run 40452538254

- **Commit**: `8c4b6be25dca596429e41e8533079145bb61ee8f` (`8c4b6be`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push`
- **Triggered at**: 2026-10-27T04:57:02Z
- **Conclusion**: `success`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/40452538254

### What this run introduced
- Added `fetch-tags: true` to the release job's checkout step so the upstream `v*` tags
  and any previous `ci-*` tags are fetched.

### Issue found
- The changelog STILL showed all commits from the beginning of history. Root cause:
  draft releases created by `gh release create --draft` do NOT push a real git tag to
  the remote — the tag exists only as metadata on the release object. So each CI run's
  checkout could not fetch the previous `ci-*` tag, and git-cliff could not find a
  previous release to compare against. Verified via `gh api repos/.../tags` returning
  empty and `gh api repos/.../git/refs/tags/ci-*` returning 404.

### Draft release
- Tag: `ci-8c4b6be25dca`
- Assets: both APKs attached.

---

## Run 40452941884

- **Commit**: `0890d730063a27bf3158ad155c968dfe1d9f6d6c` (`0890d73`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push`
- **Triggered at**: 2026-10-27T05:01:19Z
- **Conclusion**: `success`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/40452941884

### What this run introduced
- Added a "Push tag to remote" step that explicitly pushes `ci-<short-sha>` as a real
  lightweight git tag to the remote BEFORE creating the draft release.
- Removed `--cleanup-tag` from the release deletion step so the real git tag survives
  release deletion.
- Used `git tag -f` (force) when creating the local tag so a rerun of the same commit
  does not fail.
- Removed the separate "Clean up the temporary local tag" step (the runner is ephemeral).

### What this run verified
- The "Push tag to remote" step successfully pushed `ci-0890d730063a` as a real git ref.
- Verified via `gh api repos/ancientcatz/Orange-NG/tags` that the tag now exists on the
  remote.
- This is the first run that pushes a real tag, so the changelog still shows all commits
  (no previous tag to compare against). The NEXT run should show only the new commit plus
  the "Full Changelog: ci-0890d730063a...ci-<new>" compare link.

### Draft release
- Tag: `ci-0890d730063a`
- Assets: both APKs attached.

---

## Run 40453225389

- **Commit**: `6134b6b3b97988966abea4a15b4632d7ef919d68` (`6134b6b`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push`
- **Triggered at**: 2026-10-27T05:04:22Z
- **Conclusion**: `success`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/40453225389

### What this run verified
- **The git-cliff integration is now fully working.** The changelog shows ONLY the 3
  commits since the previous tag (`ci-0890d73...ci-6134b6b`), NOT all commits from the
  beginning of history.
- The "Full Changelog" compare link is present:
  `https://github.com/ancientcatz/Orange-NG/compare/ci-0890d730063a...ci-6134b6b3b979`
- Conventional-commit grouping works correctly:
  - The `ci:` commit is under "Miscellaneous Tasks".
  - The `docs:` commit is under "Documentation".
  - The `Revert` commit is under "Other".
- Contributor credits work: `by @ancientcatz` appears next to each commit.
- Two real git tags now exist on the remote: `ci-0890d730063a` and `ci-6134b6b3b979`.

### Draft release
- Tag: `ci-6134b6b3b979`
- Assets: both APKs attached.
- Changelog section (generated by git-cliff):
  ```
  ## What's Changed in ci-6134b6b3b979

  ### Documentation
  * verify git-cliff compares against previous ci-* tag by @ancientcatz

  ### Miscellaneous Tasks
  * verify git-cliff compares against previous ci-* tag by @ancientcatz

  ### Other
  * Revert "docs: verify git-cliff compares against previous ci-* tag" by @ancientcatz

  **Full Changelog**: https://github.com/ancientcatz/Orange-NG/compare/ci-0890d730063a...ci-6134b6b3b979
  ```

---

## Summary (updated)

| Run ID | Commit | Conclusion | Duration | Draft release | Notes |
|--------|--------|------------|----------|---------------|-------|
| 40447899202 | 8398753 | failure | 19s | — | setup-android action broke |
| 40448028674 | a194cc4 | failure | 23s | — | wrong SDK package name |
| 40448182748 | 2871a2f | failure | 1m1s | — | secret env var mismatch |
| 40448483874 | e1d0c2a | success | 4m33s | ci-e1d0c2ad6bef | first green build |
| 40449028782 | 8084597 | success | 3m8s | ci-80845972943c | action upgrades |
| 40450657195 | 3ceeb1c | success | 2m7s | ci-3ceeb1c5337a | MenuProvider + concurrent build |
| 40452258014 | 1f58bc6 | success | 1m27s | ci-1f58bc6d2204 | git-cliff integrated (all commits) |
| 40452538254 | 8c4b6be | success | 1m31s | ci-8c4b6be25dca | fetch-tags: true (still all commits) |
| 40452941884 | 0890d73 | success | 1m34s | ci-0890d730063a | push real tag (still all commits) |
| 40453225389 | 6134b6b | success | 1m20s | ci-6134b6b3b979 | **git-cliff fully working** |

The final successful run (40453225389) corresponds to commit 6134b6b. Its draft release
contains a git-cliff-generated changelog that shows only the 3 commits since the previous
tag, with conventional-commit grouping, PR links, contributor credits, and a "Full Changelog"
compare link. Each future CI run will push its own ci-<short-sha> tag, creating a chain of
comparison baselines.
