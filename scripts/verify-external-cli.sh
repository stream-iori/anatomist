#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="${PROJECT:-${ANATOMIST_EXTERNAL_PROJECT:-/Users/stream/codes/antcodes/ipay/imerchantsettle}}"
DB="${DB:-/tmp/imerchantsettle-anatomist.db}"
JAR="${ANATOMIST_JAR:-$ROOT/target/anatomist.jar}"

if [[ "$PROJECT" == PROJECT=* ]]; then
    PROJECT="${PROJECT#PROJECT=}"
fi

export SDKMAN_DIR="${SDKMAN_DIR:-$HOME/.sdkman}"
if [[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
    set +u
    # shellcheck disable=SC1091
    source "$SDKMAN_DIR/bin/sdkman-init.sh"
    sdk use java 25.0.3-graal >/dev/null || true
    set -u
fi

if [[ ! -d "$PROJECT" ]]; then
    echo "ERROR: external project not found: $PROJECT" >&2
    exit 2
fi
if [[ ! -f "$JAR" ]]; then
    echo "ERROR: anatomist jar not found: $JAR" >&2
    echo "Run: just jar" >&2
    exit 2
fi

SOURCE_ROOTS=()
while IFS= read -r source_root; do
    SOURCE_ROOTS+=("$source_root")
done < <(find "$PROJECT" -path '*/src/main/java' -type d | sort)
if [[ ${#SOURCE_ROOTS[@]} -eq 0 ]]; then
    echo "ERROR: no src/main/java roots found under $PROJECT" >&2
    exit 2
fi
SOURCES="$(IFS=:; echo "${SOURCE_ROOTS[*]}")"

CLI=(java --enable-native-access=ALL-UNNAMED -jar "$JAR")

run_cli() {
    "${CLI[@]}" "$@" --index "$DB"
}

assert_contains() {
    local label="$1"
    local haystack="$2"
    local needle="$3"
    if [[ "$haystack" != *"$needle"* ]]; then
        echo "FAIL: $label missing '$needle'" >&2
        echo "$haystack" >&2
        exit 1
    fi
}

echo "=== Index external project ==="
echo "project: $PROJECT"
echo "db:      $DB"
echo "sources: ${#SOURCE_ROOTS[@]}"
rm -f "$DB"
"${CLI[@]}" index "$PROJECT" \
    --project-source "$SOURCES" \
    --no-classpath \
    --spring-xml \
    --output "$DB"

echo
echo "| Check | Result |"
echo "|---|---|"

out="$(run_cli implementors-of SettleApplyServiceV3 --recursive)"
assert_contains "SettleApplyServiceV3 facade" "$out" "SettleApplyServiceV3Impl"
printf '| %-34s | PASS |\n' "SettleApplyServiceV3 facade"

out="$(run_cli implementors-of SettleTaskExecuteService --recursive)"
assert_contains "SettleTaskExecuteService facade" "$out" "SettleTaskExecuteServiceImpl"
printf '| %-34s | PASS |\n' "SettleTaskExecuteService facade"

out="$(run_cli implementors-of ReconHandler --recursive)"
assert_contains "ReconHandler implementation" "$out" "AbstractReconHandler"
assert_contains "ReconHandler implementation" "$out" "ReconDetailCompareHandler"
assert_contains "ReconHandler implementation" "$out" "ReconDetailConfirmHandler"
printf '| %-34s | PASS |\n' "ReconHandler implementors"

out="$(run_cli context ReconHandler --with-callees=2)"
assert_contains "ReconHandler call graph" "$out" "AbstractReconHandler#handle"
assert_contains "ReconHandler call graph" "$out" "ReconDetailCompareHandler#doHandle"
assert_contains "ReconHandler call graph" "$out" "ReconDetailConfirmHandler#doHandle"
printf '| %-34s | PASS |\n' "ReconHandler call graph"

out="$(run_cli call-path TrafficEngineExecutor#execute TrafficSettleEngineDAO#queryEngineById --depth 3 --source-window=1)"
assert_contains "TrafficEngineExecutor DAO path" "$out" '"found" : true'
assert_contains "TrafficEngineExecutor DAO path" "$out" "queryEngineById"
printf '| %-34s | PASS |\n' "DAO forward call-path"

out="$(run_cli used-by TrafficSettleEngineDAO --limit 80)"
assert_contains "TrafficSettleEngineDAO reverse lookup" "$out" "queryEngineById"
assert_contains "TrafficSettleEngineDAO reverse lookup" "$out" "updateEngineById"
assert_contains "TrafficSettleEngineDAO reverse lookup" "$out" "selectByDateAndStatus"
assert_contains "TrafficSettleEngineDAO reverse lookup" "$out" "insert"
printf '| %-34s | PASS |\n' "DAO reverse used-by"

out="$(run_cli deps-of TrafficEngineExecutor --filter TrafficSettleEngineDAO --limit 80)"
assert_contains "TrafficEngineExecutor DAO deps" "$out" "queryEngineById"
assert_contains "TrafficEngineExecutor DAO deps" "$out" "updateEngineById"
printf '| %-34s | PASS |\n' "DAO deps-of"

out="$(run_cli field-access com.ipay.trafficcompare.engine.TrafficEngineExecutor#trafficSettleEngineDAO --limit 50)"
assert_contains "TrafficEngineExecutor DAO field reads" "$out" '"relation" : "READS"'
assert_contains "TrafficEngineExecutor DAO field reads" "$out" "execute"
assert_contains "TrafficEngineExecutor DAO field reads" "$out" "checkAndUpdateEngineExecuteResult"
printf '| %-34s | PASS |\n' "DAO field-access"

echo
echo "external CLI verification PASSED"
