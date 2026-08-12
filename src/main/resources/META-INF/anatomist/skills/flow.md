# Data-flow decision guide

Data-flow is opt-in and expensive. Full analysis creates CFG, def-use, exception,
guard, and interprocedural facts for a large method set, increasing CPU, memory,
index size, and elapsed time. Never enable it for ordinary call tracing, branches,
type relations, or Spring wiring.

Choose the smallest sufficient analysis:

| Need | Choose |
|---|---|
| Source-level calls or branches | Structural queries; no data-flow |
| Compact method input/output dependency | Summary coverage and `flow-summary` |
| Detailed flow for a small repeated area | Scoped coverage |
| One explicit source-to-target investigation | `flow-materialize`, then `flow-path` |
| One covered method's definitions, guards, or exceptions | `flow-of`, `guards-of`, or `exception-flow` |
| Whole-project taint or explicitly complete flow | Full coverage only after disclosing cost |
| Control-dependent taint | Enable implicit taint only when explicitly required |

Read `index --help` and each flow command's `--help` for the current profile and
query syntax. `flow-summary` may aggregate one owner-qualified overload family;
other flow commands need one method. `flow-materialize` requires full exact source
and target signatures before it can write. A signature miss never falls back to an
overload family or a longer method-name prefix.

Progressive materialization writes bounded DETAIL facts. After structural source
changes, synchronize incrementally and materialize the same target again.
Never upgrade to full coverage automatically. Positive partial paths are static
possibilities. Empty partial results do not prove absence. Respect depth and
compute-budget truncation, and state hard analysis limits.
