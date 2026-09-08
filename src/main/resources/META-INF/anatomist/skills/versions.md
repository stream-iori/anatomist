# Compare and inspect versions

| Question | First command |
|---|---|
| Impact of current branch work since Base diverged | diff --base <Base> --target HEAD --merge-base --view calls --impact |
| Differences from another branch tip | diff --base <other> --target HEAD --view calls --impact |
| Include uncommitted edits | Replace --target HEAD with --target WORKTREE |
| Locate all changed files and declarations | diff --base HEAD --target WORKTREE |
| Return test callers | Add --impact-scope TEST; requires captures that indexed TEST |
| Read a historical version | index . --ref <ref>, then query --ref <ref> |

Choose the base explicitly. --merge-base compares from the common ancestor;
without it, compare the two endpoints. Use a SHA/snapshot to retain an original
fork point. Header request records the requested selectors and mode; base/target
identify the actual comparison snapshots. Git versions are captured without
switching the checkout. Diff builds missing endpoints and refreshes WORKTREE;
--no-build uses existing captures, including the last WORKTREE capture.

--view calls keeps declaration anchors, recorded CALLS changes and requested
impact. Default all also includes files and other relationships. The view only
filters output; --impact independently enables caller analysis. Header output
counts disclose hidden records. A text-touched declaration includes comments and
formatting; use Git diff for the edit. Candidate/owner anchors are navigation
fallbacks, not behavior-change claims.

Select before for deleted code and after for added code. For an entity anchor:

```bash
anatomist pipeline --snapshot <anchor.snapshot_id> --scope <anchor.scope> -- resolve '<anchor.id>' --unique --then source
```

Continue with calls, references, accesses or bindings for the entity kind.
File-only entries have no entity ID; use Git or declarations-of --file in the
snapshot. Diff emits anatomist-diff/v2, not a semantic stream. Each pipeline uses
one version; --index, --ref and --snapshot are mutually exclusive.

Impact returns one shortest path per caller/origin on each side. By default it
includes possible interface/override dispatch; --impact-dispatch resolved uses
recorded calls only. Edge candidate_kind and proofs distinguish resolved from
possible targets. Neither proves execution. Caller scope inherits --scope;
--impact-module defaults to all modules. Caller filters do not cut traversal.
Field, type-only and configuration effects are outside caller impact.

Read evidence.capabilities separately for files, declarations, relations and
impact. Open-world dispatch, missing coverage and environment changes limit
absence claims; truncation is reported separately. For a limited call site,
query calls then dispatch with explicit limits. Query scope never expands index
coverage; read skill maintenance to capture missing sources.

For retention, use snapshots list/show/pin; snapshots gc previews removal.
Query --ref reads existing captures; WORKTREE queries read the last capture.
