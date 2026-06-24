# anatomist — Agent skill

Use this skill when the user asks about Java code structure, dependencies,
call chains, impact, framework wiring, or code facts that are cheaper to query
from an index than to rediscover by reading files.

Do not treat this skill as a workflow script. It gives boundary rules and
few-shot examples. Always discover the installed CLI contract with `--help`
before choosing commands for the current task.

---

## Discovery first

Start from facts about the local tool, not memory:

```bash
anatomist doctor --format json
anatomist <command> --help
```

Use `doctor` for available commands, schema version, capabilities, and whether
an index exists. Use command help for flags and output behavior. This document
is intentionally not a complete command reference.

If no usable index exists, build one:

```bash
anatomist index <project-root> --format json
```

Index notes:

| Case | Hint |
|---|---|
| Multi-module Maven | Default source discovery should find module `src/main/java` roots. |
| Spring annotations matter | Avoid `--no-classpath`; let classpath detection resolve external annotations. |
| Spring XML matters | Add `--spring-xml` so XML bean wiring becomes `WIRES` facts. |
| Re-indexing | Prefer `--incremental` when updating an existing index. |
| DB path | Default is `~/.anatomist/<repo-name>/index.db`; all query commands accept `--index`. |

---

## Boundary

anatomist returns code facts. It does not decide business meaning.

| Item | Treat as |
|---|---|
| `ROUTE`, `HANDLES`, `CALLS`, `REFERENCES`, `INJECTS`, `WIRES` | Code facts |
| Names like `*Controller`, `*Service`, `*Job`, `*Listener` | User/Agent search rules |
| Annotation searches | Code facts under an explicit annotation rule |
| "business entry", "domain boundary", "core workflow", "smell" | Agent hypothesis, not anatomist output |
| `survey-baseline` | Aggregate structural baseline only |

Never say anatomist "found the business entry/domain". Say it found technical
signals and state the inference separately.

---

## Evidence levels

Use these labels in answers that mix code facts with interpretation:

| Level | Meaning | Example phrasing |
|---|---|---|
| Fact | Direct index result | "`route:POST /orders` HANDLES `OrderController#create`." |
| Strong signal | Multiple facts point the same way | "This route calls order validation and persistence methods." |
| Weak signal | Naming or annotation pattern only | "`*Job` matched this class; that is only a naming signal." |
| Hypothesis | Business/architecture interpretation | "Likely order submission entry, needs confirmation." |

When giving business or architecture analysis, include file/line, node id, or
relation evidence wherever possible.

---

## Few-shot patterns

These are examples, not fixed flows. Adapt them after checking `--help`.

### Technical entry facts

User asks: "Where can requests enter this app?"

```bash
anatomist search --name '*' --kind ROUTE --index <db>
anatomist context <controller-or-handler-owner> --index <db>
```

Use the returned `ROUTE` nodes and `HANDLES` framework facts as evidence. If
you infer a business entry from them, label that inference as a hypothesis.

### Known method behavior

User gives a method/class and asks what it does.

```bash
anatomist context <type-or-method> --index <db>
anatomist callees-of <method> --depth <n> --index <db>
```

If the chain stops at a template/executor or callback container, check help for
callback traversal and consider `--through-callbacks`.

### Impact analysis

User asks what breaks if a type/method changes.

```bash
anatomist used-by <type> --index <db>
anatomist callers-of <method> --depth <n> --index <db>
anatomist field-access <field> --index <db>
```

Separate "direct users" from "recursive callers". If results are paged, continue
with `next_queries` rather than assuming the first page is complete.

### Architecture or smell evidence

User asks about layering, coupling, or suspicious dependencies.

```bash
anatomist overview --deps-only --index <db>
anatomist deps-of <type-or-package-anchor> --index <db>
anatomist used-by <type-or-package-anchor> --index <db>
```

Do not label a dependency as a smell unless you state the rule being applied,
for example "controller package depends on repository package" or "many packages
write the same field".

### User-defined semantic search

User supplies a domain term, naming convention, or annotation.

```bash
anatomist search <term> --index <db>
anatomist search --name '<glob>' --kind <KIND> --index <db>
anatomist search <Annotation> --by-annotation --index <db>
```

Make the rule explicit: "Under the user-provided `*Settlement*` rule, these are
matches." Do not upgrade matches into business facts.

### Code slice for one chain

User wants a readable slice of a path.

Use anatomist to locate nodes and edges. Read source only for the few files
identified by the index when prose behavior or code snippets are needed.

---

## Anti-patterns

| Do not | Do instead |
|---|---|
| "anatomist says this is the business entry" | "anatomist found route/handler facts; business-entry is a hypothesis." |
| Use a fixed command sequence for every task | Inspect `doctor` and `<command> --help`, then choose commands. |
| Treat `search <term>` total as exact simple-name count | Check `stats.label_matches` or use `search --name '<glob>'`. |
| Ignore pagination | Use `stats.truncated`, `next_offset`, and `next_queries`. |
| Trust naming patterns as semantics | Mark naming matches as weak signals. |
| Stop at `template.execute` / lambda wrapper | Consider callback traversal if supported by help. |
| Use `survey-baseline` for candidates | Use it only for aggregate structural baseline. |

---

## Contracts and gotchas

| Topic | Note |
|---|---|
| JSON shape | Query output generally has `query`, `results`, `stats`, and often `budget`. |
| Depth | Recursive call depth is capped; check command help for current limit. |
| External edges | External targets may have FQN text but no internal node id. |
| `--no-classpath` | Suppresses many external annotation resolutions. |
| Locking | Query commands use read locks; index commands use write locks. |
| Incremental | Uses file hashing and dependency realignment; large changes may degrade to full. |
| Runtime behavior | Reflection, profiles, AOP, generated code, and config-driven dispatch may be incomplete in static facts. |

Fallback to reading source when the question depends on literals, config files,
runtime conditions, prose documentation, or exact code bodies. Use anatomist
first to narrow the file set.
