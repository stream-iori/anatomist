# Explore decision guide

Choose commands by the question:

| Need | Start with | Then |
|---|---|---|
| Find technical request/message entries | `search --format ndjson` | `resolve --unique`, then `source` |
| Understand one class or method | `context` | Use an exact method signature with `--source` |
| Map packages and layers | `overview` | Use `deps-of` or `used-by` on a suspicious boundary |
| Apply project terminology or documentation | `index-docs`, `search` | Enrich the matching node with `context` |

Read the selected command's `--help` for syntax.

The first producer uses `--format ndjson`; semantic-only downstream commands default
to NDJSON and validate revision, semantic profile, seed evidence, and final evidence.

Routes, handlers, annotations, names, and documentation are technical signals.
Do not call them business entry points or business rules without corroborating
source/configuration evidence. Prefer small source windows after graph results have
narrowed the location; do not paste whole files.

For an already located declaration, read the bounded source view directly:

```bash
anatomist context 'com.example.OrderService#createOrder(java.lang.String)' --source
```

Follow `next_queries` when `source.truncated=true`. A `stale` source status is a
soft warning with no snippet; rebuild the index before using checkout text as evidence.

Choose the smallest source scope that can answer the question:

| Question scope | Source decision |
|---|---|
| One method's behavior | Read its exact method signature with `--source`. For an exhaustive conclusion, follow `next_queries` until `source.truncated=false`. |
| Class-wide state or lifecycle across fields, constructors, initializers, or several helpers | Read the type with `--source`; the class is the reasoning unit. |
| One concern inside a large class | Use `--methods-only` to find the relevant signatures, then read those methods separately. Do not read a whole class by default. |
| A local call or branch already visible in the current page | Stop when that evidence answers the question; pagination is not mandatory. |

When `context`, `search`, or `declarations-of` returns `metadata.lombok`, use
only `modeled_capabilities` as structural facts. `partial_capabilities` and
`unmodeled_capabilities` explain uncertainty; verify them from source, build
artifacts, or the user before asserting exact members or call relations.
