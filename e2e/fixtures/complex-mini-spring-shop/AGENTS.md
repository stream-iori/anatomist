# Evaluation workspace

- Begin every analysis with `anatomist skill core`; this CLI command is the portable skill entrypoint.
- Then run `anatomist doctor --agent-preflight --format json --index .anatomist/index.db`
  and complete the incremental index gate before the first query.
- Use Anatomist for Java structure, calls, branches, types, and Spring wiring.
- Treat each command's `--help` output as the CLI syntax source of truth.
- Keep `.anatomist/index.db` synchronized with this exact checkout before using query evidence.
- Follow `next_queries` whenever a conclusion depends on truncated source.
- Obey the prompt's edit boundary. Analysis-only tasks must not change source or configuration.
- For implementation tasks, run the relevant Maven tests and re-index changed Java files before reporting completion.
- Do not commit, push, switch branches, or modify the acceptance-test module.
- Save temporary evidence under `../evidence/` so the owned workspace cleanup removes it.

Read `../anatomist-skill.md` for the exact Anatomist skill supplied for this run.
