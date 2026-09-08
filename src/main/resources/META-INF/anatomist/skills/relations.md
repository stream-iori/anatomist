# Types, method contracts and fields

| Question | First query | Continue when |
|---|---|---|
| Parents / children | resolve type → type-relations --direction outgoing / incoming | Transitive ancestry matters: add --transitive. |
| Concrete implementation candidates | resolve type → runtime-implementations | Read selected candidate declarations or methods. |
| Overridden contracts / overriding methods | resolve callable → callable-relations --direction outgoing / incoming | More than direct overrides matter: add --transitive. |
| Uses of an entity | resolve → references --direction incoming | The enclosing source matters: source on returned sites. |
| References made by an entity | resolve → references --direction outgoing | For a type's members, start a separate resolve → members → references query. |
| Field reads / writes | resolve value → accesses --mode reads / writes | Explain the value or condition from returned source sites. |

Select one owner with an exact ID/signature and module/scope where needed. The slash
choices in the table are alternatives, not literal command syntax.

Type inheritance, interface conformance and callable overrides are distinct facts.
`runtime-implementations` reports statically instantiable candidates, not live objects.
Open-world coverage may remain partial; do not choose a closed world solely to make
an empty result negative-safe. A reference or field access is not ownership or
runtime dependency proof.
