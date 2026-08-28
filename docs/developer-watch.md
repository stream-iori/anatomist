# Human-only local development watcher

`watch` is a compatibility command for a developer editing a local checkout.
It is intentionally hidden from Agent discovery (`anatomist --help`, `doctor`
JSON, and `skill`). Agents must run an explicit incremental index gate before
each analysis session instead:

```bash
anatomist index <project> --incremental --health-policy integrity \
  --format json --output <db>
```

The hidden command remains directly available to humans:

```bash
anatomist watch <project> [--auto-index] [--debounce-ms 500]
anatomist watch <project> --auto-index --output <db> \
  --project-source <paths> [--spring-xml] [--timings] [--no-classpath|--classpath <jars>] \
  [--full-policy background|inline|manual]
```

## Behavior

| Case | Behavior |
|---|---|
| No `--auto-index` | Print `CREATE` / `MODIFY` / `DELETE` events only. |
| Source change with `--auto-index` | Incrementally update the same DB. |
| Incremental cannot be trusted | Request a full rebuild for empty cache, schema/layout/profile drift, or excessive impact. |
| `--full-policy background` | Default: build a sibling DB while event collection continues, then promote it under a short lock. |
| `--full-policy inline` | Run the full rebuild in the foreground. |
| `--full-policy manual` | Keep the DB and mark it stale; run `anatomist index ... --full` manually. |
| A second auto-index process targets one DB | Fails with `WATCH_ALREADY_RUNNING`. |
| Temporary Java parse failure | Retain the prior snapshot and retry up to three times. |
| Selected config changes | Print `CONFIG_CHANGED` and exit `4`; restart manually. |

Reuse the same project source roots, scan rules, classpath, Java version, Spring
XML, flow profile, health policy, and output DB that built the initial index.
`watch` is static analysis assistance, not proof that a runtime route, callback,
or branch executed.

## Recovery

| Symptom | Action |
|---|---|
| Editor/generator leaves incomplete Java temporarily | Save again; the retry path consumes the next stable file version. |
| Persistent parse failure | Compile or inspect the reported line; verify `--java-version`. |
| Background rebuild interrupted | Restart the command; it marks and reconciles the stale state. |
| Policy/config changed | Restart; this command never hot-reloads its scan policy. |
| Need exact content verification | Use a one-shot `index --incremental --verify-content` instead. |

For detailed parse, rebuild, staging, and build-environment diagnostics, see
[Troubleshooting](troubleshooting.md).
