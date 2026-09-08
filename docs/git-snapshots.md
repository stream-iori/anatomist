# Git snapshots (1.1.0)

Version indexing is opt-in. Existing commands without a version selector retain
their per-checkout database behavior.

```bash
anatomist index . --ref HEAD
anatomist index . --ref WORKTREE
anatomist pipeline --ref HEAD -- resolve 'p.A#value()' --kind callable --exact --unique --then source
anatomist diff --base HEAD --target WORKTREE
anatomist diff --base main --target feature --merge-base
anatomist snapshots list
anatomist snapshots pin <snapshot-id>
anatomist snapshots gc --keep 20
anatomist snapshots gc --keep 20 --execute
```

`--ref` selects an already indexed Git commit, branch, or the most recently
captured WORKTREE. `--snapshot <id>` fixes an exact analysis instance. These
selectors are mutually exclusive with `--index`. Read commands never build;
`diff` builds missing endpoints unless `--no-build` is supplied. For structural
analysis without Maven use `--no-classpath` on both indexing and diff commands.

WORKTREE captures final disk contents (staged and unstaged together), including
untracked and ignored source inputs, without modifying the Git index or HEAD.
Unresolved merge conflicts and symbolic-link inputs are rejected explicitly.
Submodule recursion and separate Git staging-area versions are not supported.

Each version lives under `$ANATOMIST_HOME/versions/<repository-project-key>`.
The default home is `~/.anatomist`; it must be outside the source repository.
Linked worktrees share committed snapshots. WORKTREE pointers are checkout-local.
The graph schema remains unchanged; the catalog has its own schema version.
Published graphs are immutable, including when addressed by database filename.

Queries read frozen source, so checkout changes do not invalidate historical
source evidence. Ordinary `index`, `index-docs`, and `annotate` writes to published
snapshots are rejected. Existing standalone databases are not moved or migrated.

Diff uses [`anatomist-diff/v1`](schema/diff-v1.schema.json), independently identifies both endpoints, and emits
file and declaration changes plus final comparison evidence. Different analysis
profiles are disclosed; they do not silently establish source-caused changes.
Comments and whitespace do not count as semantic declaration changes. Renamed
methods are added/deleted symbols; file renames use Git's similarity signal.

## Incremental builds and impact

Version builds default to incremental reuse. A compatible indexed ancestor is
copied with the SQLite backup API into a private database. WORKTREE first tries
its own prior capture, then its HEAD. The existing incremental engine propagates
symbol changes and can fall back to full indexing when compatibility or cost
requires it. `index . --ref HEAD --full` explicitly requests full reconstruction.
Empty and documentation-only commits still receive version catalog records.

All builds of one repository/project use the same managed source location, so
linked checkout paths do not enter incremental compatibility decisions. Scan
layout, classpath, language version and extension checks remain enabled. External
artifact contents are checked before reuse. Non-Maven layouts should declare
source roots in project configuration or pass `--project-source` to indexing.
Indexed source roots must stay inside the selected project directory. If modules
use sibling source directories, select their common project root; external
source inputs are rejected before publication because they are not frozen.

Diff compares relationship multisets, including normalized call sites, instead
of database row IDs or location-bearing hashes. `--impact --impact-depth 3`
reports reverse static-call paths separately for each endpoint. The depth may
be set from 0 through 100; traversal is capped at 10,000 visited entities per
endpoint and reports truncation. This is possible static impact, not runtime
execution. Incomplete parsing/resolution or differing profiles make negative
conclusions unsafe. Diff only reparses files whose content changed.

## Cache and lifecycle

Source content is stored once per SHA-256 under the repository's `blobs/`
directory. Snapshot manifests preserve paths; published databases point to the
content cache. Managed detached worktrees are released after capture. Identical
requests reuse an existing snapshot without parsing or copying a database.
Graph databases remain separate physical snapshots, so graph storage is roughly
proportional to retained versions.

`snapshots list --format table` lists versions; `show`, `pin`, and `unpin` take
an ID. `gc` is a preview unless `--execute` is supplied. It retains the newest
20 successful snapshots by default, pinned versions, current named entrypoints,
and active readers/builds. Commit SHA lookups do not permanently pin history.
Only unreferenced source blobs are collected. Executed cleanup is not an undoable
operation: deleted WORKTREE contents cannot be reconstructed from Git. Small
lock-file tombstones are retained to prevent races with waiting readers.

Build and GC operations are serialized per repository/project. Failure never
publishes a partial database. Subsequent builds recover cataloged interrupted
attempts. Source reads check frozen content hashes; `doctor --snapshot <id>`
also verifies source-cache integrity.

## Verification

See [1.1.0 verification and measurements](verification-1.1.0.md) for measured
costs, test coverage, and the three-stage delivery summary.

```bash
mvn -Dtest=GitSnapshotsIT test
python3 scripts/snapshots-e2e.py --jar target/anatomist.jar
python3 scripts/snapshots-e2e.py --native target/anatomist
python3 scripts/snapshots-e2e.py --native target/anatomist --files 1000 --repeats 3 --report target/snapshot-benchmark.json
```

Pass `--fixture <local-commons-lang-checkout>` to benchmark a clone of the pinned
real-project fixture. The script modifies only disposable repositories and sets
its own `ANATOMIST_HOME`; it never changes the supplied fixture. Build progress
goes to stderr. JSON/NDJSON stdout is reserved for results.
