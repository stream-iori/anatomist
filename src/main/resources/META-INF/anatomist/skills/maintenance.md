# Index readiness and recovery

| Situation | Action | Result to inspect |
|---|---|---|
| New/current project | index . --incremental --format json | Final status, index_state and health. |
| Freshness or health uncertain | doctor --format json | Identity, freshness, coverage and diagnostics. |
| Missing test/generated sources | Adjust scan scopes and re-index | Query with the matching --scope. |
| XML / Lombok evidence needed | Enable --spring-xml / --lombok ast and re-index | Extension coverage; Lombok is off by default. |
| Configuration unexpected | doctor --format json | config_source, config_path and scan_policy_hash. |
| Incompatible standalone index | index <project> --recreate | Rebuild only the intended mutable index. |

Indexing does not require Git. Prefer incremental refresh; full indexing
is the standalone default without --incremental. Version builds attempt reuse by
default; --full forces reconstruction. Do not recreate published snapshots.
Extractor/policy changes require re-indexing; incompatible old indexes require
explicit recreation. `--changed-files-from` is only for authoritative project-relative
deltas; use normal change detection otherwise.

Configuration selects project .anatomist/config.toml, then user ~/.anatomist/config.toml,
then built-ins. Files do not merge; CLI flags override. Built-in scanning includes
MAIN + GENERATED; querying still defaults to MAIN. Keep the intended classpath and
extension profile when refreshing the index.

`status=ok` with `index_state=committed` remains queryable with health=degraded.
Keep positive results and disclose incomplete coverage; do not repeatedly rebuild
solely to force healthy status. Follow concrete diagnostics for missing dependencies
or partial Lombok coverage.

stderr progress is diagnostic. A backup completion/heartbeat does not establish
command success; use the exit code and final stdout result. Details: docs/git-snapshots.md in the source distribution.

| Version storage situation | Action |
|---|---|
| Inspect disk use | snapshots stats |
| Preview recovery and old snapshot removal | snapshots gc --keep 20 |
| Perform reviewed cleanup | snapshots gc --keep 20 --execute |
| Apply a byte budget | snapshots gc --max-bytes <bytes> --execute |
| Include expired classpath lists | Add --include-caches |

GC defaults to preview. Inspect recovery, protected reasons and budget_met.
Pins, valid entrypoints and active readers can exceed the budget. Use pin for
long-term historical navigation; unused entrypoints expire after 30 days by
default. Small lock files remain intentionally. GC recovers interrupted builds
and owned worktree registrations; never manually prune unrelated worktrees.

Automatic GC is off unless [versions.gc] auto is enabled. A successful capture
may report SNAPSHOT_CLEANUP_PENDING or SNAPSHOT_GC_PENDING on stderr: the published
snapshot remains usable, and snapshots gc --execute retries cleanup. Unowned
paths are reported and preserved. --no-build does not trigger automatic cleanup.
