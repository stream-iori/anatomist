# Data Model Reference

## Relations: What to Store

From scenario requirements, only store what Agent actually queries.

### Core 5 (cover 80% of scenarios)

| Relation | Source | What's Stored | Use Case |
|----------|--------|---------------|----------|
| **CONTAINS** | Class/Enum → Method/Field | parent → child node | "What methods does OrderService have?" |
| **CALLS** | `MethodCallExpr.resolve()` or exact reflection handle propagation | `call_site_owners` 去重 caller/file，`call_sites` 保存位置，targets 保存目标 | Call chain tracing, impact analysis |
| **INHERITS** | `getAncestors()` class ancestors | child → parent class | Inheritance chain |
| **IMPLEMENTS** | `getAncestors()` interface ancestors | implementor → interface | "Who implements this interface?" |
| **ANNOTATED_WITH** | `getAnnotations().resolve()` | node → annotation | "All nodes annotated with @Deprecated" |

### Supplementary 4 (cover remaining 20%)

| Relation | Source | What's Stored | Use Case |
|----------|--------|---------------|----------|
| **OVERRIDES** | Method resolution against parent methods | child method → parent method | Polymorphic dispatch |
| **REFERENCES** | Field/param/return type or exact reflection lookup resolution | user → referenced type/member | Dependency analysis, deletion impact |
| **READS** | NameExpr/FieldAccessExpr not on assignment LHS | method → field | "Who reads order.status?" |
| **WRITES** | AssignExpr/UnaryExpr LHS field | method → field | "Who modifies order.status?" |

### Framework Relations

| Relation | Source | What's Stored | Use Case |
|----------|--------|---------------|----------|
| **DEFINED_BY** | Spring stereotypes / `@Bean` / XML `<bean>` | BEAN → class or factory method | "Which Bean represents this class?" |
| **INJECTS** | `@Autowired` / `@Resource` / `@Inject` | owner class → injected type | Dependency analysis with DI facts |
| **HANDLES** | Spring MVC mapping annotations | ROUTE → controller method | "Which HTTP endpoint enters this method?" |
| **WIRES** | Spring XML bean refs | owner class → referenced class | XML wiring impact analysis |
| **CONFIGURES** | Spring XML `<property>` / `<constructor-arg>` | BEAN → XML config node | XML config tree roots |
| **XML_CONTAINS** | Spring XML map/list/entry/ref nesting | XML config node → XML config node | Preserve map keys, list order, nesting |
| **XML_REFERS_TO** | Spring XML `<ref>` / `<idref>` | XML_REF/XML_IDREF → BEAN | Resolve configured bean references |
| **BINDS_TO** | Spring XML callable attributes/config | XML callable/property node → Java member candidate | Bind factory/constructor/setter/init/destroy without inventing CALLS |

### Not Stored

| Relation | Why | Alternative |
|----------|-----|-------------|
| IMPORTS | Covered by REFERENCES | Not needed |
| USES | Too vague, CALLS + REFERENCES covers it | Not needed |
| semantically_similar_to | Agent LLM reasoning | Runtime inference |

## Node identity, declarations, and ownership (schema v23)

Schema v23 adds provider/language provenance and provider-scoped identity. Schema v22
added structural annotation uses, meta-annotation relations, and configured member bindings.
Schema v20 makes call-site tables the only final CALLS storage. Schema v19 added
node-level exact ranges and numeric source ordinals; v18 normalized call-site storage;
v16 removed the former dataflow tables; v15 added nullable declaration
range columns to `declarations`, and v14 added `producer_id` to all structural
fact tables. An older index must be rebuilt; no migration or compatibility read
path is provided. Declaration rows preserve AST-derived
kind, effective/declared/implicit modifiers, visibility, lexical ownership,
nesting, source location, module, and scope. Query-time declaration discovery
uses this table only.

| Declaration range column | Meaning |
|---|---|
| `begin_line`, `begin_column` | JavaParser declaration start, one-based |
| `end_line`, `end_column` | JavaParser declaration end, one-based and inclusive |
| `source_ordinal` | Stable numeric sibling/source order; never parsed from an ID |

All four values are present together or all are `NULL`. Synthetic declarations
keep them `NULL`. Source text remains in the checkout; the database stores only
the range and snapshot hash evidence used by `resolve --exact | source`.

| Table | Ownership |
|---|---|
| `nodes`, `edges`, `declarations`, `annotations`, `annotation_meta_relations`, `semantic_annotations` | required `producer_id` |

| Common field | Meaning |
|---|---|
| `provider_id`, `language` | Which language frontend owns the fact |
| `domain` | `language` or cross-language domains such as `configuration` |
| `entity_kind` | Common kind: `type`, `callable`, `value`, ... |
| `language_kind` | Namespaced detail such as `java.record` |
| `semantic`, `mechanism` | Common relation meaning plus provider-specific mechanism |

`index_providers` records the exact provider version, operations, limitations, and
profile hash used for the snapshot. Query commands fail closed when the requested
language/provider was not installed or did not produce the index.

Multiple extensions may analyze the same source/resource. Incremental cleanup
is producer-scoped. The same node ID cannot be claimed by two producers;
`EXTENSION_NODE_OWNERSHIP_CONFLICT` aborts promotion instead of silently
overwriting ownership.

Every node stores both a logical symbol and a globally unique storage key:

```text
symbol_id = provider-owned logical identity
id        = <provider_id>::<module>::<scope>::<symbol_id>
```

`module` defaults to `.` for a single-root project. `scope` is `MAIN`, `TEST`,
or `GENERATED`. Query commands default to `MAIN`.

```text
java-core::service::MAIN::com.example.OrderService
java-core::service::TEST::com.example.OrderService
```

The two rows above intentionally share `symbol_id` but cannot collide in the
primary key. An older schema database is rejected/recreated; there is no migration
or compatibility read path.

Spring XML resources own an `ARTIFACT` root. `XML_CONTAINS` links it to ordered
beans and configuration nodes. Bean metadata retains `abstract`, `parent`,
`factoryBean`, `factoryMethod`, `initMethod`, `destroyMethod`, product type, and nested
ownership. `PARENT_BEAN`/`FACTORY_BEAN` preserve construction semantics;
`XML_CALLABLE_REF` + `BINDS_TO` connects factory/constructor/lifecycle methods, while
property nodes bind setters. These facts never manufacture `CALLS`.

### Symbol ID Generation Rules

`symbol_id` preserves original case and uses minimal syntax separators.

```
CLASS/INTERFACE/ENUM/RECORD: FQN as-is                               → com.example.OrderService
METHOD:                 classFQN + # + name + (erased-signature)     → com.example.OrderService#checkout(java.lang.String,java.util.List)
CONSTRUCTOR:            classFQN + # + typeName + (erased-signature) → com.example.OrderService#OrderService(java.lang.String)
FIELD:                  classFQN + # + fieldName (no parens = field)  → com.example.OrderService#orderRepo
ENUM_CONSTANT:          enumFQN + # + constantName                   → com.example.OrderStatus#PENDING
ANONYMOUS_CLASS:        parentMethodID + $anon@L<line>C<col>         → com.example.OrderService#checkout(...)$anon@L42C18
LAMBDA:                 parentMethodID + $lambda@L<line>C<col>       → com.example.OrderService#checkout(...)$lambda@L42C18
BEAN:                   bean:<springBeanName>                         → bean:orderService
ROUTE:                  route:<HTTP_METHOD> <path>|handler:<method>   → route:POST /api/orders|handler:com.example.OrderController#create()
XML_*:                  parent XML id + segment + source location      → bean:registry@beans.xml/property:filters@L10C5I0
```

### Key Decisions

| Decision | Reason |
|----------|--------|
| Storage key includes module + scope | Main/test/generated and multi-module duplicate FQNs remain distinct |
| Preserve case | `com.example.Order` (class) vs `com.example.order` (subpackage) must not collide |
| Method uses full erased signature | Overload disambiguation; derived from `erasure().describe()` |
| Varargs retain `...` | Stable distinction in the public declaration symbol contract |
| AST-aware parameter fallback | Unresolved parameter types use normalized source/import information instead of collapsing overloads to `<unresolved>` |
| Fact provenance | Java AST edges and annotations carry the originating `source_file`; synthetic edges inherit it from their source node |
| `#` separates class from member | Java doc convention; no parens = field, with parens = method |
| Lambda/anon use source location (`@L42C18`) | Ordinal would drift on file edits; position is stable |
| Module escaping | `%`, `:` are escaped before joining with `::` |

### ID Character Semantics

- `.` → package/class hierarchy (original FQN)
- `#` → class-to-member separator
- `()` → method signature wrapper
- `,` → method parameter separator
- `$` → synthetic symbol prefix (anon/lambda)
- `@` → source location marker

## Metadata JSON Structure (by kind)

```jsonc
// kind = CLASS
{
  "isAbstract": false,
  "isInterface": false,
  "typeParameters": ["<T>"],
  "superClass": "BaseService<Order>",
  "interfaces": ["Serializable", "Runnable"]
}

// optional on Lombok-affected CLASS/FIELD nodes; FIELD omits generated_member_count
{
  "lombok": {
    "mode": "ast",
    "semantic_level": "signature-only",
    "detected_annotations": ["Builder", "Getter", "Setter"],
    "modeled_capabilities": ["getter", "setter"],
    "partial_capabilities": [],
    "unmodeled_capabilities": ["builder"],
    "coverage": "partial",
    "generated_member_count": 2,
    "inference_policy": "hypothesis_only"
  }
}

// kind = METHOD
{
  "returnType": "OrderResult",
  "parameters": [
    {"name": "orderId", "type": "String"},
    {"name": "items", "type": "List<OrderItem>"}
  ],
  "isStatic": false,
  "isAbstract": false,
  "isConstructor": false,
  "isAccessor": false,
  "modifiers": ["public"],
  "signature": "checkout(String orderId, List<OrderItem> items)"
}

// optional on a fallback call site to a project-source Lombok type
{
  "lombok_usage": {
    "status": "usage-observed",
    "evidence": "call-site",
    "capability": "builder",
    "owner": "com.example.Order",
    "root": "builder",
    "builder_type": null,
    "mapping_status": "mapped",
    "steps": [
      {"method": "name", "arity": 1, "role": "property",
       "mapping_status": "mapped", "field": "name",
       "field_id": "com.example.Order#name"},
      {"method": "build", "arity": 0, "role": "terminal"}
    ]
  }
}

// kind = ANONYMOUS_CLASS
{
  "baseType": "Runnable",
  "methods": ["run"]
}

// kind = LAMBDA
{
  "parameters": [{"name": "item", "type": "OrderItem"}],
  "returnType": "boolean",
  "signature": "lambda1(OrderItem item) -> boolean"
}

// kind = FIELD
{
  "type": "OrderRepository",
  "isStatic": false,
  "isFinal": false
}

// kind = INTERFACE
{
  "typeParameters": ["<T>", "<ID>"],
  "methods": ["findById", "save", "delete"]
}

// kind = ENUM
{
  "constants": ["PENDING", "CONFIRMED", "SHIPPED", "DELIVERED"]
}

// kind = BEAN
{
  "className": "com.example.shop.service.OrderService",
  "source": "annotation",
  "stereotype": "Service"
}

// kind = ROUTE
{
  "mappingAnnotation": "PostMapping",
  "parameters": [
    {"name": "request", "type": "CreateOrderRequest", "binding": "RequestBody"}
  ]
}
```

The stored object lives at `call_sites.metadata.lombok_usage`. `calls` also exposes
the structured object directly as `lombok_usage`.

## Edges Table Design

| Column | Type | Description |
|--------|------|-------------|
| `source_id` | TEXT FK→nodes.id | Caller/child/container |
| `target_id` | TEXT FK→nodes.id | Callee/parent/contained; **internal only**, NULL for external |
| `external_target_fqn` | TEXT | External dep FQN (e.g. `java.util.List#add`); NULL for internal |
| `relation` | TEXT | CONTAINS/INHERITS/IMPLEMENTS/OVERRIDES/REFERENCES/READS/WRITES/DEFINED_BY/BINDS_TO/INJECTS/HANDLES/WIRES/CONFIGURES/XML_CONTAINS/XML_REFERS_TO；最终库不含 CALLS |
| `call_kind` | TEXT | 历史/暂存兼容列；schema 23 最终 `edges` 的非调用关系不使用 |
| `confidence` | TEXT | `EXTRACTED` for source facts, `CONFIGURED` for framework/config facts, `INFERRED` for derived dispatch/reflection bridges |
| `resolution` | TEXT | External only: `classpath`, `ast_fallback`, `type_fallback`, `static_name_fallback`, `source_fallback`, `reflection`, or `xml`; NULL for internal edges |
| `context` | TEXT | READS/WRITES 的轻量控制路径；REFERENCES 的 field_type/parameter_type/return_type/generic_arg |
| `is_external` | INTEGER | 0=internal, 1=external |
| `source_file` | TEXT | Relative source file path when known |
| `source_location` | TEXT | Line marker such as `L32`; query commands can turn this into `source_window` snippets |
| `metadata` | TEXT | Optional JSON payload for edge-specific details |

**CHECK constraint**: `is_external=0 ⇒ target_id NOT NULL & external_target_fqn NULL` (and inverse).

**Composite indexes**: `(relation, is_external, target_id)` and `(relation, is_external, external_target_fqn)`.

**Target split rationale**: Prevents name collision between internal node ID and external FQN text; schema enforces correctness without runtime checks.

**External reverse-query contract**: External targets intentionally have no row in
`nodes`. Incoming `calls` reads `call_site_targets.external_target_fqn`; incoming
`references` reads external edge targets. `search Type` synthesizes an `EXTERNAL_CLASS`
result from both stores; it is not a row in `nodes`. It aggregates
`external_edge_count`, `relation_counts`, `resolution_counts`, and
`confidence_counts`. External edge results include `external_target=true`,
`is_external=true`, `external_target_fqn`, `resolution`, and `confidence` so
consumers do not mistake an external fact for a project-source declaration.

## Project Meta

`project_meta` is a key/value table for index snapshot metadata. It is the
place to add small scalar facts that affect query interpretation but do not
belong on every node or edge.

| Key | Meaning | Use Case |
|-----|---------|----------|
| `source_root` | Absolute root of the indexed source tree | Resolve `source_window.path` |
| `source_paths` | Path-separated source roots included in this index | Debug missing files/classes |
| `indexed_at` | Timestamp of the index run | Detect stale DBs |
| `source_git_commit` | Git commit at index time | Reproduce exact source snapshot |
| `source_git_branch` | Git branch at index time | Human context |
| `source_git_dirty` | `true` when uncommitted changes existed | Warn that DB may not match a clean commit |
| `source_git_commit_time` | Commit timestamp | Audit/repro |
| `source_git_remote_origin_url` | Git origin URL | Trace repository source |
| `java_version` | Java parser language level | Debug parser behavior |
| `classpath_hash` | Fingerprint of classpath input | Detect changed resolution environment |
| `index_version` | File-cache/schema version | Incremental compatibility |
| `index_revision_id` | Opaque identity of one committed fact set | Pipeline consistency |
| `source_snapshot_fingerprint` | Portable identity of the indexed source/resource set | Identify the built snapshot |
| `index_environment_hash` | Analysis inputs | Semantic profile derivation |
| `source_layout` / `source_layout_hash` | Module/scope/root identity mapping | Force full indexing when identity inputs change |
| `config_source` / `config_path` | Selected configuration origin and path | Explain which config profile produced the index |
| `scan_policy` / `scan_policy_hash` | Canonical scopes, roots, glob rules, and hard-exclude policy | Force full rebuild when scan eligibility changes |

CLI 的 `index_identity` 是上述已提交元数据的只读封装，只描述索引构建时的
事实集。索引与当前工作区的一致性由调用方在查询前执行增量同步来保证。

## Canonical call sites

`call_site_owners` interns each `(caller_id, source_file)` pair. `call_sites` stores only
its integer `owner_pk`, exact begin/end positions, numeric ordinal, syntax target,
receiver static type, dispatch kind, origin, resolution status, and producer.
`call_site_targets` stores one or more internal/external static targets. The stable
site ID excludes revision and target, so ambiguous overloads share a site and an
equivalent full rebuild preserves its identity.

```text
public ID: callsite:sha256:<hex(stable_hash)>
                         │
call_site_owners.owner_pk ──< call_sites.owner_pk
                                      │
call_sites.site_pk (INTEGER) ──< call_site_targets.call_site_pk
```

`owner_pk` and `site_pk` are private storage identities; they must never leak into query output.
`stable_hash` is the 32-byte binary digest used to reconstruct the unchanged public ID.
Incremental promotion captures affected callers before graph replacement and refreshes
only their persisted sites; unrelated call sites retain their rows. `edges` 中的
`CALLS` 数量必须始终为 0。

调用点 `id` 是出现位置身份；公开语义流中的 `relationship_id` 是跨索引关系身份。
前者会随挪行变化，后者对 target 集合排序去重后计算，适合 Review 比较。

## Index Diagnostics

`index_diagnostics` persists bounded, machine-readable health findings. The
same rows drive `index`, `doctor`, and `overview`/query evidence.

| Finding | `none` | `integrity` | `complete` / `--strict-health` |
|---|---:|---:|---:|
| no findings | 0 | 0 | 0 |
| external/JDK resolution gap (including informational third-party gaps) | 0 | 0 | 3 |
| parse or graph-integrity finding | 0 | 3 | 3 |
| other error | 0 | 0 unless integrity-classified | 3 |

Resolution diagnostics are grouped by `source_file`, `module`, `scope`, `phase`,
reason, symbol, and source line. `occurrence_count` is the number of failed
operations at that site; it is not a count of unique source expressions.
Stable reasons include `INTERNAL_SYMBOL_MISSING`,
`THIRDPARTY_SYMBOL_MISSING`, `JDK_SYMBOL_MISMATCH`, `METHOD_NOT_FOUND`,
`FIELD_NOT_FOUND`, `GENERIC_INFERENCE_FAILED`, `AMBIGUOUS_OVERLOAD`, and
`UNSUPPORTED_RESOLUTION`. Parsing failures use `JAVA_PARSE_FAILED`.
At most 5,000 rows are retained. When the input exceeds the bound, the final
row is `DIAGNOSTIC_STORAGE_TRUNCATED` with the omitted count; error/warning
rows are prioritized ahead of informational rows.

`DIAGNOSTIC_LIMIT_REACHED` and `DIAGNOSTIC_STORAGE_TRUNCATED` are capacity
metadata, not symbol-resolution reasons. They remain diagnostic rows for wire
compatibility and still block `complete` health, but do not appear in
`health_dimensions.resolution.other.codes`.

Query JSON derives a capability-specific `evidence` object from these rows.
Outgoing queries narrow file-scoped diagnostics to their anchor files;
incoming/global queries remain conservative because an unresolved caller can
originate anywhere.

`semantic-stream/v1` 是公开投影。存储主键 `site_pk`、内部别名和重复默认值不应泄漏；
每条记录用 revision/snapshot/profile 与 evidence 说明解释边界。

`analysis_coverage` stores file-level aggregates before the 5,000-row storage
retention step. The upstream resolution tracker still has a 50,000-group bound;
when it is reached, `diagnostic_aggregation.truncated=true` discloses that
later source-level detail is unavailable.

| Column | Meaning |
|---|---|
| `source_file`, `module`, `scope`, `language`, `provider_id` | Coverage boundary; `*` means global/unknown |
| `capability` | Query capability such as `CALL_OUTGOING` or `CALL_PATH` |
| `status` | `complete`, `partial`, or `failed` |
| `occurrences`, `groups_count` | Full aggregate counts |
| `codes`, `code_counts` | Stable reason set and per-reason counts |
| `details_truncated` | Whether detail samples were truncated |

Query safety is derived from this table, not from the bounded
`index_diagnostics` sample. Schema v16 has no migration path; older indexes
must be rebuilt.

## Core reflection facts

Core reflection analysis runs by default.
It recognizes SymbolSolver-confirmed JDK APIs only:

| Source pattern | Stored fact |
|---|---|
| `Class.forName("p.Target")` | `REFERENCES` target type |
| `Target.class.getMethod("run", String.class)` | `REFERENCES` target method |
| `getConstructor(...)` / `getDeclaredConstructor(...)` | `REFERENCES` target constructor |
| `Method.invoke(...)` / `Constructor.newInstance(...)` | call site，`dispatch_kind=REFLECTION` |

Generated facts use `confidence=INFERRED` and JSON metadata containing
`via=reflection`, `operation`, `resolution=EXACT`, target class/member/signature,
lookup mode, and value source. Local strings and reflection handles are
propagated conservatively inside one executable body. Unknown, conflicting, or
non-unique values produce no target fact.

`source_window` is not stored as a table. It is derived at query time from:

```
project_meta.source_root + call_sites/edges.source_file + exact source range
```

If an edge lacks `source_file`, query code falls back to the source node's
`nodes.source_file`.

## Annotations Table

| Column | Type | Description |
|--------|------|-------------|
| `node_id` | TEXT FK | Annotated node |
| `annotation_fqn` | TEXT nullable | 已解析/启发式 FQN；无法解析时不丢事实 |
| `raw_name` | TEXT | 源码中写下的名字 |
| `attributes` | TEXT | JSON e.g. `{"value": "/api/orders"}` |
| `target_kind` / `target_path` | TEXT | type/callable/value；参数与 record component 用稳定路径 |
| `language` / `mechanism` | TEXT | 跨语言 IR 来源；当前 frontend 为 Java |
| `resolution_status` | TEXT | `exact` / `heuristic` / `unresolved` |
| range columns | INTEGER | 注解自身的精确源码范围 |

`annotation_meta_relations` 存直接 meta 边。查询默认 direct-only；显式
`--include-meta` 才做深度 16、环安全闭包。结构范围覆盖类型、方法、构造器、
字段、参数、enum constant 和 record component；不覆盖 type-use、局部变量、
package/module 与 annotation member。
