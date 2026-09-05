---
name: anatomist
description: "Source-backed structural analysis through semantic-stream/v1 pipelines."
---

# anatomist

1. Work from the exact checkout being analysed.
2. Run `anatomist skill core`; obey its index/health gate.
3. Run `anatomist skill topics`, then load only the relevant scene.
4. Read `anatomist <command> --help`; installed help is authoritative.
5. Read `anatomist operations [operation] --index <db>` for machine-readable
   input/output types, arguments, limits, language support, and index availability.

Config order: project, user, otherwise built-in defaults. Files do not merge;
CLI flags override. Prefer `index --incremental`. After policy changes, re-index
and inspect `doctor --format json` `config_source`.

For two or more linear stages, read `anatomist pipeline --help` and prefer fused execution. Use a Unix pipeline only
for external tools, branching, or distinct index/module/scope values.

```text
find        search → resolve --unique
members     resolve → members [--recursive]
types       resolve → type-relations
runtime     resolve → runtime-implementations
calls       resolve → calls → dispatch
annotations resolve → annotations [--include-meta]
config      resolve → bindings [--semantic member]
evidence    resolve/site → source
```

Before execution, use `pipeline --explain -- ...` for static composition and
`pipeline --check --index index.db -- ...` for read-only availability checks.
They never execute selectors or consume stdin; inspect `deferred_checks`.

Put `--index`, `--module`, `--scope`, and `--format` on `pipeline`, before `--`;
never repeat them inside stages. For automation, use
`pipeline --index index.db --file pipeline.json` with
`{"stages":[["resolve",...],["calls"],["dispatch"]]}`.

Fused stages share one read-only snapshot. Treat output as successful only when it
ends with `evidence(scope=stream)`. Query errors use `anatomist-error/v1`; decide
from `code`, `category`, and structured details, not message text. Exit 5 may leave
partial seed frames on stdout, so never consume them as a completed answer.
Never conclude absence unless coverage is complete and evidence marks the negative
conclusion safe. `--accept-unframed` intentionally downgrades coverage.

`calls` is source syntax plus static resolution. `dispatch` gives possible static
candidates, not observed execution. Framework bindings come from artifact producers,
not Java semantics.

Annotations are direct by default; add `--include-meta` for composed definitions.
Inspect all ambiguous members; unresolved SymbolRef is not proof of absence.

Use `declarations-of --file <relative.java>` for changed files. For one method,
run an exact callable `resolve --then source`; page until evidence is complete.

1.0 cannot read 0.1x indexes. Run `index <project> --recreate`; there is no alias
or silent rebuild.

Lombok is off. Modeled AST facts are evidence; partial capability needs proof.
