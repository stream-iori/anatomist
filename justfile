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
RELEASED_BIN := env_var_or_default("ANATOMIST_BASELINE_BIN", env_var("HOME") + "/.local/bin/anatomist")
SKILL_FILE := ROOT + "/SKILL.md"
JURY_BIN := env_var_or_default("JURY_BIN", "jury")
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
    set -e
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
    mvn -q -DskipTests package

# Build the native binary for the host OS/arch  -> target/anatomist
native:
    #!/usr/bin/env bash
    set -e
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
    # A native executable embeds classes and the filtered version resource.
    # Do not reuse target/: it can otherwise publish a binary from an older
    # source/schema revision when Maven considers source timestamps current.
    mvn -Pnative -DskipTests clean package
    echo
    file {{NATIVE_BIN}}
    ls -lh {{NATIVE_BIN}}

# The parent release repository owns dist-bin/install.sh.  This source project
# only publishes its skill alongside its independently versioned binaries.
upload-dist-files:
    #!/usr/bin/env bash
    set -euo pipefail
    for asset in "{{SKILL_FILE}}:anatomist/SKILL.md"; do
      src="${asset%%:*}"
      name="${asset##*:}"
      test -f "$src"
      echo "Uploading $src"
      echo "  mode: {{UPLOAD_MODE}}"
      echo "  PUT:  {{UPLOAD_BASE}}/${name}"
      echo "  POST: {{UPLOAD_BASE}} (field={{UPLOAD_FIELD}}, filename=${name})"
      echo "  GET: {{DIST_BASE}}/${name}"
      case "{{UPLOAD_MODE}}" in
        put)
          curl --noproxy '*' \
              --retry 3 --retry-all-errors \
              --connect-timeout 10 --speed-time 30 --speed-limit 1024 \
              -fT "$src" "{{UPLOAD_BASE}}/${name}"
          ;;
        post|multipart)
          curl --noproxy '*' \
              --retry 3 --retry-all-errors \
              --connect-timeout 10 --speed-time 30 --speed-limit 1024 \
              -F "{{UPLOAD_FIELD}}=@${src};filename=${name}" "{{UPLOAD_BASE}}"
          ;;
        *)
          echo "ERROR: unsupported ANATOMIST_UPLOAD_MODE={{UPLOAD_MODE}} (use put or post)"
          exit 2
          ;;
      esac
      echo
      curl --noproxy '*' -fsSI "{{DIST_BASE}}/${name}" | sed -n '1,8p'
      echo
    done

# Build native, then upload it to the nginx dist-bin mirror.
upload-native: native upload-dist-files
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
upload-linux-amd64: native-linux-amd64 upload-dist-files
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
    set -e
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
    mvn test

# Adversarial regex/glob complexity guards (excluded from the default suite)
regex-perf:
    #!/usr/bin/env bash
    set -e
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
    mvn -Pregex-perf test

# 100k-seed semantic stream probe in a 128 MiB child JVM (excluded by default)
stream-stress:
    #!/usr/bin/env bash
    set -e
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
    mvn -Danatomist.test.groups=stream-performance \
        -Danatomist.test.excludedGroups= \
        -Dtest=SemanticStreamStressIT test

# Pinned, hand-reviewed Commons Lang call-resolution precision/recall gate
quality-real:
    #!/usr/bin/env bash
    set -euo pipefail
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
    mvn -Danatomist.test.groups=quality-real \
        -Danatomist.test.excludedGroups= \
        -Dtest=CommonsLangResolutionQualityIT test

# Product regression: compare 0.14 aggregate commands with 1.0 Shell and fused workflows
bench-query-refactor: native
    python3 scripts/benchmark-query-refactor.py \
        --baseline-bin "{{RELEASED_BIN}}" \
        --candidate-bin "{{NATIVE_BIN}}"

# Reproducible product regression when the released binary is unavailable
bench-query-refactor-git BASELINE_REF="dd2e575": native
    python3 scripts/benchmark-query-refactor.py \
        --baseline-ref "{{BASELINE_REF}}" \
        --candidate-bin "{{NATIVE_BIN}}"

# Same-contract comparison with schema-isolated indexes and semantic digests
bench-semantic-pipeline BASELINE_REF="00e0dc7": native
    python3 scripts/benchmark-semantic-pipeline.py \
        --baseline-ref "{{BASELINE_REF}}" \
        --candidate-bin "{{NATIVE_BIN}}"

# Schema v24 vs v25 storage, full/incremental indexing, and query baseline on Anatomist itself
bench-storage-p1 BASELINE_REF="a2c5ce7cbc7fe84e25ff7ca8907c365fbf2f21d7": native
    python3 scripts/benchmark-storage-p1.py \
        --baseline-ref "{{BASELINE_REF}}" \
        --candidate-bin "{{NATIVE_BIN}}"

# Integration tests (anything ending in *IT)
it:
    #!/usr/bin/env bash
    set -e
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
    mvn test -Dtest='*IT'

# Exercise config selection, scan policy, CLI override, and incremental fallback through the JVM jar.
config-e2e-jvm: jar
    bash scripts/config-e2e.sh jvm

# Exercise the same config contract through the native binary.
config-e2e-native: native
    bash scripts/config-e2e.sh native {{NATIVE_BIN}}

# Exercise extension lifecycle, producer ownership, Spring XML and records through the JVM jar.
extension-e2e-jvm: jar
    bash scripts/extension-e2e.sh jvm

# Run the same lifecycle through the native binary and diff its query JSON against the JVM jar.
extension-e2e-native: jar native
    bash scripts/extension-e2e.sh native {{NATIVE_BIN}}

# One specific test class or method
test-one PATTERN:
    #!/usr/bin/env bash
    set -e
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
    mvn test -Dtest='{{PATTERN}}'

# Full regression (unit + IT). Same gate used before each merge.
test-all:
    #!/usr/bin/env bash
    set -e
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
    mvn -q clean test
    mvn -q test -Dtest='IndexCommandIT,QueryServiceIT,GoldenFileIT,MicroFixtureIT,EnrichQueryIT,EnrichCommandIT,AnnotateCommandIT,IndexDocsCommandIT,PicocliCodegenIT,JdkTypeCatalogBuilderIT,JdkTypeCatalogE2EIT,CommonsLangSmokeIT,JavaParserFactoryEmbeddedJdkIT,EmbeddedJdkSolverEndToEndIT'

# Validate optional Jury Agent E2E assets without invoking a model.
agent-e2e-contract:
    #!/usr/bin/env bash
    set -euo pipefail
    command -v "{{JURY_BIN}}" >/dev/null || { echo "Jury missing; set JURY_BIN=/path/to/jury" >&2; exit 2; }
    PYTHONPATH="{{ROOT}}/e2e" python3 -m unittest discover -s "{{ROOT}}/e2e" -p 'test_*.py'
    "{{JURY_BIN}}" suite validate "{{ROOT}}/e2e/jury-suites/smoke.yaml" --strict
    "{{JURY_BIN}}" suite validate "{{ROOT}}/e2e/jury-suites/complex.yaml" --strict

# Build, index, and query the complex fixture without invoking a model.
agent-e2e-fixture:
    #!/usr/bin/env bash
    set -euo pipefail
    test -x "{{NATIVE_BIN}}" || { echo "target/anatomist missing; run: just native" >&2; exit 2; }
    ANATOMIST_E2E_BIN="${ANATOMIST_E2E_BIN:-{{NATIVE_BIN}}}" \
      python3 "{{ROOT}}/e2e/validate_complex_fixture.py"

# Run all nine real-Agent evidence cases through Jury's Codex SDK runner.
agent-e2e-smoke: agent-e2e-contract agent-e2e-fixture
    #!/usr/bin/env bash
    set -euo pipefail
    test -x "{{NATIVE_BIN}}" || { echo "target/anatomist missing; run: just native" >&2; exit 2; }
    mkdir -p "{{ROOT}}/e2e/jury-runs"
    PATH="{{ROOT}}/target:${PATH}" \
      ZDOTDIR="{{ROOT}}/e2e/shell" \
      ANATOMIST_E2E_BIN="${ANATOMIST_E2E_BIN:-{{NATIVE_BIN}}}" \
      "{{JURY_BIN}}" suite run "{{ROOT}}/e2e/jury-suites/smoke.yaml" \
        --cwd "{{ROOT}}/e2e" \
        --runs-dir "{{ROOT}}/e2e/jury-runs" \
        --adapter anatomist_jury_adapter:create_adapter

# Run only the six complex multi-module cases.
agent-e2e-complex: agent-e2e-contract agent-e2e-fixture
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p "{{ROOT}}/e2e/jury-runs"
    PATH="{{ROOT}}/target:${PATH}" \
      ZDOTDIR="{{ROOT}}/e2e/shell" \
      ANATOMIST_E2E_BIN="${ANATOMIST_E2E_BIN:-{{NATIVE_BIN}}}" \
      "{{JURY_BIN}}" suite run "{{ROOT}}/e2e/jury-suites/complex.yaml" \
        --cwd "{{ROOT}}/e2e" \
        --runs-dir "{{ROOT}}/e2e/jury-runs" \
        --adapter anatomist_jury_adapter:create_adapter

# Refresh golden files after an intentional output-format change
golden-update:
    #!/usr/bin/env bash
    set -e
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
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

    preview search 15 {{NATIVE_BIN}} search OrderService --kind type --index {{SMOKE_DB}}
    preview overview 15 {{NATIVE_BIN}} overview --index {{SMOKE_DB}}

    {{NATIVE_BIN}} search OrderService --kind type --index {{SMOKE_DB}} >"$TMP/members-1"
    {{NATIVE_BIN}} resolve --unique --index {{SMOKE_DB}} <"$TMP/members-1" >"$TMP/members-2"
    {{NATIVE_BIN}} members --recursive --index {{SMOKE_DB}} <"$TMP/members-2" >"$TMP/members-3"
    echo "=== search | resolve | members ==="
    head -n 20 "$TMP/members-3"

    METHOD='com.example.shop.service.OrderService#createOrder(com.example.shop.domain.dto.CreateOrderRequest)'
    {{NATIVE_BIN}} resolve "$METHOD" --kind callable --exact --unique --index {{SMOKE_DB}} >"$TMP/calls-1"
    {{NATIVE_BIN}} calls --index {{SMOKE_DB}} <"$TMP/calls-1" >"$TMP/calls-2"
    {{NATIVE_BIN}} source --limit 20 --index {{SMOKE_DB}} <"$TMP/calls-2" >"$TMP/calls-4"
    {{NATIVE_BIN}} pipeline --index {{SMOKE_DB}} -- \
      resolve "$METHOD" --kind callable --exact --unique \
      --then calls --then source --limit 20 >"$TMP/calls-fused"
    cmp "$TMP/calls-4" "$TMP/calls-fused"
    echo "=== resolve | calls | source ==="
    head -n 20 "$TMP/calls-4"

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
# Indexes fixture with both, runs 1.0 terminal queries and real pipelines, diffs output.
native-smoke: jar native
    #!/usr/bin/env bash
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
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

    run_cli() {
        local variant="$1"; shift
        if [[ "$variant" == jvm ]]; then "${JVM[@]}" "$@"; else "$NATIVE" "$@"; fi
    }
    normalize() {
        local db="$1"
        sed -E \
          -e 's/"index_revision_id"[[:space:]]*:[[:space:]]*"[^"]*"/"index_revision_id":"<REVISION>"/g' \
          -e "s|$db|<INDEX>|g"
    }
    run_suite() {
        local variant="$1" db="$2" prefix="$3"
        local method='com.example.shop.service.OrderService#createOrder(com.example.shop.domain.dto.CreateOrderRequest)'
        run_cli "$variant" search OrderService --kind type --index "$db" >"$prefix-search"
        run_cli "$variant" overview --deps-only --index "$db" >"$prefix-overview"
        run_cli "$variant" declarations-of --file service/src/main/java/com/example/shop/service/OrderService.java --index "$db" >"$prefix-declarations"
        run_cli "$variant" search OrderService --kind type --index "$db" |
          run_cli "$variant" resolve --unique --index "$db" |
          run_cli "$variant" members --recursive --index "$db" >"$prefix-members"
        run_cli "$variant" resolve "$method" --kind callable --exact --unique --index "$db" |
          run_cli "$variant" calls --index "$db" |
          run_cli "$variant" source --limit 20 --index "$db" >"$prefix-calls"
        run_cli "$variant" pipeline --index "$db" -- \
          resolve "$method" --kind callable --exact --unique \
          --then calls --then source --limit 20 >"$prefix-calls-fused"
        cmp "$prefix-calls" "$prefix-calls-fused"
        for case in search overview declarations members calls calls-fused; do
          normalize "$db" <"$prefix-$case" >"$prefix-$case.normalized"
        done
    }

    run_suite jvm "$DB_JVM" "${OUT}-jvm"
    run_suite native "$DB_NAT" "${OUT}-native"
    FAIL=0
    for case in search overview declarations members calls calls-fused; do
        echo "--- $case ---"
        if diff -u "${OUT}-jvm-$case.normalized" "${OUT}-native-$case.normalized"; then
            echo PASS
        else
            FAIL=1
        fi
    done

    rm -f "$DB_JVM" "$DB_NAT" "${OUT}"-jvm-* "${OUT}"-native-*
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

# Print the Maven project version after CI-friendly version interpolation.
version:
    mvn -q help:evaluate -Dexpression=project.version -DforceStdout

# Build a release-version macOS native binary without changing pom.xml.
release-native VERSION:
    #!/usr/bin/env bash
    set -eo pipefail
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
    set -u

    REL_VERSION="{{VERSION}}"
    if ! echo "$REL_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
        echo "ERROR: Invalid version '$REL_VERSION'. Must be semver (e.g. 0.1.0)"
        exit 1
    fi

    # A release candidate must be rebuilt from a clean target directory; see
    # the same invariant in `native` above.
    mvn -Pnative -DskipTests -Drevision="${REL_VERSION}" -Dchangelist= clean package
    {{NATIVE_BIN}} --version | grep -Fx "anatomist ${REL_VERSION}"
    echo
    file {{NATIVE_BIN}}
    ls -lh {{NATIVE_BIN}}

# Separate from `release` and GitHub Release publishing; run only when the internal mirror should update.
# Manually upload a release-version macOS native binary + static files to the internal dist-bin mirror.
release-upload VERSION:
    #!/usr/bin/env bash
    set -euo pipefail

    REL_VERSION="{{VERSION}}"
    if ! echo "$REL_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
        echo "ERROR: Invalid version '$REL_VERSION'. Must be semver (e.g. 0.1.0)"
        exit 1
    fi

    just release-native "$REL_VERSION"
    just upload-dist-files

    test -x "{{NATIVE_BIN}}"
    echo "Uploading {{NATIVE_BIN}}"
    echo "  version: ${REL_VERSION}"
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

# Cut a git release tag, then bump <revision> to the next SNAPSHOT line.
# Usage: just release        (uses <revision>, e.g. 0.1.0 -> v0.1.0)
#        just release 0.2.0  (override version explicitly)
release VERSION="":
    #!/usr/bin/env bash
    set -eo pipefail
    export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
    source "${SDKMAN_DIR}/bin/sdkman-init.sh"
    sdk env
    set -u

    # Determine release version
    if [[ -n "{{VERSION}}" ]]; then
        REL_VERSION="{{VERSION}}"
    else
        REL_VERSION=$(grep '<revision>' pom.xml | head -1 | sed 's/.*<revision>//;s/<\/revision>.*//')
    fi

    # Validate semver format
    if ! echo "$REL_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
        echo "ERROR: Invalid version '$REL_VERSION'. Must be semver (e.g. 0.1.0)"
        exit 1
    fi

    echo "=== Releasing v${REL_VERSION} ==="

    # Record an exact release version in the tagged commit. The following
    # development commit restores the -SNAPSHOT changelist.
    perl -0pi -e "s|<revision>.*?</revision>|<revision>${REL_VERSION}</revision>|s" pom.xml
    perl -0pi -e "s|<changelist>.*?</changelist>|<changelist></changelist>|s" pom.xml
    echo "  pom.xml revision -> ${REL_VERSION}"

    # Build release-version native.
    echo "  Building native binary..."
    mvn -Pnative -DskipTests -Drevision="${REL_VERSION}" -Dchangelist= clean package -q
    {{NATIVE_BIN}} --version | grep -Fx "anatomist ${REL_VERSION}"

    # Copy to release-dist
    mkdir -p release-dist
    cp {{NATIVE_BIN}} release-dist/anatomist-darwin-aarch64
    echo "  Copied to release-dist/anatomist-darwin-aarch64"

    # Commit and tag. Keep release-dist as a local artifact only; Git hosting
    # rejects native binaries and CI/GitHub Releases should publish them.
    git add pom.xml
    if git diff --cached --quiet; then
        git commit --allow-empty -m "release: v${REL_VERSION}"
    else
        git commit -m "release: v${REL_VERSION}"
    fi
    git tag "v${REL_VERSION}"
    echo "  Tagged v${REL_VERSION}"

    # Bump to next SNAPSHOT
    IFS='.' read -r MAJOR MINOR PATCH <<< "$REL_VERSION"
    NEXT_VERSION="${MAJOR}.$((MINOR + 1)).0-SNAPSHOT"
    NEXT_REVISION="${MAJOR}.$((MINOR + 1)).0"
    perl -0pi -e "s|<revision>.*?</revision>|<revision>${NEXT_REVISION}</revision>|s" pom.xml
    perl -0pi -e "s|<changelist>.*?</changelist>|<changelist>-SNAPSHOT</changelist>|s" pom.xml
    git add pom.xml
    git commit -m "chore: bump to ${NEXT_VERSION}"
    echo "  pom.xml revision -> ${NEXT_REVISION} (${NEXT_VERSION})"
    echo "=== Done. Run 'git push && git push --tags' to publish ==="

# ─────────────────────────────────────────── clean ────────────────────────────────────────────

# Remove build outputs (keeps the Linux build's .m2-cache; that's a slow rebuild).
clean:
    mvn clean

# Remove everything including the Linux build's Maven cache and produced artifacts.
clean-all: clean
    rm -rf .m2-cache target/native-agent-config dist/anatomist-*
