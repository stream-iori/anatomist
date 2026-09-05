# Repository Guidelines

## Project Structure & Module Organization

| Path | Purpose |
|------|---------|
| `src/main/java/com/anatomist/` | Production Java code: CLI, core, extractors, query, store, export, semantic rules. |
| `src/main/resources/schema.sql` | SQLite schema source of truth. |
| `src/main/resources/export/` | HTML export templates. |
| `src/test/java/` | JUnit 5 unit and integration tests (`*Test`, `*IT`). |
| `tests/scenarios/` | Golden semantic pipelines with `pipeline.json` and expected outputs. |
| `fixtures/` | Java projects used for indexing tests and smoke checks. |
| `docs/` | Architecture, commands, data model, testing, and config references. |

## Build, Test, and Development Commands

| Command | Use |
|---------|-----|
| `just` | List available recipes. |
| `just compile` or `mvn -q compile` | Compile main sources. |
| `just test` or `mvn test` | Run JUnit tests. |
| `just test-one ClassName#method` | Run one test target. |
| `just jar` | Build `target/anatomist.jar`. |
| `just native` | Build GraalVM native binary at `target/anatomist`. |
| `just smoke` | Index bundled fixture and run core CLI queries. |
| `just release-native 1.0.0` | Build a versioned macOS arm64 native binary. |
| `just install-from target/anatomist` | Install a built native binary to the local user path. |
| `just golden-update` | Refresh expected JSON after intentional output changes. |

Example local CLI flow:

```bash
java -jar target/anatomist.jar index fixtures/mini-spring-shop --no-classpath --output /tmp/shop.db
java -jar target/anatomist.jar search OrderService --index /tmp/shop.db
java -jar target/anatomist.jar search OrderService --kind type --format ndjson --index /tmp/shop.db \
  | java -jar target/anatomist.jar resolve --unique --index /tmp/shop.db \
  | java -jar target/anatomist.jar runtime-implementations --index /tmp/shop.db
```

For an exact method, use `resolve '<exact-signature>' --kind callable --exact --unique | source`
as the primary local-control-flow evidence. Source is snapshot-verified and
paged; use graph/flow pipelines only for relation or path proof. With
`--lombok ast`, treat Lombok output as evidence: modeled capabilities are facts,
while partial/unmodeled capabilities require further verification. Call-query
edges expose structured `lombok_usage`; `usage-observed` proves source use only,
not compilation or runtime execution.
Follow source pagination when a conclusion must cover the whole declaration.
Read an entire type only when state or lifecycle spans fields, constructors,
initializers, or several methods; for a large class, select exact methods first.

Project configuration is `.anatomist/config.toml`; use the complete commented
template in `docs/config.toml`. Lombok is off by default. Enable it with
`[extensions.lombok]` and `mode = "ast"`; `strict = true` makes incomplete
Lombok coverage fail the complete health gate.

## Coding Style & Naming Conventions

Use Java 25 with `maven.compiler.release=25`; `.sdkmanrc` is the canonical local SDK selection. Follow existing package boundaries: parsing/indexing in `core` and `extract`, application orchestration in `application`, persistence in `store`, query-only behavior in `query`, commands in `cli`. Keep JSON runtime code hand-written; avoid reflection-heavy libraries because native-image support is a core constraint.

Class names use `PascalCase`, methods and fields use `camelCase`, constants use `UPPER_SNAKE_CASE`. Match surrounding indentation; no formatter config is currently enforced.

## Testing Guidelines

Tests use JUnit 5 and Maven Surefire. Name unit tests `*Test.java`; name database/CLI tests `*IT.java`. Golden scenarios live under `tests/scenarios/<id>/` and are validated by `GoldenFileIT`. For output contract changes, run `just golden-update` and review the JSON diff before committing.

## Commit & Pull Request Guidelines

Git history follows concise Conventional Commit style, often scoped: `feat(query): ...`, `fix(asmsolver): ...`, `docs: ...`, `refactor(core): ...`, `test(asmsolver): ...`.

Pull requests should include what changed, why, commands run, and any fixture/golden-file updates. Add screenshots only for HTML export or visual output changes. Never commit local databases, smoke files, or machine-specific paths.

## Agent-Specific Instructions

Do not run `git push` unless explicitly requested. Do not switch branches in this worktree; use a temporary worktree if isolation is required.
