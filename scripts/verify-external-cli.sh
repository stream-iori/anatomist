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
    sdk env >/dev/null
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

out="$(run_cli search SettleApplyServiceV3 --kind type | run_cli resolve --unique | run_cli runtime-implementations)"
assert_contains "SettleApplyServiceV3 facade" "$out" "SettleApplyServiceV3Impl"
printf '| %-34s | PASS |\n' "SettleApplyServiceV3 facade"

out="$(run_cli search SettleTaskExecuteService --kind type | run_cli resolve --unique | run_cli runtime-implementations)"
assert_contains "SettleTaskExecuteService facade" "$out" "SettleTaskExecuteServiceImpl"
printf '| %-34s | PASS |\n' "SettleTaskExecuteService facade"

out="$(run_cli search ReconHandler --kind type | run_cli resolve --unique | run_cli runtime-implementations)"
assert_contains "ReconHandler implementation" "$out" "AbstractReconHandler"
assert_contains "ReconHandler implementation" "$out" "ReconDetailCompareHandler"
assert_contains "ReconHandler implementation" "$out" "ReconDetailConfirmHandler"
printf '| %-34s | PASS |\n' "ReconHandler implementors"

out="$(run_cli resolve ReconHandler --kind type --unique | run_cli runtime-implementations)"
assert_contains "ReconHandler call graph" "$out" "AbstractReconHandler#handle"
assert_contains "ReconHandler call graph" "$out" "ReconDetailCompareHandler#doHandle"
assert_contains "ReconHandler call graph" "$out" "ReconDetailConfirmHandler#doHandle"
printf '| %-34s | PASS |\n' "ReconHandler call graph"

out="$(run_cli resolve TrafficEngineExecutor#execute --kind callable | run_cli trace --to TrafficSettleEngineDAO#queryEngineById --max-depth 3 --dispatch possible)"
assert_contains "TrafficEngineExecutor DAO path" "$out" 'trace_step'
assert_contains "TrafficEngineExecutor DAO path" "$out" "queryEngineById"
printf '| %-34s | PASS |\n' "DAO forward trace"

out="$(run_cli resolve TrafficSettleEngineDAO --kind type --unique | run_cli references --direction incoming --limit 80)"
assert_contains "TrafficSettleEngineDAO reverse lookup" "$out" "queryEngineById"
assert_contains "TrafficSettleEngineDAO reverse lookup" "$out" "updateEngineById"
assert_contains "TrafficSettleEngineDAO reverse lookup" "$out" "selectByDateAndStatus"
assert_contains "TrafficSettleEngineDAO reverse lookup" "$out" "insert"
printf '| %-34s | PASS |\n' "DAO reverse references"

out="$(run_cli resolve TrafficEngineExecutor --kind type --unique | run_cli members --recursive --limit 200 | run_cli references --direction outgoing --limit 80)"
assert_contains "TrafficEngineExecutor DAO deps" "$out" "queryEngineById"
assert_contains "TrafficEngineExecutor DAO deps" "$out" "updateEngineById"
printf '| %-34s | PASS |\n' "DAO outgoing references"

out="$(run_cli resolve com.ipay.trafficcompare.engine.TrafficEngineExecutor#trafficSettleEngineDAO --kind value --unique | run_cli accesses --limit 50)"
assert_contains "TrafficEngineExecutor DAO field reads" "$out" 'access_site'
assert_contains "TrafficEngineExecutor DAO field reads" "$out" "execute"
assert_contains "TrafficEngineExecutor DAO field reads" "$out" "checkAndUpdateEngineExecuteResult"
printf '| %-34s | PASS |\n' "DAO accesses"

echo
echo "external CLI verification PASSED"
