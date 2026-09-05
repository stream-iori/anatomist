# Branch decision guide

Use one real semantic pipeline:

```text
resolve '<exact-callable>' --kind callable --exact --unique
  → regions --kind branch
  → sites-in --record all
  → source
```

The arrows are stage boundaries. Run them with
`pipeline --index <db> -- ... --then regions ... --then sites-in ... --then source`.

`regions` derives lightweight syntax contexts; `sites-in` returns calls and accesses
physically inside them. Use `--record call_site` or `access_site` to narrow results.
Inspect source before explaining the condition.

These facts do not prove path feasibility, business meaning, or runtime execution.
A missing site is conclusive only when the final stream evidence is complete and
negative-safe.
