# Architecture Reference

## Package layout under `src/main/java/com/anatomist/`

- `model/` — Plain data: `Node`, `Edge`, `Annotation`, `ExtractionResult`
- `core/` — Index application boundary and plumbing: `IndexRequest`, `IndexApplicationService`, `IndexOutcome`, `ProjectScanner`, `ClasspathDetector`, `JavaParserFactory`, identity/health services, and extraction context.
- `extract/` — `Extractor` implementations. `CallGraphExtractor` handles traversal/emission while `CallOverloadResolver` owns shared AST/SymbolSolver overload ranking. Plus `XmlBeanExtractor` for Spring XML beans.
- `framework/` — Compile-time extension SPI. `AstModelExtension` augments the in-memory AST, `CallSiteEvidenceProvider` decorates fallback calls without changing graph shape, `JavaUnitAnalyzer` emits per-unit facts, `ProjectResourceProvider` discovers shared resources, and `ProjectResourceAnalyzer` emits project-resource facts. `AnalyzerRegistry` wires built-ins.
- `framework/spring/` — Spring Boot baseline analyzers: stereotype beans, `@Autowired` injections, MVC routes, and optional XML bean wiring.
- `framework/lombok/` — Opt-in signature-only Lombok AST model and root `lombok.config` subset; queries disclose modeled/partial/unmodeled capability evidence rather than inventing unmodeled members.
- `store/` — schema、staging、原子替换和 `CallSitePersistence`；最终调用事实写入字典化的 `call_site_owners` / `call_sites` / `call_site_targets`。
- `semantic/` — Post-index annotations from direct code evidence: `SemanticPostProcessor` writes Javadoc summaries only; it does not infer architecture roles or business categories from names/annotations.
- `query/` — 只读查询层；`QueryService` 组合 search、declaration、call-site、Java semantic、generic semantic、overview 和源码快照校验服务。
- `query/semantic/` — `semantic-stream/v1` records、NDJSON framing、三类身份和有界 cursor。
- `cli/` — picocli adapters; `IndexOutput` owns the full/incremental text and JSON contract instead of mixing rendering into `IndexCommand`.

## Query pipeline

```text
pipeline argv / JSON spec
        │ parse + record-type preflight
        ▼
SemanticExecutionContext
  └─ one IndexLock + QueryService + SQLite connection + SemanticIdentity
        │
        ▼
SemanticCommand ── Map record ──> SemanticFrameAssembler
        │                            │ bounded SeedFrame
        └────────────────────────────▼
                              next SemanticCommand ──> final writer
```

Standalone commands use the same `SemanticCommand` adapter with NDJSON stdin/stdout.
Fused execution replaces only the intermediate transport: it does not change query services,
record order, evidence, SQLite schema, or Shell-pipeline compatibility. A cursor is closed
before its completed frame is sent downstream, so the shared connection never nests live results.

## Indexing pipeline

```
IndexCommand (picocli adapter)
  → IndexRequest → IndexApplicationService → IndexOutcome
  → ClasspathDetector.{detectSourcePaths, detect}
  → ProjectScanner.scan(sourcePaths)
  → BuiltInExtensions → PreparedExtensions (validate IDs + fingerprint)
  → JavaParserFactory.parseAll(consumer)
      AstModelExtension (main parser + every JavaParserTypeSolver)
          LombokAstModelExtension → synthetic methods/constructors/logger fields (--lombok ast)
      for each CompilationUnit:
          TypeExtractor    → CLASS/INTERFACE/ENUM/ANONYMOUS_CLASS nodes
          MethodExtractor  → METHOD nodes + CONTAINS edges
          FieldExtractor   → FIELD nodes + CONTAINS edges
          AnnotationExtractor → structural annotations + direct meta relations
          CallGraphExtractor  → staged CALLS facts with call kind/location/context
              CallSiteEvidenceProvider → namespaced metadata on fallback CALLS
          HierarchyExtractor  → INHERITS/IMPLEMENTS/OVERRIDES edges
          ReferenceExtractor  → REFERENCES edges with context
          FieldAccessExtractor → READS/WRITES edges
          JavaUnitAnalyzer pass:
              SpringComponentAnalyzer → BEAN / DEFINED_BY / INJECTS
              SpringMvcAnalyzer       → ROUTE / HANDLES
  → ProjectResourceAnalyzer pass (shared inventory + staged Java fact view):
          SpringXmlAnalyzer       → BEAN / DEFINED_BY / WIRES / BINDS_TO (--spring-xml only)
  → ExtractorPipeline provenance → source_file on Java facts
  → GraphIdentityRewriter        → provider::module::scope::symbol_id storage keys
  → GraphPostProcessor           → bind/prune graph facts
  → StagedGraphStore → sibling temporary SQLite DB
  → quick_check + foreign_key_check + schema/semantics gate
  → CallSitePersistence interns owners, writes call_sites/call_site_targets, drops final edges(CALLS)
  → atomic replacement of the live index
  → IndexHealthService           → persisted index_diagnostics
```

## Framework Analyzer Model

Framework support must add graph facts, not hard-code logic into `IndexOrchestrator`.

| Extension point | Timing | Use |
|---|---|---|
| `AstModelExtension` | Parse 后、contract hash/core extractor 前 | Lombok-like generated signatures; must be idempotent. |
| `CallSiteEvidenceProvider` | fallback CALLS 写入前 | Add source-observed metadata; cannot add nodes or edges. |
| `JavaUnitAnalyzer` | Core extractor 后 | Source annotations and declarations, e.g. Spring MVC and `@Autowired`. |
| `ProjectResourceProvider` | 项目分析前 | Discover and de-duplicate non-Java resources into one shared inventory. |
| `ProjectResourceAnalyzer` | Java facts staged 后 | XML/YAML/generated metadata; analyzers share the inventory and read-only fact view. |

Built-ins are registered once in `BuiltInExtensions`; no `ServiceLoader`, reflection scan, or external-jar loading is used. `PreparedExtensions` rejects blank/duplicate IDs and persists a fingerprint of implementation class, ID, version, and producer.

Each extension runs in isolation. A failure records `EXTENSION_*_FAILED`, discards
that extension's partial output, and lets the remaining producers continue.

```text
resource inventory ─┬─ selector A → producer-a facts
                    └─ selector B → producer-b facts

incremental promotion: delete owned facts → upsert stable nodes
                     → delete obsolete owned nodes → insert new facts
```

All structural rows carry `producer_id`. A node ID has exactly one producer;
cross-producer ownership raises `EXTENSION_NODE_OWNERSHIP_CONFLICT`. Relations
may reference nodes owned by another producer.

## Language Provider Model

```text
LanguageProvider
  ├─ descriptor   provider/language/version/extensions/operations
  ├─ discover     source roots → provider-owned files
  ├─ selectors    public selector → provider-local selector
  └─ semantics    provider-specific semantic operations
                         │
                         ▼
              common nodes / declarations / edges
```

Providers are registered explicitly in `LanguageProviderRegistry`; there is no
reflection or `ServiceLoader`, so JVM and native-image use the same registry.
The first installed provider is `java-core`. Python 3, Rust, and TypeScript use
the same contract when their parsers are added; Java concepts such as record or
JDK resolution stay inside the Java provider.

## Critical invariants

- **Storage identity is `provider::module::scope::symbol_id`.** Logical `symbol_id` remains provider-owned and preserves original case.
- **Callable identity is AST-aware.** If SymbolSolver cannot render a parameter, normalized AST type text keeps overloads distinct.
- **Record members are first-class.** Explicit methods, compact/canonical constructors, component fields, and accessors receive normal graph nodes.
- **Record is core Java semantics, not an extension.** It remains available even when all framework extensions are disabled.
- **Candidate uniqueness is based on storage keys.** Repeated extraction of the same key is not module ambiguity; distinct module/scope keys remain ambiguous.
- **Query scope defaults to MAIN.** Cross-scope lookup must be explicit with `--scope`.
- **Method ID uses erased FQN signature.** `pkg.A#foo(java.lang.String,java.util.List)` — NOT generic args.
- **`edges` CHECK constraint:** `is_external=0 ⇒ target_id NOT NULL & external_target_fqn NULL` and vice versa.
- **Symbol resolution failure → skip the entity.** Catch `RuntimeException`, call `ctx.incrementUnresolved()`.
- **Index and Query are separate.** Query-side code must never import `com.github.javaparser.*`.
- **Graph meaning is versioned separately from schema.** Missing metadata is
  legacy semantics version 0; a mismatch requires a clean rebuild even when
  SQLite schema columns are compatible.
- **A full build never mutates the last good DB.** It builds a sibling DB,
  validates it, and promotes it only after success. All SQLite content,
  including indexed documents and manual semantic annotations, is renewable.
- **No architecture role inference.** The index stores code facts and lightweight semantic annotations. Higher-level architecture judgment belongs to the calling Agent.
- **JavaDoc stored as summary only.** Extracted via `JavadocSummary.extract()` (strips @tags, first sentence rule).
- **Query output is Agent-bounded and discloses each bound.** `evidence` 和
  `truncated` 决定是否能作完整/否定结论；分页未完成时必须继续查询。
- **Exact source is primary local control-flow evidence.** `resolve --exact | source`
  只返回索引声明范围，校验源码快照，并对长方法分页。
- **CALLS has one final source of truth.** extractor 可在 staging 中使用 CALLS，
  但提交库的 `edges` 不含 CALLS；查询、overview 和增量影响分析统一读取
  `call_site_owners` / `call_sites` / `call_site_targets`。
- **DI does not manufacture calls.** `INJECTS`/`WIRES` 只作为配置证据和 dispatch
  缩小条件，不能生成不存在的源码调用点。
- **Spring Boot basics are static facts.** `BEAN`, `ROUTE`, `INJECTS`, and `HANDLES` are configured/static evidence, not proof of the exact runtime object under profiles, conditions, or AOP.
- **Spring matching is FQN/meta based.** Bare unresolved simple names never trigger Spring semantics. Source/classpath composed annotations use a bounded closure; `@AliasFor` rewriting is not modeled.
- **Configured members are not calls.** XML factory/constructor/setter/init/destroy SymbolRefs emit `BINDS_TO`; ambiguous overloads retain every candidate and unresolved refs retain their raw identity.
- **`WIRES` edges originate from CLASS nodes, not BEAN nodes.** XML WIRES must drop explicitly on XML incremental rebuild; annotation BEAN nodes must not be deleted by XML cleanup.

## Schema

Single source of truth: `src/main/resources/schema.sql`

Schema v23 has no migration path. It stores provider ownership, structural annotation/meta facts and
configured member bindings in addition to call-site joins with an internal integer
primary key and keeps the public stable ID as a 32-byte SHA-256 digest. Separate
`call_site_targets` let one syntax site retain several static candidates without
repeating a long text key.
`graph_semantics_version=6` identifies this meaning independently of table layout.
`index_revision_id` is published in the same transaction as query-visible facts.

`doctor` reports the committed index identity, health, checkout and Git metadata but
does not prove that a mutable worktree still matches the index. The caller owns
worktree lifecycle and synchronizes each index with `index --incremental` before
queries. Cross-index consumers require equal semantic profiles and pinned identities.

```text
startup / doctor
  ├─ corrupt, integrity failure, schema mismatch, empty graph ──> RECREATE
  ├─ graph semantics mismatch ──────────────────────────────────> RECREATE
  ├─ JDK/classpath/source-root environment change ──────────────> FULL
  └─ source changes only ───────────────────────────────────────> INCREMENTAL
```

There is no timed GC. The index is derived state, so deterministic compatibility
checks and safe replacement remove stale facts without background mutation.

## Fixtures

- `fixtures/mini-spring-shop/` — 3-module Maven project (api/domain/service). Baseline: 16 types, 47 methods, 76 CONTAINS edges, plus Spring BEAN/ROUTE framework facts.
- `fixtures/micro/` — 8 single-file fixtures pinning one language feature each. Driven by `MicroFixtureIT`.
- `fixtures/extension-lifecycle/` — JDK 25 record + Spring annotation/XML fixture used by full/incremental and JVM/native E2E.
- `fixtures/external/commons-lang/` — git submodule, scale baseline. Auto-skips when missing.

## Gotchas

- **ReflectionTypeSolver / `--vm-classpath`**: newer JDK leaks high-version APIs. Toggle via `--vm-classpath`.
- **Test helper visibility**: `JavaParserTestSupport` must remain `public`.
- **MethodExtractor / BR-007**: skips local classes to avoid FK violations.
- **FTS5 tokenizer**: splits on `.#()` — use `label MATCH` for precision, `qualified_name = ?` for equality.
