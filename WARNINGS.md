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
this repository, we document the exact warning text, its source, the investigation performed, and
why it remains unresolved.

## Warnings log

<!--
Template:

### <warning category>: <short description>

**Observed in**: <run-id or local run>, <file or step>
**Warning text**:
```
<verbatim warning text>
```
**Root cause**: <analysis>
**Fix applied**: <commit-sha> — <what was changed>
**Status**: fixed | unresolved (upstream) | unresolved (justified)
-->

(No warnings yet — implementation has not been built.)
