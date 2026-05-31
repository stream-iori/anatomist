# anatomist

A JavaParser + SymbolSolver-based Java code intelligence tool. Indexes a Java
project into SQLite once, then answers structural / semantic questions
(callers, callees, hierarchy, dependencies, impact) over plain SQL — no
re-parsing per query. Designed to be called by an Agent LLM (Claude Code,
Cursor, ...) as a tool.

```
┌────────────────────┐    seconds–minutes     ┌────────────────────┐
│  Java source tree  │ ─────────────────────▶ │   SQLite snapshot  │
└────────────────────┘   index (one-shot)     │  nodes + edges +   │
                                              │  FTS5 + semantic   │
                                              └─────────┬──────────┘
                                                        │ milliseconds
                                                        ▼
                                       ┌───────────────────────────────┐
                                       │  CLI / JSON responses for     │
                                       │  search / context / callers / │
                                       │  callees / hierarchy / …      │
                                       └───────────────────────────────┘
```

anatomist **never embeds an LLM**. All semantic reasoning is delegated to the
calling Agent. See [DESIGN.md](DESIGN.md) for the rationale.

---

## 5-minute hands-on

Requires JDK 21+ and Maven.

```bash
# 1. Build the fat jar
mvn -q package
# → target/anatomist.jar

# 2. Index a project (use the bundled fixture as an example)
java -jar target/anatomist.jar index fixtures/mini-spring-shop \
    --project-source fixtures/mini-spring-shop/api/src/main/java:fixtures/mini-spring-shop/domain/src/main/java:fixtures/mini-spring-shop/service/src/main/java \
    --no-classpath \
    --output /tmp/shop.db

# 3. Query: who does OrderService.createOrder call, 3 hops deep?
java -jar target/anatomist.jar callees-of \
    com.example.shop.service.OrderService#createOrder \
    --depth 3 --index /tmp/shop.db
```

Output is JSON ready for Agent consumption:

```json
{
  "query": "callees-of com.example.shop.service.OrderService#createOrder --depth 3",
  "results": [
    {
      "source": "com.example.shop.service.OrderService#createOrder(...)",
      "source_label": "createOrder",
      "target": "com.example.shop.service.OrderValidator#validate(...)",
      "target_label": "validate",
      "relation": "CALLS",
      "call_kind": "INSTANCE",
      "depth": 1,
      "source_location": "L33"
    },
    ...
  ],
  "stats": { "total": 6, "max_depth": 2 }
}
```

---

## Command catalog

Two phases, two command families.

**Index-time** (run once, slow):

| Command | What it does |
|---|---|
| `index <path>` | Parse + extract + write SQLite |
| `index --incremental` | Only re-parse files whose hash changed |
| `index-docs <path>` | Index markdown/javadoc into `documents` FTS5 table |
| `watch <path>` | Watch filesystem + auto-incremental |

**Query-time** (millisecond, JSON):

| Command | Question it answers |
|---|---|
| `search <term>` / `search @Anno --by-annotation` | "Where is …?" |
| `context <fqn>` / `... --with-callees` | "What's in this class/method?" |
| `hierarchy <type>` | "Extends + implements" |
| `implementors-of <iface>` | "Who implements this?" |
| `callers-of <method> [--depth N]` | Impact analysis |
| `callees-of <method> [--depth N]` | Call chain |
| `call-path <from> <to> [--depth N]` | Shortest CALLS chain between two methods |
| `deps-of <type>` / `used-by <type>` | Class-level dependency graph |
| `field-readers <field>` / `field-writers <field>` | Field-level impact |
| `package-deps` | Package → package edge aggregation |

Full subcommand reference + flags: `java -jar target/anatomist.jar --help` or
[`docs/scenario-2-query.md`](docs/scenario-2-query.md).

---

## Use it from an Agent

Drop [`anatomist-skill.md`](anatomist-skill.md) into your Agent's skills /
context. It's a one-page playbook that tells the Agent **when** to reach for
anatomist, **which** command answers which kind of question, and how to
compose 2–4 commands for higher-level workflows ("trace this request",
"find domain models", "assess impact"). Tested with Claude Code as the
caller.

---

## What it can do, what it can't

**Does**

- Resolve every CALLS edge at SymbolSolver level (not naive label match) —
  knows `INSTANCE` vs `STATIC` vs `CONSTRUCTOR` vs `SUPER` vs `INTERFACE`.
- Distinguish overloaded methods by erased FQN signature
  (`#foo(java.util.List)` ≠ `#foo(java.lang.String)`).
- Stable IDs for lambdas, method references, and anonymous classes
  (`$lambda@L<line>C<col>`, `$methodref@L<line>C<col>`, `$anon@L<line>`).
- Incremental re-index — only the changed file's nodes/edges are recomputed.
- Index real-world projects: Apache Commons Lang 3.12.0 (~70k LOC) in ~5s.

**Doesn't (yet)**

- Export to Mermaid / dot / GraphML (DESIGN.md §场景 5 not implemented).
- Index reflection / SPI / runtime dispatch — only static structure.
- Vector / semantic similarity search — relies on FTS5 + Agent reasoning.
- Cross-language (Kotlin, Groovy, Scala) — Java only.

---

## For contributors

Open these in order:

1. [`DESIGN.md`](DESIGN.md) — full architecture rationale, schema design,
   relation-set derivation from scenarios.
2. [`CLAUDE.md`](CLAUDE.md) — operating guide: command cheatsheet, package
   layout, critical invariants, common pitfalls.
3. [`docs/`](docs) — per-scenario specs (1: index, 2: query, 3: watch,
   4: skills, 5: export) + [`testing-strategy.md`](docs/testing-strategy.md).
4. [`.diorama/knowledge/`](.diorama/knowledge) — accumulated practical
   experience (gotchas with JavaParser, FTS5 tokenizer, etc.).

### Build & test

| Task | Command |
|---|---|
| Compile | `mvn -q compile` |
| Unit tests (default; excludes `*IT`) | `mvn test` |
| One IT class | `mvn test -Dtest=QueryServiceIT` |
| Refresh golden files after intended change | `mvn test -Dtest=GoldenFileIT -Dgolden.update=true` |
| Scale smoke (needs submodule, see [`fixtures/external/README.md`](fixtures/external/README.md)) | `mvn test -Dtest=CommonsLangSmokeIT` |
| Fat jar | `mvn -q package` → `target/anatomist.jar` |

Build target is `release=21`; runtime JDK 21+ works.

### Dependency budget

**4 direct production dependencies**, on purpose:

- `javaparser-symbol-solver-core` (transitively pulls `javassist` for jar
  reading) — AST + symbol resolution
- `sqlite-jdbc` — storage + FTS5
- `picocli` — CLI
- `jackson-databind` — JSON I/O

No Spring, no Guava, no Lombok. New dependencies require justification in a
new task's `proposal.md`.

---

## License

TBD.
