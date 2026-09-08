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

Diff uses `anatomist-diff/v1`, independently identifies both endpoints, and emits
file and declaration changes plus final comparison evidence. Different analysis
profiles are disclosed; they do not silently establish source-caused changes.
Comments and whitespace do not count as semantic declaration changes. Renamed
methods are added/deleted symbols; file renames use Git's similarity signal.
