# Migration to 1.1.0

1.1.0 adds opt-in immutable Git and WORKTREE snapshots, semantic diff, reverse
call impact, source deduplication, and explicit version garbage collection.

| Existing behavior | 1.1.0 behavior |
|---|---|
| `index <project>` | Unchanged standalone database |
| Query without a version selector | Unchanged per-checkout lookup |
| `semantic-stream/v1` | Unchanged single-version stream contract |
| Standalone graph schema 26 | Remains schema 26; no snapshot migration required |
| Incremental Git candidate detection | Also supports changes between commits |
| Source no-op | Preserves graph identity but refreshes changed Git provenance |
| New version catalog | Separate catalog schema 1 under Anatomist storage |

To opt in, run `anatomist index . --ref HEAD`, then add `--ref HEAD` to queries
or use the emitted snapshot ID with `--snapshot`. Published snapshots reject
mutation, even through their database paths. `--ref` cannot be combined with
`--output`, `--recreate`, or a manually supplied changed-files manifest.

WORKTREE contains staged and unstaged disk changes together. `--ref WORKTREE`
on a read command selects the last capture, whereas diff captures current disk
content unless `--no-build` is set. Use a snapshot ID to reproduce an exact query.
Refs with multiple analysis profiles require explicit snapshot selection.

See [Git snapshots](git-snapshots.md) for interfaces, storage and GC behavior.
