# Spring decision guide

| Need | Stages |
|---|---|
| Direct MVC/bean annotation | `search → resolve → annotations` |
| Composed/meta annotation | `search --by-annotation --include-meta` or `resolve → annotations --include-meta` |
| Injection/config bindings | `search → resolve → bindings` |
| XML factory/setter/constructor/lifecycle method | `search --kind component → resolve --unique → bindings --semantic member` |
| XML ordered/nested structure | `search --kind artifact → resolve --unique → members --recursive` |
| Java impact from configured types | `resolve → references` plus `bindings` evidence |

Arrows are fused stages; run them with `pipeline -- ... --then ...`.

Confirm the index profile includes classpath and `--spring-xml` when needed, then keep
that profile through incremental indexing. Qualifiers, profiles, conditions, proxies,
generated code, and runtime configuration can change the actual object.

Annotations are direct-only unless `--include-meta` is present. Meta expansion is
cycle-safe and stops at depth 16. Spring recognition uses resolved framework FQNs and
the same closure: a custom `com.acme.Service` is not Spring, while a composed source or
classpath annotation can reach its Spring root. `@AliasFor` attribute rewriting is not
modeled, so use direct source/config when aliased values matter.

`bindings --semantic member` emits `role`, `mechanism`, raw `symbol_ref`, candidate
index/count, and `resolution_status`:

| Status | Agent action |
|---|---|
| `exact` | One static declaration matched; inspect source when behavior matters. |
| `ambiguous` | Keep and inspect every candidate; do not pick the first overload. |
| `unresolved` | Keep the raw SymbolRef; do not conclude the method is absent. |

Factory methods, constructors, property setters, and init/destroy methods are static
bindings, not `CALLS`. Member resolution prefers the XML module/scope, checks arity,
explicit type/ref hints and static/instance requirements, and follows inherited
members. Plain string values do not invent a parameter type. Lookup/replaced methods,
XML import/alias expansion, profile activation, AOP, and runtime bean selection remain
outside this evidence.

`INJECTS` and `WIRES` are static configuration facts. They may narrow dispatch
candidates but never manufacture a source call site. Use logs/traces/runtime responses
for claims about what actually ran.
