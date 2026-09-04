---
name: anatomist
description: "Use for source-backed Java structural analysis: declarations, context, call paths, branches, types, Spring, and targeted flow."
---

# anatomist

1. Work from the exact checkout being analysed.
2. Run `anatomist skill core` and follow its index and evidence gate.
3. Run `anatomist skill topics`, then load only the one relevant scene.
4. Before executing a selected command, read `anatomist <command> --help`; it is
   the source of truth for the installed CLI version.

Select `.anatomist/config.toml`, then `~/.anatomist/config.toml`, otherwise built-in defaults. Files do not merge; CLI flags override.
Lombok defaults off. Enable source-only modeling per project:

```toml
[extensions.lombok]
mode = "ast"
strict = false
```

In AST mode, `lombok.modeled_capabilities` are signature facts; partial/unmodeled values
need proof. Structured `edge.lombok_usage` proves source use only. A mapped field is a
lead, not compile/runtime proof; never invent Builder nodes when `builder_type` is null.
For a missing symbol, inspect `doctor --format json --index <db>` for
`config_source`, `config_path`, and `scan_policy_hash`, then run
`index --incremental` after a policy change.

Prefer incremental index synchronization. Load `anatomist skill flow` for
source-backed value, exception, guard, or suspected taint questions.

Use `anatomist declarations-of --file <project-relative.java> --format json`
when a caller needs stable type/method/constructor seeds from changed files.
Use its AST-derived filters; do not parse Java declarations with regex.

For a resolved method, use `context '<exact-signature>' --source`; follow
`next_queries` if truncated.

Anatomist returns static source evidence, not proof of runtime execution or
business meaning. Use runtime evidence when the question asks what happened online.
