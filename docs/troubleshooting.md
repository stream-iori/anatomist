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
| `full` / `cache_hit` / `explicit` | Requested dependency inputs are available; use `origin` to distinguish Maven, classpath cache, and explicit input |
| `index_metadata` | Incremental parsing reused the classpath recorded by the prior DB; this is not a new Maven detection |
| `partial` | Maven failed, but usable module output/classpath entries were recovered |
| `unavailable` | Maven failed and no usable entries were recovered |
| `not_requested` | Classpath detection was disabled or not applicable |

`module_output_files` is a deprecated compatibility name for the number of
Maven `anatomist-classpath.txt` files. It never counted build output
directories. Use `maven_classpath_files` for that value and
`build_output_entries` to verify discovered `target/classes` and
`target/test-classes`, including on cache hits.

`partial` and `unavailable` degrade external-resolution coverage. They do not
fail `--health-policy integrity`, but do fail `--strict-health`.

When POM/settings/Maven-JDK inputs change, or an older DB has no recorded
classpath input hash, the next `index --incremental` automatically performs a
full rebuild before reusing any graph facts.

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
After confirming no index process is running, orphaned sidecars can be
removed safely. A normal successful run leaves none behind.

## Incremental scan misses a content change with restored timestamps

Schema v7 stores SHA-256 together with file size, nanosecond mtime, a Java
contract hash. A standalone `index --incremental` trusts an unchanged size/mtime
pair and reuses the saved SHA; this avoids reading every source file. Use
`--verify-content` when files can be rewritten while restoring both attributes.

| Path | Content check |
|---|---|
| Standalone incremental, size/mtime changed | Hashes bytes; unchanged content only refreshes stat metadata. |
| Standalone incremental, size/mtime stable | Reuses cached hash. |
| Standalone incremental with `--verify-content` | Hashes every source. |

Opening an older schema database with the v16 binary intentionally reports
`incremental degraded to full (schema_version mismatch)` once. The rebuild is
required because v16 removes the former dataflow tables and older structural
rows may also lack declaration ranges or `producer_id` ownership. There is no
compatibility migration.

Body/comment/initializer-only Java edits keep the contract hash stable, so they
do not invalidate dependent type-resolution caches or rebuild Spring wiring.
Signatures, annotations, inheritance, imports, declared fields, and declared
types remain part of the contract and can expand the exact dependency closure.

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
