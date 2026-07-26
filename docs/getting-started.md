# Getting Started

## Install (pre-built binary)

One-line install for macOS Apple Silicon:

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | sh
```

This downloads the native binary to `~/.local/bin/anatomist`. Custom install path:

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | \
  ANATOMIST_INSTALL_DIR=/usr/local/bin sh
```

After install, ensure `~/.local/bin` is in your PATH (the script will remind you if not).

The installer also downloads `anatomist/SKILL.md` and installs it for common agent clients:

| Client | Skill path |
|--------|------------|
| Qoder | `~/.qoder/skills/anatomist/SKILL.md` |
| Codex | `${CODEX_HOME:-~/.codex}/skills/anatomist/SKILL.md` |

Install only selected clients:

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | \
  ANATOMIST_SKILL_CLIENTS="codex" sh
```

Skip skill install:

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | \
  ANATOMIST_INSTALL_SKILL=0 sh
```

## Install from GitHub Releases

GitHub release assets are published at `stream-iori/anatomist`:

| Platform | Asset |
|----------|-------|
| macOS Apple Silicon | `anatomist-darwin-aarch64` |
| Linux amd64 | `anatomist-linux-amd64` |
| JVM fallback | `anatomist.jar` |

macOS Apple Silicon:

```bash
curl -Lo anatomist \
  https://github.com/stream-iori/anatomist/releases/latest/download/anatomist-darwin-aarch64
chmod +x anatomist
./anatomist --version
```

Linux amd64:

```bash
curl -Lo anatomist \
  https://github.com/stream-iori/anatomist/releases/latest/download/anatomist-linux-amd64
chmod +x anatomist
./anatomist --version
```

## Manual skill install

The skill is published next to `install.sh` under `anatomist/SKILL.md`.

Download only:

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/anatomist/SKILL.md -o SKILL.md
```

Install for Codex manually:

```bash
mkdir -p ~/.codex/skills/anatomist
curl -fsSL http://6.12.3.250:8100/dist-bin/anatomist/SKILL.md \
  -o ~/.codex/skills/anatomist/SKILL.md
```

If you use a custom mirror, use the same base as `ANATOMIST_MIRROR`:

```bash
ANATOMIST_MIRROR=http://your-mirror/dist-bin
mkdir -p ~/.codex/skills/anatomist
curl -fsSL "$ANATOMIST_MIRROR/anatomist/SKILL.md" \
  -o ~/.codex/skills/anatomist/SKILL.md
```

The skill file must keep Codex-compatible YAML frontmatter:

```yaml
---
name: anatomist
description: Use when analyzing Java code structure with anatomist.
---
```

## Build from source

### Prerequisites

- JDK 21+ (or GraalVM 25+ for native binary)
- Maven 3.9+
- `just` task runner (`brew install just`)

### Build

```bash
just jar          # → target/anatomist.jar (fat JVM jar)
just native       # → target/anatomist (native binary, requires GraalVM)
```

## Index a project

```bash
# Index the bundled example fixture
anatomist index fixtures/mini-spring-shop \
    --project-source api/src/main/java:domain/src/main/java:service/src/main/java \
    --no-classpath \
    --output /tmp/shop.db

# Index a real Maven project (auto-detects classpath)
anatomist index /path/to/your/project --output /tmp/project.db
```

Key flags:
- `--project-source` — colon-separated source roots (relative to project path)
- `--no-classpath` — skip Maven dependency resolution (faster, loses external type info)
- `--output` — SQLite database path (default: `$ANATOMIST_HOME/indexes/<repo-key>/index.db`; `$ANATOMIST_HOME` defaults to `~/.anatomist`)
- `--incremental` — only re-parse changed files
- `--spring-xml` — include Spring XML `<beans>` wiring facts
- `--timings` — show per-phase costs without changing default output
- `--health-policy integrity` — reject incomplete parse/graph snapshots while
  allowing disclosed third-party resolution gaps
- `--jdk-home` — local JDK home for native-image catalog resolution; defaults
  to `ANATOMIST_JDK_HOME` when set

Maven dependency classpaths are cached under
`$ANATOMIST_HOME/cache/classpath` using the project POM files and Maven
`settings.xml` as the fingerprint. Changing either invalidates the cache. For a
legacy reactor that inherits a `jdk.tools/tools.jar` system dependency,
anatomist retries Maven with a local JDK 8. Set
`ANATOMIST_MAVEN_JAVA_HOME=/path/to/jdk` to override the Maven runtime without
changing the JVM that runs anatomist.

The native binary bundles a real Java 8 type catalog and does not download
catalogs. To resolve a Java 9–25 target against its local JDK API, pass a
matching path once; the generated catalog is cached under
`$ANATOMIST_HOME/catalogs`:

```bash
anatomist index /path/to/project --java-version 17 --jdk-home /path/to/jdk-17
```

To keep the index fresh while editing, use `watch --auto-index` with the same
indexing shape as the initial command:

```bash
anatomist watch fixtures/mini-spring-shop \
    --project-source api/src/main/java:domain/src/main/java:service/src/main/java \
    --no-classpath \
    --output /tmp/shop.db \
    --auto-index \
    --full-policy background \
    --timings
```

For Spring XML projects, add `--spring-xml` to both `index` and `watch`.
Likewise, reuse `--jdk-home` for Watch auto-indexing when the native catalog is
needed.
`watch` keeps the static index current; it does not prove a runtime path
actually executed.

For a large project, leave the default `--full-policy background`: a necessary
full rebuild uses a temporary DB while the watcher continues receiving edits.
`doctor --index <db> --format json` reports watcher/DB state, but
`freshness_state=idle` does not detect edits made while no watcher was running.

For a one-off Agent query after local edits, use the query gate instead:

```bash
anatomist index fixtures/mini-spring-shop --incremental --health-policy integrity --format json --output /tmp/shop.db \
  && anatomist search OrderService --index /tmp/shop.db
```

Use `--verify-content` on the index command when files may have been rewritten
with restored timestamps. Reuse the source-root, classpath, Java-version, and
Spring XML options from the initial index.
Read query `evidence.status` before making a negative claim:
`confirmed_empty` is conclusive; `indeterminate` is not.

If `--timings` shows a slow `metadata_git` phase, check `doctor` and optionally
enable Git's repository-local untracked cache yourself:

```bash
git config core.untrackedCache true
```

Anatomist only detects and recommends this setting; it does not change Git
configuration or the Git index automatically.

## Query the index

All queries return JSON to stdout. Use `--index` to point at the database.

```bash
# Search by name
anatomist search OrderService --index /tmp/shop.db

# View class structure
anatomist context com.example.shop.service.OrderService --index /tmp/shop.db

# Trace call chain (3 levels deep)
anatomist callees-of com.example.shop.service.OrderService#createOrder --depth 3 --index /tmp/shop.db

# Impact analysis: who calls this method?
anatomist callers-of com.example.shop.service.OrderService#createOrder --depth 2 --index /tmp/shop.db

# Dependencies with pagination
anatomist deps-of com.example.shop.service.OrderService --limit 20 --index /tmp/shop.db
```

## Output format

Every query outputs a JSON envelope:

```json
{
  "query": "deps-of OrderService --limit 20",
  "results": [...],
  "stats": {"total": 45, "offset": 0, "truncated": true}
}
```

- `total` — full result count before pagination
- `truncated` — whether there are more results on the current depth/page, or a
  `flow-of` traversal budget was spent
- `depth_truncated` — whether graph traversal can continue beyond the requested depth
- Use `next_queries` to paginate, enlarge `--limit`, or increase `--depth`; follow
  every applicable suggestion before treating results as exhaustive

## Next steps

- [Architecture](architecture.md) — package layout, data flow, design constraints
- [Data Model](data-model.md) — Node ID rules, edge semantics, metadata JSON
- [Commands](commands.md) — full CLI reference
- [Testing](testing.md) — how to run tests, fixture design
- [Troubleshooting](troubleshooting.md) — watch parse failures and recovery
