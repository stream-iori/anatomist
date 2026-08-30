---
name: anatomist
description: "Use for source-backed structural analysis of Java projects: declaration discovery by file, entry discovery, class and method context, forward or reverse call tracing, call paths, branches, type hierarchy and implementations, field composition and access, package architecture, Spring annotations/XML wiring, documentation enrichment, and targeted data/exception/taint flow."
---

# anatomist

Use anatomist as an indexed companion to IDEA navigation and dependency tools.

1. Work from the exact checkout being analysed.
2. Run `anatomist skill core` and follow its index and evidence gate.
3. Run `anatomist skill topics`, then load only the one relevant scene.
4. Before executing a selected command, read `anatomist <command> --help`; it is
   the source of truth for the installed CLI version.

Select one indexing profile: `.anatomist/config.toml`, otherwise
`~/.anatomist/config.toml`, otherwise built-in defaults. Files do not merge;
missing keys also use built-in defaults, and CLI flags override the selected
profile. Use `[scan]` for source scopes and project-relative include/exclude
globs. For an unexpected missing symbol, inspect `doctor --format json --index
<db>` for `config_source`, `config_path`, and `scan_policy_hash`; compare the
committed profile with the intended roots, then run `index --incremental` after
a policy change. It safely rebuilds fully when compatibility changes.

Prefer incremental index synchronization. Do not enable data-flow for ordinary
structural analysis; load `anatomist skill flow` only for explicit value,
definition, exception, guard, or taint questions.

Use `anatomist declarations-of --file <project-relative.java> --format json`
when a caller needs stable type/method/constructor seeds from changed files.
Use its AST-derived filters; do not parse Java declarations with regex.

Anatomist returns static source evidence, not proof of runtime execution or
business meaning. Use runtime evidence when the question asks what happened online.
