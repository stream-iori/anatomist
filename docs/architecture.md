# Architecture Reference

## Package layout under `src/main/java/com/anatomist/`

- `model/` — Plain data: `Node`, `Edge`, `Annotation`, `ArchRole`, `ExtractionResult`
- `core/` — Index-phase plumbing: `ProjectScanner`, `ClasspathDetector`, `JavaParserFactory`, `NodeIdGenerator`, `ExtractionContext`, `SpringBeanParser`, `JavadocSummary`
- `annotations/` — `@ArchRole` annotation (SOURCE retention) + `Category` enum (7 DDD layers)
- `extract/` — `Extractor` interface + 8 implementations (`TypeExtractor`, `MethodExtractor`, `FieldExtractor`, `AnnotationExtractor`, `CallGraphExtractor`, `HierarchyExtractor`, `ReferenceExtractor`, `FieldAccessExtractor`). All store javadoc as summary only. Plus `XmlBeanExtractor` (post-Java pass for Spring XML beans).
- `store/` — `SqliteStore` (schema + atomic batched write + arch_roles read/write)
- `semantic/` — Post-index intelligence: `SemanticPostProcessor` (convention rules), `ArchRoleInferrer` (L1+L2 DDD role inference), `SmellDetector` (6 architecture smell rules)
- `query/` — Read-only query layer. `QueryService` delegates to focused services (`SearchService`, `TypeContextService`, `CallGraphService`, `DependencyService`, `EnrichmentService`, `OverviewService`). Result POJOs: `QueryEnvelope`, `NodeRow`, `EdgeRow`, `ContextResult`, `HierarchyResult`, `OverviewResult`, `PackageStat`, `BlockResult`, `SliceResult`, `EnrichResult`, `PagedResult<T>`. `CallChainSlicer` groups call chains into blocks with arch_roles priority. `JsonFormatter` + `DtoCodecs` handle serialisation (no Jackson).
- `export/` — `ExportHtmlWriter`: self-contained HTML with SVG force-directed renderer
- `cli/` — picocli commands (17 subcommands)

## Index-phase data flow

```
IndexCommand
  → ClasspathDetector.{detectSourcePaths, detect}
  → ProjectScanner.scan(sourcePaths)
  → JavaParserFactory.parseAll(consumer)
      for each CompilationUnit:
          TypeExtractor    → CLASS/INTERFACE/ENUM/ANONYMOUS_CLASS nodes
          MethodExtractor  → METHOD nodes + CONTAINS edges
          FieldExtractor   → FIELD nodes + CONTAINS edges
          AnnotationExtractor → annotations table
          CallGraphExtractor  → CALLS edges with call_kind
          HierarchyExtractor  → INHERITS/IMPLEMENTS/OVERRIDES edges
          ReferenceExtractor  → REFERENCES edges with context
          FieldAccessExtractor → READS/WRITES edges
  → SemanticPostProcessor.process(result) → semantic_annotations
  → SqliteStore.initSchema + write(result) (single transaction)
```

## Critical invariants

- **Node ID preserves original case.** `com.example.Order` ≠ `com.example.order`.
- **Method ID uses erased FQN signature.** `pkg.A#foo(java.lang.String,java.util.List)` — NOT generic args.
- **`edges` CHECK constraint:** `is_external=0 ⇒ target_id NOT NULL & external_target_fqn NULL` and vice versa.
- **Symbol resolution failure → skip the entity.** Catch `RuntimeException`, call `ctx.incrementUnresolved()`.
- **Index and Query are separate.** Query-side code must never import `com.github.javaparser.*`.
- **`arch_roles` is post-index.** Populated by `annotate --auto`, read at query time. `CallChainSlicer` falls back to annotation heuristic when empty.
- **JavaDoc stored as summary only.** Extracted via `JavadocSummary.extract()` (strips @tags, first sentence rule).
- **Query output is Agent-bounded.** callees-of/callers-of: MAX_DEPTH=20 + BFS dedup; enrich: 200 lines; deps-of/used-by/field-access: default --limit 50 + pagination; overview --deps-only: default 30.
- **`WIRES` edges originate from CLASS nodes, not BEAN nodes.** Must drop explicitly on incremental rebuild.

## Schema

Single source of truth: `src/main/resources/schema.sql`

Tables: `nodes`, `edges`, `annotations`, `node_names` (FTS5), `documents`, `doc_content` (FTS5), `semantic_annotations`, `arch_roles`, `file_cache`, `project_meta`, `file_dependencies`.

## Fixtures

- `fixtures/mini-spring-shop/` — 3-module Maven project (api/domain/service). Baseline: 16 types, 47 methods, 75 CONTAINS edges.
- `fixtures/micro/` — 8 single-file fixtures pinning one language feature each. Driven by `MicroFixtureIT`.
- `fixtures/external/commons-lang/` — git submodule, scale baseline. Auto-skips when missing.

## Gotchas

- **ReflectionTypeSolver / `--vm-classpath`**: newer JDK leaks high-version APIs. Toggle via `--vm-classpath`.
- **Test helper visibility**: `JavaParserTestSupport` must remain `public`.
- **MethodExtractor / BR-007**: skips local classes to avoid FK violations.
- **FTS5 tokenizer**: splits on `.#()` — use `label MATCH` for precision, `qualified_name = ?` for equality.
