---
name: anatomist
description: Use when analyzing Java code structure with anatomist from an IntelliJ IDEA-style workflow: indexing Java projects/docs, finding entries, inspecting local context, tracing forward callees/call paths, reverse-tracing callers/repositories/DAOs, checking type hierarchy/subtypes/implementors, inspecting field composition/access, mapping package architecture, inspecting Spring wiring/XML, watching incremental changes, and producing source-backed static evidence for Agent answers.
---

# anatomist

Use anatomist like an indexed, Agent-friendly companion to IDEA actions:
Find Usages, Call Hierarchy, Type Hierarchy, Go to Implementation, dependency
analysis, and source-backed navigation.

Always discover the local CLI contract first:

```bash
cd <project-root>
anatomist doctor --format json
anatomist <command> --help
```

Never construct or persist the default DB path. The locator is machine-local
and is resolved by `doctor.index_path` under
`$ANATOMIST_HOME/indexes/<repo-key>/index.db` (default home: `~/.anatomist`).
Use `source_snapshot_fingerprint` when a portable source identity is needed.

If no usable index exists:

```bash
anatomist index <project-root> --format json
```

Index rules:

| Case | Action |
|---|---|
| Multi-module Maven | Let default discovery find module `src/main/java` roots. |
| Spring annotations matter | Avoid `--no-classpath` when possible. |
| Spring XML matters | Add `--spring-xml` so XML beans and property/map/list/ref config trees become facts. |
| Def-use, return, exception, guard, or taint evidence matters | Build/rebuild with `--dataflow`; add `--implicit-taint` only when control-dependent taint is required. |
| Re-index current work | Prefer `--incremental` against the same DB. |
| Keep index fresh while editing | Use `watch --auto-index` with the same explicit `--output` (if any), `--project-source`, `--include-tests`, classpath policy, `--java-version`, and `--spring-xml` as the initial index. |
| Stale or risky DB | Use `--recreate`. |
| Need exact snapshot | Inspect `doctor` / `project_meta` for `source_root`, `source_snapshot_fingerprint`, index profile, `indexed_at`, `source_git_commit`, `source_git_dirty`. |
| Incremental `metadata_git` is slow | Inspect `doctor.git_untracked_cache`; recommend `git config core.untrackedCache true`, but never run it without user authorization. |
| High-fanout Java file changed | Stable symbols keep incoming edges; only removed/contract-changed symbols expand through exact callers. A `symbol impact N>limit` message means the precise set crossed `--max-realign-files`. |

## Agent query gate (P0)

Before answering any question with static code facts, an Agent must synchronize
the index for the current checkout, then query only if that command succeeds.
Do this even when no `watch` process is running:

```bash
anatomist index <project-root> --incremental --strict-health --format json --output <db> \
  && anatomist <query-command> --index <db> ...
```

| Situation | Gate |
|---|---|
| Normal local checkout | `index --incremental --strict-health` before every independent query session. A no-change pass checks file metadata but does not run Maven detection, JavaParser, or graph replacement. |
| Content may have been rewritten with restored size/mtime | Add `--verify-content`; it hashes every indexed source but still does not reparse or rewrite an unchanged graph. |
| First index used non-default source/classpath flags | Reuse the same `--project-source` or every `--source-root`, `--include-tests`, `--spring-xml`, classpath policy, and `--java-version`. |
| Gate exits non-zero | Do not issue a code query or present old-index facts as current. Report the failed index/health result instead. |

`doctor` is read-only. `freshness_state=idle` means the last watcher operation
finished; it does not compare the current source tree with the DB, so it cannot
replace this gate.

Snapshot check:

```bash
sqlite3 <db> "select key,value from project_meta where key in ('source_root','source_snapshot_fingerprint','java_version','classpath_mode','spring_xml','indexed_at','source_git_commit','source_git_dirty');"
```

If `source_git_dirty=true`, say the index came from a dirty worktree. If
`source_root` moved, `--source-window` snippets may be absent.

## IDEA task routing

Route by the human task, not by command names. These categories are MECE for
normal IDEA-style code exploration: discover, inspect local context, trace
forward, trace reverse, inspect type relations, inspect field relations, map
architecture, inspect framework wiring, and verify evidence freshness.

| IDEA task | User wording | First commands | Answer boundary |
|---|---|---|---|
| Entry discovery | "Where can requests/messages enter?" | `search --name '*' --kind ROUTE`; `search <Annotation> --by-annotation`; `context <owner>` | Routes, handlers, and annotations are technical entry facts, not business-entry proof. |
| Local context | "What is in this class/method?" | `context <type-or-method>`; add `--with-callees=N` when useful | Summarize members, annotations, and nearby calls; read source for literals and branches. |
| Forward trace | "What does this call next / until DB?" | `callees-of <method> --depth N`; `call-path <from> <to> --depth N` | Static path means possible code path, not runtime certainty. |
| Branch/control-flow slice | "What happens inside this if/else/branch?" | `branches-of <method> --source-window=3`; `callees-of <method> --in-branch --source-window=3`; `field-access <Owner>#<field> --in-branch` | Branch filters use static edge context; they locate branch-contained calls/accesses, not full condition semantics or runtime execution proof. |
| Data/exception/taint flow | "Where does this value/exception/input reach?" | `flow-of <method>`; `flow-path <from> <to>`; `exception-flow <method>`; `taint-path '*' '*'` | Requires a `--dataflow` index. Results are conservative static possibilities, not runtime proof. |
| Reverse impact | "Who uses/calls this repo/type/method?" | `callers-of <method> --depth N`; `used-by <type>` | Separate direct callers from higher-level facade/message entries. |
| Type relation | "This extends what / who implements it?" | `hierarchy <type>`; `implementors-of <type>`; `implementors-of <type> --recursive` | `hierarchy` is upward only; child/subtype expansion uses `implementors-of`. |
| Field relation | "Who holds Foo / who reads this field?" | `used-by <Foo>` filtered to `context=field_type`; `field-access <Owner>#<field>` | Composition and READS/WRITES are different facts. |
| Architecture map | "How do packages/layers depend?" | `overview --deps-only`; `deps-of <anchor>`; `used-by <anchor>` | State the architecture rule before calling something a smell. |
| Framework wiring | "How is Spring wired?" | `bean-config <bean> --property <name>` for XML map/list trees; `deps-of` / `used-by` for `INJECTS`, `WIRES`, `DEFINED_BY` | Completeness depends on classpath and `--spring-xml`. |
| Evidence hygiene | "Is this index current / keep it fresh?" | For a one-off/static answer, run the Agent query gate first; then `doctor --format json`. For continuous edits use `watch <root> --auto-index --output <db>` plus the original index flags. | `doctor` reports DB/watch state but does not detect offline edits; watch keeps static facts fresh, not runtime execution proof. |

## Task details

### Entry discovery

Use route nodes and handler facts as code evidence. If you infer a business
entry, label it as a hypothesis:

```bash
anatomist search --name '*' --kind ROUTE --index <db>
anatomist context <controller-or-handler-owner> --index <db>
```

### Forward and reverse tracing

Prefer source windows only after narrowing the graph:

```bash
anatomist callees-of <entry-method> --depth 8 --through-callbacks --source-window=2 --index <db>
anatomist callers-of <repo-method> --depth 8 --through-callbacks --source-window=2 --index <db>
anatomist call-path <from-method> <to-method> --depth 8 --source-window=2 --index <db>
```

If a path crosses a lambda, anonymous class, template, or callback body, use
`--through-callbacks` when available and mention `via` when present.

### Branch and control-flow slices

When the user asks about if/else, branch-only behavior, conditional writes, or
business rules hidden inside branches, combine graph narrowing with source
windows:

```bash
anatomist branches-of <method> --depth 3 --source-window=3 --index <db>
anatomist callees-of <method> --depth 3 --in-branch --source-window=3 --index <db>
anatomist callers-of <method> --depth 3 --in-branch --source-window=3 --index <db>
anatomist field-access <Owner>#<field> --mode all --in-branch --index <db>
anatomist deps-of <type> --in-branch --index <db>
anatomist used-by <type> --in-branch --index <db>
```

Use `--in-loop` for loop-contained calls/accesses. Read the returned
`context` value, such as `if-then@L42`, `if-else@L42`, `case@L50`, or
`ternary-then@L60`, and cite `source_window` when explaining the rule.

Boundary:

| Fact | Meaning |
|---|---|
| `--in-branch` result | The CALLS/READS/WRITES edge is physically inside a branch-like AST block. |
| `context=if-then@L42` | The edge is inside the then branch of the `if` at line 42. |
| `branches-of` result | Existing branch-contained CALLS/READS/WRITES grouped by owner method and context. |
| Source window | Evidence for the condition and surrounding code; the Agent must read it. |
| Missing result | No indexed edge matched the branch filter; it does not prove no runtime branch exists. |

Without `--dataflow`, branch support is only edge filtering by lightweight
control context. With `--dataflow`, `guards-of` and `flow-of` use the optional
lightweight CFG/reaching-definitions graph; it is conservative and does not
prove path feasibility.

### Data, exception, and taint flow

```bash
anatomist flow-of <method> --depth 8 --index <db>
anatomist flow-path <source> <target> --depth 20 --index <db>
anatomist flow-summary <method> --index <db>
anatomist guards-of <method> --index <db>
anatomist exception-flow <method> --index <db>
anatomist taint-path '*' '*' --depth 30 --index <db>
```

Treat `POSSIBLE` and `INFERRED` edges as conservative evidence. Arrays are
whole-object abstractions; heap aliases, reflection beyond the bounded core
patterns, dynamic proxies, implicit runtime exceptions, and SAT/path
feasibility remain outside the guarantee.

### Core reflection

Default indexing recognizes exact `Class.forName`, `Class.getMethod` /
`getDeclaredMethod`, constructor lookup, `Method.invoke`, and
`Constructor.newInstance` targets. Use existing queries:

```bash
anatomist deps-of <type> --index <db>
anatomist callees-of <method> --depth 3 --index <db>
anatomist callers-of <target-method> --depth 3 --index <db>
```

Reflection lookup facts are `REFERENCES`; reflective invocations are `CALLS`
with `call_kind=REFLECTION`. Require `confidence=INFERRED`,
`metadata.via=reflection`, and `metadata.resolution=EXACT` before describing a
target. Missing reflection facts mean the value was dynamic, conflicting, or
outside the bounded patterns; they do not prove no runtime target exists.

### Type relation

Do not use `hierarchy` to answer "who are the subclasses?"

```text
hierarchy Foo
  Foo -> parent -> grandparent
  Foo -> directly implemented interfaces

implementors-of Foo --recursive
  Foo <- direct child <- transitive child
```

### Field relation

Use different evidence for ownership and access:

| Question | Command | Evidence |
|---|---|---|
| "Which classes compose/hold `Foo`?" | `used-by Foo` then filter | `REFERENCES` with `context=field_type`; source is a FIELD node such as `Bar#foo`. |
| "Who reads/writes `Bar#foo`?" | `field-access Bar#foo --mode all` | `READS` / `WRITES` from methods or lambdas to the field. |

Filter example:

```bash
anatomist used-by com.example.Foo --index <db> \
  | jq '.results[] | select(.relation=="REFERENCES" and .context=="field_type")'
```

### Docs and naming rules

When user-provided terms, conventions, or docs matter:

```bash
anatomist search <term> --index <db>
anatomist search --name '<glob>' --kind <KIND> --index <db>
anatomist search <Annotation> --by-annotation --index <db>
anatomist index-docs <root> --index <db>
anatomist context <node> --enrich --with-docs --index <db>
```

Say "under the user-provided `*Settlement*` rule, these matched." Treat docs as
supporting evidence, not source behavior.

### Spring XML config trees

When Spring XML contains business structure in `property`, `constructor-arg`,
`map`, `list`, `entry`, `ref`, `value`, `null`, or `idref`, query it directly:

```bash
anatomist bean-config FilterRegistry --property filters --index <db>
anatomist bean-config FilterRegistry --property filters --format json --index <db>
```

Use this for ordered filter chains such as `flowType -> stage -> filters`.
`WIRES` is still useful for class dependency impact, but it does not preserve
map keys, list order, or nesting.

### Evidence freshness and watch

Use `watch` only when the task benefits from a live static index during editing
or code generation. Do not start it for a one-off query.

```bash
anatomist watch <root> --auto-index --output <db> \
  --project-source <same-as-index> \
  [--include-tests] [--spring-xml] [--no-classpath|--classpath <same-as-index>] \
  [--java-version <same-as-index>] [--full-policy background]
```

Rules:

| Case | Action |
|---|---|
| Initial index used `--project-source` | Pass the same value to `watch`; otherwise multi-module or custom roots can drift. |
| Initial index used `--include-tests` | Pass `--include-tests` to `watch`; otherwise test-source edits may be reported by the watcher but ignored by the index. |
| Initial index used `--spring-xml` | Pass `--spring-xml` to `watch`; otherwise XML bean edits will not stay in `WIRES` evidence or XML config trees. |
| Initial index used `--no-classpath` or `--classpath` | Reuse the same classpath policy so unresolved/type-resolution behavior stays comparable. |
| Watch reports an edit but index says `Changed files: 0` | The changed file is outside the indexed source roots/cache; refresh watch flags before trusting the event as indexed evidence. |
| Watch reports a temporary Java parse failure | The last committed index remains consistent but stale for that path. Watch retries three times, then retains the path until the next event; finish the syntax and save again. |
| No files changed in the index cache | `index --incremental` returns quickly and does not re-run Maven classpath detection. |
| Java/test source changed and the prior full index used Maven classpath detection | `index --incremental` / `watch --auto-index` reuses cached classpath metadata instead of re-running Maven. |
| Build file changed | Watch re-detects source roots, Java version, and classpath artifacts. It continues incrementally when the environment fingerprint is unchanged and runs one full index when it changed. |
| Large full rebuild | Default `background` keeps collecting events and swaps a complete temporary DB only after replay. Check `doctor.freshness_state`; old graph results can be stale while rebuilding. |
| `WATCH_ALREADY_RUNNING` | Another auto-index watcher owns this DB. Reuse that process or choose a different `--output`. |
| User asks "did this run online?" | `watch` is not enough; ask for logs, traces, metrics, or runtime evidence. |

Explain the boundary as:

```text
watch keeps the anatomist static index current.
It does not prove that a route, branch, callback, profile, or runtime path executed.
```

## Evidence interpretation

anatomist returns static code facts. It does not decide business meaning.

| Level | Meaning | Use in answers |
|---|---|---|
| Fact | Direct index result | "`OrderController#create` HANDLES `POST /orders`." |
| Strong signal | Multiple facts align | "The route calls validation and persistence methods." |
| Weak signal | Naming or annotation convention only | "`*Job` matched this class; this is only a naming signal." |
| Hypothesis | Business or architecture interpretation | "Likely order submission entry; needs confirmation." |

Report node ids, relation names, file/line, or `source_window` snippets when
possible. Page large results with `stats.truncated`, `next_offset`, and
`next_queries`.

## Anti-patterns

| Do not | Do instead |
|---|---|
| Say "anatomist found the business entry" | Say it found route/handler facts; business entry is an inference. |
| Use one fixed command sequence for every task | Route by the IDEA task and inspect command help. |
| Use `hierarchy` for child/subclass expansion | Use `implementors-of`, with `--recursive` for full closure. |
| Mix field composition with field access | Use `used-by + context=field_type` for composition, `field-access` for READS/WRITES. |
| Treat `--in-branch` as full business-rule extraction | Use it to narrow branch-contained edges, then read source windows for conditions. |
| Stop at `template.execute` or callback container | Try callback traversal if supported. |
| Trust naming matches as semantics | Mark them as weak signals. |
| Paste long source files | Use source windows and cite exact file/line. |
| Assume the DB matches current source | Check `project_meta` when precision matters. |
| Treat no static path as no runtime path | Mention static-analysis limits. |

Runtime behavior can still be incomplete for unbounded reflection, profiles,
AOP, generated code, dynamic dispatch, and configuration-driven routing.
Fallback to source, config, logs, docs, or runtime evidence when the question
depends on those.
