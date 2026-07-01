# anatomist

JavaParser + SymbolSolver-based Java code intelligence tool. Indexes a Java project into SQLite once, then answers structural/semantic questions via CLI — no re-parsing per query. Designed as an Agent LLM tool (Claude Code, Cursor, ...).

```
┌────────────────────┐    seconds–minutes     ┌────────────────────┐
│  Java source tree  │ ─────────────────────▶ │   SQLite snapshot  │
└────────────────────┘   index (one-shot)     │  nodes + edges +   │
                                              │  FTS5 + semantic   │
                                              └─────────┬──────────┘
                                                        │ milliseconds
                                                        ▼
                                       ┌───────────────────────────────┐
                                       │  CLI / JSON responses for     │
                                       │  search / context / callers / │
                                       │  callees / hierarchy / deps   │
                                       └───────────────────────────────┘
```

---

## Quick start

```bash
# Build
just jar                    # → target/anatomist.jar
# or: just native           # → target/anatomist (GraalVM native binary)

# Index
java -jar target/anatomist.jar index fixtures/mini-spring-shop \
    --project-source api/src/main/java:domain/src/main/java:service/src/main/java \
    --no-classpath --output /tmp/shop.db

# Query
java -jar target/anatomist.jar callees-of \
    com.example.shop.service.OrderService#createOrder \
    --depth 3 --index /tmp/shop.db
```

For Agent integration, prefer machine-readable health and build checks:

```bash
anatomist doctor --format json --index /tmp/shop.db
anatomist index . --format json --output /tmp/shop.db
anatomist survey-baseline . --format json --index /tmp/shop.db
```

---

## What it does

- CLI commands covering: search, context, call chain, hierarchy, dependencies, field access, overview, survey-baseline, export, annotation
- Stable Agent contract: every subcommand supports `--help`; `doctor` and `index` support JSON status summaries
- Progressive disclosure for large repos: `survey-baseline`, paged search, paged context members, and paged/filtered call chains
- Source-backed graph slices: `callees-of` / `callers-of` / `call-path --source-window=3` return file/line snippets for Agent evidence
- Index snapshot metadata in `project_meta`: source root, source paths, index time, git commit/branch/dirty/remote
- SymbolSolver-level call resolution (not naive label match) — distinguishes INSTANCE/STATIC/CONSTRUCTOR/SUPER/INTERFACE
- Stable IDs for lambdas, method refs, anonymous classes
- Incremental re-index (only changed files)
- Spring XML bean wiring (`--spring-xml`)
- Pagination + keyword filter on all list queries
- GraalVM native binary (~10ms cold start vs ~300ms JVM)

## What it doesn't do

- No embedded LLM — all reasoning delegated to calling Agent
- No cross-language (Java only)
- No runtime/reflection dispatch analysis
- No vector/semantic similarity (relies on FTS5 + Agent reasoning)

---

## Documentation

| Doc | Purpose |
|-----|---------|
| [docs/getting-started.md](docs/getting-started.md) | Installation, first index, first query |
| [docs/commands.md](docs/commands.md) | Full CLI reference (all 19 commands + flags) |
| [docs/architecture.md](docs/architecture.md) | Package layout, data flow, design constraints |
| [docs/data-model.md](docs/data-model.md) | Node ID rules, edge semantics, metadata JSON |
| [docs/testing.md](docs/testing.md) | Test strategy, fixtures, golden files |
| [AGENTS.md](AGENTS.md) | Contributor and Agent collaboration guide |
| [SKILL.md](SKILL.md) | Codex skill definition (when/how to call anatomist) |
| [todo.md](todo.md) | Future work |

---

## For contributors

```bash
brew install just           # task runner
just                        # list all recipes
just test                   # run all tests (unit + IT)
just native-smoke           # verify native binary = JVM jar output
just golden-update          # refresh golden files after output format changes
```

3 production dependencies: `javaparser-symbol-solver-core` + `sqlite-jdbc` + `picocli`. No Jackson, no javassist, no Spring at runtime. JSON I/O is hand-written. Native-image compatible.
