# Type and field relation decision guide

| Question | Choose | Boundary |
|---|---|---|
| What does this type extend or directly implement? | `hierarchy` | Upward relations only |
| Which concrete or transitive types implement/extend it? | `implementors-of` | Expand recursively only when the full closure matters |
| Which classes hold a field of this type? | `used-by` | Require a field-type reference context |
| Which methods read or write this exact field? | `field-access` | Reads and writes are access facts, not ownership |

Read the selected command's `--help` for exact selectors and scope. Never substitute
`hierarchy` for subtype discovery, and never mix type composition with field access.
Short type or field names must identify one owner. When packages, modules, or
scopes contain duplicates, use the candidate's full FQN plus `--module`/`--scope`;
do not treat merged results as one relation set.

A missing selector is an error, not evidence that a relation set is empty.
Only interpret an empty relation after the target resolves and the response
marks the negative conclusion safe. Full external FQNs remain valid for reverse
queries only when the index already contains matching external edges.
