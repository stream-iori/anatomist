# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**anatomist** is a JDT-based Java code intelligence tool. It indexes Java source into SQLite (nodes + edges + FTS5) so an Agent LLM can answer structural/semantic questions via CLI commands without re-parsing every time.

**Two strict phases**:
- **Index phase** — JDT parses sources → Extractors → SQLite. Slow (sec–min), one-shot.
- **Query phase** — SQL + FTS5 + recursive CTE only. **Never** invokes JDT. Millisecond response.

anatomist **never embeds an LLM**. All semantic reasoning is delegated to the calling Agent (Claude Code / Cursor / ...).

See [DESIGN.md](DESIGN.md) for the full design rationale and [docs/scenario-1-index.md](docs/scenario-1-index.md) for the authoritative index-phase spec.

## Commands

| Task | Command |
|------|---------|
| Compile | `mvn -q compile` |
| Run all tests | `mvn test` |
| Run one test class | `mvn test -Dtest=SqliteStoreWriteTest` |
| Run one test method | `mvn test -Dtest=MethodExtractorTest#extract_distinguishesOverloads` |
| Run several at once | `mvn test -Dtest=NodeIdGeneratorTest,JdtParserFactoryTest` |
| Package fat jar | `mvn -q package` (output: `target/anatomist.jar`) |
| End-to-end index against fixture | `java -jar target/anatomist.jar index fixtures/mini-spring-shop --project-source fixtures/mini-spring-shop/api/src/main/java:fixtures/mini-spring-shop/domain/src/main/java:fixtures/mini-spring-shop/service/src/main/java --no-classpath --output /tmp/x.db` |

Build target: `release=21`. The runtime JDK declared in `.sdkmanrc` is `25.0.3-graal` (the `release=21` target is decoupled from runtime, so JDK 21+ works).

## Architecture

### Package layout under `src/main/java/com/anatomist/`

- `model/` — Plain data: `Node`, `Edge`, `Annotation`, `ExtractionResult`
- `core/`  — Index-phase plumbing: `ProjectScanner`, `ClasspathDetector`, `JdtParserFactory`, `NodeIdGenerator`, `ExtractionContext`
- `extract/` — `Extractor` interface + 7 implementations (currently 2 implemented, 5 are throwing skeletons; see Phase-1 status in `.diorama/knowledge/facts/domain-model.md` §6)
- `store/` — `SqliteStore` (schema + atomic batched write)
- `cli/` — `AnatomistCli` (picocli root) + `IndexCommand`

### Index-phase data flow

```
IndexCommand
  → ClasspathDetector.{detectSourcePaths, detect}      (mvn dependency:build-classpath; degrades to empty + WARN on failure)
  → ProjectScanner.scan(sourcePaths)                   (Files.walk, filter excludes)
  → JdtParserFactory.parseAll(files, requestor)        (ASTParser.createASTs — single shared binding context)
      for each CompilationUnit:
          TypeExtractor.extract(...)                   (CLASS/INTERFACE/ENUM nodes)
          MethodExtractor.extract(...)                 (METHOD nodes + CONTAINS edges)
  → SqliteStore.initSchema + write(result)             (single transaction, rollback on any failure)
```

`SqliteStore.initSchema()` loads `src/main/resources/schema.sql`. The splitter is `BEGIN..END`-aware because FTS5 triggers contain inner semicolons.

### Critical invariants — violate and the schema/tests will reject you

- **Node ID preserves original case.** `com.example.Order` (class) and `com.example.order` (subpackage) must not collide. See `NodeIdGenerator` and `docs/scenario-1-index.md §Node ID 生成规则`.
- **Method ID uses erased FQN signature.** `pkg.A#foo(java.lang.String,java.util.List)` — NOT generic args. Built from `IMethodBinding.getParameterTypes()[i].getErasure().getQualifiedName()`, not `getKey()`.
- **`edges` has a CHECK constraint** enforcing `is_external=0 ⇒ target_id non-null & external_target_fqn null` and the inverse. Any extractor producing a CONTAINS / CALLS edge with a dangling `target_id` will cause an FK or CHECK failure and rollback the whole index transaction.
- **`null` binding → skip the entity.** Never invent partial Nodes. Increment an unresolved counter instead.
- **Index and Query are separate.** Query-side code must read SQLite only. Do not import anything from `org.eclipse.jdt` outside `core/` and `extract/`.

### Schema location

The single source of truth for SQLite DDL is `src/main/resources/schema.sql`. The structure mirrors `docs/scenario-1-index.md §完整 DDL` exactly. `documents` and `semantic_annotations` tables belong to Phase 2 and are intentionally **not** created yet.

### Fixture

`fixtures/mini-spring-shop/` — three-module Maven project (api / domain / service). It's the canonical end-to-end input. Baseline after Phase 1 MVP: 15 types, 46 methods, 46 CONTAINS edges. These numbers should grow **monotonically** as more Extractors land — use them as a regression baseline.

## Workflow conventions

### Diorama SDD

This repo uses the `diorama` skill for scenario-driven development. Use `/diorama` for the main loop; the workflow phases are `specify → plan → generate → consolidate → done`, each ending in a phase-checkpoint commit.

- Project knowledge lives under `.diorama/knowledge/`:
  - `facts/glossary.json` — 16 terms (Node / Edge / Extractor / Index Phase / Query Phase / Agent / Binding / ...)
  - `facts/domain-model.md` — entity diagram, 6 business rules R1–R6, 3 scenario sequence diagrams, Phase 1 coverage matrix
  - `facts/tech-context.md` — Maven coordinates, dep budget, source layout, decay check
  - `rules/experience.md` — practical pitfalls (E1–E6) you should read before touching JDT / FTS5 / SQL DDL parsing / Maven seam patterns
- Active task state lives at `.diorama/tasks/<task-id>/{proposal,design,tasks,task.json}`.

### Dependency budget

Production has **exactly 4 runtime deps** (JDT 3.45.0, sqlite-jdbc 3.47.0.0, picocli 4.7.6, jackson-databind 2.17.0). Do not add more without explicit justification in a new task's `proposal.md` / `design.md`.

### JDT `createASTs` system-library gotcha

If `includeRunningVMClasspath=false` **and** classpath is empty, JDT throws `IllegalStateException: Missing system library`. Production `IndexCommand` keeps `false` (to avoid leaking the running JDK's API surface into Java-8 targets) and relies on `ClasspathDetector` for at least one jar. Tests that need bindings without a real classpath set `true` (see `JdtParserFactoryTest`). Full reasoning in `.diorama/knowledge/rules/experience.md` E1.

### Test helper visibility

`src/test/java/com/anatomist/core/JdtTestSupport` is referenced from other test packages (`extract/`, etc.) and **must remain `public`**. Don't downgrade visibility "for cleanup".

### MethodExtractor / BR-007 invariant

`MethodExtractor.emit` short-circuits when `declClass.isAnonymous() || declClass.isLocal()`. Reason: Phase 1 MVP does not emit ANONYMOUS_CLASS Node (BR-007), so a method inside one would produce a CONTAINS edge with no matching source Node → FK violation → whole index rollback. Remove this guard **only** in the task that introduces ANONYMOUS_CLASS / LAMBDA node extraction.

### FTS5 tokenizer surprise

`node_names` uses FTS5 default tokenizer, which splits `pkg.Class#method()` into `pkg`, `Class`, `method`. Use `label MATCH '<term>'` to scope to a column when you need precision; use `nodes.qualified_name = ?` for true equality lookups.
