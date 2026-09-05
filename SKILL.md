---
name: anatomist
description: "Use for source-backed Java structural analysis: declarations, context, call paths, branches, types, Spring, and targeted flow."
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

Examples:

```bash
anatomist search PaymentGateway --kind type --format ndjson |
  anatomist resolve --unique |
  anatomist runtime-implementations --instantiability yes |
  anatomist source

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

Use `declarations-of --file <relative.java>` for changed files. Use
`context '<exact-signature>' --source` for one method; follow `next_queries`.

Lombok is off. Modeled AST facts are evidence; partial capability needs proof.
