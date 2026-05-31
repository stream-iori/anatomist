# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**anatomist** is a **JavaParser + SymbolSolver**-based Java code intelligence tool. It indexes Java source into SQLite (nodes + edges + FTS5) so an Agent LLM can answer structural/semantic questions via CLI commands without re-parsing every time.

**Two strict phases**:
- **Index phase** — JavaParser parses sources, SymbolSolver resolves bindings (sources via `JavaParserTypeSolver`, jars via the in-house `JarTypeSolver`), Extractors emit nodes/edges → SQLite. Slow (sec–min), one-shot.
- **Query phase** — SQL + FTS5 + recursive CTE only. **Never** invokes the parser. Millisecond response.

anatomist **never embeds an LLM**. All semantic reasoning is delegated to the calling Agent (Claude Code / Cursor / ...).

See [DESIGN.md](DESIGN.md) for the full design rationale and [docs/scenario-1-index.md](docs/scenario-1-index.md) for the authoritative index-phase spec.

## Commands

| Task | Command |
|------|---------|
| Compile | `mvn -q compile` |
| Run all tests | `mvn test` (unit tests only; surefire's default pattern doesn't pick up `*IT`) |
| Run one test class | `mvn test -Dtest=SqliteStoreWriteTest` |
| Run one test method | `mvn test -Dtest=MethodExtractorTest#extract_distinguishesOverloads` |
| Run several at once | `mvn test -Dtest=NodeIdGeneratorTest,JavaParserFactoryTest` |
| Run the fixture IT | `mvn test -Dtest=IndexCommandIT` |
| Package fat jar | `mvn -q package` (output: `target/anatomist.jar`) |
| End-to-end index against fixture | `java -jar target/anatomist.jar index fixtures/mini-spring-shop --project-source fixtures/mini-spring-shop/api/src/main/java:fixtures/mini-spring-shop/domain/src/main/java:fixtures/mini-spring-shop/service/src/main/java --no-classpath --output /tmp/x.db` |

Build target: `release=21`. The runtime JDK declared in `.sdkmanrc` is `25.0.3-graal` (the `release=21` target is decoupled from runtime, so JDK 21+ works).

## Architecture

### Package layout under `src/main/java/com/anatomist/`

- `model/` — Plain data: `Node`, `Edge`, `Annotation`, `ExtractionResult`
- `core/`  — Index-phase plumbing: `ProjectScanner`, `ClasspathDetector`, `JavaParserFactory`, `NodeIdGenerator`, `ExtractionContext`
- `extract/` — `Extractor` interface + 8 implementations. Phase 1 of the JavaParser rewrite ships `TypeExtractor` and `MethodExtractor` as real impls; the other 6 (`FieldExtractor`, `AnnotationExtractor`, `CallGraphExtractor`, `HierarchyExtractor`, `ReferenceExtractor`, `FieldAccessExtractor`) are no-op skeletons with TODO Phase 1.5 markers pointing at the JavaParser / SymbolSolver APIs that should replace each former implementation call.
- `store/` — `SqliteStore` (schema + atomic batched write)
- `cli/` — `AnatomistCli` (picocli root) + `IndexCommand`

### Index-phase data flow

```
IndexCommand
  → ClasspathDetector.{detectSourcePaths, detect}   (mvn dependency:build-classpath; degrades to empty + WARN on failure)
  → ProjectScanner.scan(sourcePaths)                (Files.walk, filter excludes)
  → JavaParserFactory.parseAll(consumer)            (SourceRoot.tryToParse per source root;
                                                     CombinedTypeSolver = JavaParserTypeSolver(src)*
                                                       + JarTypeSolver(jar)*  (javassist-backed)
                                                       + ReflectionTypeSolver (toggle via --vm-classpath))
      for each CompilationUnit:
          TypeExtractor.extract(...)                (CLASS/INTERFACE/ENUM/ANONYMOUS_CLASS nodes)
          MethodExtractor.extract(...)              (METHOD nodes + CONTAINS edges)
          (other extractors no-op until Phase 1.5)
  → SqliteStore.initSchema + write(result)          (single transaction, rollback on any failure)
```

`SqliteStore.initSchema()` loads `src/main/resources/schema.sql`. The splitter is `BEGIN..END`-aware because FTS5 triggers contain inner semicolons.

### Critical invariants — violate and the schema/tests will reject you

- **Node ID preserves original case.** `com.example.Order` (class) and `com.example.order` (subpackage) must not collide. See `NodeIdGenerator` and `docs/scenario-1-index.md §Node ID 生成规则`.
- **Method ID uses erased FQN signature.** `pkg.A#foo(java.lang.String,java.util.List)` — NOT generic args. Built from `ResolvedMethodDeclaration.getParam(i).getType().erasure().describe()` (see `NodeIdGenerator.erasedTypeDescribe`).
- **`edges` has a CHECK constraint** enforcing `is_external=0 ⇒ target_id non-null & external_target_fqn null` and the inverse. Any extractor producing a CONTAINS / CALLS edge with a dangling `target_id` will cause an FK or CHECK failure and rollback the whole index transaction. `IndexCommand.pruneDanglingInternalEdges` sweeps these defensively while extractor coverage catches up.
- **Symbol resolution failure → skip the entity.** SymbolSolver throws `UnsolvedSymbolException` / `UnsupportedOperationException`. Catch as `RuntimeException` per emit-site and call `ctx.incrementUnresolved()`; never invent a partial Node.
- **Index and Query are separate.** Query-side code must read SQLite only. Do not import anything from `com.github.javaparser.*` outside `core/` and `extract/`.

### Schema location

The single source of truth for SQLite DDL is `src/main/resources/schema.sql`. The structure mirrors `docs/scenario-1-index.md §完整 DDL` exactly. `documents` and `semantic_annotations` tables belong to Phase 2 and are intentionally **not** created yet.

### Fixture

`fixtures/mini-spring-shop/` — three-module Maven project (api / domain / service). It's the canonical end-to-end input. Baseline after the Phase 1 scenario-1 gap-closure (LAMBDA / METHOD_REF / RECORD / Java version detection / isAccessor all live): **16 types, 47 methods, 75 CONTAINS edges, ≥1 LAMBDA, ≥1 METHOD_REF**. Residual `Pruned dangling` (~6 on this fixture) comes from a pre-existing anonymous-class id-encoding mismatch between TypeExtractor (`$anon@L<line>`) and CallGraphExtractor resolution (`Anonymous-<uuid>`); not in REQ-001..005 scope. Numbers must grow **monotonically** as more Extractors come back online — use them as a regression baseline.

## Workflow conventions

### Diorama SDD

This repo uses the `diorama` skill for scenario-driven development. Use `/diorama` for the main loop; the workflow phases are `specify → plan → generate → consolidate → done`, each ending in a phase-checkpoint commit.

- Project knowledge lives under `.diorama/knowledge/`:
  - `facts/glossary.json` — terms (Node / Edge / Extractor / Index Phase / Query Phase / Agent / ...)
  - `facts/domain-model.md` — entity diagram, business rules, scenario sequence diagrams, Phase 1 coverage matrix
  - `facts/tech-context.md` — Maven coordinates, dep budget, source layout, decay check
  - `rules/experience.md` — practical pitfalls you should read before touching the parser / FTS5 / SQL DDL parsing / Maven seam patterns
- Active task state lives at `.diorama/tasks/<task-id>/{proposal,design,tasks,task.json}` (created on demand by the diorama skill; not committed under live development).

### Dependency budget

Production has **4 direct runtime deps** (`javaparser-symbol-solver-core` — bundles `javassist` as a transitive for `JarTypeSolver`, `sqlite-jdbc`, `picocli`, `jackson-databind`). Do not add more without explicit justification in a new task's `proposal.md` / `design.md`.

### ReflectionTypeSolver / `--vm-classpath` gotcha

`JavaParserFactory` optionally adds a `ReflectionTypeSolver` so JDK types (`java.lang.*`, `java.util.*`) resolve. If the anatomist process runs on a much newer JDK than the target project, this leaks high-version APIs into the resolution (`String.strip()`, `List.of()`, sealed-class types) and creates false-positive bindings. `IndexCommand` exposes it via `--vm-classpath` (defaults to `true` so the fixture and most projects resolve `java.lang.*` out of the box; turn it off when analyzing older Java versions).

### Test helper visibility

`src/test/java/com/anatomist/core/JavaParserTestSupport` is referenced from other test packages (`extract/`, etc.) and **must remain `public`**. Don't downgrade visibility "for cleanup".

### MethodExtractor / BR-007 invariant

`MethodExtractor.skipDeclaringType` short-circuits when the declaring type isn't one of class / interface / enum / annotation — i.e. it skips methods declared inside a local class. Anonymous classes are now emitted by `TypeExtractor`, so anonymous-class methods are safe to extract; only true local classes (e.g. `class Inner` inside a method body) are still dropped to avoid CONTAINS edges with no matching source Node → FK violation → whole index rollback. Loosen this guard **only** in the task that introduces LOCAL_CLASS / LAMBDA node extraction.

### FTS5 tokenizer surprise

`node_names` uses FTS5 default tokenizer, which splits `pkg.Class#method()` into `pkg`, `Class`, `method`. Use `label MATCH '<term>'` to scope to a column when you need precision; use `nodes.qualified_name = ?` for true equality lookups.
