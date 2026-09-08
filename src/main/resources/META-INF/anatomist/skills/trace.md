# Calls and focused routes

| Question | First query | Continue when |
|---|---|---|
| Direct callers | resolve callable → calls --direction incoming | The calling arguments or condition matter: source on the returned site. |
| Direct calls | resolve callable → calls | The call syntax matters: source on the returned site. |
| Possible virtual targets | resolve callable → calls → dispatch | Behavior matters: select a candidate's exact callable and resolve → source. |
| Route A to B | resolve A → trace --to B | Read selected hop methods in separate resolve → source queries. |

Use full method signatures for both endpoints. Select overloads before tracing.
`calls` reports source call sites and static resolution. `dispatch` adds possible
virtual targets. Configuration bindings can constrain possibilities, not create calls.

`trace` returns one path within --max-depth, not all paths. An empty bounded search
cannot rule out longer routes or unresolved calls. Trace records cannot be piped to
source; select and resolve the relevant endpoint/hop instead.

Use `trace --dispatch possible` only when virtual candidates are relevant. Neither
that mode nor dispatch proves execution. Reflection evidence requires a concrete
inferred target in the result.
