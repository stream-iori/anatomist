# anatomist — task runner.
#
# Install `just` once: `brew install just`  (or `cargo install just` / package manager).
# Then in this repo, run `just` to see the list of commands.
#
# Conventions:
#   - The default recipe lists what's available (`just`).
#   - All recipes are non-interactive and safe to re-run (idempotent where it matters).
#   - Tests + smokes use the bundled mini-spring-shop fixture so they're self-contained.

set shell := ["bash", "-cu"]

# Resolve project paths once.
ROOT       := justfile_directory()
FIXTURE    := ROOT + "/fixtures/mini-spring-shop"
SOURCES    := FIXTURE + "/api/src/main/java:" + FIXTURE + "/domain/src/main/java:" + FIXTURE + "/service/src/main/java"
SMOKE_DB   := "/tmp/anatomist-smoke.db"
NATIVE_BIN := ROOT + "/target/anatomist"
INSTALL_DIR := env_var_or_default("ANATOMIST_INSTALL_DIR", env_var("HOME") + "/.local/bin")

# Default: list recipes
default:
    @just --list

# ─────────────────────────────────────────── build ────────────────────────────────────────────

# Compile Java sources (skip tests)
compile:
    mvn -q compile

# Build the fat JVM jar -> target/anatomist.jar
jar:
    mvn -q -DskipTests package

# Build the native binary for the host OS/arch  -> target/anatomist
native:
    mvn -Pnative -DskipTests package
    @echo
    @file {{NATIVE_BIN}}
    @ls -lh {{NATIVE_BIN}}

# Build a Linux amd64 native binary inside a CentOS 7.9 container
# (use this from macOS to produce a binary that runs on RHEL/CentOS/Ubuntu servers)
native-linux-amd64:
    ./docker/build-linux-amd64.sh

# Rebuild the Docker build image from scratch (forces yum + GraalVM re-download)
native-linux-amd64-rebuild:
    ./docker/build-linux-amd64.sh --rebuild-image

# Install the native binary to ~/.local/bin (or $ANATOMIST_INSTALL_DIR)
install: native
    ./docker/install-local.sh {{NATIVE_BIN}}

# Install a specific binary (e.g. the linux amd64 cross-build)
install-from BIN:
    ./docker/install-local.sh {{BIN}}

# Uninstall from ~/.local/bin (restores .bak if present)
uninstall:
    @if [[ -f "{{INSTALL_DIR}}/anatomist.bak" ]]; then \
        mv "{{INSTALL_DIR}}/anatomist.bak" "{{INSTALL_DIR}}/anatomist"; \
        echo "Restored previous install from anatomist.bak"; \
    elif [[ -f "{{INSTALL_DIR}}/anatomist" ]]; then \
        rm -f "{{INSTALL_DIR}}/anatomist"; \
        echo "Removed {{INSTALL_DIR}}/anatomist"; \
    else \
        echo "Nothing to uninstall at {{INSTALL_DIR}}/anatomist"; \
    fi

# ─────────────────────────────────────────── tests ────────────────────────────────────────────

# Unit tests
test:
    mvn test

# Integration tests (anything ending in *IT)
it:
    mvn test -Dtest='*IT'

# One specific test class or method
test-one PATTERN:
    mvn test -Dtest='{{PATTERN}}'

# Full regression (unit + IT). Same gate used before each merge.
test-all:
    mvn -q clean test
    mvn -q test -Dtest='IndexCommandIT,QueryServiceIT,GoldenFileIT,MicroFixtureIT,EnrichQueryIT,EnrichCommandIT,AnnotateCommandIT,IndexDocsCommandIT,PicocliCodegenIT,JdkTypeCatalogBuilderIT,JdkTypeCatalogE2EIT,AsmVsJavassistDiffIT,AsmVsJavassistJarDiffIT,CommonsLangSmokeIT,JavaParserFactoryEmbeddedJdkIT,EmbeddedJdkSolverEndToEndIT'

# Refresh golden files after an intentional output-format change
golden-update:
    mvn test -Dtest=GoldenFileIT -Dgolden.update=true

# ─────────────────────────────────── smoke (run binary end-to-end) ─────────────────────────────

# Index the bundled fixture into /tmp/anatomist-smoke.db using the native binary
index-fixture:
    rm -f {{SMOKE_DB}}
    {{NATIVE_BIN}} index {{FIXTURE}} \
        --project-source {{SOURCES}} \
        --no-classpath \
        --output {{SMOKE_DB}}

# Run several query commands against the smoke index. Run `just index-fixture` first.
smoke: index-fixture
    @echo "=== search ==="
    {{NATIVE_BIN}} search OrderService --index {{SMOKE_DB}} | head -15
    @echo "=== callees-of ==="
    {{NATIVE_BIN}} callees-of com.example.shop.service.OrderService#createOrder --depth 2 --index {{SMOKE_DB}} | head -20
    @echo "=== context ==="
    {{NATIVE_BIN}} context com.example.shop.service.OrderService --index {{SMOKE_DB}} | head -15
    @echo "=== hierarchy ==="
    {{NATIVE_BIN}} hierarchy com.example.shop.service.OrderService --index {{SMOKE_DB}} | head -15
    @echo "=== enrich ==="
    {{NATIVE_BIN}} enrich --node OrderService --index {{SMOKE_DB}} 2>&1 | head -10

# Smoke the installed binary on $PATH (after `just install`).
smoke-installed:
    @command -v anatomist >/dev/null || { echo "anatomist not on PATH. Run \`just install\`."; exit 1; }
    rm -f {{SMOKE_DB}}
    anatomist index {{FIXTURE}} \
        --project-source {{SOURCES}} \
        --no-classpath \
        --output {{SMOKE_DB}}
    anatomist search OrderService --index {{SMOKE_DB}} | head -10

# Compare native-binary startup latency vs JVM jar (3 runs each).
bench-startup: index-fixture jar
    #!/usr/bin/env bash
    set -u
    echo "=== native binary ==="
    for i in 1 2 3; do
        /usr/bin/time -p {{NATIVE_BIN}} search OrderService --index {{SMOKE_DB}} > /dev/null 2>/tmp/_t
        echo "  run $i: $(grep real /tmp/_t)"
    done
    echo "=== JVM jar ==="
    if [[ ! -f /tmp/cp.txt ]]; then
        mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
    fi
    CP=$(cat /tmp/cp.txt)
    for i in 1 2 3; do
        /usr/bin/time -p java -cp "target/classes:$CP" com.anatomist.cli.AnatomistCli \
            search OrderService --index {{SMOKE_DB}} > /dev/null 2>/tmp/_t
        echo "  run $i: $(grep real /tmp/_t)"
    done

# ─────────────────────────────────────────── clean ────────────────────────────────────────────

# Remove build outputs (keeps the Linux build's .m2-cache; that's a slow rebuild).
clean:
    mvn clean

# Remove everything including the Linux build's Maven cache and produced artifacts.
clean-all: clean
    rm -rf .m2-cache target/native-agent-config dist/anatomist-*
