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
UPLOAD_BASE := env_var_or_default("ANATOMIST_UPLOAD_BASE", "http://6.12.3.250:8100/upload")
DIST_BASE   := env_var_or_default("ANATOMIST_DIST_BASE", "http://6.12.3.250:8100/dist-bin")
DIST_NAME   := env_var_or_default("ANATOMIST_DIST_NAME", "anatomist-darwin-aarch64")
UPLOAD_MODE := env_var_or_default("ANATOMIST_UPLOAD_MODE", "put")
UPLOAD_FIELD := env_var_or_default("ANATOMIST_UPLOAD_FIELD", "file")

# Default: list recipes
default:
    @just --list

# ─────────────────────────────────────────── build ────────────────────────────────────────────

# Compile Java sources (skip tests)
compile:
    mvn -q compile

# Build the fat JVM jar -> target/anatomist.jar
jar:
    #!/usr/bin/env bash
    export SDKMAN_DIR="${HOME}/.sdkman"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh" || true
    sdk use java 25.0.3-graal || true
    mvn -q -DskipTests package

# Build the native binary for the host OS/arch  -> target/anatomist
native:
    #!/usr/bin/env bash
    export SDKMAN_DIR="${HOME}/.sdkman"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh" || true
    sdk use java 25.0.3-graal || true
    set -e
    mvn -Pnative -DskipTests package
    echo
    file {{NATIVE_BIN}}
    ls -lh {{NATIVE_BIN}}

# Build native, then upload it to the nginx dist-bin mirror.
upload-native: native
    #!/usr/bin/env bash
    set -euo pipefail
    test -x "{{NATIVE_BIN}}"
    echo "Uploading {{NATIVE_BIN}}"
    echo "  mode: {{UPLOAD_MODE}}"
    echo "  PUT:  {{UPLOAD_BASE}}/{{DIST_NAME}}"
    echo "  POST: {{UPLOAD_BASE}} (field={{UPLOAD_FIELD}}, filename={{DIST_NAME}})"
    echo "  GET: {{DIST_BASE}}/{{DIST_NAME}}"
    case "{{UPLOAD_MODE}}" in
      put)
        curl --noproxy '*' \
            --retry 3 --retry-all-errors \
            --connect-timeout 10 --speed-time 30 --speed-limit 1024 \
            -fT "{{NATIVE_BIN}}" "{{UPLOAD_BASE}}/{{DIST_NAME}}"
        ;;
      post|multipart)
        curl --noproxy '*' \
            --retry 3 --retry-all-errors \
            --connect-timeout 10 --speed-time 30 --speed-limit 1024 \
            -F "{{UPLOAD_FIELD}}=@{{NATIVE_BIN}};filename={{DIST_NAME}}" "{{UPLOAD_BASE}}"
        ;;
      *)
        echo "ERROR: unsupported ANATOMIST_UPLOAD_MODE={{UPLOAD_MODE}} (use put or post)"
        exit 2
        ;;
    esac
    echo
    curl --noproxy '*' -fsSI "{{DIST_BASE}}/{{DIST_NAME}}" | sed -n '1,8p'

# Build a Linux amd64 native binary inside a CentOS 7.9 container
# (use this from macOS to produce a binary that runs on RHEL/CentOS/Ubuntu servers)
native-linux-amd64:
    ./docker/build-linux-amd64.sh

# Build the Linux amd64 native binary, then upload it to the nginx dist-bin mirror.
upload-linux-amd64: native-linux-amd64
    #!/usr/bin/env bash
    set -euo pipefail
    test -x "{{NATIVE_BIN}}"
    DIST_NAME="anatomist-linux-amd64"
    echo "Uploading {{NATIVE_BIN}}"
    echo "  mode: {{UPLOAD_MODE}}"
    echo "  PUT:  {{UPLOAD_BASE}}/${DIST_NAME}"
    echo "  POST: {{UPLOAD_BASE}} (field={{UPLOAD_FIELD}}, filename=${DIST_NAME})"
    echo "  GET: {{DIST_BASE}}/${DIST_NAME}"
    case "{{UPLOAD_MODE}}" in
      put)
        curl --noproxy '*' \
            --retry 3 --retry-all-errors \
            --connect-timeout 10 --speed-time 30 --speed-limit 1024 \
            -fT "{{NATIVE_BIN}}" "{{UPLOAD_BASE}}/${DIST_NAME}"
        ;;
      post|multipart)
        curl --noproxy '*' \
            --retry 3 --retry-all-errors \
            --connect-timeout 10 --speed-time 30 --speed-limit 1024 \
            -F "{{UPLOAD_FIELD}}=@{{NATIVE_BIN}};filename=${DIST_NAME}" "{{UPLOAD_BASE}}"
        ;;
      *)
        echo "ERROR: unsupported ANATOMIST_UPLOAD_MODE={{UPLOAD_MODE}} (use put or post)"
        exit 2
        ;;
    esac
    echo
    curl --noproxy '*' -fsSI "{{DIST_BASE}}/${DIST_NAME}" | sed -n '1,8p'

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
    #!/usr/bin/env bash
    export SDKMAN_DIR="${HOME}/.sdkman"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh" || true
    sdk use java 25.0.3-graal || true
    mvn test

# Integration tests (anything ending in *IT)
it:
    #!/usr/bin/env bash
    export SDKMAN_DIR="${HOME}/.sdkman"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh" || true
    sdk use java 25.0.3-graal || true
    mvn test -Dtest='*IT'

# One specific test class or method
test-one PATTERN:
    #!/usr/bin/env bash
    export SDKMAN_DIR="${HOME}/.sdkman"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh" || true
    sdk use java 25.0.3-graal || true
    mvn test -Dtest='{{PATTERN}}'

# Full regression (unit + IT). Same gate used before each merge.
test-all:
    #!/usr/bin/env bash
    export SDKMAN_DIR="${HOME}/.sdkman"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh" || true
    sdk use java 25.0.3-graal || true
    mvn -q clean test
    mvn -q test -Dtest='IndexCommandIT,QueryServiceIT,GoldenFileIT,MicroFixtureIT,EnrichQueryIT,EnrichCommandIT,AnnotateCommandIT,IndexDocsCommandIT,PicocliCodegenIT,JdkTypeCatalogBuilderIT,JdkTypeCatalogE2EIT,CommonsLangSmokeIT,JavaParserFactoryEmbeddedJdkIT,EmbeddedJdkSolverEndToEndIT'

# Refresh golden files after an intentional output-format change
golden-update:
    #!/usr/bin/env bash
    export SDKMAN_DIR="${HOME}/.sdkman"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh" || true
    sdk use java 25.0.3-graal || true
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
    #!/usr/bin/env bash
    set -euo pipefail
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT

    preview() {
        local label="$1"
        local lines="$2"
        shift 2
        local out="$TMP/${label//[^A-Za-z0-9_]/_}.out"
        echo "=== $label ==="
        if ! "$@" > "$out" 2>&1; then
            cat "$out"
            return 1
        fi
        head -n "$lines" "$out"
    }

    preview search 15 {{NATIVE_BIN}} search OrderService --index {{SMOKE_DB}}
    preview callees-of 20 {{NATIVE_BIN}} callees-of com.example.shop.service.OrderService#createOrder --depth 2 --index {{SMOKE_DB}}
    preview context 15 {{NATIVE_BIN}} context com.example.shop.service.OrderService --index {{SMOKE_DB}}
    preview hierarchy 15 {{NATIVE_BIN}} hierarchy com.example.shop.service.OrderService --index {{SMOKE_DB}}
    preview enrich 20 {{NATIVE_BIN}} context --enrich OrderService --index {{SMOKE_DB}}

# Smoke the installed binary on $PATH (after `just install`).
smoke-installed:
    @command -v anatomist >/dev/null || { echo "anatomist not on PATH. Run \`just install\`."; exit 1; }
    rm -f {{SMOKE_DB}}
    anatomist index {{FIXTURE}} \
        --project-source {{SOURCES}} \
        --no-classpath \
        --output {{SMOKE_DB}}
    anatomist search OrderService --index {{SMOKE_DB}} | head -10

# Verify native binary produces identical JSON output to JVM jar.
# Indexes fixture with both, runs 6 query commands, diffs output.
native-smoke: jar native
    #!/usr/bin/env bash
    export SDKMAN_DIR="${HOME}/.sdkman"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh" || true
    sdk use java 25.0.3-graal || true
    set -eo pipefail
    JVM=(java --enable-native-access=ALL-UNNAMED -jar "{{ROOT}}/target/anatomist.jar")
    NATIVE="{{NATIVE_BIN}}"
    DB_JVM="/tmp/anatomist-native-smoke-jvm.db"
    DB_NAT="/tmp/anatomist-native-smoke-native.db"
    SOURCES="{{SOURCES}}"
    FIXTURE="{{FIXTURE}}"
    OUT="/tmp/anatomist-native-smoke"
    JVM_LOG="${OUT}-jvm-index.log"
    NATIVE_LOG="${OUT}-native-index.log"

    native_diagnostics() {
        echo "=== Native diagnostics ==="
        file "$NATIVE" || true
        ls -lh "$NATIVE" || true
        if command -v codesign >/dev/null 2>&1; then
            codesign -dv --verbose=4 "$NATIVE" 2>&1 || true
        fi
        if command -v xattr >/dev/null 2>&1; then
            xattr -l "$NATIVE" 2>&1 || true
        fi
        if command -v spctl >/dev/null 2>&1; then
            spctl --assess --type execute -vv "$NATIVE" 2>&1 || true
        fi
    }

    echo "=== Indexing with JVM jar ==="
    rm -f "$DB_JVM"
    "${JVM[@]}" index "$FIXTURE" --project-source "$SOURCES" --no-classpath --output "$DB_JVM" >"$JVM_LOG" 2>&1 || {
        rc=$?
        echo "JVM index failed with exit code $rc"
        cat "$JVM_LOG"
        exit "$rc"
    }
    cat "$JVM_LOG"

    echo "=== Indexing with native binary ==="
    rm -f "$DB_NAT"
    "$NATIVE" index "$FIXTURE" --project-source "$SOURCES" --no-classpath --output "$DB_NAT" >"$NATIVE_LOG" 2>&1 || {
        rc=$?
        echo "native index failed with exit code $rc"
        cat "$NATIVE_LOG"
        native_diagnostics
        exit "$rc"
    }
    cat "$NATIVE_LOG"

    CMDS=(
        "search OrderService"
        "callees-of com.example.shop.service.OrderService#createOrder --depth 2"
        "context com.example.shop.service.OrderService"
        "deps-of com.example.shop.service.OrderService --limit 50"
        "overview --deps-only"
        "hierarchy com.example.shop.service.OrderService"
    )

    FAIL=0
    for cmd in "${CMDS[@]}"; do
        echo "--- $cmd ---"
        read -r -a args <<< "$cmd"
        "${JVM[@]}" "${args[@]}" --index "$DB_JVM" 2>/dev/null | sed 's/"query"[[:space:]]*:[[:space:]]*"[^"]*",*//' > "${OUT}-jvm.json"
        "$NATIVE" "${args[@]}" --index "$DB_NAT" 2>/dev/null | sed 's/"query"[[:space:]]*:[[:space:]]*"[^"]*",*//' > "${OUT}-native.json"
        if ! diff -q "${OUT}-jvm.json" "${OUT}-native.json" > /dev/null 2>&1; then
            echo "FAIL: output differs for: $cmd"
            diff "${OUT}-jvm.json" "${OUT}-native.json" | head -20
            FAIL=1
        else
            echo "PASS"
        fi
    done

    rm -f "$DB_JVM" "$DB_NAT" "${OUT}-jvm.json" "${OUT}-native.json"
    if [[ $FAIL -ne 0 ]]; then
        echo "native-smoke FAILED: native binary output differs from JVM jar"
        exit 1
    fi
    echo "native-smoke PASSED: all outputs identical"

# Opt-in smoke against a large external project. Not part of default CI.
external-cli PROJECT="/Users/stream/codes/antcodes/ipay/imerchantsettle": jar
    PROJECT="{{PROJECT}}" scripts/verify-external-cli.sh

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

# ─────────────────────────────────────────── release ──────────────────────────────────────────

# Cut a release: build native, copy to release-dist/, commit, tag, bump to next SNAPSHOT.
# Usage: just release        (uses version from pom.xml, e.g. 0.1.0-SNAPSHOT -> v0.1.0)
#        just release 0.2.0  (override version explicitly)
release VERSION="":
    #!/usr/bin/env bash
    set -euo pipefail
    export SDKMAN_DIR="${HOME}/.sdkman"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh" || true
    sdk use java 25.0.3-graal || true

    # Determine release version
    if [[ -n "{{VERSION}}" ]]; then
        REL_VERSION="{{VERSION}}"
    else
        REL_VERSION=$(grep '<version>' pom.xml | head -1 | sed 's/.*<version>//;s/<\/version>.*//' | sed 's/-SNAPSHOT//')
    fi

    # Validate semver format
    if ! echo "$REL_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
        echo "ERROR: Invalid version '$REL_VERSION'. Must be semver (e.g. 0.1.0)"
        exit 1
    fi

    echo "=== Releasing v${REL_VERSION} ==="

    # Update pom.xml to release version
    sed -i '' "0,/<version>.*<\/version>/s|<version>.*</version>|<version>${REL_VERSION}</version>|" pom.xml
    echo "  pom.xml -> ${REL_VERSION}"

    # Build native
    echo "  Building native binary..."
    mvn -Pnative -DskipTests package -q

    # Copy to release-dist
    mkdir -p release-dist
    cp {{NATIVE_BIN}} release-dist/anatomist-darwin-aarch64
    echo "  Copied to release-dist/anatomist-darwin-aarch64"

    # Commit and tag
    git add pom.xml release-dist/anatomist-darwin-aarch64
    git commit -m "release: v${REL_VERSION}"
    git tag "v${REL_VERSION}"
    echo "  Tagged v${REL_VERSION}"

    # Bump to next SNAPSHOT
    IFS='.' read -r MAJOR MINOR PATCH <<< "$REL_VERSION"
    NEXT_VERSION="${MAJOR}.$((MINOR + 1)).0-SNAPSHOT"
    sed -i '' "0,/<version>.*<\/version>/s|<version>.*</version>|<version>${NEXT_VERSION}</version>|" pom.xml
    git add pom.xml
    git commit -m "chore: bump to ${NEXT_VERSION}"
    echo "  pom.xml -> ${NEXT_VERSION}"
    echo "=== Done. Run 'git push && git push --tags' to publish ==="

# ─────────────────────────────────────────── clean ────────────────────────────────────────────

# Remove build outputs (keeps the Linux build's .m2-cache; that's a slow rebuild).
clean:
    mvn clean

# Remove everything including the Linux build's Maven cache and produced artifacts.
clean-all: clean
    rm -rf .m2-cache target/native-agent-config dist/anatomist-*
