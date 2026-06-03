# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**anatomist** is a **JavaParser + SymbolSolver**-based Java code intelligence tool. It indexes Java source into SQLite (nodes + edges + FTS5) so an Agent LLM can answer structural/semantic questions via CLI commands without re-parsing every time.

**Two strict phases**:
- **Index phase** — JavaParser parses sources, SymbolSolver resolves bindings (sources via `JavaParserTypeSolver`, jars via the in-house `JarTypeSolver`), Extractors emit nodes/edges → SQLite. Slow (sec–min), one-shot.
- **Query phase** — SQL + FTS5 + recursive CTE only. **Never** invokes the parser. Millisecond response.

anatomist **never embeds an LLM**. All semantic reasoning is delegated to the calling Agent (Claude Code / Cursor / ...).

**Native-image affinity is a global design constraint.** anatomist's distribution target is a GraalVM native binary covering *all* commands (index, incremental index, watch, query, enrich, annotate). When designing or modifying any production code, ask first: *"does this hold under native-image's closed-world assumption?"*. Concretely, the following patterns require a documented native-image adaptation in the task's `design.md` before they may land:

- `Class.forName` / reflective method or field access
- `java.lang.reflect.Proxy` / dynamic proxies
- `ServiceLoader` discovery (unless the SPI is already covered by an upstream `META-INF/native-image/` config)
- Runtime bytecode generation or loading (cglib, javassist `CtClass.toClass()`, custom `ClassLoader`)
- Reflection-based POJO marshalling (Jackson `ObjectMapper`, Gson, etc.) — prefer compile-time codegen or hand-written serializers
- Resource access via `getResourceAsStream` without a corresponding `resource-config.json` entry

Concrete decisions already locked in by [scenario-6-native-image.md](docs/scenario-6-native-image.md) §决策 1–6, to be applied progressively:

- **Drop javassist** → replace `JarTypeSolver` with self-written `AsmTypeSolver` (ASM `ClassReader` reads `.class` without loading)
- **Drop `ReflectionTypeSolver`** → pre-generate per-JDK type catalogs as embedded binary resources (`META-INF/anatomist/jdkN-types.bin`)
- **Drop `jackson-databind`** → hand-written JSON I/O for the ~14 flat POJOs (no reflection, ~300 LOC)
- **picocli** → add `picocli-codegen` annotation processor for compile-time reflect-config
- **sqlite-jdbc** → already ships `META-INF/native-image/` config (no action)

See [DESIGN.md](DESIGN.md) for the full design rationale and [docs/scenario-1-index.md](docs/scenario-1-index.md) for the authoritative index-phase spec.

## Commands

| Task | Command |
|------|---------|
| Compile | `mvn -q compile` |
| Run all tests | `mvn test` (unit tests only; surefire's default pattern doesn't pick up `*IT`) |
| Run one test class | `mvn test -Dtest=SqliteStoreWriteTest` |
| Run one test method | `mvn test -Dtest=MethodExtractorTest#extract_distinguishesOverloads` |
| Run several at once | `mvn test -Dtest=NodeIdGeneratorTest,JavaParserFactoryTest` |
| Run the canonical fixture IT | `mvn test -Dtest=IndexCommandIT` |
| Run scenario-2 query L2 IT | `mvn test -Dtest=QueryServiceIT` |
| Run golden-file L3 IT | `mvn test -Dtest=GoldenFileIT` |
| Refresh all golden files | `mvn test -Dtest=GoldenFileIT -Dgolden.update=true` |
| Run micro-fixture L1 IT | `mvn test -Dtest=MicroFixtureIT` |
| Run commons-lang scale IT (needs submodule) | `mvn test -Dtest=CommonsLangSmokeIT` (see `fixtures/external/README.md`; auto-skips when missing) |
| Package fat jar | `mvn -q package` (output: `target/anatomist.jar`) |
| End-to-end index against fixture | `java -jar target/anatomist.jar index fixtures/mini-spring-shop --project-source api/src/main/java:domain/src/main/java:service/src/main/java --no-classpath --output /tmp/x.db` |
| Run a query against an index | `java -jar target/anatomist.jar callees-of com.example.shop.service.OrderService#createOrder --depth 3 --index /tmp/x.db` |
| Project overview (Agent map) | `java -jar target/anatomist.jar overview --format markdown --index /tmp/x.db` |
| Export self-contained HTML (human view) | `java -jar target/anatomist.jar export --format html --output /tmp/project.html --index /tmp/x.db` |

Build target: `release=21`. The runtime JDK declared in `.sdkmanrc` is `25.0.3-graal` (the `release=21` target is decoupled from runtime, so JDK 21+ works).

## Architecture

### Package layout under `src/main/java/com/anatomist/`

- `model/` — Plain data: `Node`, `Edge`, `Annotation`, `ExtractionResult`
- `core/`  — Index-phase plumbing: `ProjectScanner` (`.java` scan + `scanSpringXml` for `<beans>` configs), `ClasspathDetector`, `JavaParserFactory`, `NodeIdGenerator`, `ExtractionContext`, `SpringBeanParser` (pure-SAX `<bean>` reader).
- `extract/` — `Extractor` interface + 8 implementations. Phase 1 of the JavaParser rewrite ships `TypeExtractor` and `MethodExtractor` as real impls; the other 6 (`FieldExtractor`, `AnnotationExtractor`, `CallGraphExtractor`, `HierarchyExtractor`, `ReferenceExtractor`, `FieldAccessExtractor`) are no-op skeletons with TODO Phase 1.5 markers pointing at the JavaParser / SymbolSolver APIs that should replace each former implementation call. Plus `XmlBeanExtractor` — **not** an `Extractor` (XML has no `CompilationUnit`): a separate post-Java pass that joins bean class FQNs against indexed node ids to emit `BEAN` nodes + `DEFINED_BY` / `WIRES` edges (only when `--spring-xml` is set).
- `store/` — `SqliteStore` (schema + atomic batched write)
- `query/` — Read-only query layer over the built SQLite index. `QueryService` is the single SQL-bearing class; `QueryEnvelope` / `NodeRow` / `EdgeRow` / `ContextResult` / `HierarchyResult` / `OverviewResult` + `PackageStat` are Jackson-serializable result POJOs; `JsonFormatter` owns the snake_case + INDENT_OUTPUT mapper. `QueryService.overview()` (kind/edge/package tallies + package-dep skeleton) and `classDepsInternal(maxEdges)` (class→class internal edges, with a recursive-CONTAINS rollup so lambdas / method-refs / anon bodies fold up to their named type) power the `overview` and `export` commands. Never imports `com.github.javaparser.*`.
- `export/` — `ExportHtmlWriter`: renders a single self-contained HTML file from `OverviewResult` + class-level edges. Reads `/export/template.html` via `getResourceAsStream` (mirrors `SqliteStore.readSchema()`), substitutes a `/*__ANATOMIST_DATA__*/` placeholder with a `Json.writeCompact` data blob. Template ships an inline plain-JS SVG force-directed renderer (no d3/CDN): collapsible package tree + package-dependency graph, drill down to a package's class-level subgraph.
- `cli/` — `AnatomistCli` (picocli root) + `IndexCommand` / `IndexDocsCommand` / `WatchCommand` (index-side) + query subcommands (`SearchCommand` / `ContextCommand` / `CalleesOfCommand` / `CallersOfCommand` / `HierarchyCommand` / `ImplementorsOfCommand` / `DepsOfCommand` / `UsedByCommand` / `FieldReadersCommand` / `FieldWritersCommand` / `CallPathCommand` / `PackageDepsCommand` / `EnrichCommand` / `AnnotateCommand`) + project-overview commands `OverviewCommand` (top-down summary, `--format markdown|json`, `--depth N`) and `ExportCommand` (`--format html --output f.html`, `--max-edges`). Query subcommands resolve the index db via `IndexPath` (explicit `--index` flag → fallback `./.anatomist/index.db`).

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
- **`WIRES` edges originate from CLASS nodes, not BEAN nodes.** So deleting `BEAN` nodes does **not** cascade-delete the `WIRES` edges that model their wiring. The incremental bean-graph rebuild must drop `WIRES` explicitly — `SqliteStore.deleteSpringBeanGraph()` deletes `WIRES` first, then `BEAN` nodes (whose `DEFINED_BY` edges do cascade). Modelling `WIRES` as CLASS→CLASS is also what lets it fold into `deps-of`/`used-by` with zero query rework (`QueryService` filters `relation IN ('CALLS','REFERENCES','WIRES')`).

### Schema location

The single source of truth for SQLite DDL is `src/main/resources/schema.sql`. The structure mirrors `docs/scenario-1-index.md §完整 DDL` exactly. `documents` and `semantic_annotations` tables belong to Phase 2 and are intentionally **not** created yet.

### Fixtures

**`fixtures/mini-spring-shop/`** — three-module Maven project (api / domain / service). The canonical end-to-end input. Baseline after the Phase 1 scenario-1 gap-closure (LAMBDA / METHOD_REF / RECORD / Java version detection / isAccessor all live): **16 types, 47 methods, 75 CONTAINS edges, ≥1 LAMBDA, ≥1 METHOD_REF**. Residual `Pruned dangling` is now **2 on this fixture** after `NodeIdGenerator` learned the stable `$anon@L<line>` id for `JavaParserAnonymousClassDeclaration`; remaining 2 come from edge cases (nested anon inside lambda, etc.) — should reach 0 in a future extractor sweep. Numbers must grow **monotonically** as more Extractors come back online — use them as a regression baseline. The module also ships `service/src/main/resources/applicationContext.xml` (4 beans, one with a `constructor-arg` + two `property` refs); with `--spring-xml` the index gains **4 BEAN nodes, 4 DEFINED_BY edges, 3 WIRES edges** (CLASS→CLASS from `OrderService`).

**`fixtures/micro/`** — 8 single-file <30-line fixtures (LambdaInStream / AnonymousRunnable / OverloadedMethods / StaticVsInstance / GenericRepository / FieldReadWrite / EnumWithMethods / InterfaceDefaultMethod) — each pins one language feature. Driven end-to-end by `MicroFixtureIT` (10 tests, including 2 JDK 8 negative assertions). See `docs/testing-strategy.md §二 Fixture A`.

**`fixtures/external/commons-lang/`** — git submodule pinned to `rel/commons-lang-3.12.0` (Apache Commons Lang 3.12.0, JDK 8). Scale baseline: indexes in ~5s, `Pruned dangling = 54` (down from 188 before the anon-class id fix; vs. 2 on mini-spring-shop). **The 54 number must trend down** as extractor coverage improves; if it grows, a regression has slipped in. Driver `CommonsLangSmokeIT` runs only when the submodule is checked out (per-test `assumeTrue` → visible "Skipped: N" in Surefire). Setup command lives in `fixtures/external/README.md`.

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

**Phase 5 (native image) will reshape this budget**: Jackson and javassist will be dropped, `picocli-codegen` added as a compile-time annotation processor only. Terminal budget = 3 direct runtime deps (`javaparser-symbol-solver-core` + `sqlite-jdbc` + `picocli`). Any new dep proposal must justify its native-image story.

### ReflectionTypeSolver / `--vm-classpath` gotcha

`JavaParserFactory` optionally adds a `ReflectionTypeSolver` so JDK types (`java.lang.*`, `java.util.*`) resolve. If the anatomist process runs on a much newer JDK than the target project, this leaks high-version APIs into the resolution (`String.strip()`, `List.of()`, sealed-class types) and creates false-positive bindings. `IndexCommand` exposes it via `--vm-classpath` (defaults to `true` so the fixture and most projects resolve `java.lang.*` out of the box; turn it off when analyzing older Java versions).

### Test helper visibility

`src/test/java/com/anatomist/core/JavaParserTestSupport` is referenced from other test packages (`extract/`, etc.) and **must remain `public`**. Don't downgrade visibility "for cleanup".

### MethodExtractor / BR-007 invariant

`MethodExtractor.skipDeclaringType` short-circuits when the declaring type isn't one of class / interface / enum / annotation — i.e. it skips methods declared inside a local class. Anonymous classes are now emitted by `TypeExtractor`, so anonymous-class methods are safe to extract; only true local classes (e.g. `class Inner` inside a method body) are still dropped to avoid CONTAINS edges with no matching source Node → FK violation → whole index rollback. Loosen this guard **only** in the task that introduces LOCAL_CLASS / LAMBDA node extraction.

### FTS5 tokenizer surprise

`node_names` uses FTS5 default tokenizer, which splits `pkg.Class#method()` into `pkg`, `Class`, `method`. Use `label MATCH '<term>'` to scope to a column when you need precision; use `nodes.qualified_name = ?` for true equality lookups.
