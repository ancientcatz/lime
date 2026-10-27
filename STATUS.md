# Status

## Current state

- Phase 0 (environment setup): DONE
- Phase 1 (inspection): DONE
- Phase 2 (libxposed API 102 migration): DONE
- Phase 3 (NPatch Remote API evaluation): DONE — decision: not needed initially
- Phase 4 (build toolchain modernization): DONE
- Phase 5 (GitHub Actions CI/CD): DONE
- Phase 6 (temporary release signing): DONE
- Phase 7 (Actions monitoring and iteration): DONE — final run is green, no actionable warnings
- Phase 8 (testing and verification): DONE (automated checks pass; manual device testing
  requires a physical device or emulator with NPatch installed and is documented in TESTING.md)
- Phase 9 (final review): DONE

## Definition of Done checklist

### Application

- [x] Orange-NG builds against `io.github.libxposed:api:102.0.0`.
- [x] Legacy hooking implementation migrated to `XposedModule` + `hook().intercept()`.
- [x] `META-INF/xposed/{java_init.list, module.prop, scope.list}` present in both APKs
  (verified by unzipping both the debug and release APKs).
- [x] Original per-app orientation functionality preserved (Activity.attach hook with
  ExceptionMode.PROTECTIVE, skips own application ID, maps orientation via the unchanged
  `mapActivityOrientation` function, no-op when no config exists for the package).
- [x] NPatch compatibility investigated and validated to the extent possible (module.prop
  declares `minApiVersion=101` / `targetApiVersion=102`, scope.list preserves the original
  target, java_init.list declares the entry class, R8 keeps the entry class with the
  `-adaptresourcefilecontents` rule rewriting java_init.list to the obfuscated name).
- [x] NPatch Remote API integrated only if testing shows it is needed (currently NOT
  integrated; documented rationale in API_MIGRATION.md).
- [x] Both debug and release APKs build successfully (verified locally and in CI).
- [x] Release APK correctly signed (verified with `apksigner verify --verbose` in CI:
  `Verifies`, `Verified using v3 scheme: true`).
- [x] No actionable source/Gradle/Kotlin/lint warnings remain (ObsoleteLintCustomCheck fixed
  by bumping fragment-ktx to 1.5.1; ExpiredTargetSdkVersion disabled with documented
  justification; AndroidGradlePluginVersion/GradleDependency documented as intentional;
  Node.js 20 deprecation fixed by upgrading actions; ubuntu-latest migration is informational
  only and cannot be acted on from the workflow side).

### CI/CD

- [x] GitHub Actions performs the required automated validation (clean, lintDebug,
  testDebugUnitTest, assembleDebug, assembleRelease, APK presence and signature verification).
- [x] Every pushed implementation commit receives a monitored workflow run (5 runs total:
  3 failures iterated to 2 successes).
- [x] Both APKs retained as workflow artifacts (30-day retention).
- [x] Every successful implementation build has a draft release containing both APKs
  (ci-e1d0c2ad6bef and ci-80845972943c).
- [x] CI releases uniquely identifiable by commit (`ci-<short-sha>` tag pattern).
- [x] Rerunning a workflow does not create duplicate releases for the same commit (the
  release job deletes any existing release for the tag before creating a fresh one).
- [x] The final successful run corresponds to the actual final implementation commit
  (run 40449028782 → commit 80845972943ca939e9ef95dea83f73be112979b4).
- [x] The workflow does not expose credentials (signing secrets are only referenced inside
  the build job, only conditionally decoded, only on push/workflow_dispatch — never on
  pull_request; the PAT is never embedded in the workflow; GITHUB_TOKEN is used for all
  GitHub operations).

### Security

- [x] PAT used only for the target repository (authenticated as `ancientcatz`, only
  ancientcatz/Orange-NG was touched).
- [x] Decoded PAT never printed or committed (ROT13-decoded via `tr`, piped directly to
  `gh auth login --with-token`, never written to disk, never passed as a command-line
  argument; `gh auth setup-git` configures the git credential helper so HTTPS operations
  use gh's stored credential without exposing the token to git).
- [x] Temporary keystore and passwords stored only in GitHub Actions secrets
  (ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD).
- [x] No signing material committed to Git (verified by `git diff --name-only master..HEAD`
  showing no `.jks`, `.keystore`, `.p12`, `.pem`, or `.key` files).
- [x] Temporary sensitive files removed from the execution environment (local keystore
  shredded and unlinked after secrets were configured; CI workflow shreds the decoded
  keystore in a post step that runs even on failure).
- [x] CI signing and release operations follow least-privilege principles (build job has
  `contents: read` (default); release job has `contents: write`; no other permissions
  requested).

### Repository and documentation

- [x] Implementation resides on `feat/npatch-libxposed-102` (5 commits ahead of master).
- [x] Documentation preserved on `docs/npatch-migration-notes` (orphan branch, 8 files).
- [x] Both branches pushed to GitHub.
- [x] Working tree and Git history reviewed (clean diff, no accidental changes, no sensitive
  files, no generated build outputs in Git).
- [x] All significant findings and unresolved issues documented (README.md, MIGRATION_PLAN.md,
  API_MIGRATION.md, BUILD_AND_CI.md, TESTING.md, ACTIONS_LOG.md, WARNINGS.md, STATUS.md).
- [x] Nothing merged into `master` (master still points at aefb4a5, the original default
  branch tip).

## Outstanding issues

- **Manual device testing not executed**: the automated CI pipeline validates compilation,
  lint, unit tests, APK presence, APK signature, META-INF/xposed/ metadata, R8 retention of
  the entry class, and absence of legacy `de.robv.android.xposed` references in the dex. It
  does NOT validate runtime behavior under NPatch (the orientation hook actually firing on
  `com.seleuco.mame4droid` activities). This requires a physical device or emulator with
  NPatch installed. The manual testing procedure is documented in TESTING.md.
- **NPatch Remote API fallback not preemptively integrated**: per the NPatch Remote API
  README's own guidance, we prefer the standard libxposed `XposedServiceHelper` path and
  only fall back to `NPatchRemoteClient` if device testing shows the standard service binder
  is not delivered to the settings process. If that happens, the integration procedure is
  documented in API_MIGRATION.md.
- **Two draft releases exist**: one per successful implementation commit (ci-e1d0c2ad6bef
  for commit e1d0c2a, and ci-80845972943c for commit 8084597). Both are drafts, neither is
  published. The user can delete the older one (ci-e1d0c2ad6bef) if they only want the latest.
  This is the intended behavior per the task: "Every successful pushed implementation commit
  must produce a draft release."
- **AndroidX dependency versions are not the latest**: this is intentional per the task
  brief ("Use mutually compatible, supported toolchain versions... instead of independently
  upgrading every version to its latest release"). See WARNINGS.md for the full rationale.
