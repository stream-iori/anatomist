# anatomist — Agent skill

**When to use**: the user asks a *structural* question about a Java codebase —
"who calls X?", "what does Y depend on?", "show me the class hierarchy",
"how does the X flow work?". If you're tempted to grep + read 10 files to
answer, `anatomist` likely answers it in one query.

**Not for**: pure-text questions ("how do I write a unit test for foo?"),
runtime behavior, build/CI issues, or non-Java code.

---

## Setup check (do once per session)

1. Discover the local contract:
   ```bash
   anatomist doctor --format json
   ```
   Use `commands`, `schema_version`, `capabilities`, and `index_exists`
   instead of guessing from text output.

2. Is there an index at `~/.anatomist/<repo-name>/index.db`?
   - **Yes** → skip to "Query playbook".
   - **No** → build the index:
     ```bash
     anatomist index <project-root> --format json
     ```

3. Index tips:
   - Multi-module Maven projects work out of the box — every module's
     `src/main/java` is auto-discovered (no need for `--project-source`).
   - On a Spring-style project where annotations matter, **do not** add
     `--no-classpath` — let `ClasspathDetector` pull
     `mvn dependency:build-classpath`. If classpath detection exits non-zero,
     run `mvn install -DskipTests` first so sibling-module deps resolve.
   - If the project wires beans via Spring **XML** (`<beans>` configs), add
     `--spring-xml` so that XML-only injection shows up as `WIRES` edges.
   - For subsequent updates: `anatomist index <project> --incremental` is
     cheap (only re-parses changed files + their dependents).

4. Index db default: `~/.anatomist/<repo-name>/index.db`. All query commands
   accept `--index <path>` to override.

---

## Query playbook

Every command emits JSON to stdout in the shape:
```json
{ "query": "<echo of cmd line>", "results": [...], "stats": {...}, "budget": {...} }
```

Mutation commands that Agents should call in JSON mode:

| Need | Command |
|---|---|
| Check CLI/index/schema/capabilities | `doctor --format json [--index <db>]` |
| Build an index and verify counts/errors | `index <project-root> --format json [--output <db>]` |
| First bounded map for a large repo | `survey-baseline <project-root> --format json --index <db>` |

`survey-baseline` also reports `candidate_sources` and `warnings`; inspect them
before treating missing candidates as a business fact.

### Find things

| You want… | Command |
|---|---|
| Locate a type/method by name | `search OrderService` |
| Page a large search | `search Order --limit 50 --offset 50` |
| Restrict by kind | `search OrderService --kind METHOD` |
| Count/enumerate a naming pattern precisely (e.g. all `*Facade`) | `search --name '*Facade' --kind INTERFACE` |
| How many match? (true count, ignores `--limit`) | `search --name '*EventPlugin' --kind CLASS --count` |
| Find by annotation | `search @RestController --by-annotation` |
| Jump to a type by FQN | `context com.example.shop.service.OrderService` |
| See implementations of an interface | `implementors-of OrderRepository` |
| Include impls reached through abstract bases | `implementors-of OrderRepository --recursive` |
| Just count implementors | `implementors-of OrderRepository --count` |

### Understand structure

| You want… | Command |
|---|---|
| Fields + method signatures of a class | `context <fqn>` |
| Page members of a large class | `context <fqn> --members-limit 50 --members-offset 50` |
| Same + 1-hop outgoing calls per method | `context <fqn> --with-callees` |
| Same + N-hop calls | `context <fqn> --with-callees=3` |
| Enriched view (semantic annotations + docs + suggested queries) | `context <fqn> --enrich` |
| Enriched package-level view | `context --package <pkg> --enrich` |
| Extends chain + direct implements | `hierarchy <fqn>` |
| Outgoing CALLS+REFERENCES from a class | `deps-of <fqn>` |
| Incoming CALLS+REFERENCES to a class (who uses me) | `used-by <fqn>` |
| Package → package dependency skeleton | `overview --deps-only` |
| Full project summary (node counts, edge counts, per-package stats) | `overview` |

### Trace call chains

| You want… | Command |
|---|---|
| What does this method call? | `callees-of <method-fqn>` |
| Recursive (multi-hop) callees | `callees-of <method-fqn> --depth 5` |
| Page/filter a wide call graph | `callees-of <method-fqn> --depth 3 --limit 50 --offset 50 --filter Order` |
| Chain stops at a template/executor (e.g. only `→ execute`)? Follow into the callback | `callees-of <method-fqn> --depth 5 --through-callbacks` |
| Calls only in loops | `callees-of <method-fqn> --in-loop` |
| Calls only in branches | `callees-of <method-fqn> --in-branch` |
| Group by package/class blocks | `callees-of <method-fqn> --depth 3 --blocks package` |
| Who calls this method? (impact analysis) | `callers-of <method-fqn>` |
| Callers show as `…$anon@…#process()` noise? Attribute to the real method | `callers-of <method-fqn> --through-callbacks` |
| Shortest call path between two methods | `call-path <from> <to> --depth 5` |

### Field-level impact

| You want… | Command |
|---|---|
| Who reads a field | `field-access Order#status --mode reads` |
| Who writes a field | `field-access Order#status --mode writes` |
| All access (reads + writes) | `field-access Order#status` |

### Pagination

`deps-of`, `used-by`, `field-access`, `overview --deps-only` support:

```
--limit 20        Max results per page (default 50)
--offset 0        Skip N results
--filter <term>   Substring match on target label/FQN
```

Paged JSON uses `stats.truncated` and `stats.next_offset`; continue with
`next_queries` when present. `search`, `callees-of`, and `callers-of` report
`stats.total`, `stats.limit`, `stats.offset`, `stats.truncated`, and top-level
`budget` even on the first page.

---

## FQN syntax cheat-sheet

The resolver accepts increasingly loose forms:

| Form | Example | Matches |
|---|---|---|
| Full FQN | `com.example.shop.service.OrderService` | Exact `nodes.qualified_name` |
| Class#method | `OrderService#createOrder` | All overloads |
| Class#method(params) | `OrderService#createOrder(CreateOrderRequest)` | Single exact overload |
| Dot-separated | `OrderService.createOrder` | Last `.` splits class/method |
| Bare name | `OrderService` or `createOrder` | By `nodes.label` |

**Method signatures use erased types** — `java.util.List`, not `java.util.List<String>`.

---

## Architecture judgment

anatomist does not infer architecture roles. It indexes code facts and direct
semantic evidence; architecture judgment belongs to the Agent.

Use these facts as inputs:

| Evidence | Command |
|---|---|
| Entry-like classes | `search @RestController --by-annotation` / `search --name '*Controller' --kind CLASS` |
| Persistence-like classes | `search @Entity --by-annotation` / `search @Repository --by-annotation` |
| Package boundaries | `overview --deps-only` |
| Flow shape | `callees-of <method-fqn> --depth N --blocks package` |
| Type structure | `context <fqn> --enrich` |

---

## Composite workflows

Agent typically composes 2–4 commands. Common patterns:

### "What does the X flow look like?" (trace a business flow)

```
1. search @RestController --by-annotation         # find controllers
2. context <ControllerFqn> --with-callees         # find the matching action + 1-hop
3. callees-of <Controller#action> --depth 5       # the full call chain
4. context <leaf-node> --enrich                   # read annotations + docs for any node
```

### "Who depends on Y?" (impact analysis)

```
1. used-by <YFqn>                                 # callers + reference sites
2. callers-of <YFqn>#<method> --depth 3           # if Y is a method
3. hierarchy <YFqn>                               # subclasses also "depend"
```

### "Identify the core domain model"

```
1. search @Entity --by-annotation                 # entity classes
2. context <EachEntity> --enrich                  # fields + annotations + semantics
3. hierarchy <EachEntity>                         # inheritance shape
```

### "Architecture overview"

```
1. survey-baseline <repo> --format json           # bounded map first
2. overview --deps-only --limit 50                # package skeleton
3. search @Entity --by-annotation --limit 50      # persistence/domain candidates
4. search @RestController --by-annotation         # entry candidates
5. callees-of <entry#method> --depth 5 --blocks package
```

### "Bounded contexts / package dependencies"

```
1. overview --deps-only                           # package → package edges
2. deps-of <ServiceFqn>                           # cross-service edges
3. overview --depth 2                             # collapse to top-2 package levels
```

---

## Semantic annotation loop

When the user wants to **capture business intent back into the index**:

```
enrich → reason (Agent) → annotate → enrich (verify)
```

1. **Aggregate** — `context <fqn> --enrich` (or `--package <pkg> --enrich`)
   pulls members, annotations, semantic annotations, one-hop callees, related
   docs, and suggested follow-up queries. Default format: markdown.
   Use `--format json` for structured input.

2. **Investigate** — follow suggested queries when the enrich view leaves gaps.

3. **Annotate** — write the conclusion back:
   ```
   anatomist annotate <node-id> \
     --label "订单服务" \
     --category BUSINESS_SERVICE \
     --description "Coordinates checkout: validates, prices, persists, kicks off fulfilment." \
     --source LLM --confidence MEDIUM
   ```

4. **Batch** — prepare `annotations.json` and use `--from-json annotations.json`.

5. **Verify** — re-run `context <fqn> --enrich` to confirm.

### Source field rules

- `LLM` — Agent reasoning. Default.
- `DOC` — verbatim from project docs.
- `CONVENTION` / `JAVADOC` — **CLI-forbidden**; reserved for auto-processors.

---

## Important contracts

- **JSON shape is locked** by `tests/scenarios/*/expected.json` (golden files).
  Field names, key ordering, snake_case — stable across runs.
- **Recursive `--depth` capped at 20** (`QueryService.MAX_DEPTH`); larger
  values silently clamp.
- **External edges** carry `"is_external": true` and `"external_target_fqn"`
  but no `target` id. Don't follow them with recursive queries.
- **`--no-classpath` mode** suppresses annotation resolution for external
  annotations (Spring, JUnit, etc.). Only JDK annotations survive.
- **Concurrent access**: query commands acquire a shared read lock; index
  commands acquire an exclusive write lock. Queries block until an in-progress
  index completes — safe for parallel Agent invocations.
- **Incremental index** uses SHA-256 file hashing + transitive dependency
  closure. Files above `--max-realign-files` (default 200) degrade to full.
- **`search <term>` is FTS — it matches the package path too.** `search Facade`
  matches every node under a `.facade.` package, not just classes named `*Facade`,
  so its total is misleading. Check `stats.label_matches` (how many hits actually
  match the simple name), or use `search --name '<glob>'` for an exact name count.
- **Call chains break at template/callback boundaries.** Logic passed as an
  anonymous class or lambda (e.g. `template.execute(callback)`, `TransactionTemplate`,
  stream lambdas) lives in a `…$anon@…#process()` / `…$lambda@…` body whose CALLS
  are attributed to that body, not the enclosing method. A `callees-of` that returns
  only `→ execute` is the tell — re-run with `--through-callbacks` to follow in.
  `via=<body-id>` on the result marks edges that came from inside a callback.

---

## When anatomist cannot answer

Fall back to reading source when:

- The question is about **string literals / error messages / config keys** —
  not indexed.
- The question is about **runtime dispatch through reflection / SPI** — only
  static call edges live in the index.
- The question is about a **specific commit's diff** — anatomist sees the
  current snapshot only. Use `git log` / `git diff`.
- The user wants **prose-level documentation** of behavior — anatomist
  surfaces structure, not intent. Combine `context` output with reading the
  source file pointed to by `source_file` + `source_location` fields.

In all these cases, take *one* anatomist query first to locate the relevant
file(s), then read source — much cheaper than grepping cold.
