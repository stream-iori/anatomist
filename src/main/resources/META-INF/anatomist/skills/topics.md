# Skill topics

Load `core` first, then exactly one matching scene:

| User intent | Scene |
|---|---|
| Find entries, understand local code, map packages, use project docs, compare Git versions | `explore` |
| Follow calls forward/reverse or prove a focused call route | `trace` |
| Explain conditional or loop-contained behavior | `branch` |
| Inspect inheritance, implementations, composition, reads, or writes | `relations` |
| Inspect Spring annotations, injection, wiring, or XML trees | `spring` |
| Trace values, exceptions, guards, or suspected taint from paths and source | `flow` |

If a task spans scenes, start with the cheapest structural scene and load another
only when the first result exposes a concrete need. Use each command's `--help` as
the source of truth for syntax and options.
