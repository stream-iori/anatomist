# Repository Guidelines

## Project Structure & Module Organization

| Path | Purpose |
|------|---------|
| `src/main/java/com/anatomist/` | Production Java code: CLI, core, extractors, query, store, export, semantic rules. |
| `src/main/resources/schema.sql` | SQLite schema source of truth. |
| `src/main/resources/export/` | HTML export templates. |
| `src/test/java/` | JUnit 5 unit and integration tests (`*Test`, `*IT`). |
| `tests/scenarios/` | Golden-file CLI scenarios with `input.cmd` and `expected.json`. |
| `fixtures/` | Java projects used for indexing tests and smoke checks. |
| `docs/` | Architecture, commands, data model, and testing references. |

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
| `just golden-update` | Refresh expected JSON after intentional output changes. |

Example local CLI flow:

```bash
java -jar target/anatomist.jar index fixtures/mini-spring-shop --no-classpath --output /tmp/shop.db
java -jar target/anatomist.jar search OrderService --index /tmp/shop.db
```

## Coding Style & Naming Conventions

Use Java 21 with `maven.compiler.release=21`. Follow existing package boundaries: parsing/indexing in `core` and `extract`, persistence in `store`, query-only behavior in `query`, commands in `cli`. Keep JSON runtime code hand-written; avoid reflection-heavy libraries because native-image support is a core constraint.

Class names use `PascalCase`, methods and fields use `camelCase`, constants use `UPPER_SNAKE_CASE`. Match surrounding indentation; no formatter config is currently enforced.

## Testing Guidelines

Tests use JUnit 5 and Maven Surefire. Name unit tests `*Test.java`; name database/CLI tests `*IT.java`. Golden scenarios live under `tests/scenarios/<id>/` and are validated by `GoldenFileIT`. For output contract changes, run `just golden-update` and review the JSON diff before committing.

## Commit & Pull Request Guidelines

Git history follows concise Conventional Commit style, often scoped: `feat(query): ...`, `fix(asmsolver): ...`, `docs: ...`, `refactor(core): ...`, `test(asmsolver): ...`.

Pull requests should include what changed, why, commands run, and any fixture/golden-file updates. Add screenshots only for HTML export or visual output changes. Never commit local databases, smoke files, or machine-specific paths.

## Agent-Specific Instructions

Do not run `git push` unless explicitly requested. Do not switch branches in this worktree; use a temporary worktree if isolation is required.
