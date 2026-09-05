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

Use fused execution for multi-stage queries:

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
anatomist pipeline --index index.db -- \
  resolve 'OrderService#run()' --kind callable --exact --unique \
  --then calls --then dispatch --then source
```

The Unix pipeline remains valid when stages need different global scopes or external
tools. Fused stages share one read-only snapshot and check seed/final evidence. Never conclude
absence when `coverage` is not `complete`; `--accept-unframed` intentionally
downgrades it. `calls` is source syntax plus static resolution. `dispatch` gives
possible static candidates, not observed execution. Framework bindings come from
artifact producers, not Java semantics.

Use `declarations-of --file <relative.java>` for changed files. For one method,
run an exact callable `resolve --then source`; page until evidence is complete.

1.0 cannot read 0.1x indexes. Run `index <project> --recreate`; there is no alias
or silent rebuild.

Lombok is off. Modeled AST facts are evidence; partial capability needs proof.
