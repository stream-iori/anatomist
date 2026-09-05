# Getting Started

## Install (pre-built binary)

One-line install for macOS Apple Silicon:

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | sh -s -- --components anatomist
```

This downloads the native binary to `~/.local/bin/anatomist`. Custom install path:

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | \
  ANATOMIST_INSTALL_DIR=/usr/local/bin sh -s -- --components anatomist
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
  DIORAMA_CLIENTS="codex" sh -s -- --components anatomist
```

Skip skill install:

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | \
  ANATOMIST_INSTALL_SKILL=0 sh -s -- --components anatomist
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

## Skill installation

The versioned skill path is selected from the unified release manifest, so use
the installer instead of the former flat `anatomist/SKILL.md` URL:

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | \
  DIORAMA_CLIENTS="codex" sh -s -- --components anatomist
```

For a custom mirror, use `--mirror http://your-mirror/dist-bin`. The manifest
and the selected skill must come from the same mirror/version.

The skill file must keep Codex-compatible YAML frontmatter:

```yaml
---
name: anatomist
description: Use when analyzing Java code structure with anatomist.
---
```

The installed `SKILL.md` is only a compact bootstrap. Task guidance ships inside
the same-version binary:

```bash
anatomist skill topics
anatomist skill core
```

Use `skill` to choose the workflow and evidence boundary; use the selected
command's `--help` for exact syntax and defaults.

## Build from source

### Prerequisites

- JDK 25+; use the GraalVM 25 version pinned by `.sdkmanrc` for native builds
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
- `--lombok off|ast` — opt in to common Lombok AST signatures; query results disclose modeled, partial, and unmodeled capabilities
- `--timings` — show per-phase costs without changing default output
- `--health-policy integrity` — reject incomplete parse/graph snapshots while
  allowing disclosed third-party resolution gaps
- `--jdk-home` — local JDK home for native-image catalog resolution; defaults
  to `ANATOMIST_JDK_HOME` when set
- `--scan-scope MAIN|TEST|GENERATED` — repeatable source-root scope selector
- `--scan-include <glob>` / `--scan-exclude <glob>` — repeatable
  project-relative file rules

### Configure scan scope

The selected config is exactly one file: project
`.anatomist/config.toml`, otherwise `~/.anatomist/config.toml`, otherwise
built-in defaults. A project file replaces—not merges with—the user file.

```toml
[scan]
scopes = ["MAIN", "GENERATED"]
include = ["src/**"]
exclude = ["**/*IT.java"]
```

`**` crosses directories; `*` and `?` do not. `source_roots` may replace
`scopes` for explicit `module@scope=path` mappings, but the two keys cannot be
combined. Invalid config fails closed with exit code 2. On a policy change,
`index --incremental` performs a full rebuild. Full details are in [the command reference](commands.md#configuration).

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

For every Agent query after local edits, use the explicit query gate:

```bash
anatomist index fixtures/mini-spring-shop --incremental --health-policy integrity --format json --output /tmp/shop.db \
  && anatomist search OrderService --index /tmp/shop.db
```

Use `--verify-content` on the index command when files may have been rewritten
with restored timestamps. Reuse the source-root, scan-policy, classpath,
Java-version, and Spring XML options from the initial index.
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

Existing queries return a v2 JSON envelope. Semantic pipelines use NDJSON records.
Pass the same `--index` to every process.

```bash
# Search by name
anatomist search OrderService --index /tmp/shop.db

# View class structure
anatomist context com.example.shop.service.OrderService --index /tmp/shop.db

# Read one exact method body, with snapshot-verified source evidence
anatomist context 'com.example.shop.service.OrderService#createOrder(java.lang.String)' \
    --source --index /tmp/shop.db

# Trace call chain (3 levels deep)
anatomist callees-of com.example.shop.service.OrderService#createOrder --depth 3 --index /tmp/shop.db

# Impact analysis: who calls this method?
anatomist callers-of com.example.shop.service.OrderService#createOrder --depth 2 --index /tmp/shop.db

# Dependencies with pagination
anatomist deps-of com.example.shop.service.OrderService --limit 20 --index /tmp/shop.db
```

## Output format

```bash
set -o pipefail
anatomist search OrderService --kind type --format ndjson --index /tmp/shop.db |
  anatomist resolve --unique --index /tmp/shop.db
```

`search` needs explicit `--format ndjson`; semantic-only downstream commands default
to NDJSON and verify revision, profile, seed evidence, and final evidence.

Every query outputs a JSON envelope:

```json
{
  "contract_version": 2,
  "query": "deps-of OrderService --limit 20",
  "results": [...],
  "stats": {"total": 45, "offset": 0, "truncated": true}
}
```

Query JSON is a versioned projection, not a database-row dump. Version 2 omits
default provenance and fields already represented by `source`, `target`, the
parent node, or `source_range`.

- `total` — full result count before pagination
- `truncated` — whether there are more results on the current depth/page
- `depth_truncated` — whether graph traversal can continue beyond the requested depth
- Use `next_queries` to paginate, enlarge `--limit`, or increase `--depth`; follow
  every applicable suggestion before treating results as exhaustive

## Next steps

- [Architecture](architecture.md) — package layout, indexing pipeline, design constraints
- [Data Model](data-model.md) — Node ID rules, edge semantics, metadata JSON
- [Commands](commands.md) — full CLI reference
- [Testing](testing.md) — how to run tests, fixture design
- [Troubleshooting](troubleshooting.md) — indexing and environment diagnosis
