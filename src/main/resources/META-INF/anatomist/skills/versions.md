# Compare and inspect versions

| Question | First command | Continue when |
|---|---|---|
| What changed on disk? | diff --base HEAD --target WORKTREE | Add --impact for possible caller impact. |
| What changed between refs? | diff --base <base> --target <target> | Use --merge-base for changes since the common ancestor. |
| Read historical code | index . --ref <ref>, then query --ref <ref> | Fix --snapshot <id> when the branch name may move. |
| Inspect captures | snapshots list | Use show for one ID; pin versions that must be retained. |

Version commands require Git. They capture versions without switching the checkout.
Query --ref reads an existing capture; WORKTREE queries read the last capture.
Diff builds missing endpoints and refreshes WORKTREE unless --no-build is selected.
Each ordinary pipeline uses one version; --index, --ref and --snapshot are mutually
exclusive and belong at the pipeline level.

Historical source is frozen. Do not repair it from current disk content. Compare
versions with diff's independent anatomist-diff/v1 result, not mixed semantic streams.
Incomplete evidence or different analysis environments limit negative and
source-only conclusions. Impact reports possible static callers, not runtime impact.

Use explicit SHA/snapshot IDs to retain a fork point. Linked worktrees can share
committed captures; WORKTREE remains checkout-local. `snapshots gc` previews removal;
--execute performs it, retaining protected/pinned versions. Read snapshots --help
only when managing retention.
