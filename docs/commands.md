# CLI Command Reference

Query commands output JSON to stdout. Mutation commands default to text and support JSON where noted.

## Agent Guidance

### `skill`

Print a small decision guide for one analysis scene. With no scene, it prints
the mandatory core workflow.

```bash
anatomist skill [core|explore|trace|branch|relations|spring|flow|topics]
```

| Interface | Answers |
|---|---|
| `anatomist skill [scene]` | Why and when to use a command, query order, evidence boundaries, and cost choices |
| `anatomist <command> --help` | Exact syntax, parameters, defaults, and supported options |
| `anatomist doctor --agent-preflight --format json` | Facts about the current checkout, index health/profile, and the next safe action |

The interfaces are intentionally orthogonal: scene guidance points to commands
but does not duplicate their option reference.

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
| `--jdk-home <path>` | Local JDK used to build a native-image type catalog | `ANATOMIST_JDK_HOME`, else embedded JDK 8 catalog |
| `--dataflow` | Build optional CFG, def-use, return, exception, guard, and interprocedural flow facts | false |
| `--dataflow-mode off\|full\|summary\|scoped` | Choose full detail, summaries only, or selected detail. `--dataflow` remains an alias for `full`. | off |
| `--dataflow-scope <kind:glob>` | Repeatable `package:`, `method:`, or `source:` selector; implies `scoped` when mode is omitted. | none |
| `--implicit-taint` | Propagate taint through control dependencies; implies `--dataflow` | false |
| `--exclude <dirs>` | Comma-separated directories to skip | none |
| `--include-tests` | Also index test sources | false |
| `--scan-scope MAIN\|TEST\|GENERATED` | Repeatable source scope; replaces configured `scan.scopes` | config/default |
| `--scan-include <glob>` | Repeatable project-relative include glob; replaces configured `scan.include` | config/default |
| `--scan-exclude <glob>` | Repeatable project-relative exclude glob; replaces configured `scan.exclude` | config/default |
| `--incremental` | Only re-parse changed files (uses file_cache) | false |
| `--verify-content` | Hash every indexed source during incremental change detection instead of trusting unchanged size/mtime. | false |
| `--spring-xml` | Also parse Spring XML `<beans>` configs into BEAN/DEFINED_BY/WIRES facts and XML property/map/list/ref config trees. Spring annotation Bean/MVC facts are indexed by default. | false |
| `--timings` | Add per-phase milliseconds to text output or JSON `timings_ms`, including incremental `symbol_delta`, `impact_analysis`, `graph_replace`, and metadata sub-phases. Output is unchanged when omitted. | false |
| `--format json` | Emit a stable Agent summary: `command`, `status`, `schema_version`, `index_path`, `stats`, `warnings`, `errors` | text |
| `--health-policy none\|integrity\|complete` | Select the health gate; see the table below | none |
| `--strict-health` | Compatibility alias for `--health-policy complete` | false |

### Configuration

Exactly one configuration file is selected; files are never merged:

```text
<project>/.anatomist/config.toml exists?
          │
      yes ├──► use project config only
          │
       no └──► ~/.anatomist/config.toml exists?
                       │
                   yes ├──► use user config
                       │
                    no └──► use built-in defaults
```

Therefore an empty project config also replaces the whole user config. Missing
keys in the selected file use built-in defaults. `ANATOMIST_CONFIG` is not a
supported override. CLI options override the selected file.

```toml
[index]
java_version = 17
spring_xml = false
vm_classpath = true
dataflow = false
dataflow_mode = "off"
dataflow_scopes = []
implicit_taint = false

[scan]
scopes = ["MAIN", "GENERATED"]
include = ["src/**", "modules/**/src/**"]
exclude = ["**/*IT.java", "**/generated/**"]

[external]
exclude_patterns = ["java.lang.*", "com.example.generated.**"]
```

| `[scan]` key | Meaning | Default |
|---|---|---|
| `scopes` | Auto-detected source-root kinds to scan: `MAIN`, `TEST`, `GENERATED` | `MAIN`, `GENERATED` |
| `source_roots` | Explicit `module@scope=project/relative/path` roots | none |
| `include` | A file must match at least one project-relative glob | `**` |
| `exclude` | A matching file is removed after include matching | empty |

`source_roots` and `scopes` are mutually exclusive. `**` crosses directories;
`*` and `?` stay within one path segment. Absolute paths, `..`, unknown keys,
wrong types, duplicate keys, and malformed TOML fail with exit code 2. The old
`[index].include_tests` and `[index].exclude` keys are rejected; use `[scan]`.
Hard-excluded directories (`target`, `build`, `.gradle`, `.git`, `.idea`, and
`node_modules`) remain protected, except an explicitly detected generated source
root under `target`.

The effective scan policy is stored as `scan_policy` and `scan_policy_hash` in
`project_meta`. Changing roots, scopes, include/exclude rules, or the hard policy
causes `index --incremental` to rebuild fully so removed files cannot remain stale.

Target-project language support is Java 8–25. Detection precedence is
`--java-version` → selected `config.toml` → Maven/Gradle declarations → Java 8.
Maven detection reads compiler `release`/`source`, plugin configuration, local
parent properties, and property references. Gradle detection statically reads
toolchains, compatibility/release assignments, and simple `gradle.properties`
references; it never executes a build script.

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
| `complete` | Every warning/error and every disclosed resolution gap, including informational third-party gaps | Completeness-sensitive automation |

A failed gate returns exit code 3. `--strict-health` is exactly
`--health-policy complete`; combining it with another policy is an argument
error (exit code 2).

For Maven reactors, default source discovery also indexes Java files below
`target/generated-sources/*` as `scope=GENERATED` with the owning module. Other
content below `target` remains excluded. Incremental indexing detects generated
file additions, changes, deletions, and newly created generated-source roots.

### Local JDK catalogs

Native Anatomist carries a verified JDK 8 catalog and never downloads JDK
metadata. For a Java 9–25 target, point it at a matching installed JDK:

```bash
anatomist index . --java-version 17 --jdk-home /path/to/jdk-17
# or: ANATOMIST_JDK_HOME=/path/to/jdk-17 anatomist index . --java-version 17
```

The JDK home must contain a matching `release` file and either JDK 8's
`rt.jar` or JDK 9+'s `jmods/`. Anatomist builds a catalog once and caches it
under `$ANATOMIST_HOME/catalogs`; the cache identity includes the catalog
generation, JDK release, and archive fingerprint. A mismatched path is rejected rather than silently
using a different API surface. JVM runs continue to use ReflectionTypeSolver.

Example:

```bash
anatomist index . --format json --output /tmp/index.db
```

With `--timings`, full indexing keeps `full_index` as the parent measurement and
reports `full_stage_write`, `full_stage_resolve`, and `full_stage_promote` for
the file-backed streaming path. Incremental runs report `stage_write` and
`stage_promote`. Change detection reports `file_stat` and only reports `file_hash`
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
field, method, declaration, annotation, hierarchy, reference, call-graph, field-access,
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
| `classpath_input_hash` | Maven POM/settings/JDK input identity; a changed or missing value forces the next incremental index to rebuild with a fresh classpath |
| `config_source` / `config_path` | Which configuration was selected: `project`, `user`, or built-in `default`, plus its path when present |
| `scan_policy` / `scan_policy_hash` | Canonical resolved scan roots/scopes/globs and its compatibility fingerprint |

### `doctor`
Report CLI capabilities, schema version, and index health.

```bash
anatomist doctor --format json [--index <db>] \
  [--agent-preflight] \
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
| `source_root` / `source_snapshot_fingerprint` / `source_snapshot` | Local checkout ownership, portable indexed-source identity, and indexed-vs-current Git commit match when available |
| `java_version` / `classpath_mode` / `spring_xml` | Index profile used to build the current facts |
| `config_source` / `config_path` / `scan_policy_hash` | Selected configuration and the scan-policy identity committed into the index |
| `classpath_detection` | Detection status plus `origin`; `maven_classpath_files` counts Maven output files and `build_output_entries` counts discovered `target/classes` / `target/test-classes`. Legacy `module_output_files` is deprecated. |
| `commands` | Supported subcommands for Agent self-discovery |
| `capabilities` | Stable feature flags such as Spring facts and JSON summaries |
| `index_state` | `committed`, `empty`, `incompatible`, `missing`, or `unknown` |
| `health` / `health_dimensions` / `gate` | Legacy summary, dimension detail, and selected policy result |
| `resolution_diagnostic_counts` | Occurrences grouped by resolution diagnostic code, independent of diagnostic page size |
| `resolution_diagnostic_groups` | Retained distinct file/phase/code/symbol/source-site groups; unlike counts, this is bounded by diagnostic retention |
| `diagnostics` | Persisted findings shared with `index` and `survey-baseline` |
| `diagnostic_stats` | Total/matched/page counts for bounded diagnostic output |
| `diagnostic_aggregation` | Whether the 50,000-group aggregation bound was reached and how many later occurrences were folded into metadata |
| `diagnostic_storage` | Whether persisted diagnostic samples were truncated, including retained and omitted group counts |
| `diagnostic_coverage.files` | With `--diagnostic-file`, pre-storage-retention file/module/scope coverage rows; capability `file-resolution-coverage` advertises this contract |
| `git_untracked_cache` | Repository Git setting: `enabled`, `disabled`, or `unknown` |
| `agent_preflight` | Present with `--agent-preflight`: read-only Agent readiness, blockers, flow coverage, and next commands |

When Git untracked cache is not enabled, `doctor` reports non-mutating advice:

```bash
git config core.untrackedCache true
```

Anatomist never runs this command automatically. With `--timings`, a slow
incremental Git status check prints the same advice once per process.

Use `--health-policy integrity` for the normal Agent gate. Use
`--strict-health` when warnings or any disclosed resolution gap must fail the command.
Without a policy, health is reported but does not change the exit code.

Use `--agent-preflight` before an Agent starts a static investigation. It never
re-indexes or changes configuration; `agent_preflight.next_commands` contains
the required repair or index gate command.

`UNRESOLVED_SYMBOLS` is the aggregate of the individual resolution reason
counts; do not add it to those reasons a second time. Informational third-party
gaps can coexist with top-level `health=healthy`: inspect
`health_dimensions.resolution.external` or the complete gate when exhaustive
negative conclusions matter.

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

The canonical project path is stored as `project_meta.source_root`. Reusing an
explicit database for a different project fails with `INDEX_PROJECT_MISMATCH`
before documents or metadata are changed. A symlink resolving to the same
project is accepted.

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
files; `freshness_state=idle` is not a source-tree comparison and cannot replace
the Agent query gate after offline edits.

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
An unresolved selector is not an empty query: it exits 2 with
`SYMBOL_NOT_FOUND`. Only a successfully resolved symbol with no matching facts
may produce `confirmed_empty`.

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
`taint-path` requires `full`. `flow-path` requires either `full` or DETAIL
coverage for both endpoints; use `flow-materialize` to add DETAIL facts for one
bounded static call path. A path found with partial coverage is usable positive
static evidence; an empty partial result never proves absence.

```bash
anatomist flow-of com.example.Service#run --depth 8 --index <db>
anatomist flow-path <source-method-or-node> <target-method-or-node> \
  [--from-slot arg:0] [--to-slot return] \
  [--include-control] [--include-exception] --depth 20 --index <db>
anatomist flow-summary com.example.Service#run --index <db>
anatomist guards-of com.example.Service#run --index <db>
anatomist exception-flow com.example.Service#run --index <db>
anatomist taint-path '*' '*' --depth 30 --index <db>
anatomist flow-materialize <source-method-signature> <target-method-signature> --depth 8 --index <db>
```

| Command | Evidence |
|---|---|
| `flow-of` | CFG, def-use, argument, return, and cross-method edges |
| `flow-path` | Shortest bounded static flow path; data edges only by default |
| `flow-materialize` | Explicitly write DETAIL facts for source files on one shortest static call path |
| `flow-summary` | `arg:n`/`this` to return or exception summaries |
| `guards-of` | Condition dependencies and true/false guarded facts |
| `exception-flow` | Explicit throw, catch, and declared exception propagation |
| `taint-path` | Configured source-to-sink path; sanitizer nodes stop traversal |

Traversal output reports requested/effective/reached depth and whether an
expandable frontier remains. `flow-of --limit` is a compute budget rather than
an offset page: when `limit_truncated=true`, rerun its larger-limit
`next_queries` entry. `flow-path` and `taint-path` empty results are conclusive
only when `depth_truncated=false` and evidence permits a negative conclusion.
`flow-materialize` writes only after the structural index passes its integrity
and freshness checks. It does not write when the static call path is absent or
depth-truncated; use its `next_commands` to increase depth or re-index first.
Both materialization endpoints must be full exact method signatures. Missing,
family, or ambiguous selectors fail before the write lock is acquired and leave
all flow tables unchanged.

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

### Selector contract

| Selector | Meaning |
|---|---|
| `pkg.Type#method(java.lang.String)` | Exact signature; a miss never degrades to the method family |
| `pkg.Type#method` | Only that owner's overload family; never `methodExtra` |
| `Type#method` or bare `method` | Valid only when it identifies one owner; otherwise `SYMBOL_AMBIGUOUS` |
| Type/field short name | Valid only when unique across the selected module and scope |
| Storage node key | Exact node, still constrained by `--module` and `--scope` |

`callees-of`, `callers-of`, `branches-of`, and `flow-summary` may aggregate one
owner-qualified overload family. Single-target context, path, relation, and
detailed-flow commands require one resolved target. Ambiguity exits with code 2
and returns exact candidate IDs and follow-up queries. `search` remains explicitly
fuzzy and is not governed by this selector contract.

Method patterns support full-string `*` and `?` glob matching. Matching uses a
bounded non-regex state machine: the configuration file is limited to 1 MiB,
each source/sink/sanitizer list to 256 valid rules, and each method pattern to
512 characters. Invalid or excess entries are skipped with
`TAINT_RULE_SKIPPED` / `TAINT_RULE_LIMIT_EXCEEDED`; an oversized or malformed
file is disabled with `TAINT_RULES_TOO_LARGE` / `TAINT_RULES_INVALID`.
Explicit data flow is the default.
`--implicit-taint` also adds possible taint flow through control guards.

### `search`
Find project nodes and query-only external classpath types by name (FTS5), precise simple-name, or annotation.

```bash
anatomist search <term> [--kind CLASS|METHOD|...] [--limit 20] [--by-annotation] --index <db>
anatomist search SafeFastjsonParser --kind EXTERNAL_CLASS --index <db>
anatomist search <term> --limit 20 --offset 20 --index <db>
anatomist search --name '<glob>' [--kind CLASS|INTERFACE|...] [--count] --index <db>
anatomist search <term> --count --index <db>
```

- Default `<term>`: FTS5 match over qualified name / label / javadoc — **also matches package path tokens** (e.g. `search Facade` matches everything under a `.facade.` package). FTS results carry a `stats.label_matches` count: how many returned rows actually match the simple name, so an inflated `total` is easy to spot.
- Default name/FTS search also appends matching virtual `EXTERNAL_CLASS` rows derived from project external edges. Use `--kind EXTERNAL_CLASS` to return only those rows. They carry `external_target=true`, `external_edge_count`, and relation/resolution/confidence count maps; Anatomist does not create a source node or index the dependency JAR.
- `--by-annotation` searches only project nodes because dependency annotations are not indexed.
- `--by-annotation` requires `<term>`; `--count --by-annotation` counts the same annotation result set.
- `--name '<glob>'`: precise simple-name match against the label only (`*`/`?` globs, e.g. `--name '*Plugin'`). Bypasses FTS — use this to count/enumerate a naming pattern.
- `--count`: return only the true total (results omitted), **independent of `--limit`**. Works with `--name` or FTS.
- Search output always reports `stats.total`, `stats.limit`, `stats.offset`, `stats.truncated`, and `budget`, including the first page. Continue with `next_queries` when `stats.truncated=true`.

### `declarations-of`

Enumerate Java declarations already extracted from one parsed source file. The
query is read-only and never scans source text.

```bash
anatomist declarations-of \
  --file src/main/java/com/example/AuthenticationService.java \
  [--scope MAIN|TEST|GENERATED|ALL] [--module <name>] \
  [--visibility public,protected,private,package] \
  [--kind type,method,constructor] \
  [--top-level-types] [--direct-members] [--include-synthetic] \
  [--limit 100] [--offset 0] [--format json] --index <db>
```

| Field/flag | Contract |
|---|---|
| `symbol_id` | Stable logical symbol accepted by other Anatomist queries; overloads include the full parameter signature |
| `declaration_kind` | `type`, `method`, or `constructor`; callers never infer constructors from names |
| `type_kind` | `class`, `interface`, `enum`, `record`, or `annotation` for type rows |
| `modifiers` | Effective Java modifiers; `declared_modifiers` and `implicit_modifiers` preserve provenance |
| `visibility` | Effective `public`, `protected`, `private`, or `package` visibility |
| `nesting_depth` | Top-level type is 0; its direct callables/nested types are 1 |
| `direct_member` | True only for a callable directly owned by a top-level type |
| `--top-level-types` | Filters nested type rows only |
| `--direct-members` | Filters method/constructor rows to direct members of top-level types |

`--file` must be a normalized, project-relative `.java` path. Defaults enumerate
all explicit source declarations, including private/package declarations, but
exclude synthesized declarations. Filters are SQL predicates over persisted AST
facts. Pagination always reports `stats` and `budget` truncation independently.

Fail-closed evidence errors return exit code 3 with empty `results`,
`coverage=incomplete`, and `negative_conclusion_safe=false`. Stable codes are
`INDEX_MISSING`, `SCHEMA_MISMATCH`, `INDEX_STALE`, `FILE_NOT_INDEXED`,
`FILE_PARSE_FAILED`, `DECLARATION_COVERAGE_INCOMPLETE`, and
`GRAPH_INTEGRITY_FAILED`.

Diorama-ready example:

```json
{
  "query": "declarations-of --file src/main/java/com/example/AuthenticationService.java --scope MAIN --visibility public,protected --kind type,method --top-level-types --direct-members --limit 100",
  "results": [
    {
      "symbol_id": "com.example.AuthenticationService",
      "qualified_name": "com.example.AuthenticationService",
      "label": "AuthenticationService",
      "kind": "CLASS",
      "declaration_kind": "type",
      "type_kind": "class",
      "visibility": "public",
      "modifiers": ["public"],
      "declared_modifiers": ["public"],
      "implicit_modifiers": [],
      "source_file": "src/main/java/com/example/AuthenticationService.java",
      "source_location": "L8",
      "module": ".",
      "scope": "MAIN",
      "nesting_depth": 0,
      "direct_member": false,
      "synthetic": false
    },
    {
      "symbol_id": "com.example.AuthenticationService#authenticate()",
      "qualified_name": "com.example.AuthenticationService#authenticate",
      "label": "authenticate",
      "kind": "METHOD",
      "declaration_kind": "method",
      "visibility": "public",
      "modifiers": ["public"],
      "declared_modifiers": ["public"],
      "implicit_modifiers": [],
      "declaring_type": "com.example.AuthenticationService",
      "source_file": "src/main/java/com/example/AuthenticationService.java",
      "source_location": "L12",
      "module": ".",
      "scope": "MAIN",
      "nesting_depth": 1,
      "direct_member": true,
      "synthetic": false
    }
  ],
  "stats": {"total": 2, "offset": 0, "limit": 100, "truncated": false},
  "budget": {"mode": "rows", "emitted": 2, "total": 2, "truncated": false},
  "evidence": {"status": "positive", "coverage": "complete", "negative_conclusion_safe": true}
}
```

### `context`
Show node structure + optional enrichment.

```bash
anatomist context <fqn> [--with-callees=N] [--format markdown|json] --index <db>
anatomist context <fqn> --enrich [--with-docs] [--format markdown|json] --index <db>
anatomist context --enrich --package <pkg> [--with-docs] [--format markdown|json] --index <db>
anatomist context <fqn> --members-limit 50 --members-offset 50 --index <db>
```

- Default: node + fields + methods + annotations + framework facts (`DEFINED_BY`, `INJECTS`, `HANDLES`, `WIRES`)
- `--enrich`: adds semantic annotations, related docs, suggested queries
- `--format markdown`: 200-line budgeted output
- `--members-limit` / `--members-offset`: page class members for large classes
- `--methods-only` / `--fields-only`: narrow member paging by kind
- `--package` and `--with-docs` require `--enrich`; package lookup obeys
  `--module` and `--scope`
- Member paging/filter flags belong to the non-enriched node view and are
  rejected when they would otherwise be ignored

### `bean-config`
Show structured Spring XML bean config trees.

```bash
anatomist bean-config FilterRegistry --property filters --index <db>
anatomist bean-config FilterRegistry --property filters --format json \
  --module service --scope MAIN --limit 20 --offset 20 --index <db>
```

Use this when XML `map` / `list` structure carries behavior such as ordered
filter chains. `WIRES` only shows class dependency impact; `bean-config`
preserves keys, order, and nesting.
Bean-name matching is a literal substring search (`%`, `_`, and `\\` are not
SQL wildcards). JSON output is paged and reports
`total/limit/offset/truncated/next_offset` plus a continuation query.

Spring MVC indexing expands every class-path, method-path, and declared HTTP
method combination. Route labels stay human-readable while route IDs include
the handler method, so identical routes in separate modules do not overwrite
each other. Injection indexing covers explicitly annotated injection points and
the single-constructor component convention, including field/parameter
`@Qualifier` values. Custom composed annotations, ordinary setter injection,
profiles, and AOP runtime selection remain outside static coverage.

### `callees-of`
Outgoing call chain from a method.

```bash
anatomist callees-of <method-fqn> [--depth N] [--through-callbacks] [--source-window[=N]] [--blocks=class|package] --index <db>
anatomist callees-of <method-fqn> --depth 3 --limit 50 --offset 50 --filter Order --index <db>
anatomist callees-of <method-fqn> --depth 2 --source-window=3 --index <db>
```

- Max depth: 20. BFS with dedup (no infinite loops).
- JSON reports `depth_requested`, `depth_effective`, `max_depth`,
  `depth_truncated`, and `frontier_count`. When `depth_truncated=true`, follow
  the larger-depth `next_queries` entry before treating the chain as exhaustive.
- `--blocks`: slices chain into class or package blocks.
- `--through-callbacks`: follow CALLS made inside anonymous-class / lambda bodies defined in the method (and nested), attributing them to the method. Essential for template-callback code (`SettleServiceTemplate#execute(callback)`, `TransactionTemplate.execute(...)`, stream lambdas) where the real downstream logic lives in the callback body. Synthesized edges are tagged `call_kind=CALLBACK` (when no original kind) and carry `via=<body-id>` pointing at the callback the call physically came from.
- `--source-window[=N]`: attach a `source_window` object to each emitted edge, using `source_location` plus `project_meta.source_root`. Default context is 3 lines when the flag is present. Use it when an Agent answer needs source evidence without opening every file separately.
- `--limit` / `--offset` / `--filter`: page wide call graphs and narrow by source/target/relation substring. JSON always includes paging stats and `budget`, including the first page.
- `--limit` is applied after the requested-depth traversal. Paging recovers
  omitted rows at that depth; it does not recover deeper rows.
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
# Query an already-indexed external target without indexing its dependency JAR.
anatomist callers-of com.vendor.json.SafeFastjsonParser#parseObject(java.lang.String) --depth 2 --index <db>
```

- Pierces interface/abstract dispatch via OVERRIDES (interface method → implementors).
- External method target: if `<method-fqn>` is absent from `nodes`, Anatomist also
  looks up external `CALLS` edges by `external_target_fqn`. A full signature is an
  exact match; `com.vendor.Type#method` matches all indexed overloads of that method.
  The external edge is depth 1, and `--depth N` continues upward only through
  project-internal callers. It does not index or traverse the dependency JAR.
- External edge identity is explicit in JSON: `external_target=true`, `is_external=true`,
  `external_target_fqn`, `resolution`, and the extractor's `confidence`. Do not treat it as a
  project declaration (`target` and `target_symbol_id` are null).
- `--through-callbacks`: when an incoming call originates inside an anonymous-class / lambda body, attribute it to the enclosing real method (tagged `via=<body-id>`, `call_kind=CALLBACK`) instead of reporting the synthetic `$anon@…#process()` node — so impact analysis reaches the actual caller.
- `--source-window[=N]`: attach numbered source snippets to returned caller edges. Good for impact reports where each caller needs file/line evidence.
- `--limit` / `--offset` / `--filter`: page wide impact graphs and continue with `stats.next_offset`. JSON always includes paging stats and `budget`, including the first page.
- Depth disclosure matches `callees-of`; an impact result is exhaustive only
  when both `stats.truncated=false` and `stats.depth_truncated=false`.

### `call-path`
Shortest path between two methods.

```bash
anatomist call-path <from-fqn> <to-fqn> [--depth N] [--through-callbacks] [--source-window[=N]] [--blocks=class|package] --index <db>
```

- `--through-callbacks`: allow the shortest-path BFS to traverse calls made inside anonymous-class / lambda callback bodies. Callback hops keep the outer method as `source` and record the physical body in `via`.
- `--source-window[=N]`: attach source snippets to each hop. Use it when explaining a concrete end-to-end path.
- An empty result with `stats.depth_truncated=true` means only that no path was
  found within `depth_effective`; use the suggested larger-depth query.

### `branches-of`
Group branch-contained `CALLS` / `READS` / `WRITES` for a method.

```bash
anatomist branches-of <method-fqn> [--depth N] [--through-callbacks] [--source-window[=N]] --index <db>
anatomist branches-of <method-fqn> --depth 3 --source-window=3 --index <db>
```

- Reuses existing `edge.context` facts such as `if-then@L42` and `if-else@L42`; it does not build a full CFG.
- `--source-window[=N]`: attach source snippets around the branch line so Agents can read the condition.
- `--depth`: include downstream methods reached by the existing `callees-of` traversal.
- Branch slices propagate the callee traversal's depth metadata and larger-depth
  follow-up query.

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
# Includes project callers/references to an external class already present in the index.
anatomist used-by com.vendor.json.SafeFastjsonParser --index <db>
```

- For an external type FQN, matches `external_target_fqn = <type>` (for example a
  type reference) and `<type>#…` (its indexed methods/fields). This returns direct
  project `CALLS` and `REFERENCES` without creating an external CLASS node.
- External result rows expose `external_target=true`, `resolution`, and `confidence`.
- Use the full type FQN for external lookup. A short name such as
  `SafeFastjsonParser` remains a node-name lookup and is intentionally not guessed.

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

`truncated` and `depth_truncated` are independent. The first means more rows or
a spent `flow-of` traversal budget; the second means a graph frontier remains
beyond `depth_effective`. Follow every applicable `next_queries` entry. Empty
results support a negative conclusion only when neither bound is truncated and
`evidence.negative_conclusion_safe=true`.

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
