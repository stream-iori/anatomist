# Data Model Reference

## Relations: What to Store

From scenario requirements, only store what Agent actually queries.

### Core 5 (cover 80% of scenarios)

| Relation | Source | What's Stored | Use Case |
|----------|--------|---------------|----------|
| **CONTAINS** | Class/Enum → Method/Field | parent → child node | "What methods does OrderService have?" |
| **CALLS** | `MethodCallExpr.resolve()` | caller method → callee method; `call_kind` = INSTANCE/STATIC/CONSTRUCTOR/SUPER/INTERFACE | Call chain tracing, impact analysis |
| **INHERITS** | `getAncestors()` class ancestors | child → parent class | Inheritance chain |
| **IMPLEMENTS** | `getAncestors()` interface ancestors | implementor → interface | "Who implements this interface?" |
| **ANNOTATED_WITH** | `getAnnotations().resolve()` | node → annotation | "All nodes annotated with @Deprecated" |

### Supplementary 4 (cover remaining 20%)

| Relation | Source | What's Stored | Use Case |
|----------|--------|---------------|----------|
| **OVERRIDES** | Method resolution against parent methods | child method → parent method | Polymorphic dispatch |
| **REFERENCES** | Field/param/return type resolution | user → referenced type | Dependency analysis, deletion impact |
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

### Not Stored

| Relation | Why | Alternative |
|----------|-----|-------------|
| IMPORTS | Covered by REFERENCES | Not needed |
| USES | Too vague, CALLS + REFERENCES covers it | Not needed |
| semantically_similar_to | Agent LLM reasoning | Runtime inference |

## Node Identity (schema v7)

Every node stores both a logical symbol and a globally unique storage key:

```text
symbol_id = extractor-level Java/config identity
id        = <module>::<scope>::<symbol_id>
```

`module` defaults to `.` for a single-root project. `scope` is `MAIN`, `TEST`,
or `GENERATED`. Query commands default to `MAIN`.

```text
service::MAIN::com.example.OrderService
service::TEST::com.example.OrderService
```

The two rows above intentionally share `symbol_id` but cannot collide in the
primary key. An older schema database is rejected/recreated; there is no migration
or compatibility read path.

### Symbol ID Generation Rules

`symbol_id` preserves original case and uses minimal syntax separators.

```
CLASS/INTERFACE/ENUM/RECORD: FQN as-is                               → com.example.OrderService
METHOD:                 classFQN + # + name + (erased-signature)     → com.example.OrderService#checkout(java.lang.String,java.util.List)
FIELD:                  classFQN + # + fieldName (no parens = field)  → com.example.OrderService#orderRepo
ENUM_CONSTANT:          enumFQN + # + constantName                   → com.example.OrderStatus#PENDING
ANONYMOUS_CLASS:        parentMethodID + $anon@L<line>               → com.example.OrderService#checkout(...)$anon@L42
LAMBDA:                 parentMethodID + $lambda@L<line>C<col>       → com.example.OrderService#checkout(...)$lambda@L42C18
BEAN:                   bean:<springBeanName>                         → bean:orderService
ROUTE:                  route:<HTTP_METHOD> <path>                    → route:POST /api/orders
XML_*:                  parent XML id + segment + source location      → bean:registry@beans.xml/property:filters@L10C5I0
```

### Key Decisions

| Decision | Reason |
|----------|--------|
| Storage key includes module + scope | Main/test/generated and multi-module duplicate FQNs remain distinct |
| Preserve case | `com.example.Order` (class) vs `com.example.order` (subpackage) must not collide |
| Method uses full erased signature | Overload disambiguation; derived from `erasure().describe()` |
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

## Edges Table Design

| Column | Type | Description |
|--------|------|-------------|
| `source_id` | TEXT FK→nodes.id | Caller/child/container |
| `target_id` | TEXT FK→nodes.id | Callee/parent/contained; **internal only**, NULL for external |
| `external_target_fqn` | TEXT | External dep FQN (e.g. `java.util.List#add`); NULL for internal |
| `relation` | TEXT | CALLS/CONTAINS/INHERITS/IMPLEMENTS/OVERRIDES/REFERENCES/READS/WRITES/DEFINED_BY/INJECTS/HANDLES/WIRES/CONFIGURES/XML_CONTAINS/XML_REFERS_TO |
| `call_kind` | TEXT | CALLS only: INSTANCE/STATIC/CONSTRUCTOR/SUPER/INTERFACE |
| `confidence` | TEXT | `EXTRACTED` for source facts, `CONFIGURED` for framework/config facts, `INFERRED` for query-time dispatch bridges |
| `context` | TEXT | CALLS/READS/WRITES: lightweight control path such as `for@L4>if-then@L5`; REFERENCES: field_type/parameter_type/return_type/generic_arg |
| `is_external` | INTEGER | 0=internal, 1=external |
| `source_file` | TEXT | Relative source file path when known |
| `source_location` | TEXT | Line marker such as `L32`; query commands can turn this into `source_window` snippets |
| `metadata` | TEXT | Optional JSON payload for edge-specific details |

**CHECK constraint**: `is_external=0 ⇒ target_id NOT NULL & external_target_fqn NULL` (and inverse).

**Composite indexes**: `(relation, is_external, target_id)` and `(relation, is_external, external_target_fqn)`.

**Target split rationale**: Prevents name collision between internal node ID and external FQN text; schema enforces correctness without runtime checks.

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
| `source_layout` / `source_layout_hash` | Module/scope/root identity mapping | Force full indexing when identity inputs change |

## Index Diagnostics

`index_diagnostics` persists bounded, machine-readable health findings. The
same rows drive `index`, `doctor`, and `survey-baseline` health output.

| Severity | Default exit | With `--strict-health` |
|---|---:|---:|
| healthy / no rows | 0 | 0 |
| warning (`DEGRADED`) | 0 | 3 |
| error (`UNHEALTHY`) | 0 | 3 |

`source_window` is not stored as a table. It is derived at query time from:

```
project_meta.source_root + edges.source_file + edges.source_location
```

If an edge lacks `source_file`, query code falls back to the source node's
`nodes.source_file`.

## Annotations Table

| Column | Type | Description |
|--------|------|-------------|
| `node_id` | TEXT FK | Annotated node |
| `annotation_fqn` | TEXT | e.g. `java.lang.Deprecated` |
| `attributes` | TEXT | JSON e.g. `{"value": "/api/orders"}` |

Separate table because annotation-based search (B4 scenario) needs SQL precision queries — can't efficiently index inside metadata JSON.
