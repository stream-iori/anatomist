# CLAUDE.md

## What this project is

**anatomist** — JavaParser + SymbolSolver based Java code intelligence tool. Indexes Java source into SQLite (nodes + edges + FTS5) for Agent LLM structural/semantic queries via CLI.

## Tech stack

| Component | Version/Detail |
|-----------|---------------|
| Language | Java 21 (release target), runtime JDK 25.0.3-graal (`.sdkmanrc`) |
| Build | Maven, single module |
| Parser | javaparser-symbol-solver-core 3.28.1 |
| Storage | sqlite-jdbc 3.47.0.0 |
| CLI | picocli 4.7.6 |
| JSON | hand-written (no Jackson at runtime since native-image target) |
| Test | JUnit 5, surefire includes `**/*Test.java` + `**/*IT.java` |
| Coverage | JaCoCo 0.8.12 (85% line coverage) |

## Key constraints

- **Two phases**: Index (JavaParser, slow) → Query (SQL only, ms-level). Never parse at query time.
- **No embedded LLM**. All reasoning delegated to calling Agent.
- **Native-image affinity**: no reflection, no jackson-databind, no javassist. Verified via `just native-smoke`.
- **4 runtime deps** (will become 3 post native-image): javaparser, sqlite-jdbc, picocli, jackson-databind.

## Commands

| Task | Command |
|------|---------|
| Compile | `mvn -q compile` |
| Test (all) | `mvn test` |
| Single test | `mvn test -Dtest=ClassName#method` |
| Package | `mvn -q package` → `target/anatomist.jar` |
| Index | `java -jar target/anatomist.jar index <path> --output db` |
| Query | `java -jar target/anatomist.jar callees-of <method> --depth 3 --index db` |
| Arch roles | `java -jar target/anatomist.jar annotate --auto --index db` |
| Lint | `java -jar target/anatomist.jar lint --arch-smell --index db` |
| Search by role | `java -jar target/anatomist.jar search ADAPTER --by-role --index db` |
| Paginated deps | `java -jar target/anatomist.jar deps-of X --limit 20 --offset 0 --filter Y --index db` |

## Directory guide

| Path | What's there |
|------|-------------|
| `src/main/java/com/anatomist/` | Production code (model/core/extract/store/semantic/query/export/cli/annotations) |
| `src/main/resources/schema.sql` | Single source of truth for SQLite DDL |
| `src/test/java/` | Unit tests + integration tests |
| `docs/` | Technical documentation |
| `docs/getting-started.md` | New user guide: build → index → query |
| `docs/commands.md` | Full CLI reference (17 commands + all flags) |
| `docs/architecture.md` | Package layout, data flow, critical invariants, gotchas |
| `docs/data-model.md` | Node ID rules, metadata JSON, edge semantics, relations |
| `docs/testing.md` | Test strategy, fixture design, golden file patterns |
| `fixtures/` | Test fixtures (mini-spring-shop, micro, external/commons-lang) |
| `todo.md` | Unimplemented future work |
| `anatomist-skill.md` | Agent skill definition |

## Workflow

- Uses `diorama` skill for scenario-driven development (`/diorama`)
- Project knowledge: `.diorama/knowledge/` (glossary, domain-model, tech-context, experience rules)
- Detailed architecture reference: [docs/architecture.md](docs/architecture.md)
