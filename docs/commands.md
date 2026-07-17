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
| `--output <path>` | SQLite output path | `$ANATOMIST_HOME/indexes/<repo-key>/index.db` |
| `--project-source <paths>` | Colon-separated source roots (relative to project) | auto-detect |
| `--source-root <module>@<scope>=<path>` | Explicit module/scope identity; repeat for each root. Mutually exclusive with `--project-source` | inferred |
| `--classpath <jars>` | Colon-separated jar paths | auto-detect via Maven |
| `--no-classpath` | Skip classpath detection entirely | false |
| `--vm-classpath` | Use JVM's own classloading for JDK types | true |
| `--java-version <N>` | Target Java language level | auto-detect |
| `--dataflow` | Build optional CFG, def-use, return, exception, guard, and interprocedural flow facts | false |
| `--dataflow-mode off\|full\|summary\|scoped` | Choose full detail, summaries only, or selected detail. `--dataflow` remains an alias for `full`. | off |
| `--dataflow-scope <kind:glob>` | Repeatable `package:`, `method:`, or `source:` selector; implies `scoped` when mode is omitted. | none |
| `--implicit-taint` | Propagate taint through control dependencies; implies `--dataflow` | false |
| `--exclude <dirs>` | Comma-separated directories to skip | none |
| `--include-tests` | Also index test sources | false |
| `--incremental` | Only re-parse changed files (uses file_cache) | false |
| `--verify-content` | Hash every indexed source during standalone incremental scans instead of trusting unchanged size/mtime. Watch candidates are always hashed. | false |
| `--spring-xml` | Also parse Spring XML `<beans>` configs into BEAN/DEFINED_BY/WIRES facts and XML property/map/list/ref config trees. Spring annotation Bean/MVC facts are indexed by default. | false |
| `--timings` | Add per-phase milliseconds to text output or JSON `timings_ms`, including incremental `symbol_delta`, `impact_analysis`, `graph_replace`, and metadata sub-phases. Output is unchanged when omitted. | false |
| `--format json` | Emit a stable Agent summary: `command`, `status`, `schema_version`, `index_path`, `stats`, `warnings`, `errors` | text |
| `--health-policy none\|integrity\|complete` | Select the health gate; see the table below | none |
| `--strict-health` | Compatibility alias for `--health-policy complete` | false |

Target-project language support is Java 8–17. Detection precedence is
`--java-version` → `.anatomist/config.toml` → Maven/Gradle declarations → Java 8.
Maven detection reads compiler `release`/`source`, plugin configuration, local
parent properties, and property references. Gradle detection statically reads
toolchains, compatibility/release assignments, and simple `gradle.properties`
references; it never executes a build script. Java 18+ is rejected before parsing.

JSON `stats` reports `scanned_files`, `attempted_files`, `parsed_files`,
`failed_files`, `parse_completeness`, and `completeness`. A failed file makes
health degraded and is not cached. `index_state` distinguishes a committed
snapshot from a rejected build. `health_dimensions` separates parse,
graph-integrity, and internal/external/JDK resolution quality; `gate` reports
the selected policy, pass/fail result, and blocking diagnostic codes.

| Policy | Blocks | Intended use |
|---|---|---|
| `none` | Nothing; health is disclosure only | Interactive inspection |
| `integrity` | Parse failure, dangling facts, schema/empty/promotion failure | Normal Agent code-analysis gate |
| `complete` | Every warning or error, including third-party resolution gaps | Completeness-sensitive automation |

A failed gate returns exit code 3. `--strict-health` is exactly
`--health-policy complete`; combining it with another policy is an argument
error (exit code 2).

Example:

```bash
anatomist index . --format json --output /tmp/index.db
```

With `--timings`, full indexing keeps `full_index` as the parent measurement and
reports `full_stage_write`, `full_stage_resolve`, and `full_stage_promote` for
the file-backed streaming path. Incremental runs report `stage_write` and
`stage_promote`; Watch also reports `staging_setup` and `known_ids` for its reused
session state. Change detection reports `file_stat` and only reports `file_hash`
when bytes were read. Impact queries are split into `impact_exact` and
`impact_prefix`; metadata separates asynchronous Git work from `git_status_wait`.
The older `full_write_*` keys remain as compatibility aliases.
Classpath-backed runs additionally report `classpath_index_build`,
`type_cache_load`, and `type_cache_write`. These include packed-cache work and
are absent when indexing without dependency classpath entries.
It adds non-overlapping top-level children for schema setup, parse/extract, project
analysis, graph rewriting, SQLite writes, file-cache/metadata work, dependency
refresh, `ANALYZE`, and final statistics/health. `full_parse_extract` is further
split into parser overhead and extractor work; extractor keys identify type,
field, method, annotation, hierarchy, reference, call-graph, field-access,
reflection, framework-analyzer, and source-origin costs. Parent and nested measurements
overlap by design and must not all be summed together.

Full indexing also writes a source snapshot into `project_meta`. The most useful
keys for Agents are:

| Key | Meaning |
|-----|---------|
| `source_root` | Absolute project root used to resolve `source_window` snippets |
| `source_paths` | Source roots included in this index |
| `source_layout` / `source_layout_hash` | Canonical module/scope/root mapping and its incremental compatibility fingerprint |
| `source_snapshot_fingerprint` | Portable hash of logical source identities and file contents; excludes absolute paths and timestamps |
| `indexed_at` | Index timestamp |
| `source_git_commit` | Git commit of the indexed source tree, when available |
| `source_git_branch` | Git branch, when available |
| `source_git_dirty` | Whether the source worktree had uncommitted changes |
| `source_git_commit_time` | Commit timestamp, when available |
| `source_git_remote_origin_url` | Origin URL, when available |

### `doctor`
Report CLI capabilities, schema version, and index health.

```bash
anatomist doctor --format json [--index <db>] \
  [--diagnostic-file <path>] [--diagnostic-code <reason>] \
  [--diagnostic-scope MAIN] [--diagnostic-module <module>] \
  [--diagnostic-phase <phase>] [--offset 0] [--limit 100]
```

JSON includes:

| Field | Meaning |
|-------|---------|
| `version` | CLI version |
| `schema_version` | Current writer schema |
| `default_index_path` / `index_path` | Resolved index locations |
| `index_exists` | Whether the target DB exists |
| `source_root` / `source_snapshot_fingerprint` | Local checkout ownership and portable indexed-source identity |
| `java_version` / `classpath_mode` / `spring_xml` | Index profile used to build the current facts |
| `classpath_detection` | `full`, `partial`, `unavailable`, `cache_hit`, `explicit`, or `not_requested`, plus Maven exit/sample/counts |
| `commands` | Supported subcommands for Agent self-discovery |
| `capabilities` | Stable feature flags such as Spring facts and JSON summaries |
| `index_state` | `committed`, `empty`, `incompatible`, `missing`, or `unknown` |
| `health` / `health_dimensions` / `gate` | Legacy summary, dimension detail, and selected policy result |
| `diagnostics` | Persisted findings shared with `index` and `survey-baseline` |
| `diagnostic_stats` | Total/matched/page counts for bounded diagnostic output |
| `git_untracked_cache` | Repository Git setting: `enabled`, `disabled`, or `unknown` |

When Git untracked cache is not enabled, `doctor` reports non-mutating advice:

```bash
git config core.untrackedCache true
```

Anatomist never runs this command automatically. With `--timings`, a slow
incremental Git status check prints the same advice once per Watch process.

Use `--health-policy integrity` for the normal Agent gate. Use
`--strict-health` only when any persisted warning must fail the command.
Without a policy, health is reported but does not change the exit code.

The default DB stays under the tool-owned `$ANATOMIST_HOME` cache (default
`~/.anatomist`) so repositories are not polluted with generated SQLite files.
`<repo-key>` combines a sanitized checkout basename with the first 12 hex
characters of SHA-256(realpath), so same-named checkouts do not collide. This
path is a machine-local locator; portable consumers should persist
`source_snapshot_fingerprint`, not `index_path`.

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
  --project-source <paths> [--spring-xml] [--timings] [--no-classpath|--classpath <jars>] \
  [--full-policy background|inline|manual]
```

Use `watch` to keep an existing index fresh while editing. It is a file-change
watcher, not a runtime tracer.

| Case | Behavior |
|---|---|
| No `--auto-index` | Print `CREATE` / `MODIFY` / `DELETE` events only. |
| Source change with `--auto-index` | Run `index --incremental` against the same DB. |
| Body-only or uniquely named member addition | Contract fingerprint stays stable, so update stable nodes in place; preserve incoming edges and Spring wiring without reparsing unchanged callers. |
| Removed/renamed/contract-changed symbol or overload family | Reparse only source files selected by exact internal/external symbol edges. |
| Build-file change (`pom.xml`, Gradle settings) | Re-resolve the index environment. Continue incrementally when inputs are unchanged; otherwise request a full rebuild. |
| Incremental cannot be trusted | Empty cache, schema mismatch, source-layout drift, or a symbol-impact cap requests a full rebuild rather than blocking the event loop. |
| `--full-policy background` | Default. Build a sibling temporary DB while WatchService keeps collecting events; replay collected changes, then switch the complete DB under a short lock. |
| `--full-policy inline` | Legacy behavior: run full indexing in the watch process. Use only when blocking event collection is acceptable. |
| `--full-policy manual` | Keep the existing DB and mark it stale; run `anatomist index ... --full` yourself. |
| Second `watch --auto-index` for one DB | Fails with `WATCH_ALREADY_RUNNING`; one process must own the event stream. |
| Changed Java is temporarily unparsable | Keep the previous committed index and retry three times at `max(100ms, debounce-ms)` even if no second filesystem event arrives. |
| Parse retries are exhausted | Retain pending paths without a busy loop; the next source event resets the retry budget. Idle/iteration shutdown returns non-zero while work remains pending. |
| Other auto-index failure | Retain pending paths but do not enter the timed parse-retry loop. |
| `--fail-fast` | Exit immediately on the first failed auto-index attempt, including a parse failure. |

For complex projects, pass the same indexing shape used for the initial index:

| Initial index flag | Matching watch flag |
|---|---|
| `--output <db>` | Always reuse the same `--output <db>`. |
| `--project-source <paths>` | Reuse it for multi-module or non-standard source roots. |
| `--spring-xml` | Reuse it when Spring XML `<beans>` should stay indexed as `WIRES` facts and XML config trees. |
| `--no-classpath` / `--classpath <jars>` | Reuse the same classpath policy so type resolution stays comparable. |
| `--java-version <N>` | Reuse it when the project is not detected correctly. |
| `--source-root ...` | Reuse every explicit module/scope mapping. |
| `--health-policy none\|integrity\|complete` | Apply the same health gate to auto-index results. |
| `--strict-health` | Compatibility alias for complete health. |
| `--timings` | Print discovery, change detection, parse/write/wiring/dependency, metadata sub-phases, and total costs for auto-index runs. |
| `--dataflow`, `--dataflow-mode`, `--dataflow-scope`, `--implicit-taint` | Reuse the exact same flow-analysis profile and selectors. |

For normal source edits, `watch --auto-index` forwards only the changed candidate
paths and reuses the resolved source roots, Spring XML inventory, classpath metadata,
JavaParser session, known node IDs, and an empty staging schema. Stable node IDs are
updated in place. Removed or contract-changed
symbols expand the batch through exact incoming edges, overload families, unresolved
external targets, and type owners until the impact set reaches a fixed point.
Filesystem overflow triggers reconciliation. Source-layout/environment changes fall back to
the full correctness scan. Background rebuilds keep the old complete index queryable; use
`doctor --index <db> --format json` and inspect `freshness_state` before relying on it as
current. `--max-realign-files` is a hard safety cap (default 1000);
below it, stored full/incremental timings choose incremental work only when its estimated
cost is at most 70% of the full baseline. Without history, the fallback budget is 20% of
Java files with a 200-file floor. Realignment parsing is streamed in batches of at most 128.

`watch` keeps static anatomist facts current. It does not prove that a route,
branch, callback, bean profile, or runtime path actually executed.

See [Troubleshooting](troubleshooting.md#watch-reports-a-java-parse-failure) for
parse errors observed during editor or code-generator writes.

### Agent query gate (P0)

Query commands are read-only: they do not index the current checkout first.
For a one-off Agent session, synchronize before querying and let the shell stop
on an index or health failure:

```bash
anatomist index <project-path> --incremental --health-policy integrity --format json --output <db> \
  && anatomist search OrderService --index <db>
```

| Case | Result |
|---|---|
| No source changes | Scan file metadata and return `Changed files: 0`; Maven detection, JavaParser, and graph replacement are skipped. |
| Source changes | Reparse the affected closure; incompatible environment/schema or excessive impact falls back to a full index. |
| Exact content verification required | Add `--verify-content`. This hashes every indexed file, but an unchanged graph is still not reparsed or rewritten. |
| Initial index used non-default flags | Reuse its `--project-source` / `--source-root`, `--include-tests`, `--spring-xml`, classpath policy, and `--java-version` in the gate. |
| Gate fails | Do not issue a query or call an older graph current. |

`doctor --health-policy integrity` verifies the existing DB but does not inspect source
files; `freshness_state=idle` only says a watcher operation finished. It is not
a substitute for the Agent query gate when users edited code without `watch`.

## Query Phase

All node-oriented query commands accept `--module <name>` and
`--scope MAIN|TEST|GENERATED|ALL`. The default is `scope=MAIN`; use `ALL` only
when duplicate symbols across scopes are intentional.

JSON query responses include top-level `evidence`:

| `evidence.status` | Meaning | Safe conclusion |
|---|---|---|
| `positive` | Facts were found; diagnostics may still make the result non-exhaustive | Returned facts are usable |
| `confirmed_empty` | No fact found and no relevant persisted gap applies | Negative conclusion is allowed |
| `indeterminate` | No fact found, but relevant parse/resolution coverage is partial | Do not conclude “none exists” |
| `partial_aggregate` | Aggregate counts omit an unknown amount of relevant facts | Treat totals as lower bounds |

`coverage`, `affected_dimensions`, `diagnostic_counts`, and
`negative_conclusion_safe` explain the decision. Ordinary empty queries still
exit 0 so existing callers can parse the response; `call-path` and flow
coverage errors keep their existing command-specific exit behavior.

### Flow queries

Flow facts are opt-in. Use `--dataflow` for a complete graph,
`--dataflow-mode summary` for compact method summaries, or one or more scoped
selectors:

```bash
anatomist index . --dataflow-mode summary
anatomist index . --dataflow-scope 'package:com.example.payment.**'
anatomist index . --dataflow-scope 'method:com.example.OrderService#checkout*'
anatomist index . --dataflow-scope 'source:service/src/main/java/com/example/**'
```

`flow-summary` works in every enabled mode. `flow-of`, `guards-of`, and
`exception-flow` require detailed coverage for the selected method.
`flow-path` and `taint-path` require `full`; scoped/summary indexes return
`FLOW_COVERAGE_INCOMPLETE` instead of a misleading empty path.

```bash
anatomist flow-of com.example.Service#run --depth 8 --index <db>
anatomist flow-path <source-method-or-node> <target-method-or-node> \
  [--from-slot arg:0] [--to-slot return] \
  [--include-control] [--include-exception] --depth 20 --index <db>
anatomist flow-summary com.example.Service#run --index <db>
anatomist guards-of com.example.Service#run --index <db>
anatomist exception-flow com.example.Service#run --index <db>
anatomist taint-path '*' '*' --depth 30 --index <db>
```

| Command | Evidence |
|---|---|
| `flow-of` | CFG, def-use, argument, return, and cross-method edges |
| `flow-path` | Shortest bounded static flow path; data edges only by default |
| `flow-summary` | `arg:n`/`this` to return or exception summaries |
| `guards-of` | Condition dependencies and true/false guarded facts |
| `exception-flow` | Explicit throw, catch, and declared exception propagation |
| `taint-path` | Configured source-to-sink path; sanitizer nodes stop traversal |

Taint rules live in `.anatomist/taint-rules.json`:

```json
{
  "sources": [{"method": "javax.servlet.*#getParameter*", "slot": "return"}],
  "sinks": [{"method": "java.sql.Statement#execute*", "slot": "arg:0"}],
  "sanitizers": [{"method": "com.example.SqlEscaper#escape*", "slot": "return"}]
}
```

Slot rules are strict:

| Rule/endpoint | Allowed slots |
|---|---|
| taint source | `return` |
| taint sink | `arg:N`, `this` |
| sanitizer | `return` |
| `flow-path --from-slot/--to-slot` | `arg:N`, `return`, `throw` |

Invalid taint slots are skipped with `TAINT_RULE_SLOT_INVALID`. A sink path is
accepted only through its configured argument/receiver edge. `flow-path`
traverses `DEF_USE`, argument, return, call argument/return, and taint edges by
default. Control/guard and exception edges require their explicit flags.
If a method selector matches multiple overloads, use the full method signature;
the command returns `FLOW_ENDPOINT_AMBIGUOUS` instead of searching all overloads.

Method patterns support full-string `*` and `?` glob matching. Matching uses a
bounded non-regex state machine: the configuration file is limited to 1 MiB,
each source/sink/sanitizer list to 256 valid rules, and each method pattern to
512 characters. Invalid or excess entries are skipped with
`TAINT_RULE_SKIPPED` / `TAINT_RULE_LIMIT_EXCEEDED`; an oversized or malformed
file is disabled with `TAINT_RULES_TOO_LARGE` / `TAINT_RULES_INVALID`.
Explicit data flow is the default.
`--implicit-taint` also adds possible taint flow through control guards.

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
- Exact `Method.invoke` / `Constructor.newInstance` targets appear as
  `CALLS` with `call_kind=REFLECTION`, `confidence=INFERRED`, and
  `metadata.via=reflection`. The original JDK reflection API call remains visible.

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

Exact `Class.forName`, method lookup, and constructor lookup targets appear as
`REFERENCES` with `metadata.via=reflection`. Dynamic or conflicting values do
not create guessed targets.

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
