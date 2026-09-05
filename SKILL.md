---
name: anatomist
description: "Source-backed Java analysis through semantic-stream/v1 pipelines."
---

# anatomist

1. Work from the exact checkout being analysed.
2. Run `anatomist skill core`; obey its index/health gate.
3. Run `anatomist skill topics`, then load only the relevant scene.
4. Read `anatomist <command> --help`; installed help is authoritative.

Config order: project, user, otherwise built-in defaults. Files do not merge;
CLI flags override. Prefer `index --incremental`. After policy changes, re-index
and inspect `doctor --format json` `config_source`.

Use the canonical NDJSON pipeline:

```text
find        search --format ndjson | resolve --unique
members     ... | members [--recursive]
types       ... | type-relations
runtime     ... | runtime-implementations
calls       ... | calls | dispatch
config      ... | bindings
evidence    ... | source
```

Example:

```bash
anatomist resolve 'OrderService#run()' --kind callable --exact --unique |
  anatomist calls | anatomist dispatch | anatomist source

anatomist search applicationContext --kind artifact --format ndjson |
  anatomist resolve --unique | anatomist members --recursive
```

Stages check revision/profile and seed/final evidence. Never conclude
absence when `coverage` is not `complete`; `--accept-unframed` intentionally
downgrades it. `calls` is source syntax plus static resolution. `dispatch` gives
possible static candidates, not observed execution. Framework bindings come from
artifact producers, not Java semantics.

Use `declarations-of --file <relative.java>` for changed files. For one method,
use `resolve '<exact-signature>' --kind callable --exact --unique | source` and
follow source pagination until evidence is complete.

1.0 cannot read 0.1x indexes. Run `index <project> --recreate`; there is no alias
or silent rebuild.

Lombok is off. Modeled AST facts are evidence; partial capability needs proof.
