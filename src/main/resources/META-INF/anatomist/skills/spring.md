# Spring decision guide

| Need | Pipeline |
|---|---|
| MVC handler annotations | `search | resolve | annotations` |
| Injection/config bindings | `search | resolve | bindings` |
| XML ordered/nested structure | `search --kind artifact | resolve --unique | members --recursive` |
| Java impact from configured types | `resolve | references` plus `bindings` evidence |

Confirm the index profile includes classpath and `--spring-xml` when needed, then keep
that profile through incremental indexing. Qualifiers, profiles, conditions, proxies,
generated code, and runtime configuration can change the actual object.

`INJECTS` and `WIRES` are static configuration facts. They may narrow dispatch
candidates but never manufacture a source call site. Use logs/traces/runtime responses
for claims about what actually ran.
