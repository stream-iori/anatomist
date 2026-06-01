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

## Where the index database lives

Per-project, under your home directory:

```
~/.anatomist/<project-basename>/index.db
```

So `anatomist index /work/order-service` writes to
`~/.anatomist/order-service/index.db`, and `cd /work/order-service ; anatomist
search Foo` reads from the same path. The DB is a single SQLite file
(~400 KB for a small project, ~10 MB for commons-lang3 scale).

Overrides:

| When you want… | Pass |
|---|---|
| A specific DB location | `--output /tmp/x.db` (index) / `--index /tmp/x.db` (query) |
| Sandboxed storage root (e.g. tests) | `export ANATOMIST_HOME=/tmp/anatomist-test` |
| Keep the DB inside the project (old default) | Create `<project>/.anatomist/index.db` manually before first index — anatomist detects the legacy location and keeps writing there |

Index DB lifecycle is independent of source: deleting the project does
**not** delete its index unless you also remove the directory under
`~/.anatomist/`.

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

The repo ships a [`justfile`](justfile) — install [`just`](https://github.com/casey/just)
once (`brew install just` / `cargo install just`) and run `just` in the
project root for the full list. The most common ones:

| Task | `just` recipe | Underlying command |
|---|---|---|
| Compile | `just compile` | `mvn -q compile` |
| Unit tests | `just test` | `mvn test` |
| Full regression (unit + 16 IT classes) | `just test-all` | `mvn clean test` + targeted IT run |
| Run one test | `just test-one QueryServiceIT` | `mvn test -Dtest=…` |
| Refresh golden files | `just golden-update` | `mvn test -Dtest=GoldenFileIT -Dgolden.update=true` |
| Build the JVM fat jar | `just jar` | `mvn -q -DskipTests package` |
| Build host-arch native binary | `just native` | `mvn -Pnative -DskipTests package` |
| Cross-build Linux amd64 binary | `just native-linux-amd64` | `./docker/build-linux-amd64.sh` |
| Install to `~/.local/bin/anatomist` | `just install` | builds + `./docker/install-local.sh` |
| Smoke test installed binary | `just smoke-installed` | indexes mini-spring-shop, queries it |
| Startup-latency comparison | `just bench-startup` | 3 runs each, native vs JVM |
| Uninstall (restores `.bak` if present) | `just uninstall` | |

Build target is `release=21`; runtime JDK 21+ works.

### Native image (GraalVM)

A single self-contained binary covering every CLI subcommand
(`index` / `query` / `watch` / `enrich` / `annotate` / ...). See
[`docs/scenario-6-native-image.md`](docs/scenario-6-native-image.md) for the
architectural rationale.

**Prereq**: GraalVM JDK 21+ with `native-image` on `$PATH`
(`sdk install java 25.0.3-graal` via SDKMAN, or any equivalent).

```bash
# 1. Build (~1 minute on M-series, ~3 min on x86)
mvn -Pnative -DskipTests package
# → target/anatomist  (~44 MB, single Mach-O / ELF binary)

# 2. (macOS only) strip provenance + re-stamp the adhoc signature so
#    Gatekeeper/amfid lets the binary run. Linux & Windows can skip this.
xattr -cr target/anatomist
codesign --force --sign - target/anatomist

# 3. Smoke: index the bundled fixture then run a few queries
./target/anatomist index fixtures/mini-spring-shop \
    --project-source fixtures/mini-spring-shop/api/src/main/java:fixtures/mini-spring-shop/domain/src/main/java:fixtures/mini-spring-shop/service/src/main/java \
    --no-classpath \
    --output /tmp/smoke.db
./target/anatomist search OrderService --index /tmp/smoke.db
./target/anatomist callees-of com.example.shop.service.OrderService#createOrder \
    --depth 2 --index /tmp/smoke.db
./target/anatomist context com.example.shop.service.OrderService --index /tmp/smoke.db
./target/anatomist hierarchy com.example.shop.service.OrderService --index /tmp/smoke.db
```

Verified end-to-end on GraalVM 25.0.3 / macOS 14 arm64:

| | native | JVM jar (cold) |
|---|---:|---:|
| `index mini-spring-shop` | **234 ms** | 879 ms |
| `search OrderService` (warm) | **~160 ms** | ~410 ms |
| Binary size | 43.6 MB | 256 KB jar + ~30 MB classpath |

#### macOS Gatekeeper note

On macOS 14+ (Sonoma / Sequoia) the first execution of a freshly built
native binary may be killed silently (exit 137, empty output) — amfid
rejects ad-hoc-signed local binaries with
`Code=-423 "The file is adhoc signed or signed by an unknown certificate chain"`.

Most cases are fixed by the `xattr -cr` + `codesign` round-trip shown
above. If amfid still rejects after that, open
**System Settings → Privacy & Security**, scroll to the bottom, click
**"Allow Anyway"** once after the first kill. Linux and Windows builds
do not need this dance.

#### Regenerating the reachability metadata

The bundled
[`reachability-metadata.json`](src/main/resources/META-INF/native-image/com.antcodes/anatomist/reachability-metadata.json)
covers JavaParser's MetaModel reflective field accesses (`variables` on
`FieldDeclaration` / `VariableDeclarationExpr` and friends). If a new
extractor surfaces additional reflection, regenerate with the GraalVM
tracing agent:

```bash
mvn -q compile
CP=$(mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt && cat /tmp/cp.txt)
mkdir -p target/native-agent-config
java -agentlib:native-image-agent=config-output-dir=target/native-agent-config \
    -cp "target/classes:$CP" com.anatomist.cli.AnatomistCli \
    index fixtures/mini-spring-shop \
    --project-source fixtures/mini-spring-shop/api/src/main/java:fixtures/mini-spring-shop/domain/src/main/java:fixtures/mini-spring-shop/service/src/main/java \
    --no-classpath --output /tmp/agent-trace.db
# Then merge into the shipped config:
cp target/native-agent-config/reachability-metadata.json \
   src/main/resources/META-INF/native-image/com.antcodes/anatomist/
mvn -Pnative -DskipTests package
```

#### Cross-build for Linux amd64 (from any host)

The macOS / arm64 `target/anatomist` only runs on macOS / arm64. To
produce a binary that runs on **Linux x86_64 hosts with glibc ≥ 2.17**
(CentOS 7+, RHEL 7+, Ubuntu 16.04+, Debian 9+), use the bundled
Dockerfile that bakes a CentOS 7.9 + GraalVM JDK 25 + Maven 3.9 build
toolchain:

```bash
./docker/build-linux-amd64.sh
# 1st run:  builds the docker image (~10 min, mostly yum install + GraalVM download)
# 2nd run:  re-uses the image; native compile finishes in ~6 min
# Output:   target/anatomist  (Linux ELF amd64, ~44 MB)
```

(See [`docker/Dockerfile.amd64-build`](docker/Dockerfile.amd64-build) for
the exact toolchain. The user-suggested `centos:6.10` base cannot host
GraalVM 21+ — its glibc 2.12 predates the JDK's 2.17 ABI floor — so we
pin to `centos:centos7.9.2009` from the same SWR mirror, which has
glibc 2.17.)

### Dependency budget

**3 direct production dependencies**, on purpose:

- `javaparser-symbol-solver-core` — AST + symbol resolution
- `sqlite-jdbc` — storage + FTS5 (ships GraalVM `META-INF/native-image/`
  feature, so no extra config to make it work in a native binary)
- `picocli` — CLI (with `picocli-codegen` as a compile-time annotation
  processor only — generates the reflect-config that native-image needs)

Plus `asm` as a build-time + runtime helper for our self-written
`AsmTypeSolver` (replaces `javassist` for jar bytecode reading in a
native-image-friendly way; see scenario 6 §决策 1) and
`JdkTypeCatalogBuilder`. JSON I/O is hand-written
([`com.anatomist.json`](src/main/java/com/anatomist/json/Json.java)) — no
jackson at runtime.

No Spring, no Guava (apart from javaparser's transitive dep), no Lombok.
New dependencies require justification in a new task's `proposal.md`.

---

## License

TBD.
