#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-jvm}"
NATIVE_BIN="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
if [[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
  set +u
  # shellcheck disable=SC1091
  source "$SDKMAN_DIR/bin/sdkman-init.sh"
  sdk env >/dev/null
  set -u
fi
cp -R "$ROOT/fixtures/extension-lifecycle" "$WORK/project"
cp -R "$ROOT/fixtures/lombok-sample/src/main/java/sample" "$WORK/project/src/main/java/"

JVM=(java --enable-native-access=ALL-UNNAMED -jar "$ROOT/target/anatomist.jar")
if [[ "$MODE" == native ]]; then
  test -x "$NATIVE_BIN"
  RUNNER=("$NATIVE_BIN")
else
  RUNNER=("${JVM[@]}")
fi

DB="$WORK/index.db"
"${RUNNER[@]}" index "$WORK/project" --no-classpath --spring-xml --lombok ast --output "$DB" >/dev/null

"${RUNNER[@]}" search Account --kind RECORD --index "$DB" | grep -q '"producer_id":"java-core"'
"${RUNNER[@]}" resolve 'example.AccountReader#read(example.Account)' --kind callable --exact --unique --index "$DB" |
  "${RUNNER[@]}" calls --index "$DB" | grep -q 'example.Account#id()'
"${RUNNER[@]}" search --name beans-a.xml --kind artifact --index "$DB" |
  "${RUNNER[@]}" resolve --unique --index "$DB" |
  "${RUNNER[@]}" members --recursive --index "$DB" | grep -q '"producer_id":"spring-xml"'
"${RUNNER[@]}" search getName --kind METHOD --index "$DB" | grep -q '"producer_id":"lombok-ast"'
"${RUNNER[@]}" search getName --kind METHOD --index "$DB" | grep -q '"synthetic_origin"'
"${RUNNER[@]}" resolve 'sample.UserService#display(sample.User)' --kind callable --exact --unique --index "$DB" |
  "${RUNNER[@]}" calls --index "$DB" | grep -q 'sample.User#getName()'
"${RUNNER[@]}" resolve sample.AccessorUser --kind type --unique --index "$DB" |
  "${RUNNER[@]}" describe --index "$DB" | grep -q '"coverage":"partial"'
if "${RUNNER[@]}" resolve sample.AccessorUser --kind type --unique --index "$DB" |
   "${RUNNER[@]}" members --recursive --index "$DB" | grep -q '"name":"getName"'; then
  echo "Accessors must not expose an uncertain getName signature" >&2
  exit 1
fi
"${RUNNER[@]}" declarations-of --file src/main/java/sample/User.java --index "$DB" >"$WORK/declarations.ndjson"
grep -q '"lombok"' "$WORK/declarations.ndjson"

sed -i.bak 's/sharedService/freshShared/g' \
  "$WORK/project/src/main/java/example/SharedService.java" \
  "$WORK/project/src/main/resources/beans-a.xml"
rm "$WORK/project/src/main/java/example/SharedService.java.bak" \
   "$WORK/project/src/main/resources/beans-a.xml.bak"
"${RUNNER[@]}" index "$WORK/project" --no-classpath --spring-xml --lombok ast --incremental --output "$DB" \
  >/dev/null 2>"$WORK/first-incremental.log"
if grep -q 'degraded to full' "$WORK/first-incremental.log"; then
  cat "$WORK/first-incremental.log" >&2
  echo "first extension lifecycle update did not use the incremental path" >&2
  exit 1
fi
"${RUNNER[@]}" search --name beans-a.xml --kind artifact --index "$DB" |
  "${RUNNER[@]}" resolve --unique --index "$DB" |
  "${RUNNER[@]}" members --recursive --index "$DB" >"$WORK/fresh-config.ndjson"
if ! grep -q '"name":"freshShared"' "$WORK/fresh-config.ndjson"; then
  cat "$WORK/fresh-config.ndjson" >&2
  echo "incremental XML members did not contain freshShared" >&2
  exit 1
fi

rm "$WORK/project/src/main/resources/beans-b.xml"
"${RUNNER[@]}" index "$WORK/project" --no-classpath --spring-xml --lombok ast --incremental --output "$DB" >/dev/null
"${RUNNER[@]}" search --name peer --kind component --count --index "$DB" >"$WORK/deleted-peer.ndjson"
grep -q '"record":"result_count"' "$WORK/deleted-peer.ndjson"
grep -q '"count":0' "$WORK/deleted-peer.ndjson"

run_case() {
  local mode="$1" db="$2" case_name="$3"
  local -a cli
  if [[ "$mode" == jvm ]]; then cli=("${JVM[@]}"); else cli=("$NATIVE_BIN"); fi
  case "$case_name" in
    search) "${cli[@]}" search Account --kind RECORD --index "$db" ;;
    declarations) "${cli[@]}" declarations-of --file src/main/java/sample/User.java --index "$db" ;;
    calls) "${cli[@]}" resolve 'sample.UserService#display(sample.User)' --kind callable --exact --unique --index "$db" |
      "${cli[@]}" calls --index "$db" ;;
    config) "${cli[@]}" search --name beans-a.xml --kind artifact --index "$db" |
      "${cli[@]}" resolve --unique --index "$db" |
      "${cli[@]}" members --recursive --index "$db" ;;
    overview) "${cli[@]}" overview --deps-only --index "$db" ;;
  esac
}

if [[ "$MODE" == native ]]; then
  command -v jq >/dev/null
  JVM_DB="$WORK/jvm.db"
  "${JVM[@]}" index "$WORK/project" --no-classpath --spring-xml --lombok ast --output "$JVM_DB" >/dev/null
  for case_name in search declarations calls config overview; do
    run_case jvm "$JVM_DB" "$case_name" |
      sed -E 's/"index_revision_id":"[^"]*"/"index_revision_id":"<REVISION>"/g' |
      jq -S . >"$WORK/jvm.json"
    run_case native "$DB" "$case_name" |
      sed -E 's/"index_revision_id":"[^"]*"/"index_revision_id":"<REVISION>"/g' |
      jq -S . >"$WORK/native.json"
    diff -u "$WORK/jvm.json" "$WORK/native.json"
  done
fi

echo "extension lifecycle E2E passed ($MODE)"
