#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-jvm}"
NATIVE_BIN="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -R "$ROOT/fixtures/extension-lifecycle" "$WORK/project"
cp -R "$ROOT/fixtures/lombok-sample/src/main/java/sample" \
  "$WORK/project/src/main/java/"

JVM=(java --enable-native-access=ALL-UNNAMED -jar "$ROOT/target/anatomist.jar")
if [[ "$MODE" == "native" ]]; then
  test -x "$NATIVE_BIN"
  RUNNER=("$NATIVE_BIN")
else
  RUNNER=("${JVM[@]}")
fi

DB="$WORK/index.db"
"${RUNNER[@]}" index "$WORK/project" --no-classpath --spring-xml --lombok ast --output "$DB" >/dev/null
"${RUNNER[@]}" search Account --kind RECORD --index "$DB" | grep -q '"producer_id" : "java-core"'
"${RUNNER[@]}" callees-of 'example.AccountReader#read' --index "$DB" | grep -q 'example.Account#id()'
"${RUNNER[@]}" bean-config --format=json holder --index "$DB" | grep -q '"producer_id" : "spring-xml"'
"${RUNNER[@]}" search getName --kind METHOD --index "$DB" | grep -q '"producer_id" : "lombok-ast"'
"${RUNNER[@]}" search getName --kind METHOD --index "$DB" | grep -q '"synthetic_origin"'
"${RUNNER[@]}" callees-of 'sample.UserService#display' --index "$DB" | grep -q 'sample.User#getName()'
"${RUNNER[@]}" search --name User --kind CLASS --index "$DB" | grep -q '"lombok"'
"${RUNNER[@]}" context sample.AccessorUser --index "$DB" | grep -q '"coverage" : "partial"'
if "${RUNNER[@]}" context sample.AccessorUser --index "$DB" | grep -q '"label" : "getName"'; then
  echo "Accessors must not expose an uncertain getName signature" >&2
  exit 1
fi
"${RUNNER[@]}" declarations-of --file src/main/java/sample/User.java --index "$DB" \
  | grep -q '"lombok"'

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
"${RUNNER[@]}" bean-config --format=json holder --index "$DB" | grep -q 'bean:freshShared'

rm "$WORK/project/src/main/resources/beans-b.xml"
"${RUNNER[@]}" index "$WORK/project" --no-classpath --spring-xml --lombok ast --incremental --output "$DB" >/dev/null
set +e
"${RUNNER[@]}" bean-config --format=json peer --index "$DB" >"$WORK/deleted-peer.json"
query_status=$?
set -e
test "$query_status" -eq 2
grep -q '"total" : 0' "$WORK/deleted-peer.json"

if [[ "$MODE" == "native" ]]; then
  command -v jq >/dev/null
  JVM_DB="$WORK/jvm.db"
  "${JVM[@]}" index "$WORK/project" --no-classpath --spring-xml --lombok ast --output "$JVM_DB" >/dev/null
  for query in \
    "search Account --kind RECORD" \
    "callees-of example.AccountReader#read" \
    "search getName --kind METHOD" \
    "search --name User --kind CLASS" \
    "context sample.AccessorUser" \
    "declarations-of --file src/main/java/sample/User.java" \
    "callees-of sample.UserService#display" \
    "bean-config --format=json holder" \
    "overview --deps-only"; do
    read -r -a args <<< "$query"
    "${JVM[@]}" "${args[@]}" --index "$JVM_DB" \
      | jq -S 'if (.query | type) == "string" then .query |= sub("--index [^ ]+"; "--index <INDEX>") else . end' \
      > "$WORK/jvm.json"
    "$NATIVE_BIN" "${args[@]}" --index "$DB" \
      | jq -S 'if (.query | type) == "string" then .query |= sub("--index [^ ]+"; "--index <INDEX>") else . end' \
      > "$WORK/native.json"
    diff -u "$WORK/jvm.json" "$WORK/native.json"
  done
fi

echo "extension lifecycle E2E passed ($MODE)"
