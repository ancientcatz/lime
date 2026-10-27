# GitHub Actions Run Log

This file is a chronological record of every GitHub Actions run that is part of the migration.
Each entry records: run ID, triggering commit SHA, trigger event, conclusion, jobs, warnings
investigated, and any fixes pushed.

<!--
Append a new entry for every run using the template:

## Run <run-id>

- **Commit**: `<full-sha>` (`<short-sha>`)
- **Branch**: `feat/npatch-libxposed-102`
- **Trigger**: `push` | `workflow_dispatch` | `pull_request`
- **Triggered at**: `<ISO 8601 timestamp>`
- **Conclusion**: `success` | `failure` | `cancelled`
- **URL**: https://github.com/ancientcatz/Orange-NG/actions/runs/<run-id>

### Jobs
- `build`: <conclusion> (<duration>)
- `release`: <conclusion> (<duration>) | skipped

### Logs inspected
- ...

### Warnings found
- ...

### Fixes applied
- ...

### Draft release
- Tag: `ci-<short-sha>`
- URL: https://github.com/ancientcatz/Orange-NG/releases/tag/ci-<short-sha>
- Assets: `orange-ng-v<version>-debug.apk`, `orange-ng-v<version>-release.apk`
-->

(No runs yet — implementation has not been pushed.)
