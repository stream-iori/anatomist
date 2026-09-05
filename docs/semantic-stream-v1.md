# Semantic Stream v1

## Contract

`semantic-stream/v1` is UTF-8 NDJSON: one JSON object per line. Unknown fields are
additive; unknown record types and incompatible major contracts fail.

```text
data(seed A)* → evidence(seed A)
data(child B) → evidence(child B) → evidence(parent A)
... → exactly one evidence(scope=stream)
```

Data records carry `seed_id`, `index_revision_id`, `source_snapshot_id`, and
`semantic_profile_id`. Expand operations create one child seed per result and retain
`parent_seed_id` plus bounded `derived_from` lineage.

## Failures and limits

| Exit | Meaning |
|---:|---|
| 0 | Success, including confirmed empty |
| 1 | Internal or I/O failure |
| 2 | CLI, selector, record type, or malformed input |
| 3 | Index, schema, or capability failure |
| 4 | Framing, revision, snapshot, or profile conflict |

Limits: 1 MiB per line, JSON depth 64, 1,000,000 records per stream, 100,000
records per seed, 100,000 seeds per stream, and 1,000 source lines. Completed seed
frames are delivered before stream EOF; only the current seed is buffered.
`--accept-unframed` accepts data-only
input but forces `coverage=unknown`, which is unsafe for negative conclusions.
An upstream `partial`, non-complete coverage, or `truncated=true` makes the downstream
stream evidence incomplete; a downstream command cannot upgrade upstream coverage.

`ndjson` is the streaming format. `json` deliberately buffers a terminal array;
`table` is terminal human output. An early-closing Unix consumer is treated as a
successful broken pipe. Commands check capabilities before work: default
`--on-unsupported=fail` exits 3, while `continue` emits `UNSUPPORTED_CAPABILITY`
evidence and keeps the stream incomplete.

Machine-readable contracts: [Semantic Stream schema](schema/semantic-stream-v1.schema.json)
and [Artifact IR schema](schema/artifact-ir-v1.schema.json). Both allow additive fields,
while the stream reader rejects unknown record types.

## Capability matrix

| Adapter | Entity lookup | Call sites | Source | Type/dispatch | Artifact |
|---|---|---|---|---|---|
| Java 0.16 | supported | supported | supported | supported (`exact`/CHA) | supported (Spring XML) |
| Python | contract fixture only | unsupported | unsupported | unsupported | unsupported |
| TypeScript | contract fixture only | unsupported | unsupported | unsupported | unsupported |
| Rust | contract fixture only | unsupported | unsupported | unsupported | unsupported |

## Compatibility

Existing commands retain their v2 defaults. `search` enters the semantic protocol
with `--format ndjson`; downstream canonical operations use semantic streams.
Legacy stdout never contains deprecation text.

| Release | Compatibility policy |
|---|---|
| `0.16.x` | Canonical semantic commands are supported; legacy v2 commands remain unchanged. |
| `0.17.x+` | Documentation may label overlapping legacy commands as aliases; stdout stays stable. |
| Before `1.0` | No legacy removal without at least two minor releases of notice and an equivalence report. |

## Canonical operations

| Operation | Accepts | Emits | Boundary |
|---|---|---|---|
| `type-relations` | entity(type) | type_relation | one semantic per transitive closure |
| `runtime-implementations` | entity(type) | entity + proof | open worlds remain partial |
| `callable-relations` | entity(callable) | callable_relation | override/contract facts only |
| `calls` | entity(callable) | call_site | source syntax and static targets |
| `dispatch` | call_site | dispatch_target | static candidates, never observation |
| `members` | entity(container) | entity | language/config containment |
| `bindings` | entity | binding_relation | cross-domain configuration binding |
| `source` | entity/site/dispatch | source_slice | snapshot-verified evidence |

Spring XML creates an `artifact` root and ordered configuration entities.
Abstract, parent-based, factory-created and nested beans are retained even when
they have no class binding.
