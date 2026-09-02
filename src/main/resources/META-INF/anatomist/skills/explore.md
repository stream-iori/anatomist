# Explore decision guide

Choose commands by the question:

| Need | Start with | Then |
|---|---|---|
| Find technical request/message entries | `search` | Inspect the owning type with `context` |
| Understand one class or method | `context` | Use an exact method signature with `--source` |
| Map packages and layers | `overview` | Use `deps-of` or `used-by` on a suspicious boundary |
| Apply project terminology or documentation | `index-docs`, `search` | Enrich the matching node with `context` |

Read the selected command's `--help` for syntax.

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
