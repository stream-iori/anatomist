# Compare and inspect versions

| Question | First command | Continue when |
|---|---|---|
| Where is changed code on disk? | diff --base HEAD --target WORKTREE | Select a before/after anchor and query its snapshot. |
| What changed between refs? | diff --base <base> --target <target> | Use --merge-base for changes since the common ancestor. |
| Read historical code | index . --ref <ref>, then query --ref <ref> | Fix --snapshot <id> when the branch name may move. |
| Inspect captures | snapshots list | Use show for one ID; pin versions that must be retained. |
| Which callers may be affected? | diff --base HEAD --target WORKTREE --impact | Use --impact-module for caller selection across modules. |
| Which test callers may be affected? | diff --base HEAD --target WORKTREE --impact --impact-scope TEST | Requires captures that indexed TEST. |

Version commands require Git. They capture versions without switching the checkout.
Query --ref reads an existing capture; WORKTREE queries read the last capture.
Diff builds missing endpoints and refreshes WORKTREE unless --no-build is selected.
Each ordinary pipeline uses one version; --index, --ref and --snapshot are mutually
exclusive and belong at the pipeline level.

Diff emits `anatomist-diff/v2`. A declaration entry means text was touched,
including comments and formatting; use Git diff for the edit itself. A candidate
or owner anchor is a navigation fallback, not proof that its behavior changed.
Use before for deleted code and after for added code. For an entity anchor:

```bash
anatomist pipeline --snapshot <anchor.snapshot_id> --scope <anchor.scope> -- resolve '<anchor.id>' --unique --then source
```

Continue with calls, references, accesses or bindings for the selected entity kind.
File-only entries have no entity ID; inspect the file in that snapshot or use
`declarations-of --file` when it was indexed. Historical source stays frozen.
Diff records are not semantic streams; select anchors instead of piping diff into source.

Read `evidence.capabilities` separately for files, declarations, relations and impact.
File changes cover the project manifest; --scope/--module select declarations and
relationships. Missing scope coverage is not absence. Read `skill maintenance` to
capture missing sources; query scope does not expand what was indexed.

Impact follows static calls only, with one shortest representative path for each
caller/change-origin pair on each side. Caller filters do not cut traversal paths.
It does not model field/configuration effects or prove runtime execution. Respect
reported limits, coverage gaps and environment differences before concluding absence.

Use explicit SHA/snapshot IDs to retain a fork point. Linked worktrees can share
committed captures; WORKTREE remains checkout-local. `snapshots gc` previews removal;
--execute performs it, retaining protected/pinned versions. Read snapshots --help
only when managing retention.
