# Source-backed value tracing

Anatomist does not materialize a separate data-flow graph. Use structural paths to
choose a small source slice, then reason from the snapshot-verified source.

| Need | Evidence path |
|---|---|
| Value movement inside one method | Resolve the full exact signature, then run `context '<signature>' --source` |
| Value movement across methods | Prove candidate hops with `call-path`, then inspect every method with `context '<signature>' --source` |
| Callers that may supply a value | Use `callers-of` to bound candidates, then inspect caller and callee source |
| Guards, branches, and loops | Use `branches-of` for locations, then verify the condition and assignments in source |
| Interface or abstract dispatch | Use `implementors-of`; treat candidate paths as possible dispatch, not runtime proof |
| Suspected taint or exception propagation | Trace the call route and inspect sanitizers, assignments, throws, and catches in source |

`context --source` returns the full exact source when it fits. If source is paged,
follow `next_queries` until `source.truncated=false`; never infer from the first page
alone. A selector ambiguity response must be resolved to one full signature first.

State the evidence boundary: structural edges and source establish static
possibilities. They do not prove a runtime value, chosen implementation, or executed
branch. Use logs, traces, tests, or debugger evidence when the conclusion is runtime-specific.
