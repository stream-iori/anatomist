# Call-trace decision guide

| Question | Choose |
|---|---|
| What source call sites occur next? | `resolve ... --kind callable | calls` |
| What can this method call next, including legacy traversal? | `callees-of` |
| Who can reach or depend on this method? | `callers-of` |
| Is there a focused route from A to B? | `call-path` |
| Which types depend on an external class? | `used-by` after resolving the full external name |

Read each selected command's `--help` for traversal, callback, paging, and source
options. Include callback bodies only when the path crosses lambdas, anonymous
classes, templates, or callback containers.

Prefer `resolve | calls | source` when call-site identity, exact source range, or
pipeline evidence matters. `calls` deliberately excludes override dispatch.

Use a full method signature for a single path endpoint. `Class#method` may
aggregate overloads only in set-valued caller, callee, or branch queries; it
never matches longer names such as `methodExtra`. If a short owner or bare method
matches different owners, choose one candidate from the ambiguity response.

Depth and result limits are independent. A short page is not necessarily a complete
traversal, and an empty bounded path is not absence proof while an expandable
frontier remains. Describe reflection targets only when the result identifies an
exact inferred reflection resolution. Static reachability is not runtime execution.
