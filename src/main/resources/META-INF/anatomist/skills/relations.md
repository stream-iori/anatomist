# Type and field relation decision guide

| Question | Stages | Boundary |
|---|---|---|
| Parent/conformance facts | `resolve type → type-relations` | Pick direction and semantic explicitly |
| Concrete transitive candidates | `resolve type → runtime-implementations` | Open world may stay partial |
| Types/members referenced | `resolve [→ members --recursive] → references` | Not a runtime dependency proof |
| Exact field reads/writes | `resolve value --unique → accesses` | Access is not ownership |

Read each command's `--help`. Short names must resolve to one owner; use full FQN plus
`--module`/`--scope` when duplicates exist. A missing selector is an error, not an
empty relation set. Only complete, negative-safe evidence supports absence.
Arrows are fused stages; translate them to `pipeline -- ... --then ...`.
