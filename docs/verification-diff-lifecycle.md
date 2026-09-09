# Diff lifecycle verification — 1.2.1-SNAPSHOT

## Delivered behavior

| Priority | Change | Boundary |
|---|---|---|
| P0 | Capture Git-visible files plus required Java, generated inputs, Spring XML and configuration. | Unrelated ignored reports do not create new WORKTREE snapshots. `versions.capture.include_ignored` adds explicit paths. |
| P0 | Recover owned workspaces, interrupted catalog entries and private Maven subprocesses. | PID/start-time receipts protect unrelated processes; unknown directories remain untouched. Cleanup failure after publication is a warning. |
| P0 | Compare under endpoint read leases; serialize only preparation and GC. | GC skips live readers. Explicit snapshot IDs remain fixed. |
| P1 | Select catalog candidates by commit/configuration or checkout pointer; cache external artifact validation within one operation. | Immutable databases remain separate copies; a new version can reuse parsing through SQLite backup and incremental indexing. |
| P1 | Batch verified committed Java patches, stream relation groups, load reverse calls on demand, and spill large result rows. | JSON/NDJSON retain the v2 contract. More than 8 MiB of serialized rows uses owned temporary storage. |
| P2 | Add storage statistics, budget/age retention and optional automatic GC. | Automatic GC is off by default. Pins, live entrypoints and readers override byte budgets. Lock-file tombstones remain. |
| P2 | Update help, versions/maintenance guides, root SKILL and output schema. | Historical captures remain readable by ID; capture-policy mismatches limit file-level conclusions. |

## Reproduction

Run from this repository with Java selected by `.sdkmanrc`. Use a built native
binary and keep compilation separate from performance measurements.

```bash
mvn test
uv run --with-requirements e2e/requirements.txt python -m unittest discover -s e2e -p 'test_*.py'
just diff-e2e-native
just diff-lifecycle-e2e
just diff-stress-e2e
just agent-e2e-versions
python3 scripts/diff-performance.py --native target/anatomist --baseline /path/to/previous/anatomist
```

The process and performance fixtures own their repositories and `ANATOMIST_HOME`.
The Git/Maven suite owns its Maven repository and verifies JVM/native output
parity. Successful and failed runs write JSON reports, including artifact hashes;
fixtures are removed unless `--keep-on-failure` is explicitly selected.

The Agent oracle validates actual consumed JSON/NDJSON and source identity on
both sides. Reads through `cat`, `jq`, `sed` or Python can establish consumption
of a preserved complete artifact; file counts and hashes alone cannot. An Agent
may refine a filtered diff into an unfiltered capture check. Failed initial runs
remain in `e2e/jury-runs`; reruns do not overwrite that evidence.

## Acceptance coverage

Validation was performed on macOS arm64 with GraalVM Java 25.0.3. The full JUnit
suite passed; subsequent changes were rerun against their affected suites. Final
Surefire reports contain 891 tests with zero failures, errors or skips. The Python
oracle suite passes 34 tests. The final native reports `1.2.1-SNAPSHOT`.

| Final runtime report | Result |
|---|---|
| `target/diff-maven-verified.json` | 12 passing Git/Maven scenarios across JVM/native, including parity. |
| `target/diff-lifecycle-verified.json` | 12 passing process lifecycle scenarios across JVM/native. |
| `target/diff-stress-verified.json` | 4 passing large-output/concurrency scenarios. |

| Layer | Assertions |
|---|---|
| JUnit | Ignored inputs, required generated/XML inputs, symlink parents, multiple configurations, cross-worktree selection, frozen branches, catalog migration, entrypoint expiry, byte budgets and auto-GC. |
| Process E2E | Parent-only SIGKILL, orphan Maven recovery, missing directory with live Git worktree registration, concurrent cached reads, cleanup warnings, primary-error preservation and pinned budgets. |
| Output stress | 15,000 changed methods; 10,978,470 serialized bytes spill; GC preserves slow-reader endpoints; broken output emits `DIFF_OUTPUT_FAILED`; result files and reader leases are released. |
| Git/Maven E2E | MAIN/TEST coverage, configuration matching, explicit IDs, missing source/dependency caches, moved branches, worktree sharing and runtime parity. |
| Agent E2E | Branch work, branch tips, fixed-instance coverage boundary, ignored reports, and storage preview/execution/navigation. |
| Performance | Five-module projects with 1,000/10,000 Java files; five repetitions per warm/change scenario; a separate small project with 100 real immutable snapshots and retention to two. |

## Measured performance (2026-09-09)

Baseline: local `1.2.0-SNAPSHOT`; current: `1.2.1-SNAPSHOT`. Native hashes are
recorded in `target/diff-verification-artifacts.json`. Results below come from
`target/diff-performance-controlled.json`, after local compilation and runtime
acceptance finished. Other desktop/Agent activity can still introduce noise.
Times are seconds; warm/change values are medians of five runs. Cold is one run.

| Scenario | 1k baseline | 1k current | 10k baseline | 10k current |
|---|---:|---:|---:|---:|
| Cold preparation + diff | 3.221 | 3.098 | 30.777 | 32.552 |
| Cached committed diff | 0.598 | 0.369 | 1.903 | 1.497 |
| One-file new commit | 1.357 | 1.294 | 7.287 | 7.505 |
| 100-file new commit | 4.132 | 1.832 | 11.256 | 8.617 |
| Ignored report changed | 4.387 | 2.565 | 15.975 | 3.953 |

Neither warm comparisons nor single-file updates regressed beyond the 10% gate.
At 10k files, cold preparation increased 5.8% and single-file updates increased
3.0%; cached comparisons improved 21.3%, batch updates 23.4%, and ignored-report
reuse 75.3%. These are fixture measurements, not universal speedup guarantees.

| Storage/RSS | 1k baseline | 1k current | 10k baseline | 10k current |
|---|---:|---:|---:|---:|
| Store before GC (MiB) | 58.36 | 42.14 | 517.48 | 373.90 |
| Store after GC (MiB) | 6.62 | 6.65 | 58.27 | 58.30 |
| Maximum observed process RSS (MiB) | 196.70 | 200.55 | 546.81 | 534.67 |

With 100 real snapshots, a cached two-endpoint comparison validated two snapshots
and performed no build. GC removed 98 and retained two. The initial measurement
passes overlapped compilation; they remain available as preliminary reports and
are not used for the comparison above.

## Agent acceptance and oracle corrections

| Scenario | Passing real Agent run |
|---|---|
| branch-work-impact | `run-20260909-012242-86eeb8c1` |
| branch-tip-impact | `run-20260909-014512-60ec1073` |
| impact-boundary | `run-20260909-014430-7932d59c` |
| ignored-lifecycle | `run-20260909-014803-891a0fef` |
| storage-lifecycle | `run-20260909-014809-ca19dc71` |

Each run records its exact binary/skill hashes. Branch-work and the first passing
storage run used the initial implementation binary; the other reruns used the
final native build. Later production fixes were also covered by targeted JUnit
and final JVM/native lifecycle, stress and Maven acceptance.

The first oracle missed legitimate `jq`/`sed` reads; that detection was fixed and
affected cases rerun. An additional final-native storage run
`run-20260909-015114-76516774` passed GC and diff checks but exposed a second
oracle gap: it read exact source through JSON instead of NDJSON. The oracle now
accepts both complete envelopes. Its preserved, hash-verified consumed artifacts
passed both-side navigation rechecking before and after GC:
`target/diff-agent-storage-json-recheck.json`. This is a deterministic recheck of
actual Agent evidence, not another model run; the original failed report remains
unchanged. Python regressions cover both oracle corrections.

## Interpretation limits

Graph storage still grows with retained snapshot count. Source blobs are shared
by content hash; Git-ignored non-input files do not consume new source snapshots.
A cold capture still pays for materialization, indexing and storing source.
WORKTREE reuse still verifies selected disk inputs. Warm committed comparisons
avoid materialization, SQLite backup and parsing.

Spilling bounds serialized result-row storage, not the entire process: node
navigation maps, SQLite sorting, dispatch caches and offset indexes still need
memory. GC is explicit or opt-in after new builds; it is not a background daemon.
Space statistics are observations during concurrent activity, not an atomic
filesystem snapshot. Protected data can leave `budget_met=false`.
