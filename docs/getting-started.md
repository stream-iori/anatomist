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
| Claude | `${CLAUDE_CONFIG_DIR:-~/.claude}/skills/anatomist/SKILL.md` |

Install only selected clients:

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | \
  ANATOMIST_SKILL_CLIENTS="codex claude" sh
```

Skip skill install:

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | \
  ANATOMIST_INSTALL_SKILL=0 sh
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
- `--output` — SQLite database path (default: `.anatomist/index.db`)
- `--incremental` — only re-parse changed files

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
- `truncated` — whether there are more results
- Use `--offset N` to paginate, `--filter <keyword>` to narrow results

## Next steps

- [Architecture](architecture.md) — package layout, data flow, design constraints
- [Data Model](data-model.md) — Node ID rules, edge semantics, metadata JSON
- [Commands](commands.md) — full CLI reference
- [Testing](testing.md) — how to run tests, fixture design
