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
                                       │  callees / hierarchy / lint   │
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

---

## What it does

- 17 CLI commands covering: search, context, call chain, hierarchy, dependencies, field access, overview, export, annotation, lint
- SymbolSolver-level call resolution (not naive label match) — distinguishes INSTANCE/STATIC/CONSTRUCTOR/SUPER/INTERFACE
- Stable IDs for lambdas, method refs, anonymous classes
- Incremental re-index (only changed files)
- Spring XML bean wiring (`--spring-xml`)
- Architecture role inference (DDD 7 layers) + smell detection (6 rules)
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
| [docs/commands.md](docs/commands.md) | Full CLI reference (all 17 commands + flags) |
| [docs/architecture.md](docs/architecture.md) | Package layout, data flow, design constraints |
| [docs/data-model.md](docs/data-model.md) | Node ID rules, edge semantics, metadata JSON |
| [docs/testing.md](docs/testing.md) | Test strategy, fixtures, golden files |
| [CLAUDE.md](CLAUDE.md) | Agent collaboration guide (tech stack, commands, directory index) |
| [anatomist-skill.md](anatomist-skill.md) | Agent skill definition (when/how to call anatomist) |
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
