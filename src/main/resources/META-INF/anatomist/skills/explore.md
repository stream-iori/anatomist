# Locate code and project structure

| Question | First query | Continue when |
|---|---|---|
| Find a name | search <name> | Several candidates: select a full ID/signature and narrow module/scope. |
| Inspect a declaration | resolve → describe | Behavior matters: resolve the exact method and read source. |
| List members | resolve type → members | Nested containment matters: add --recursive. |
| Inspect a changed file | declarations-of --file <relative.java> | Select the declarations relevant to the change. |
| Map packages | overview | For dependency edges use --deps-only; inspect selected entities with references. |
| Find related project docs | resolve → related-docs | Read the returned document location when it matters to the question. |

Search recalls candidates; `resolve --unique` requires one entity per seed and
will fail on ambiguous input. An exact method needs no preliminary search.
`describe` returns declaration metadata; `members` starts from the resolved entity,
not from describe output.

If documents have not been indexed, use `index-docs <project>` for the intended
mutable index. Names, annotations and documents identify leads; verify source or
configuration before presenting them as business rules.

For method logic read `skill source`; for version comparisons read `skill versions`.
