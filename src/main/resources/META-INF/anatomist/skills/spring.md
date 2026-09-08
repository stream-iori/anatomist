# Annotations and Spring configuration

| Question | First query | Continue when |
|---|---|---|
| Direct annotations | resolve → annotations | Composed annotations matter: add --include-meta. |
| Find annotated declarations | search <annotation> --by-annotation | Select candidates; use --include-meta for composed definitions. |
| Injection/configuration bindings | resolve → bindings | Choose direction and semantic from bindings --help if needed. |
| XML factory/setter/constructor/lifecycle member | resolve component → bindings --semantic member | Read each matched declaration when behavior matters. |
| XML nested structure | resolve artifact → members --recursive | Inspect selected entities' bindings. |

For XML, ensure the index includes Spring XML; read `skill maintenance` if coverage
is missing. Qualifiers, profiles, conditions, proxies and runtime configuration can
change the selected object.

| Member resolution | Interpretation |
|---|---|
| exact | One static declaration matched. |
| ambiguous | Inspect all candidates; do not select the first overload. |
| unresolved | Keep the raw symbol_ref; the declaration is not proven absent. |

Annotations are direct by default. Composed annotation attributes using @AliasFor
are not rewritten; inspect source/configuration when their values matter. Framework
recognition requires Spring identities, not a matching simple annotation name.

Factory, setter and lifecycle bindings are configuration facts rather than source
call sites. XML import/alias expansion, profile activation, AOP and actual bean
selection require further evidence. Resolve a configured member to read its body.
