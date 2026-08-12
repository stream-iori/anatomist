# Core decision guide

Use this guide for every independent static-analysis session.

1. Work from the exact checkout being analysed. Treat the resolved index path as
   the writer and snapshot identity; never reuse another checkout's index.
2. Read `doctor --help`, then run `doctor` in Agent-preflight JSON mode.
3. Follow the reported repair action when preflight has blockers. Otherwise run
   the `index` incremental integrity gate before querying. Read `index --help`
   first and preserve the existing source, classpath, Spring, and flow profile.
4. Stop when the gate fails. Do not query an older committed snapshot as if it
   described current source.
5. Load exactly one relevant scene from `skill topics`, then read each selected
   query command's `--help` before execution.

Interpret evidence conservatively:

- Positive indexed facts are usable but may not be exhaustive when coverage is partial.
- Say an item is absent only when evidence confirms an empty result and marks a
  negative conclusion safe.
- For bounded traversals, also require result, depth, and compute-budget
  truncation signals to be clear. Follow suggested continuation queries when present.
- Static paths describe possible source relationships, not runtime execution.
- Use logs, traces, metrics, configuration, or runtime responses when the question
  asks what happened online.

Prefer incremental synchronization. Build a fresh index only when no usable index
exists or the reported schema/layout/profile transition requires rebuilding.
