# Call-trace decision guide

| Question | Stages |
|---|---|
| Direct source calls | `resolve callable --exact --unique → calls` |
| Possible virtual targets | `... → calls → dispatch` |
| Direct callers | `resolve callable --exact --unique → calls --direction incoming` |
| Focused route A → B | `resolve A --kind callable --exact --unique → trace --to B` |
| Source proof for a site | `... → calls [→ dispatch] → source` |

Arrows are stage boundaries. Execute linear stages with
`pipeline --index <db> -- <stage> --then <stage>`.

Use full signatures for single endpoints. Partial names may resolve several overloads;
pick one entity before tracing. `calls` proves source syntax plus static resolution.
`dispatch` and `trace --dispatch possible` add static candidates, never runtime proof.

Limits and open-world evidence are independent. An empty bounded trace is not absence
proof unless final evidence is complete and negative-safe. Reflection is evidence only
when the output identifies a concrete inferred target.
