# anatomist — Agent skill

**When to use**: the user asks a *structural* question about a Java codebase —
"who calls X?", "what does Y depend on?", "show me the class hierarchy",
"how does the X flow work?". If you're tempted to grep + read 10 files to
answer, `anatomist` likely answers it in one query.

**Not for**: pure-text questions ("how do I write a unit test for foo?"),
runtime behavior, build/CI issues, or non-Java code.

---

## Setup check (do once per session)

1. Is there a `.anatomist/index.db` in the project root?
   - **Yes** → skip to "Query playbook".
   - **No** → build the index: `anatomist index <project-root>`. For a multi-
     module Maven project, pass `--project-source` with `:` separated module
     source roots (see `anatomist index --help`). On a Spring-style project
     where annotations matter, **do not** add `--no-classpath` — let
     `ClasspathDetector` pull `mvn dependency:build-classpath`.

2. Confirm the index is up to date: `anatomist index <project> --incremental`
   is cheap; the user can also run `anatomist watch <project>` in the
   background.

Index db default location: `<project>/.anatomist/index.db`. All query commands
accept `--index <path>` to override.

---

## Query playbook

Every command emits JSON to stdout in the shape:
```json
{ "query": "<echo of cmd line>", "results": [...], "stats": {...} }
```

### Find things

| You want… | Command | Notes |
|---|---|---|
| Locate a type/method by name | `anatomist search OrderService` | FTS5 prefix match. Add `--kind METHOD` to restrict. |
| Find by annotation | `anatomist search @RestController --by-annotation` | Matches annotation FQN substring (case-sensitive). |
| Jump to a type by FQN | `anatomist context com.example.shop.service.OrderService` | Returns node + contained members + class-level annotations. |
| See implementations of an interface | `anatomist implementors-of OrderRepository` | Both FQN and short label work. |

### Understand structure

| You want… | Command |
|---|---|
| All fields + method signatures of a class | `anatomist context <fqn>` |
| Same + 1 layer of outgoing calls per method | `anatomist context <fqn> --with-callees` |
| Extends chain + direct implements | `anatomist hierarchy <fqn>` |
| Class-level outgoing CALLS+REFERENCES | `anatomist deps-of <fqn>` |
| Class-level incoming CALLS+REFERENCES (who uses me) | `anatomist used-by <fqn>` |
| Aggregated package → package edges | `anatomist package-deps` |

### Trace call chains

| You want… | Command |
|---|---|
| What does this method call? | `anatomist callees-of <method-fqn>` |
| Recursive (multi-hop) callees | `anatomist callees-of <method-fqn> --depth 5` |
| Who calls this method? (impact analysis) | `anatomist callers-of <method-fqn>` |
| Shortest call path between two methods | `anatomist call-path <from> <to> --depth 5` |

### Field-level impact

| You want… | Command |
|---|---|
| Who reads `order.status` | `anatomist field-readers Order.status` |
| Who writes `order.status` | `anatomist field-writers Order.status` |

---

## FQN syntax cheat-sheet

The resolver accepts increasingly loose forms for the same target:

| Form | Example | When it matches |
|---|---|---|
| Full FQN | `com.example.shop.service.OrderService` | Exact `nodes.qualified_name` |
| Class + method | `com.example.shop.service.OrderService#createOrder` | All overloads of that method |
| Class + method + signature | `com.example.shop.service.OrderService#createOrder(com.example.shop.domain.dto.CreateOrderRequest)` | Single exact overload (id match) |
| Shorthand | `OrderService.createOrder` | Last `.` splits class/method; class matched by short label |
| Bare name | `OrderService` (type) / `createOrder` (method) | By `nodes.label` |

**Method param FQNs use *erased* signatures** — `java.util.List`, not
`java.util.List<String>`. Built from `ResolvedType.erasure().describe()`.

---

## Composite workflows (mirror DESIGN.md §端到端流程)

Agent typically composes 2–4 commands. Common patterns:

### "What does the X flow look like?"  (E3 业务流程)

```
1. search @RestController --by-annotation        # find controllers
2. context <ControllerFqn>                       # find the matching action
3. callees-of <Controller#action> --depth 5      # the full call chain
4. (optional) context <leaf-method> for any node # read its annotations / signature
```

### "Who depends on Y?"  (F1/F3 impact)

```
1. used-by <YFqn>                                # callers + reference sites
2. callers-of <YFqn>#<method> --depth 3          # if Y is a method
3. (optional) hierarchy <YFqn>                   # subclasses also "depend"
```

### "Identify the core domain model"  (E1 — DESIGN.md §场景 4)

```
1. search @Entity --by-annotation                # entity classes
2. context <EachEntity> for each                 # fields + annotations
3. (optional) hierarchy <EachEntity>             # inheritance shape
```

### "Bounded contexts"  (E2)

```
1. search @Service --by-annotation
2. deps-of <EachService>                         # cross-service edges
3. package-deps                                  # aggregated module shape
```

---

## Important contracts

- **JSON shape is locked** by `tests/scenarios/*/expected.json` (golden file
  IT). Field names, key ordering, snake_case — stable across runs.
- **Recursive `--depth` is capped at 20** (`QueryService.MAX_DEPTH`); pass any
  larger value and it silently clamps.
- **External edges** (calls/references into non-project code like JDK or
  Spring) carry `"is_external": true` and `"external_target_fqn": "..."` but
  no `target` id. Don't try to follow them with recursive queries.
- **`--no-classpath` mode** suppresses Spring annotation resolution — only
  `@Override` / `@Deprecated` / `@SuppressWarnings` survive. If the user
  cares about `@RestController` / `@Service` etc., make sure the index was
  built *with* classpath (default).
- **Anonymous class methods** are emitted under
  `<enclosingMethod>$anon@L<line>` — their callers/callees show up in
  query results under that id form, not under any opaque JavaParser
  generated name.

---

## When anatomist *cannot* answer

Fall back to reading source when:

- The question is about **string literals / error messages / config keys** —
  these aren't indexed.
- The question is about **runtime dispatch through reflection / SPI** — only
  static call edges live in the index.
- The question is about a **specific commit's diff** — anatomist sees the
  current snapshot only. Use `git log` / `git diff` instead.
- The user wants **prose-level documentation** of behavior — anatomist
  surfaces structure, not intent. Combine `context` output with reading the
  source file the result points at (`source_file` + `source_location` fields
  on every result).

In all these cases, prefer to take *one* anatomist query first to locate the
relevant file(s), then read source — much cheaper than grepping cold.

---

## Writing architecture docs from code

When the user wants to **understand a service or package and capture the
business intent back into the index**, use the closed loop:

```
enrich → reason (you, the Agent) → annotate → enrich (verify)
```

### Workflow

1. **Aggregate** — `anatomist enrich --node <fqn>` (or `--package <pkg>`)
   pulls members, annotations, semantic annotations already on record,
   one-hop callees, optional related docs (`--with-docs`), and suggested
   follow-up queries — all in one markdown blob (≤ 200 lines).
   Use `--format json` when you want structured input.
2. **Investigate further** — follow the suggested queries (`callers-of`,
   `hierarchy`, `used-by`, ...) when the enrich view leaves a question
   open. Don't guess at intent from names alone.
3. **Reason** — synthesize a business label / category / description from
   the aggregated structure + javadoc + related docs. Categories that
   appear in the index today: `BUSINESS_SERVICE`, `BUSINESS_REPOSITORY`,
   `BUSINESS_CONTROLLER`, `BUSINESS_ENTITY`, `BUSINESS_VALUE_OBJECT`.
   Pick the closest match or invent a new one consistently across runs.
4. **Annotate** — write the conclusion back so the next session benefits:

   ```
   anatomist annotate <node-id> \
     --label "订单服务" \
     --category BUSINESS_SERVICE \
     --description "Coordinates checkout: validates request, prices items, persists order, kicks off async fulfilment." \
     --source LLM --confidence MEDIUM
   ```

   Repeated runs on the same `(node-id, category, source)` upsert in place —
   safe to re-run after refining the wording.
5. **Batch sync** — when working over a package, prepare a JSON file
   `annotations.json` (snake_case fields matching `SemanticAnnotation`) and
   pass `--from-json annotations.json` to write all at once.
6. **Verify** — re-run `anatomist enrich --node <fqn>` and confirm the new
   semantic annotation appears in the *Semantic Annotations* table.

### Source field rules

- `LLM` — the conclusion came from your reasoning. Default.
- `DOC` — the conclusion was lifted verbatim from project docs.
- `CONVENTION` / `JAVADOC` — **CLI-forbidden**; these are reserved for
  `SemanticPostProcessor` (Spring stereotype scan) and the future javadoc
  extractor. The CLI exits 1 if you try.

### When to pair with other commands

- Before annotating a controller, run `anatomist callers-of <controller>`
  to confirm it's an HTTP entry (no internal callers) vs. an internal
  facade.
- Before annotating a repository, run `anatomist implementors-of <iface>`
  to see if it has multiple impls — that may warrant separate annotations
  per impl.
- After annotating a package, run `anatomist enrich --package <pkg>` to
  read back the aggregated semantic table as a sanity check.
