# Orange-NG → NPatch / libxposed API 102 Migration — Documentation

This orphan branch holds all task documentation for the modernization of `ancientcatz/Orange-NG`
for use with NPatch (a fork of LSPosed) and the modern libxposed API 102.0.0.

The implementation itself lives on the `feat/npatch-libxposed-102` branch of the same repository.
This branch is intentionally an orphan: it has no shared history with the implementation, so
documentation commits never pollute the implementation diff and never land on `master`.

## File index

| File | Purpose |
|------|---------|
| `README.md` | This index. |
| `MIGRATION_PLAN.md` | High-level migration strategy and phase breakdown. |
| `API_MIGRATION.md` | Inventory of every legacy `de.robv.android.xposed` usage and its modern libxposed API 102 replacement. |
| `BUILD_AND_CI.md` | Build toolchain changes, dependency choices, and GitHub Actions CI/CD design. |
| `TESTING.md` | Automated checks executed, NPatch-specific checks, and manual testing procedure. |
| `ACTIONS_LOG.md` | Chronological log of GitHub Actions runs (run IDs, commits, conclusions, warnings investigated). |
| `WARNINGS.md` | Warnings encountered, root cause analysis, fixes applied, and any unresolved upstream warnings with rationale. |
| `STATUS.md` | Current status of the migration, what is done, what is outstanding, definition-of-done checklist. |

## Quick links

- Implementation branch: https://github.com/ancientcatz/Orange-NG/tree/feat/npatch-libxposed-102
- Documentation branch: https://github.com/ancientcatz/Orange-NG/tree/docs/npatch-migration-notes
- Default branch (must not be touched): `master`
- Modern Xposed API: https://github.com/libxposed/api (version 102.0.0)
- Modern Xposed service: https://github.com/libxposed/service (version 102.0.0)
- Modern API Javadoc: https://libxposed.github.io/api/
- Official example module: https://github.com/libxposed/example
- NPatch manager: https://github.com/7723mod/NPatch
- NPatch Remote API (fallback only): https://github.com/7723mod/NPatch-Remote-API

## Credential and security posture

- The GitHub PAT used for this work is ROT13-encoded at rest in the task brief, decoded only in-memory
  via `tr`, piped directly into `gh auth login --with-token`, and never printed, never written to disk,
  never passed as a command-line argument, and never committed.
- `gh auth setup-git` is used so that HTTPS git operations use gh's stored credential helper; no
  plaintext token is exposed to git either.
- The temporary Android release keystore is generated locally with strong random passwords, stored
  Base64-encoded in GitHub Actions secrets, and the local copy is deleted after the secrets are
  configured. No signing material is ever committed.
- The default branch `master` is never modified, force-pushed, or merged into. All implementation
  work lives on `feat/npatch-libxposed-102` and only the user may merge it into `master` as a single
  commit after explicit approval.
