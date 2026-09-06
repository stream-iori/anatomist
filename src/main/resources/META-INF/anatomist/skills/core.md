# Core workflow

Use the index belonging to the exact checkout under analysis. Prefer
`index --incremental`; recreate after extractor or semantic policy changes.

```text
direct call evidence   resolve exact callable → calls → source
virtual candidates     resolve exact callable → calls → dispatch → source
changed file           declarations-of --file <project-relative.java>
```

Use command `--help`, `operations <operation>`, `pipeline --explain`,
`pipeline --check`, and `doctor` only when their information is needed. They are
diagnostic tools, not mandatory setup steps.

For configuration failures, inspect `doctor --format json` fields
`config_source`, `config_path`, and `scan_policy_hash`; the profile committed into the index
changes when policy changes and then requires re-indexing.

Prefer fused `pipeline` for two or more linear stages. A valid NDJSON stream
starts with `stream_header`, has one evidence record per logical seed, and ends
with `evidence(scope=stream)`. Missing final evidence is failure. Absence is safe
only with complete coverage and `negative_conclusion_safe=true`.

`calls` reports source syntax and static targets. `dispatch` reports possible
virtual targets, never observed runtime execution. Query failures use structured
`anatomist-error/v1` codes.
