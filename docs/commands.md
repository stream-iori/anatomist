# CLI Command Reference

All commands output JSON to stdout (except `overview --format markdown` and `export --format html`).

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
| `--spring-xml` | Also parse Spring XML `<beans>` configs into BEAN/DEFINED_BY/WIRES facts. Spring annotation Bean/MVC facts are indexed by default. | false |

### `index-docs`
Index project markdown documents for FTS5 search.

```bash
anatomist index-docs <path> --index <db>
```

### `watch`
Monitor source tree, report or auto-index changes.

```bash
anatomist watch <project-path> [--auto-index] [--debounce-ms 500]
```

## Query Phase

### `search`
Find nodes by name (FTS5), precise simple-name, annotation, or architecture role.

```bash
anatomist search <term> [--kind CLASS|METHOD|...] [--limit 20] [--by-annotation] [--by-role] --index <db>
anatomist search --name '<glob>' [--kind CLASS|INTERFACE|...] [--count] --index <db>
anatomist search <term> --count --index <db>
```

- Default `<term>`: FTS5 match over qualified name / label / javadoc — **also matches package path tokens** (e.g. `search Facade` matches everything under a `.facade.` package). FTS results carry a `stats.label_matches` count: how many returned rows actually match the simple name, so an inflated `total` is easy to spot.
- `--name '<glob>'`: precise simple-name match against the label only (`*`/`?` globs, e.g. `--name '*EventPlugin'`). Bypasses FTS — use this to count/enumerate a naming pattern.
- `--count`: return only the true total (results omitted), **independent of `--limit`**. Works with `--name` or FTS.

### `context`
Show node structure + optional enrichment.

```bash
anatomist context <fqn> [--with-callees=N] [--enrich] [--with-docs] [--package <pkg>] [--format markdown|json] --index <db>
```

- Default: node + fields + methods + annotations + framework facts (`DEFINED_BY`, `INJECTS`, `HANDLES`, `WIRES`)
- `--enrich`: adds semantic annotations, arch_role, related docs, suggested queries
- `--format markdown`: 200-line budgeted output

### `callees-of`
Outgoing call chain from a method.

```bash
anatomist callees-of <method-fqn> [--depth N] [--through-callbacks] [--blocks=class|package] --index <db>
```

- Max depth: 20. BFS with dedup (no infinite loops).
- `--blocks`: slices chain into architectural blocks with DDD role labels.
- `--through-callbacks`: follow CALLS made inside anonymous-class / lambda bodies defined in the method (and nested), attributing them to the method. Essential for template-callback code (`SettleServiceTemplate#execute(callback)`, `TransactionTemplate.execute(...)`, stream lambdas) where the real downstream logic lives in the callback body. Synthesized edges are tagged `call_kind=CALLBACK` (when no original kind) and carry `via=<body-id>` pointing at the callback the call physically came from.

### `callers-of`
Incoming call chain (impact analysis).

```bash
anatomist callers-of <method-fqn> [--depth N] [--through-callbacks] [--blocks=class|package] --index <db>
```

- Pierces interface/abstract dispatch via OVERRIDES (interface method → implementors).
- `--through-callbacks`: when an incoming call originates inside an anonymous-class / lambda body, attribute it to the enclosing real method (tagged `via=<body-id>`, `call_kind=CALLBACK`) instead of reporting the synthetic `$anon@…#process()` node — so impact analysis reaches the actual caller.

### `call-path`
Shortest path between two methods.

```bash
anatomist call-path <from-fqn> <to-fqn> [--depth N] [--blocks=class|package] --index <db>
```

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

- Includes: node kind counts, edge counts, arch_role distribution, package tallies, package deps
- `--deps-only`: output only package dependency edges
- `--depth N`: collapse package tree to N segments

### `export`
Self-contained HTML visualization.

```bash
anatomist export --format html --output <file.html> [--max-edges 20000] --index <db>
```

## Annotation Phase

### `annotate`
Write business-semantic annotations or auto-infer architecture roles.

```bash
# Manual annotation
anatomist annotate <node-id> --category BUSINESS_SERVICE --label "订单服务" --index <db>

# Auto-infer DDD layers (L1 annotation + L2 call-pattern rules)
anatomist annotate --auto --index <db>

# Batch from JSON file
anatomist annotate --from-json annotations.json --index <db>
```

### `lint`
Detect architecture smells.

```bash
anatomist lint --arch-smell [--format markdown|json] --index <db>
```

Detects 6 smell types:
- **anemic-model** — DOMAIN_MODEL with only getters/setters
- **fat-application** — APPLICATION class with business logic
- **layer-bypass** — ENTRY directly calls REPOSITORY
- **circular-dependency** — APPLICATION ↔ DOMAIN_SERVICE bidirectional
- **adapter-leak** — non-ADAPTER class calls HTTP/MQ clients
- **domain-spillover** — DOMAIN_MODEL depends on framework annotations

## Pagination

`deps-of`, `used-by`, `field-access`, `overview --deps-only` support:

| Flag | Description | Default |
|------|-------------|---------|
| `--limit N` | Results per page | 50 (30 for overview) |
| `--offset N` | Skip N results | 0 |
| `--filter <keyword>` | Substring match on target label/FQN | none |

JSON stats always include `{"total": N, "offset": N, "truncated": bool}`.

## Context Filters

`deps-of`, `used-by`, `field-access` support:

| Flag | Description |
|------|-------------|
| `--in-loop` | Keep only edges inside loops (for/while/do) |
| `--in-branch` | Keep only edges inside branches (if/else/case/catch) |
