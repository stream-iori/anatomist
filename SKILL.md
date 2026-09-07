---
name: anatomist
description: 'Java code analysis through semantic-stream/v1 pipelines.'
---

# anatomist

Work from the exact checkout being analysed. Load `anatomist skill core`, then
use `anatomist skill topics` to select only the relevant scene.

Prefer `index --incremental`; clean Git worktrees use a candidate-only fast path.
CI/editor integrations with an authoritative delta should add
`--changed-files-from <file|->` using project-relative paths. After extractor or
policy changes, recreate the index. Use `doctor --format json` only when index
health or freshness is in doubt. Read command help or `operations <operation>`
only when an option or capability is unclear.

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

`calls` proves source call syntax and static targets. `dispatch` expands possible
virtual targets; it does not prove runtime execution. Framework bindings do not
manufacture calls.

Use `declarations-of --file <relative.java>` for changed files. For one method,
resolve the exact callable and pipe to `source`; follow source pagination when a
conclusion must cover the full declaration.

For 0.1x indexes use `index <project> --recreate`. Lombok is off by default;
partial modeled coverage requires additional evidence.
