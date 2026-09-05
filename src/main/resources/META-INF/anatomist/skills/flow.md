# Source-backed value tracing

Anatomist does not materialize a separate data-flow graph. Use structural pipelines to
choose exact source slices, then reason from snapshot-verified code.

| Need | Evidence stages |
|---|---|
| Value inside one method | `resolve exact callable → source` |
| Value across methods | `resolve callable → trace --to ...`，再逐个 `resolve → source` |
| Callers supplying a value | `resolve callable → calls --direction incoming → source` |
| Guards/branches/loops | `resolve callable → regions → sites-in → source` |
| Abstract dispatch | `resolve callable → calls → dispatch → source` |
| Field movement | `resolve value → accesses → source` |

Arrows are fused stages; run linear queries with `pipeline -- ... --then ...`.

Use the full exact signature and resolve ambiguity first. Continue `source --offset`
until the needed range is no longer truncated. Static paths and source establish
possibilities, not runtime values, selected implementations, or executed branches.
