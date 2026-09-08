---
name: anatomist
description: "Java code analysis through semantic-stream/v1 pipelines."
---

# anatomist

Work from the exact checkout being analysed. Load `anatomist skill core`, then
use `anatomist skill topics` to select only the relevant scene.

Prefer `index --incremental`; clean Git worktrees use a candidate-only fast path.
Use `--changed-files-from <file|->` only with an authoritative project-relative
delta. Recreate after extractor/policy changes. Use `doctor --format json` only when
health/freshness is in doubt; help or `operations <operation>` for unclear options.

Git versions: `index . --ref HEAD` tries incremental reuse; query with `--ref`
or `--snapshot <id>`. Use `diff --base HEAD --target WORKTREE` for disk changes.
Ordinary indexing needs no Git. Load `core` for version and progress rules.
`[anatomist-progress]` on stderr is diagnostic, not query evidence or command
success; stdout retains the result contract.

Configuration uses project config, then user config, then built-ins; files do
not merge and CLI flags override. Inspect `doctor` `config_source` when needed.

```text
find        search → resolve --unique
members     resolve → members [--recursive]
types       resolve → type-relations
runtime     resolve → runtime-implementations
calls       resolve → calls [→ source]
dispatch    resolve → calls → dispatch [→ source]
annotations resolve → annotations [--include-meta]
config      resolve → bindings [--semantic member]
```

For two or more linear stages, prefer fused `pipeline -- ... --then ...`.
Use `pipeline --explain` or `--check` only for unfamiliar or failing pipelines.
Put index/module/scope/format on `pipeline`, not inside stages.
Pipeline failures exit 5 and do not emit final stream evidence.

NDJSON begins with `stream_header`, groups all results for one input seed, and
ends with `evidence(scope=stream)`. Missing final evidence means failure. Never
conclude absence unless coverage is complete and `negative_conclusion_safe` is
true. Query errors use `anatomist-error/v1`; decide from `code`, not message.

`status=ok` and `index_state=committed` remain queryable with `health=degraded`
or partial resolution. Retain positive results, disclose degraded coverage,
and do not derive negative conclusions from partial/unknown evidence.

`calls` proves source call syntax and static targets. `dispatch` expands possible
virtual targets; it does not prove runtime execution. Framework bindings do not
manufacture calls.

Use `declarations-of --file <relative.java>` for changed files. For one method,
resolve the exact callable and pipe to `source`; follow source pagination when a
conclusion must cover the full declaration. `source` returns at most 1000 lines;
repeat it with `--limit 1000 --offset <declaration-relative-line>` until
`truncated=false`. Require stable stream identity and increasing offsets.

For 0.1x indexes use `index <project> --recreate`. Lombok is off by default;
partial modeled coverage requires additional evidence.
