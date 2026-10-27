# Build, Toolchain, and CI/CD

## Toolchain

The chosen versions mirror the official libxposed example module, so dependency compatibility is
validated by upstream.

| Component | Old version | New version | Reason |
|-----------|-------------|-------------|--------|
| AGP | 7.1.3 | 9.2.1 | Required to consume libxposed API 102 (which itself is built with AGP 9.2.1 and compileSdk 37). |
| Gradle | 7.3.3 | 9.5.1 | Required by AGP 9.2.1. |
| Kotlin | 1.9.0 | 2.0.21 | Compatible with AGP 9.x; stable; works with kotlinx-serialization 1.6.0. |
| compileSdk | 32 | 37 | Required by libxposed API 102. |
| buildToolsVersion | (default) | 37.0.0 | Matches compileSdk. |
| Java source/target | 1.8 | 21 | JDK 21 is the baseline required by the task and is the JDK used by libxposed itself. |
| Kotlin jvmTarget | 1.8 | 21 | Matches Java target. |
| minSdk | 30 | 30 | Preserved — no technical incompatibility forces a change. |
| targetSdk | 32 | 32 | Preserved — changing targetSdk has runtime behavior implications (e.g., notification runtime permissions, foreground service types). |
| Version catalog | none | `gradle/libs.versions.toml` | Centralizes versions for reproducible builds. |

## Dependencies

| Dependency | Old | New | Scope |
|------------|-----|-----|-------|
| `de.robv.android.xposed:api` | 82 | (removed) | was `compileOnly` |
| `de.robv.android.xposed:api:82:sources` | (present) | (removed) | was `compileOnly` |
| `io.github.libxposed:api` | — | 102.0.0 | `compileOnly` (module side — never packaged) |
| `io.github.libxposed:service` | — | 102.0.0 | `implementation` (settings-app side — needed for `XposedServiceHelper`) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.6.0 | 1.6.0 | `implementation` (kept; works with Kotlin 2.0.21) |
| AndroidX libs | various 1.4.x/2.1.x | kept as-is | The legacy versions still build against compileSdk 37; no incompatibility observed. Upgrading them is out of scope for this migration. |

The `compileOnly` scope on `io.github.libxposed:api` is critical: it ensures the API jar is never
packaged into the APK as a duplicate runtime implementation (the framework provides the
implementation at runtime). The `implementation` scope on `io.github.libxposed:service` is also
correct: the service library is a real runtime dependency that talks to the framework via a
ContentProvider.

## Signing configuration

The release build is signed with a temporary keystore stored in GitHub Actions secrets. The Gradle
build reads four environment variables and only enables the release signing config when all four
are present:

- `ORANGE_KEYSTORE_FILE` — path to the decoded keystore file on the runner.
- `ORANGE_KEYSTORE_PASSWORD`
- `ORANGE_KEY_ALIAS`
- `ORANGE_KEY_PASSWORD`

The debug build is signed with the standard Android debug keystore (independent of the release
keystore) so it remains installable side-by-side with the release build.

Local builds without these environment variables produce an unsigned release APK; this is intended
so that local validation can still run `assembleRelease` without a keystore.

## R8 / minification

- `release.isMinifyEnabled = true` (kept from the legacy build).
- `release.isShrinkResources = true` (added — matches the libxposed example and shrinks unused
  resources).
- ProGuard rules in `app/proguard-rules.pro` updated with the official libxposed rules plus
  kotlinx-serialization keep rules for `lsposed.orange.model.**`.

## GitHub Actions CI/CD

Workflow: `.github/workflows/build.yml`.

### Triggers

- `push` to `feat/npatch-libxposed-102` → full pipeline (build + sign + release).
- `workflow_dispatch` → full pipeline (build + sign + release).
- `pull_request` to `feat/npatch-libxposed-102` → build + lint + test only. Signing secrets are
  not available to PR code, so the release APK is built unsigned and the `release` job is skipped.

### Jobs

**`build` job** (runs on `ubuntu-latest`):
1. Checkout the exact commit (fetch-depth: 1 is sufficient; we don't need history for the build).
2. Setup JDK 21 (Temurin distribution).
3. Setup Android SDK with `android-actions/setup-android@v3` (cache-friendly, pinned to a SHA tag).
4. Cache Gradle dependencies and the Gradle distribution for faster subsequent runs.
5. Run `./gradlew --warning-mode=all --no-daemon clean`.
6. Run `./gradlew --warning-mode=all --no-daemon lintDebug`.
7. Run `./gradlew --warning-mode=all --no-daemon testDebugUnitTest`.
8. Decode the keystore from `ANDROID_KEYSTORE_BASE64` into a temporary file (only when secrets are
   available — i.e., not for PR builds).
9. Run `./gradlew --warning-mode=all --no-daemon assembleDebug assembleRelease` with the four
   signing env vars set (or empty for PR builds).
10. Verify both APKs exist and are non-empty (`test -s`).
11. Verify the release APK is signed (`apksigner verify --verbose`).
12. Copy both APKs to predictable filenames:
    `orange-ng-v<versionName>-debug.apk` and `orange-ng-v<versionName>-release.apk`.
13. Upload both APKs as workflow artifacts (`actions/upload-artifact@v4`).
14. Cleanup the temporary keystore (always, even on failure — `post:` step).

**`release` job** (runs on `ubuntu-latest`, depends on `build`, only for `push` and
`workflow_dispatch`):
1. Download both APK artifacts from the `build` job.
2. Compute the short commit SHA.
3. Compute the CI tag: `ci-<short-sha>`.
4. Compute the release title: `Orange-NG CI Build <short-sha>`.
5. Build release notes containing: branch, full commit SHA, Gradle version, AGP version, build
   timestamp, and a note that this is an automated CI build (not a stable release).
6. Check if a draft release already exists for the tag (via `gh release view ci-<short-sha>`).
   - If yes: delete and recreate the assets (so a rerun doesn't leave stale APKs) but keep the tag.
   - If no: create a new draft release with `gh release create ci-<short-sha> --draft --target
     <working-branch> --title "..." --notes "..." orange-ng-v...-debug.apk orange-ng-v...-release.apk`.
7. Verify the release has both assets attached.

### Permissions

- `build` job: `contents: read` (default — only needs to read the source).
- `release` job: `contents: write` (needed to create draft releases and tags).

### Security considerations

- The `GITHUB_TOKEN` automatically provided to the workflow is used for all GitHub operations
  (creating releases, uploading assets). The personal access token is never embedded in the
  workflow.
- Signing secrets are only referenced inside the `build` job, and only conditionally — for
  `pull_request` events the workflow skips the signing step entirely.
- Third-party actions are pinned to a specific minor version (`@v3`, `@v4`) which is the
  recommended compromise between reproducibility and receiving security patches.
- The workflow never writes to `master`: the `release` job uses `--target feat/npatch-libxposed-102`
  when creating the draft release's associated tag, and the tag is `ci-<short-sha>` (clearly
  distinguishable from a real release tag like `v1.2.0`).

## Temporary signing caveats

- The keystore configured in GitHub Actions secrets is a **temporary development identity**, not a
  production signing identity.
- Changing signing identities later (e.g., to a production key) prevents in-place upgrade of an
  installed APK — users will need to uninstall and reinstall.
- The keystore passwords are strong random values; they are not stored anywhere except in GitHub
  Actions secrets.
- The local keystore file is deleted immediately after the secrets are configured.

## Release notes generation (git-cliff)

Release notes for each draft release are generated by
[`orhun/git-cliff-action@v4`](https://github.com/orhun/git-cliff-action) from the
commit history, using the `cliff.toml` config in the repo root.

### Configuration (`cliff.toml`)

The config combines:
- **GitHub template** (from `git-cliff/examples/github.toml`): enables PR links,
  contributor credits, and the "Full Changelog: A...B" compare footer.
- **Conventional-commit grouping** (from `git-cliff/config/cliff.toml`): buckets
  commits into Features / Bug Fixes / Documentation / Performance / Refactor /
  Styling / Testing / Miscellaneous Tasks / Security / Revert / Other based on
  conventional-commit prefixes (`feat:`, `fix:`, `doc:`, etc.).
- `filter_unconventional = false` so non-conventional commits still appear under
  "Other" instead of being silently dropped.
- `use_branch_tags = true` so only tags reachable from the current branch are
  considered.

### Workflow integration (release job)

1. **Checkout** with `fetch-depth: 0` (full history) and `fetch-tags: true` (so
   previous `ci-*` tags are fetched).
2. **Delete existing draft release** for this commit (if any) WITHOUT
   `--cleanup-tag` — the real git tag must survive so the next run can compare
   against it.
3. **Create local tag** `ci-<short-sha>` at HEAD (`git tag -f`) so git-cliff's
   `--latest` flag treats HEAD as the newest tagged release.
4. **Generate changelog** with `orhun/git-cliff-action@v4`, passing
   `--latest --strip header` so only the section for the latest tag is emitted
   (without the global "# Changelog" header). `GITHUB_TOKEN` is supplied via env
   so git-cliff's GitHub integration can fetch PR metadata without rate limits.
5. **Push tag to remote** (`git push origin ci-<short-sha>`) as a real lightweight
   git tag. This is necessary because `gh release create --draft` does NOT push a
   real git tag — the tag exists only as metadata on the release object and is
   materialized as a git ref only when the release is published. Without this
   explicit push, the next CI run's checkout cannot fetch the previous `ci-*`
   tag, and git-cliff cannot find a previous release to compare against.
6. **Build release notes** by concatenating: the build metadata table (branch /
   commit / built / run / artifacts / install instructions) + a "## Changelog"
   heading + the git-cliff output + the CI disclaimer footer.
7. **Create draft release** with `--notes-file` pointing at the combined notes.

### How the comparison works

- **First CI run**: no previous `ci-*` tag exists on the remote, so git-cliff
  emits all commits from the beginning of history.
- **Subsequent runs**: the previous `ci-*` tag (pushed by step 5 of the previous
  run) is fetched by step 1. git-cliff's `--latest` flag picks the new
  `ci-<short-sha>` tag as the latest, finds the previous `ci-*` tag as the
  comparison baseline, and emits only the commits between them. The "Full
  Changelog: <previous>...<current>" compare link appears at the bottom of the
  changelog section.

### Why we push tags explicitly

`gh release create --draft <tag>` creates a draft release with the given tag
name, but does NOT push a real git tag to the remote. The tag exists only as
metadata on the release object. This means:
- `gh api repos/.../tags` returns empty (no real git tags).
- `gh api repos/.../git/refs/tags/<tag>` returns 404.
- The next CI run's `git fetch` cannot fetch the tag.
- git-cliff cannot find a previous release to compare against.

By explicitly pushing the tag with `git push origin ci-<short-sha>`, we create a
real git ref that persists across runs. The push is idempotent (if the tag
already exists at the same commit, git says "Everything up-to-date" and exits 0).
Authentication is handled by `actions/checkout`'s `persist-credentials: true`
(the default), which stores the `GITHUB_TOKEN` in the local git config's
`http.<url>.extraheader` — so the token never appears in the command line, push
output, or shell history.

## targetSdk change (commit 32c0bab)

Per user request, `targetSdk` was bumped from 32 to 33 (Android 13+). This is the only
runtime-behavior change in the migration. Implications (all verified safe for this app):

| Android 13 (API 33) change | Impact on Orange-NG |
|----------------------------|---------------------|
| `POST_NOTIFICATIONS` runtime permission | None — app does not post notifications. |
| Granular media permissions | None — app does not read media files. |
| Predictive back gesture | None — app does not declare `android:enableOnBackInvokedCallback`, so default behavior is preserved. |
| `NEARBY_WIFI_DEVICES` | None — not used. |
| `BODY_SENSORS_BACKGROUND` | None — not used. |
| `ACTIVITY_RECOGNITION` | None — not used. |

The `ExpiredTargetSdkVersion` lint check (previously disabled with justification) now
passes naturally, so the `disable += "ExpiredTargetSdkVersion"` rule was removed from
`app/build.gradle.kts`.

`versionCode` was bumped 4 -> 5 and `versionName` 1.2.0-npatch -> 1.3.0-npatch to reflect
the targetSdk change.

## Pre-fork commit exclusion (commit 32c0bab)

Per user request, pre-fork commits and contributors are now excluded from release notes.
This is achieved by pushing the `v1.1.0` tag at the fork point (`aefb4a5`, the upstream
v1.1.0 commit) to the remote. The `ancientcatz/Orange-NG` fork did not previously have
this tag — only the upstream `Erbzur/Orange` repo had it.

With `v1.1.0` present on the remote:
- git-cliff's first `ci-*` run (when no previous `ci-*` tag exists) uses `v1.1.0` as the
  baseline, so only migration commits (by `@ancientcatz`) appear in the changelog.
- Subsequent runs already used the previous `ci-*` tag as the baseline, so they were
  already correct.
- The `v1.1.0` tag is semantically correct: the fork was based on upstream v1.1.0, and
  the fork point commit is exactly where upstream tagged v1.1.0.

Old draft releases that had incorrect notes (showing upstream commits and `@Erbzur` as a
contributor) were deleted WITHOUT `--cleanup-tag` so the real git tags survive for
git-cliff's comparison chain. Only the 2 latest draft releases remain.

## Trigger policy change (commit d254cb2)

Per user request, the release trigger policy changed:

| Trigger | Build job | Release job | Draft release created? |
|---------|-----------|-------------|------------------------|
| `push` to `feat/npatch-libxposed-102` | ✓ (full build/lint/test/sign + artifact upload) | ✗ (skipped) | No |
| `workflow_dispatch` (no tag input) | ✓ | ✓ | Yes, tagged `ci-<short-sha>` |
| `workflow_dispatch` (with `tag` input, e.g. `v1.3.0`) | ✓ | ✓ | Yes, tagged with the input value |
| `pull_request` | ✓ (build/lint/test only, no signing) | ✗ (skipped) | No |

### `tag` input

`workflow_dispatch` accepts an optional `tag` string input:
- **Empty** (default): the release is tagged `ci-<short-sha>` — an ad-hoc testable build off
  the latest commit. Title: "Orange-NG CI Build `<short-sha>`". Disclaimer: "This is an
  automated CI build from the branch. It is not a stable release."
- **Custom** (e.g. `v1.3.0`): the release is tagged with the provided value — a versioned
  release. Title: "Orange-NG `v1.3.0`". Disclaimer: "This is a draft release prepared by CI
  from the branch. Review the changelog and assets, then publish the release when ready."

The `tag` input lets the user cut a real versioned release (e.g. `v1.3.0`) by dispatching
the workflow with that tag, or cut an ad-hoc CI build by dispatching without a tag.

### Concurrency

The concurrency group now includes `github.event_name`:
`build-${{ github.event_name }}-${{ github.ref }}`. This ensures a push does NOT cancel an
in-flight `workflow_dispatch` release (and vice versa) — they run independently. Multiple
pushes in quick succession still cancel each other (good — saves runner minutes), and
multiple dispatches in quick succession cancel each other too.

## Split build into concurrent jobs (commit ca69532)

Per user request, the monolithic `build` job was split into three concurrent jobs that run
in parallel on separate runners, mimicking the approach used for separate ABI builds:

| Job | Purpose | Signing | Runs on | Artifact |
|-----|---------|---------|---------|----------|
| `validate` | lint + unit tests | No | All events | — |
| `build-debug` | assemble debug APK | No (debug keystore) | All events | `apk-debug-<sha>` |
| `build-release` | assemble release APK | Yes (on push/dispatch) | All events | `apk-release-<sha>` |
| `release` | create draft release | — | workflow_dispatch only | — |

`validate`, `build-debug`, and `build-release` have no `needs` dependency, so they start
simultaneously and run on separate runners — true parallelism. The `release` job
`needs: [validate, build-debug, build-release]`, so it waits for all three to succeed
before downloading the artifacts and creating the release. If any of the three fails, the
release is skipped (default `needs` behavior).

**Speed improvement**: push wall-clock time dropped from ~1m16s (single-job with `--parallel`)
to ~47s (3 concurrent jobs) — a 38% speedup. The debug build (fast, no R8) and the release
build (slow, R8 + resource shrinking) no longer compete for the same runner's resources.

## `debug` input (commit ca69532)

`workflow_dispatch` now accepts a second optional input:

| Input | Type | Default | Purpose |
|-------|------|---------|---------|
| `tag` | string | `""` | Tag name for the release. Empty = ci-<short-sha>. Custom = versioned release. |
| `debug` | boolean | `false` | When `true`, the debug APK is also attached to the release. When `false` (default), only the release APK is published. |

When `debug = false` (default):
- The release job downloads only `apk-release-<sha>`.
- The `gh release create` command attaches only the release APK.
- The release notes list only the release APK in the Artifacts section.
- The Install instructions say "Install the release APK."

When `debug = true`:
- The release job downloads both `apk-release-<sha>` and `apk-debug-<sha>`.
- The `gh release create` command attaches both APKs.
- The release notes list both APKs in the Artifacts section.
- The Install instructions say "Install the release APK (or the debug APK for development)."
- The metadata table includes a "Debug APK included: true" row.

The `debug` input lets the user control whether the debug APK is published. By default,
only the release APK is published (debug APKs are for development, not distribution). When
the user wants to also distribute the debug APK (e.g. for testers who need a debuggable
build), they enable `debug` on the dispatch.
