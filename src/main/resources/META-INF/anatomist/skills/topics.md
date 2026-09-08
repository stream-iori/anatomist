# Task routes

Choose a direct query when it is sufficient. Arrows below are pipeline stages;
selection between candidates is a decision before starting the next query.

| Question | First query | More guidance when needed |
|---|---|---|
| What does this exact method do? | resolve exact callable → source | `skill source` |
| Where is this name defined? | search; select candidate → resolve | `skill explore` |
| What is in this file? | declarations-of --file <relative.java> | `skill explore` |
| How is the project organized? | overview | `skill explore` |
| What are this type's members? | resolve type → members | `skill explore` |
| Who calls this method? | resolve callable → calls --direction incoming | `skill trace` |
| What does it call? | resolve callable → calls | `skill trace` |
| Which virtual targets are possible? | resolve callable → calls → dispatch | `skill trace` |
| Is there a call route from A to B? | resolve A → trace --to B | `skill trace` |
| Which types can implement this API? | resolve type → runtime-implementations | `skill relations` |
| Which methods override this method? | resolve callable → callable-relations --direction incoming | `skill relations` |
| Who reads or writes this field? | resolve value → accesses | `skill relations` |
| What annotations/configuration apply? | resolve → annotations or bindings | `skill spring` |
| Where is changed code? | diff --base HEAD --target WORKTREE | `skill versions` |
| Which callers may branch work affect? | diff --base <base> --target HEAD --merge-base --view calls --impact | `skill versions` |
| Is the index usable/current? | doctor --format json | `skill maintenance` |

For rules not yet loaded, read `skill core`. Load another scene only when the
current evidence exposes a specific need.
