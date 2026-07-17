# Troubleshooting

## Maven classpath detection fails on `jdk.tools` or `tools.jar`

An old parent POM can declare a system-scoped JDK dependency that exists only
in JDK 8. Anatomist drains and inspects Maven output, then retries once with a
local JDK 8 discovered from `JAVA8_HOME`, macOS `java_home`, or SDKMAN.

```bash
ANATOMIST_MAVEN_JAVA_HOME=/path/to/jdk8 anatomist index /path/to/project
```

Successful dependency classpaths are cached by POM/settings fingerprint, so a
subsequent full index does not pay the Maven reactor cost again. Delete the
matching file under `$ANATOMIST_HOME/cache/classpath` only when diagnosing a
suspected stale cache; missing jar files invalidate it automatically.

Check `doctor --format json` before treating Maven output as all-or-nothing:

| `classpath_detection.status` | Meaning |
|---|---|
| `full` / `cache_hit` / `explicit` | Requested dependency inputs are available |
| `partial` | Maven failed, but usable module output/classpath entries were recovered |
| `unavailable` | Maven failed and no usable entries were recovered |
| `not_requested` | Classpath detection was disabled or not applicable |

`partial` and `unavailable` degrade external-resolution coverage. They do not
fail `--health-policy integrity`, but do fail `--strict-health`.

## Watch reports a Java parse failure

Typical output:

```text
WARN: source temporarily unparsable; previous index retained; retry 1/3 in 500ms: [src/A.java] — Parse error ...
ERROR: source remains unparsable after 3 retries; previous index retained; waiting for next file event: [src/A.java]
```

This usually means `watch --auto-index` observed a Java file while an editor or
code generator was still writing it. A diagnostic such as `Found "throws"`
can therefore describe a transient snapshot rather than the final file on disk.

```text
MODIFY → debounce → parse failed → keep last committed index
                              └→ retry up to 3 times
```

| State | Behavior |
|---|---|
| A retry parses successfully | Commit the final source graph and clear the pending path. |
| All three retries fail | Stop timed retries, keep the pending path, and wait for the next file event. |
| Another source event arrives | Merge it with pending paths and reset the three-retry budget. |
| `--fail-fast` is enabled | Exit after the first failed parse attempt. |
| Database, schema, or other non-parse failure | Do not enter the timed parse-retry loop. |

The previous SQLite snapshot remains internally consistent because incremental
replacement starts only after every requested Java file produces a compilation
unit. It is still **stale** for the failed path until a retry succeeds.

### What to do

1. Finish the Java syntax and save the file again. A successful retry needs no
   full re-index.
2. If the error persists, compile the affected module or inspect the reported
   line. Also confirm `--java-version` matches the project.
3. Use `--fail-fast` in automation that should stop rather than retain pending
   work.
4. Do not increase `--debounce-ms` as the only fix. A larger debounce reduces
   the chance of reading a partial save but cannot guarantee atomic editor or
   generator writes.

Expected parse failures are reported without a Java stack trace. An unexpected
stack trace indicates a different failure class and should be investigated as
a database, classpath, schema, or implementation problem.

## A staging database remains beside the index

Full and incremental indexing buffer bounded fact batches in a file-backed
SQLite sidecar named like `index.db.stage-<pid>-<uuid>.db`. The sidecar keeps
Node/Edge objects out of the Java heap; it is not a queryable index and is
deleted after commit or rollback.

```text
parse/extract → staging sidecar → SQL graph finalization → index.db transaction
```

| Situation | Result |
|---|---|
| Parse or graph finalization fails | The previously committed index remains unchanged. |
| Promotion fails | SQLite rolls back the graph replacement. |
| Process is killed | A sidecar can remain; no partial graph is committed. |
| Disk becomes full | Free space, remove stale sidecars, then retry. |

Do not delete a sidecar while its matching index command holds the write lock.
After confirming no index/watch process is running, orphaned sidecars can be
removed safely. A normal successful run leaves none behind.

## Watch is rebuilding in the background

With the default `watch --full-policy background`, a large full rebuild writes a
temporary `index.db.rebuild-<uuid>.db` while the prior committed `index.db`
remains readable. Watch continues collecting paths and applies them after the
replacement is promoted.

```text
old complete index ──query──> available
                     \-> temporary full build -> replay events -> short locked swap
```

Use `doctor --index <db> --format json` to inspect `freshness_state`:

| State | Meaning |
|---|---|
| `idle` | The last watcher operation completed. |
| `rebuilding` / `incremental` | The graph remains internally complete but may lag new files. |
| `stale` / `failed` | Rebuild did not complete; inspect `rebuild_reason` and run a full index if needed. |

If a watcher is interrupted, the next auto-index watcher cleans the abandoned
temporary DB, marks the index stale, and reconciles the source tree before
accepting new work. Do not start a second `--auto-index` watcher for the same DB.

## Incremental scan misses a content change with restored timestamps

Schema v7 stores SHA-256 together with file size, nanosecond mtime, a Java
contract hash. A standalone `index --incremental` trusts an unchanged size/mtime
pair and reuses the saved SHA; this avoids reading every source file. Use
`--verify-content` when files can be rewritten while restoring both attributes.

| Path | Content check |
|---|---|
| `watch --auto-index` event candidate | Always hashes the final file bytes. |
| Standalone incremental, size/mtime changed | Hashes bytes; unchanged content only refreshes stat metadata. |
| Standalone incremental, size/mtime stable | Reuses cached hash. |
| Standalone incremental with `--verify-content` | Hashes every source. |

Opening an older schema database with the v11 binary intentionally reports
`incremental degraded to full (schema_version mismatch)` once. The rebuild is
required because old rows have no lossless capability coverage aggregate and
anonymous-class IDs do not include columns. There is no compatibility migration.

Body/comment/initializer-only Java edits keep the contract hash stable, so they
do not invalidate dependent type-resolution caches or rebuild Spring wiring.
Signatures, annotations, inheritance, imports, declared fields, and declared
types remain part of the contract and can expand the exact dependency closure.

## Watch reports a build environment decision

`pom.xml`, Gradle build, or settings changes no longer mean an unconditional full index.
Watch re-detects source roots, Java version, and classpath artifacts first:

| Message | Meaning |
|---|---|
| `Build environment unchanged; continuing incremental` | The build edit did not change inputs relevant to parsing/type resolution. |
| `Build environment changed: ...` | At least one indexed input changed; one full index follows. |
| `estimated incremental ... >70% full baseline` | The exact impact closure is valid but expected to cost more than a full scan. |
| `symbol impact ... >hard cap` | The 1000-file default memory/correctness guard was exceeded. |

Successful full indexing or overflow reconciliation restores Watch's candidate-file fast
path. Failures retain pending paths and keep the conservative reconciliation path.

## The dependency type cache is stale or corrupt

Dependency JAR lookup and parsed ASM declarations use versioned packed files
under `~/.anatomist/cache/types/`. The cache key includes ordered classpath
entries plus each JAR's size and modification time. Class directories remain
live-scanned because build output is mutable.

| Situation | Result |
|---|---|
| First run or changed JAR | Rebuild the packed origin index and use ASM on metadata misses. |
| Warm run | Load the packed FQN index and reuse parsed type metadata. |
| Bad version, CRC, or truncated file | Delete that cache file and fall back to JAR/ASM resolution. |
| Cache write fails | Indexing still succeeds; only the next run stays cold. |

Set `-Danatomist.typeCache.dir=/path` to isolate the packed cache. Memory
experiments can tune `anatomist.typeCache.asmMaxBytes`,
`anatomist.typeCache.combinedMaxEntries`, and
`anatomist.typeCache.sourceMaxEntries`; invalid or non-positive values use the
safe defaults.
