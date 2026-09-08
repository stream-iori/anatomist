# Git snapshots and change navigation (1.2)

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
`diff` prepares endpoints matching its indexing configuration unless `--no-build` is supplied. For structural
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

## Diff navigation v2

Diff uses [`anatomist-diff/v2`](schema/diff-v2.schema.json). It locates text changes
in frozen source and returns indexed declaration anchors for each side. Comments
and formatting count as touched text, not behavior changes. Use Git diff for the
edit itself, then existing queries for its meaning. Renamed methods remain
added/deleted identities; committed file renames use Git's similarity signal.

| Record | Meaning and next action |
|---|---|
| `file_change` | Project manifest entry changed; before/after gives snapshot and file. |
| `declaration_change` | Text touched an indexed declaration or navigation fallback; select its before/after anchor. |
| `relation_change` | Indexed relationship or count changed independently of text navigation; select endpoints in the corresponding snapshot. |
| `impact` | One caller/change-origin pair on one side, with a shortest representative path and both navigation anchors. |

Entity anchors contain `snapshot_id`, `id`, `symbol`, `kind`, `module`, `scope`,
`file`, `origin`, `precision`, and available source coordinates. `precision` is
`declaration`, `candidate` (including indistinguishable same-line declarations),
`owner` (containing type), or `unlocated`. File anchors use `file`. Missing ranges
are omitted, not invented. A generated identity can be resolved without having
its own source range. Use the containing type/file when precise navigation is unavailable.

```bash
anatomist diff --base HEAD --target WORKTREE
# Substitute an entity anchor from before (deleted code) or after (added code):
anatomist pipeline --snapshot <anchor.snapshot_id> --scope <anchor.scope> -- resolve '<anchor.id>' --unique --then source
# Select api changes, but return test callers across the project:
anatomist diff --base HEAD --target WORKTREE --include-tests --module api --impact --impact-scope TEST
```

Diff is not a semantic stream. Select an anchor and start a single-version query;
do not pipe diff directly into source. Continue with calls, references, accesses
or bindings when the entity kind supports the question. File-only entries can
be inspected with Git, or enumerated using `declarations-of` if indexed.

Header `selection.files` is `project_manifest`; `scope` and `module` select
declarations and relationships. `impact.scope` and `impact.module` select returned
callers, not traversal intermediates. Module defaults to the whole project for
impact; impact scope inherits declaration scope. These two options require `--impact`.
Unknown modules fail when both endpoints establish absence; a module present on
only one side is a valid addition/deletion. Missing metadata remains unknown.

Final `evidence.capabilities` separates `files`, `declarations`, `relations` and
`impact`. Requested capabilities expose status, completeness, truncation and
reasons; declaration/relationship/impact evidence also identifies reasons per side.
Each `negative_conclusion_safe` applies only to that capability and its disclosed
selection/model. Impact not requested has `status=not_requested`, without a safety flag.
There is no global safety boolean. Different environments limit relationship and
impact conclusions, while valid text navigation remains available.

Query selection does not expand indexing. TEST missing from a capture is partial,
not proof of no test changes. Use `diff --include-tests` to prepare test coverage
automatically, or select existing snapshot IDs with the required coverage. Old
snapshots lacking coverage metadata remain readable with unknown coverage.

## Automatic preparation including Maven tests

```bash
anatomist diff --base main --target HEAD --merge-base --include-tests --scope ALL --impact-scope ALL --view calls --impact
```

| Option | Responsibility |
|---|---|
| `--include-tests` | Add TEST to automatic scan coverage, including test-only Maven modules. |
| `--scope ALL` | Select declarations and relationships across captured scopes. |
| `--impact-scope ALL` | Return callers across captured scopes; traversal is not filtered. |
| `--no-build` | Read only captures matching the indexing request; never invoke Maven or an index build. |

Without `--include-tests`, existing scan defaults and configuration remain in
effect. Explicit source roots take precedence; a TEST request with MAIN-only
roots fails with `SNAPSHOT_COVERAGE_MISMATCH`. Includes/excludes, unresolved
dependencies and open-world dispatch still limit the relevant capabilities.

Automatic preparation uses the same indexing request fingerprint as `index
--ref`. Committed snapshots are selected by frozen commit and request; WORKTREE
captures also belong to a checkout. MAIN-only and TEST-enabled captures can
coexist. Unrelated profiles and invalid candidates do not hide a usable match.
Linked worktrees share committed captures through their Git common-directory and
project-relative identity; storage remains under `ANATOMIST_HOME`.

Captured project build outputs (such as Maven `target/classes`) retain their
artifact fingerprints after the private build checkout is cleaned. External
dependency files are still checked for changes during automatic cache selection.
Explicit historical snapshot reads remain available independently of those live dependencies.

With `--no-build`, missing captures return `SNAPSHOT_MISSING`, configuration
mismatches return `SNAPSHOT_CONFIG_MISMATCH`, and unavailable matching artifacts
return `SNAPSHOT_NOT_READY`. These use `anatomist-error/v1`; `details` identifies
the side, selector, requested options and candidate IDs when available.

Explicit `snapshot:<id>` selectors remain fixed. Explicit requirements such as
TEST coverage are validated against that instance, with unknown coverage treated
as unconfirmed. Historical reads without new requirements retain their original
behavior. `--merge-base` rejects an explicit base snapshot when the ancestor is
a different commit; when it is the same commit, the original instance is retained.

## Branch comparisons and caller views

| Intent | Command |
|---|---|
| Current branch work since Base diverged | `anatomist diff --base Base --target HEAD --merge-base --view calls --impact` |
| Current branch tip versus another tip | `anatomist diff --base other --target HEAD --view calls --impact` |
| Include disk edits | Replace `--target HEAD` with `--target WORKTREE` |

The base is explicit. A merge base is the current common ancestor, not a recorded
original fork point; use a fixed SHA/snapshot when that distinction matters.
Header `request.mode` is `endpoints` or `merge_base`; `request.base/target` contain
`selector` and its resolved `commit`. Header `base/target` remain the actual frozen
comparison endpoints. WORKTREE request commit describes HEAD at selection time for automatic capture.
With --no-build, it describes the last captured commit, including after a branch
switch; merge-base uses that captured commit. The endpoint identifies the exact
disk capture.

`--view all` is the default. `--view calls` retains every declaration anchor,
CALLS relation changes and requested impact, hiding file details and other
relationships. Analysis and evidence precede this presentation filter.
`output` contains `view`, `analyzed`, `emitted` and `hidden` record counts;
`evidence.emitted` is the visible change count. Hidden files are not absent files.

`--impact-dispatch auto` is the default when impact is requested. It augments
recorded calls with possible Java dispatch, independently in each snapshot.
`resolved` uses recorded calls only. Explicit --impact-dispatch requires --impact.
The model remains reverse_static_calls: candidate expansion is static evidence.
`impact.dispatch` discloses the selected mode; auto uses `world=workspace-open`.
The graph always spans captured scopes/modules; caller filters apply at output.

Path edges expose `candidate_kind=resolved|possible`; candidate edges add
`call_site`, `resolved_targets`, `algorithm`, `world`, `mechanism`,
`resolution_status`, `reason`, callable `proof` and `type_proof`.
`contains_possible_dispatch` summarizes the selected path. One shortest path is
retained per caller/origin; ties prefer paths containing only resolved edges,
then stable identity order. Relationship changes never include invented candidate deltas.

Dispatch preserves nonvirtual calls, includes inherited bodies on their actual
declarations, and restricts candidates to captured compatible receiver types.
Receiver metadata can be a declaring-type fallback, so candidates may be broader
than source-level receiver analysis. Configuration bindings add evidence without
excluding other compatible implementations. Missing external types, unsupported
call mechanisms or hierarchy gaps prevent a complete candidate claim.

Auto exposes dispatch_max_depth=20, dispatch_limit=50 targets per site and
dispatch_state_limit=100000 per snapshot, independently of call depth and the
existing impact entity/state budgets. Candidate expansion is lazy and cached by
call site within each snapshot. `impact` evidence includes per-side dispatch_states.
DISPATCH_DEPTH_LIMIT, DISPATCH_TARGET_LIMIT and DISPATCH_STATE_LIMIT indicate
actual truncation; DISPATCH_OPEN_WORLD and resolution/hierarchy gaps do not.
For further candidates, select the call site in its snapshot and query dispatch
with explicit limits. Type-only, field and configuration effects are not propagated.

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
of database row IDs or location-bearing hashes. It does not reparse declarations
to classify signature/body changes. `--impact --impact-depth 3` follows static
calls separately on each endpoint, retaining one shortest path per caller/origin
pair, including when multiple changed methods share a caller. Paths are representative;
relationship source locations are samples, not all occurrences.

Traversal uses the captured call graph across modules and scopes, and filters
returned callers afterwards. Depth is 0..100. Each endpoint is bounded at 10,000
entities and 100,000 entity/origin states; depth/entity/state exhaustion is disclosed.
Entity and origin coverage are reported independently of path enumeration.
Field, type and configuration propagation are not modeled. This is possible
static call impact, not runtime execution or proof that touched code changed behavior.

## Cache and lifecycle

SQLite backup reports newline-delimited progress on stderr. Filter the fixed
`[anatomist-progress]` prefix and parse space-separated `key=value` fields:

```text
[anatomist-progress] phase=sqlite_backup status=started elapsed_ms=0
[anatomist-progress] phase=sqlite_backup status=running percent=42 copied_pages=4200 total_pages=10000 elapsed_ms=2000
[anatomist-progress] phase=sqlite_backup status=completed percent=100 copied_pages=10000 total_pages=10000 elapsed_ms=3100
```

Start/end are always emitted for an actual backup; running updates appear every
two seconds, even without new SQLite callbacks. Unknown page counts/percentages
are omitted. Unchanged percentages are valid heartbeats, not evidence of forward
progress. `completed` means the backup API returned successfully, not that the
entire index build succeeded. Failure emits `status=failed` and preserves the
existing command error. Fast backups have only start/end lines; cache hits and
builds without a baseline emit none. stdout JSON/NDJSON remains unchanged.

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
