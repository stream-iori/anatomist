# Compare versions

| Intent | First command |
|---|---|
| Branch work since Base diverged | diff --base <Base> --target HEAD --merge-base --view calls --impact |
| Compare branch tips | diff --base <other> --target HEAD --view calls --impact |
| Include disk edits | Replace --target HEAD with --target WORKTREE |
| Include test callers | Add --include-tests --scope ALL --impact-scope ALL |

--merge-base selects the common ancestor; omit it for endpoint comparison. Header request records commits; base/target
identify frozen instances. Checkout stays unchanged; linked worktrees share
committed snapshots.

--include-tests prepares TEST coverage, including test-only Maven modules.
Query scopes never expand a capture. Explicit roots must include TEST;
otherwise preparation fails with SNAPSHOT_COVERAGE_MISMATCH. Scan exclusions apply.

WORKTREE captures Git-visible files plus required source/configuration inputs,
including ignored/generated sources. Unrelated ignored reports and local
Anatomist databases are excluded. Declare extras in [versions.capture]
include_ignored. Header capture discloses both policies: file evidence covers
captured inputs, not every disk file.

Automatic preparation matches indexing configuration and refreshes WORKTREE.
Unrelated profiles do not block a match. --no-build never indexes or invokes
Maven; WORKTREE selects its last matching capture. Inspect error side, selector
and candidates: SNAPSHOT_MISSING means absent; SNAPSHOT_CONFIG_MISMATCH means
incompatible configuration; SNAPSHOT_NOT_READY means unavailable artifacts.
Explicit snapshot IDs remain fixed and conflicting requirements fail.
--merge-base cannot replace an explicit base snapshot with another commit.

--view calls retains declaration anchors, CALLS changes and requested impact;
all also shows files and other relationships. Views only filter presentation;
output discloses hidden counts. Text touches include comments/formatting.
Use Git diff for edits. Candidate/owner anchors are navigation fallbacks,
not behavior claims. Use before for deleted code and after for added code:

```bash
anatomist pipeline --snapshot <anchor.snapshot_id> --scope <anchor.scope> -- resolve '<anchor.id>' --unique --then source
```

Continue with calls, references, accesses or bindings. File-only entries have
no entity ID. Diff is anatomist-diff/v2, not a semantic stream. Each query uses
one version; --index, --ref and --snapshot are mutually exclusive. Query --ref
never builds. Use snapshots list/show/pin for retention and skill maintenance
for recovery. --timings distinguishes preparation and comparison costs.

Impact gives one shortest path per caller/origin on each side. Auto includes
possible dispatch; resolved uses recorded calls. Neither proves execution.
Caller filters do not cut traversal. Field/type/configuration propagation is
unsupported. Read evidence.capabilities: missing coverage, open-world dispatch,
environment changes and truncation limit absence claims. Continue with calls
then dispatch to inspect a limited call site's evidence.
