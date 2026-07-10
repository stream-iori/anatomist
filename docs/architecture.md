# Architecture Reference

## Package layout under `src/main/java/com/anatomist/`

- `model/` — Plain data: `Node`, `Edge`, `Annotation`, `ExtractionResult`
- `core/` — Index application boundary and plumbing: `IndexRequest`, `IndexApplicationService`, `IndexOutcome`, `ProjectScanner`, `ClasspathDetector`, `JavaParserFactory`, identity/health services, and extraction context.
- `extract/` — `Extractor` implementations. `CallGraphExtractor` handles traversal/emission while `CallOverloadResolver` owns shared AST/SymbolSolver overload ranking. Plus `XmlBeanExtractor` for Spring XML beans.
- `framework/` — Internal analyzer SPI for framework/middleware concepts. `JavaAstAnalyzer` handles AST-backed concepts, `ProjectAnalyzer` handles project resources. `AnalyzerRegistry` wires built-ins.
- `framework/spring/` — Spring Boot baseline analyzers: stereotype beans, `@Autowired` injections, MVC routes, and optional XML bean wiring.
- `store/` — `SqliteStore` (schema + atomic batched write)
- `semantic/` — Post-index annotations from direct code evidence: `SemanticPostProcessor` writes Javadoc summaries only; it does not infer architecture roles or business categories from names/annotations.
- `query/` — Read-only query layer. `QueryService` delegates to focused services (`SearchService`, `TypeContextService`, `CallGraphService`, `BranchSliceService`, `DependencyService`, `EnrichmentService`, `OverviewService`). Result POJOs: `QueryEnvelope`, `NodeRow`, `EdgeRow`, `BranchSlice`, `ContextResult`, `HierarchyResult`, `OverviewResult`, `PackageStat`, `BlockResult`, `SliceResult`, `EnrichResult`, `PagedResult<T>`. `CallChainSlicer` groups call chains into class/package blocks. `JsonFormatter` + `DtoCodecs` handle serialisation (no Jackson).
- `export/` — `ExportHtmlWriter`: self-contained HTML with SVG force-directed renderer
- `cli/` — picocli adapters; `IndexOutput` owns the full/incremental text and JSON contract instead of mixing rendering into `IndexCommand`.

## Index-phase data flow

```
IndexCommand (picocli adapter)
  → IndexRequest → IndexApplicationService → IndexOutcome
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
          SpringComponentAnalyzer → BEAN / DEFINED_BY / INJECTS
          SpringMvcAnalyzer       → ROUTE / HANDLES
  → ProjectAnalyzer pass:
          SpringXmlAnalyzer       → BEAN / DEFINED_BY / WIRES (--spring-xml only)
  → ExtractorPipeline provenance → source_file on Java facts
  → GraphIdentityRewriter        → module::scope::symbol_id storage keys
  → GraphPostProcessor           → bind/prune graph facts
  → SqliteStore.initSchema + write(result) (single transaction)
  → IndexHealthService           → persisted index_diagnostics
```

## Framework Analyzer Model

Framework support must add graph facts, not hard-code logic into `IndexOrchestrator`.

| Analyzer type | Use |
|---|---|
| `JavaAstAnalyzer` | Source annotations and declarations, e.g. Spring MVC and `@Autowired`. |
| `ProjectAnalyzer` | Non-Java resources, e.g. Spring XML, future MyBatis XML, YAML, generated metadata. |

Built-ins are registered in `AnalyzerRegistry`. Keep shared relations generic (`DEFINED_BY`, `INJECTS`, `HANDLES`, `WIRES`) so future middleware analyzers can reuse the query layer.

## Critical invariants

- **Storage identity is `module::scope::symbol_id`.** Logical `symbol_id` still preserves original case.
- **Callable identity is AST-aware.** If SymbolSolver cannot render a parameter, normalized AST type text keeps overloads distinct.
- **Record members are first-class.** Explicit methods, compact/canonical constructors, component fields, and accessors receive normal graph nodes.
- **Candidate uniqueness is based on storage keys.** Repeated extraction of the same key is not module ambiguity; distinct module/scope keys remain ambiguous.
- **Query scope defaults to MAIN.** Cross-scope lookup must be explicit with `--scope`.
- **Method ID uses erased FQN signature.** `pkg.A#foo(java.lang.String,java.util.List)` — NOT generic args.
- **`edges` CHECK constraint:** `is_external=0 ⇒ target_id NOT NULL & external_target_fqn NULL` and vice versa.
- **Symbol resolution failure → skip the entity.** Catch `RuntimeException`, call `ctx.incrementUnresolved()`.
- **Index and Query are separate.** Query-side code must never import `com.github.javaparser.*`.
- **No architecture role inference.** The index stores code facts and lightweight semantic annotations. Higher-level architecture judgment belongs to the calling Agent.
- **JavaDoc stored as summary only.** Extracted via `JavadocSummary.extract()` (strips @tags, first sentence rule).
- **Query output is Agent-bounded.** callees-of/callers-of: MAX_DEPTH=20 + BFS dedup; enrich: 200 lines; deps-of/used-by/field-access: default --limit 50 + pagination; overview --deps-only: default 30.
- **Spring Boot basics are static facts.** `BEAN`, `ROUTE`, `INJECTS`, and `HANDLES` are configured/static evidence, not proof of the exact runtime object under profiles, conditions, or AOP.
- **`WIRES` edges originate from CLASS nodes, not BEAN nodes.** XML WIRES must drop explicitly on XML incremental rebuild; annotation BEAN nodes must not be deleted by XML cleanup.

## Schema

Single source of truth: `src/main/resources/schema.sql`

Tables: `nodes`, `edges`, `annotations`, `node_names` (FTS5), `documents`, `doc_content` (FTS5), `semantic_annotations`, `file_cache`, `project_meta`, `file_dependencies`, `index_diagnostics`.

## Fixtures

- `fixtures/mini-spring-shop/` — 3-module Maven project (api/domain/service). Baseline: 16 types, 47 methods, 76 CONTAINS edges, plus Spring BEAN/ROUTE framework facts.
- `fixtures/micro/` — 8 single-file fixtures pinning one language feature each. Driven by `MicroFixtureIT`.
- `fixtures/external/commons-lang/` — git submodule, scale baseline. Auto-skips when missing.

## Gotchas

- **ReflectionTypeSolver / `--vm-classpath`**: newer JDK leaks high-version APIs. Toggle via `--vm-classpath`.
- **Test helper visibility**: `JavaParserTestSupport` must remain `public`.
- **MethodExtractor / BR-007**: skips local classes to avoid FK violations.
- **FTS5 tokenizer**: splits on `.#()` — use `label MATCH` for precision, `qualified_name = ?` for equality.
