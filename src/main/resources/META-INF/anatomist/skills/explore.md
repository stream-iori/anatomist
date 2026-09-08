# Explore decision guide

| Need | Stages |
|---|---|
| Find candidates | `search → resolve --unique` |
| Understand one type | `resolve --kind type --unique → describe`，成员另接 `members` |
| Read one exact method | `resolve --kind callable --exact --unique → source` |
| Map packages and dependencies | `overview`，再用 `members → references` 缩小范围 |
| Apply project docs | `index-docs`，再用 `search → resolve → related-docs` |
| Compare Git versions | `diff --base <ref> --target <ref>`; add `--impact` for possible callers |

Read every selected command's `--help`. Arrows above describe stages, not Shell syntax.
Execute them as `pipeline --index <db> -- <stage> --then <stage>` and require final
stream evidence.

Versioned queries accept `--snapshot <id>` or `--ref <Git-ref|WORKTREE>` at the
pipeline level. WORKTREE queries read the last capture; diff refreshes WORKTREE
unless `--no-build` is used. Use `snapshots list` to inspect capture identities.
Routes, annotations, names, and documents are
technical signals; verify source/configuration before calling them business rules.
Annotation search is direct by default. Add `--include-meta` only for composed
definitions, then inspect `direct`, `meta_depth`, `via`, and `resolution_status`.

Use `source --limit/--offset` until the required declaration range is covered.
Read an entire type only when state or lifecycle spans fields, constructors,
initializers, or several methods. For one concern, resolve exact methods first.
A local call already proven by the current slice needs no extra page.

When entity/declaration metadata contains `lombok`, only modeled capabilities are
structural facts. Partial/unmodeled capabilities need source or build evidence.
