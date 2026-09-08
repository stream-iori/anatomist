# Query and evidence rules

Use an index for the intended checkout or captured version. Query scope defaults
to MAIN; select TEST, GENERATED or ALL when needed and ensure those sources were indexed.

For one known method, read its source directly:

```bash
anatomist pipeline -- resolve 'p.Service#run()' --kind callable --exact --unique --then source
```

| Decision | Rule |
|---|---|
| Locate a target | Known ID/signature: resolve directly. Otherwise search, inspect candidates, then select an exact target. |
| Combine queries | Use pipeline for linear stages on one index, module and scope. Put shared options before `--`; separate stages with `--then`. |
| Read evidence | NDJSON starts with stream_header and ends with evidence(scope=stream). Missing final evidence makes the stream unusable. |
| Conclude absence | Require complete coverage and negative_conclusion_safe=true for the question's selected scope. |
| Incomplete results | Retain positive facts, disclose missing coverage, and follow pagination or obtain further evidence where the conclusion requires it. |
| Interpret execution | Calls, dispatch candidates and configuration bindings are static evidence; actual execution requires runtime evidence. |
| Handle failure | Use the structured error code. Query success, coverage and negative-conclusion safety are separate judgments. |

Stop when the evidence answers the question. Read `skill topics` only to choose
another task route. Read command help for unclear arguments, `operations <command>`
for contracts/support, `pipeline --explain` for composition, and `pipeline --check`
for index availability. These are optional inspections, not setup steps.

For missing/stale indexes or degraded health, read `skill maintenance`.
