# CLI Command Reference

Query commands output JSON to stdout. Mutation commands default to text and support JSON where noted.

## Index Phase

### `index`
Index a Java project into SQLite.

```bash
anatomist index <project-path> [options]
```

| Flag | Description | Default |
|------|-------------|---------|
| `--output <path>` | SQLite output path | `.anatomist/index.db` |
| `--project-source <paths>` | Colon-separated source roots (relative to project) | auto-detect |
| `--classpath <jars>` | Colon-separated jar paths | auto-detect via Maven |
| `--no-classpath` | Skip classpath detection entirely | false |
| `--vm-classpath` | Use JVM's own classloading for JDK types | true |
| `--java-version <N>` | Target Java language level | auto-detect |
| `--exclude <dirs>` | Comma-separated directories to skip | none |
| `--include-tests` | Also index test sources | false |
| `--incremental` | Only re-parse changed files (uses file_cache) | false |
| `--spring-xml` | Also parse Spring XML `<beans>` configs into BEAN/DEFINED_BY/WIRES facts and XML property/map/list/ref config trees. Spring annotation Bean/MVC facts are indexed by default. | false |
| `--format json` | Emit a stable Agent summary: `command`, `status`, `schema_version`, `index_path`, `stats`, `warnings`, `errors` | text |

Example:

```bash
anatomist index . --format json --output /tmp/index.db
```

Full indexing also writes a source snapshot into `project_meta`. The most useful
keys for Agents are:

| Key | Meaning |
|-----|---------|
| `source_root` | Absolute project root used to resolve `source_window` snippets |
| `source_paths` | Source roots included in this index |
| `indexed_at` | Index timestamp |
| `source_git_commit` | Git commit of the indexed source tree, when available |
| `source_git_branch` | Git branch, when available |
| `source_git_dirty` | Whether the source worktree had uncommitted changes |
| `source_git_commit_time` | Commit timestamp, when available |
| `source_git_remote_origin_url` | Origin URL, when available |

### `doctor`
Report CLI capabilities, schema version, and index health.

```bash
anatomist doctor --format json [--index <db>]
```

JSON includes:

| Field | Meaning |
|-------|---------|
| `version` | CLI version |
| `schema_version` | Current writer schema |
| `default_index_path` / `index_path` | Resolved index locations |
| `index_exists` | Whether the target DB exists |
| `commands` | Supported subcommands for Agent self-discovery |
| `capabilities` | Stable feature flags such as Spring facts and JSON summaries |

### `survey-baseline`
Return a structural first-pass baseline for large repositories.

```bash
anatomist survey-baseline <project-path> --format json --index <db>
```

JSON includes:

| Field | Meaning |
|-------|---------|
| `overview` | Aggregate package/type/method/package-dependency counts |
| `budget` | Structural summary size metadata |
| `warnings` / `errors` | Machine-readable quality notes |
| `next_queries` | Suggested structural follow-up commands |

### `index-docs`
Index project markdown documents for FTS5 search.

```bash
anatomist index-docs <path> --index <db>
```

### `watch`
Monitor source tree, report or auto-index changes.

```bash
anatomist watch <project-path> [--auto-index] [--debounce-ms 500]
anatomist watch <project-path> --auto-index --output <db> \
  --project-source <paths> [--spring-xml] [--no-classpath|--classpath <jars>]
```

Use `watch` to keep an existing index fresh while editing. It is a file-change
watcher, not a runtime tracer.

| Case | Behavior |
|---|---|
| No `--auto-index` | Print `CREATE` / `MODIFY` / `DELETE` events only. |
| Source change with `--auto-index` | Run `index --incremental` against the same DB. |
| Build-file change (`pom.xml`, Gradle settings) | Run a full re-index because source roots/classpath may have changed. |
| Incremental cannot be trusted | `index --incremental` degrades to full, for example empty cache, schema mismatch, or too-large realign closure. |

For complex projects, pass the same indexing shape used for the initial index:

| Initial index flag | Matching watch flag |
|---|---|
| `--output <db>` | Always reuse the same `--output <db>`. |
| `--project-source <paths>` | Reuse it for multi-module or non-standard source roots. |
| `--spring-xml` | Reuse it when Spring XML `<beans>` should stay indexed as `WIRES` facts and XML config trees. |
| `--no-classpath` / `--classpath <jars>` | Reuse the same classpath policy so type resolution stays comparable. |
| `--java-version <N>` | Reuse it when the project is not detected correctly. |

`watch` keeps static anatomist facts current. It does not prove that a route,
branch, callback, bean profile, or runtime path actually executed.

## Query Phase

### `search`
Find nodes by name (FTS5), precise simple-name, or annotation.

```bash
anatomist search <term> [--kind CLASS|METHOD|...] [--limit 20] [--by-annotation] --index <db>
anatomist search <term> --limit 20 --offset 20 --index <db>
anatomist search --name '<glob>' [--kind CLASS|INTERFACE|...] [--count] --index <db>
anatomist search <term> --count --index <db>
```

- Default `<term>`: FTS5 match over qualified name / label / javadoc — **also matches package path tokens** (e.g. `search Facade` matches everything under a `.facade.` package). FTS results carry a `stats.label_matches` count: how many returned rows actually match the simple name, so an inflated `total` is easy to spot.
- `--name '<glob>'`: precise simple-name match against the label only (`*`/`?` globs, e.g. `--name '*Plugin'`). Bypasses FTS — use this to count/enumerate a naming pattern.
- `--count`: return only the true total (results omitted), **independent of `--limit`**. Works with `--name` or FTS.
- Search output always reports `stats.total`, `stats.limit`, `stats.offset`, `stats.truncated`, and `budget`, including the first page. Continue with `next_queries` when `stats.truncated=true`.

### `context`
Show node structure + optional enrichment.

```bash
anatomist context <fqn> [--with-callees=N] [--enrich] [--with-docs] [--package <pkg>] [--format markdown|json] --index <db>
anatomist context <fqn> --members-limit 50 --members-offset 50 --index <db>
```

- Default: node + fields + methods + annotations + framework facts (`DEFINED_BY`, `INJECTS`, `HANDLES`, `WIRES`)
- `--enrich`: adds semantic annotations, related docs, suggested queries
- `--format markdown`: 200-line budgeted output
- `--members-limit` / `--members-offset`: page class members for large classes
- `--methods-only` / `--fields-only`: narrow member paging by kind

### `bean-config`
Show structured Spring XML bean config trees.

```bash
anatomist bean-config FilterRegistry --property filters --index <db>
anatomist bean-config FilterRegistry --property filters --format json --index <db>
```

Use this when XML `map` / `list` structure carries behavior such as ordered
filter chains. `WIRES` only shows class dependency impact; `bean-config`
preserves keys, order, and nesting.

### `callees-of`
Outgoing call chain from a method.

```bash
anatomist callees-of <method-fqn> [--depth N] [--through-callbacks] [--source-window[=N]] [--blocks=class|package] --index <db>
anatomist callees-of <method-fqn> --depth 3 --limit 50 --offset 50 --filter Order --index <db>
anatomist callees-of <method-fqn> --depth 2 --source-window=3 --index <db>
```

- Max depth: 20. BFS with dedup (no infinite loops).
- `--blocks`: slices chain into class or package blocks.
- `--through-callbacks`: follow CALLS made inside anonymous-class / lambda bodies defined in the method (and nested), attributing them to the method. Essential for template-callback code (`SettleServiceTemplate#execute(callback)`, `TransactionTemplate.execute(...)`, stream lambdas) where the real downstream logic lives in the callback body. Synthesized edges are tagged `call_kind=CALLBACK` (when no original kind) and carry `via=<body-id>` pointing at the callback the call physically came from.
- `--source-window[=N]`: attach a `source_window` object to each emitted edge, using `source_location` plus `project_meta.source_root`. Default context is 3 lines when the flag is present. Use it when an Agent answer needs source evidence without opening every file separately.
- `--limit` / `--offset` / `--filter`: page wide call graphs and narrow by source/target/relation substring. JSON always includes paging stats and `budget`, including the first page.

`source_window` JSON shape:

| Field | Meaning |
|-------|---------|
| `path` | Absolute source file path |
| `line` | Edge line number |
| `start_line` / `end_line` | Included snippet range |
| `snippet` | Numbered source lines |

### `callers-of`
Incoming call chain (impact analysis).

```bash
anatomist callers-of <method-fqn> [--depth N] [--through-callbacks] [--source-window[=N]] [--blocks=class|package] --index <db>
anatomist callers-of <method-fqn> --depth 3 --limit 50 --offset 50 --filter <keyword> --index <db>
anatomist callers-of <method-fqn> --depth 2 --source-window=3 --index <db>
```

- Pierces interface/abstract dispatch via OVERRIDES (interface method → implementors).
- `--through-callbacks`: when an incoming call originates inside an anonymous-class / lambda body, attribute it to the enclosing real method (tagged `via=<body-id>`, `call_kind=CALLBACK`) instead of reporting the synthetic `$anon@…#process()` node — so impact analysis reaches the actual caller.
- `--source-window[=N]`: attach numbered source snippets to returned caller edges. Good for impact reports where each caller needs file/line evidence.
- `--limit` / `--offset` / `--filter`: page wide impact graphs and continue with `stats.next_offset`. JSON always includes paging stats and `budget`, including the first page.

### `call-path`
Shortest path between two methods.

```bash
anatomist call-path <from-fqn> <to-fqn> [--depth N] [--through-callbacks] [--source-window[=N]] [--blocks=class|package] --index <db>
```

- `--through-callbacks`: allow the shortest-path BFS to traverse calls made inside anonymous-class / lambda callback bodies. Callback hops keep the outer method as `source` and record the physical body in `via`.
- `--source-window[=N]`: attach source snippets to each hop. Use it when explaining a concrete end-to-end path.

### `branches-of`
Group branch-contained `CALLS` / `READS` / `WRITES` for a method.

```bash
anatomist branches-of <method-fqn> [--depth N] [--through-callbacks] [--source-window[=N]] --index <db>
anatomist branches-of <method-fqn> --depth 3 --source-window=3 --index <db>
```

- Reuses existing `edge.context` facts such as `if-then@L42` and `if-else@L42`; it does not build a full CFG.
- `--source-window[=N]`: attach source snippets around the branch line so Agents can read the condition.
- `--depth`: include downstream methods reached by the existing `callees-of` traversal.

### `hierarchy`
Inheritance chain + interfaces for a type.

```bash
anatomist hierarchy <type-fqn> --index <db>
```

### `implementors-of`
Classes implementing an interface/extending a type.

```bash
anatomist implementors-of <type-fqn> [--recursive] [--count] --index <db>
```

- Default: direct implementors/subtypes (one IMPLEMENTS/INHERITS hop).
- `--recursive`: transitive closure — surfaces leaf concrete classes reached through intermediate abstract bases.
- `--count`: return only the count of implementors (results omitted).

### `deps-of`
Outgoing dependencies (CALLS + REFERENCES + WIRES + INJECTS + HANDLES + DEFINED_BY).

```bash
anatomist deps-of <type> [--limit 50] [--offset 0] [--filter <keyword>] --index <db>
```

### `used-by`
Incoming dependencies (impact analysis), including Spring MVC route handlers and DI/configuration facts.

```bash
anatomist used-by <type> [--limit 50] [--offset 0] [--filter <keyword>] --index <db>
```

### `field-access`
Who reads/writes a field.

```bash
anatomist field-access <field-ref> [--mode reads|writes|all] [--limit 50] [--offset 0] [--filter <keyword>] --index <db>
```

### `overview`
Project-wide statistics and package dependency skeleton.

```bash
anatomist overview [--format markdown|json] [--depth N] [--deps-only] [--limit 30] [--offset 0] --index <db>
```

- Includes: node kind counts, edge counts, package tallies, package deps
- `--deps-only`: output only package dependency edges
- `--depth N`: collapse package tree to N segments

### `export`
Self-contained HTML visualization.

```bash
anatomist export --format html --output <file.html> [--max-edges 20000] --index <db>
```

## Annotation Phase

### `annotate`
Write user-supplied semantic annotations.

```bash
# Manual annotation
anatomist annotate <node-id> --category REVIEWED --label "reviewed" --index <db>

# Batch from JSON file
anatomist annotate --from-json annotations.json --index <db>
```

## Agent Integration Contract

Use these commands for deterministic tool integration:

| Need | Command |
|------|---------|
| Discover CLI/schema/capabilities | `anatomist doctor --format json` |
| Build index and verify success | `anatomist index <repo> --format json` |
| Build a structural first-pass baseline | `anatomist survey-baseline <repo> --format json --index <db>` |
| Query code facts | `anatomist search <term> --limit 50 --index <db>` |

All subcommands support `--help` for self-discovery.

## Pagination

`deps-of`, `used-by`, `field-access`, `overview --deps-only` support:

| Flag | Description | Default |
|------|-------------|---------|
| `--limit N` | Results per page | 50 (30 for overview) |
| `--offset N` | Skip N results | 0 |
| `--filter <keyword>` | Substring match on target label/FQN | none |

JSON stats include `{"total": N, "limit": N, "offset": N, "truncated": bool}`. When more rows exist, outputs add `next_offset` and `next_queries`. Paged commands include top-level `budget` so Agents can distinguish emitted rows from total matches.

## Large Repository Workflow

Use this progressive path instead of asking for everything at once:

| Step | Command |
|------|---------|
| 1. Health and capabilities | `anatomist doctor --format json --index <db>` |
| 2. Structural project baseline | `anatomist survey-baseline <repo> --format json --index <db>` |
| 3. Package skeleton | `anatomist overview --deps-only --limit 50 --offset 0 --index <db>` |
| 4. Symbol search | `anatomist search <term> --limit 50 --offset 0 --index <db>` |
| 5. Type drill-down | `anatomist context <type> --members-limit 50 --index <db>` |
| 6. Flow drill-down | `anatomist callees-of <method> --depth 3 --limit 50 --index <db>` |
| 7. Source-backed proof | `anatomist callees-of <method> --depth 2 --limit 20 --source-window=3 --index <db>` |
| 8. Branch slice | `anatomist branches-of <method> --depth 3 --source-window=3 --index <db>` |

## Context Filters

`callees-of`, `callers-of`, `deps-of`, `used-by`, `field-access` support:

| Flag | Description |
|------|-------------|
| `--in-loop` | Keep only edges inside loops (for/while/do) |
| `--in-branch` | Keep only edges inside branches (if/else/case/catch) |
