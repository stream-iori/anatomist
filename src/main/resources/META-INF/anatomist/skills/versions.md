# Compare and inspect versions

| Intent | First command |
|---|---|
| Branch work since Base diverged | diff --base <Base> --target HEAD --merge-base --view calls --impact |
| Compare branch tips | diff --base <other> --target HEAD --view calls --impact |
| Include disk edits | Replace --target HEAD with --target WORKTREE |
| Prepare and inspect test coverage | Add --include-tests --scope ALL --impact-scope ALL --impact |

```bash
anatomist diff --base main --target HEAD --merge-base --include-tests --scope ALL --impact-scope ALL --view calls --impact
```

Choose the base explicitly. --merge-base uses the common ancestor; omit it for
endpoint comparison. Header request records selectors and mode; base/target identify frozen instances.
Captures preserve the checkout; linked worktrees share committed snapshots.

--include-tests adds TEST scan coverage, including Maven test-only modules.
--scope and --impact-scope only select results. Defaults remain unchanged.
Explicit roots must include TEST when requested; otherwise preparation fails
with SNAPSHOT_COVERAGE_MISMATCH. Other scan exclusions still apply.

Automatic preparation matches indexing configuration and refreshes WORKTREE.
MAIN-only captures cannot satisfy TEST requests; unrelated profiles do not block
matching captures. --no-build never indexes or invokes Maven; it selects matching
captures, including the checkout's last WORKTREE capture for that configuration.
SNAPSHOT_MISSING means no capture; SNAPSHOT_CONFIG_MISMATCH means incompatible
configuration; SNAPSHOT_NOT_READY means unavailable artifacts. Inspect error
side, selector and candidates. Exact IDs remain fixed; explicit requirements
are validated. --merge-base cannot change an explicit base snapshot's commit.

--view calls retains declaration anchors, CALLS changes and requested
impact. Default all also includes files and other relationships. Views filter
presentation only; header output discloses hidden records. Text touches include
comments/formatting; use Git diff for edits. Candidate/owner anchors are navigation
fallbacks, not behavior claims. Select before for deleted code, after for added:

```bash
anatomist pipeline --snapshot <anchor.snapshot_id> --scope <anchor.scope> -- resolve '<anchor.id>' --unique --then source
```

Continue with calls, references, accesses or bindings. File-only entries have no
entity ID. Diff is anatomist-diff/v2, not a semantic stream. Each query uses one
version; --index, --ref and --snapshot are mutually exclusive. Query --ref never
builds. Use snapshots list/show/pin for retention; gc previews removal.

Impact returns one shortest path per caller/origin on each side. Auto includes
possible dispatch; resolved uses recorded calls. Neither proves execution.
Caller filters do not cut traversal. Field/type-only/configuration effects are
outside caller impact. Read each evidence.capabilities entry: missing coverage,
open-world dispatch and environment changes limit absence claims; truncation is
separate. Expand a limited call site's evidence with calls then dispatch.
