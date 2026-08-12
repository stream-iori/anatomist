# Branch decision guide

Use `branches-of` to discover calls and field accesses physically contained in
branch-like source blocks. Use the branch filters on `callees-of`, `callers-of`,
`field-access`, `deps-of`, or `used-by` after the owner or target is known.

Read the selected command's `--help`, request a small source window, and inspect the
returned control context before explaining the condition. Use loop filtering only
for edges physically contained in loops.

Branch results locate indexed edges inside syntax blocks. They do not prove path
feasibility, business semantics, or runtime execution. A missing branch-contained
edge is not proof that no runtime branch exists.
