#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-jvm}"
export SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
if [[ "$MODE" == "jvm" && -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
  set +u
  # shellcheck disable=SC1091
  source "$SDKMAN_DIR/bin/sdkman-init.sh"
  sdk env >/dev/null
  set -u
fi
E2E_TMP="$(mktemp -d "${TMPDIR:-/tmp}/anatomist-config-e2e.XXXXXX")"
trap 'rm -rf "$E2E_TMP"' EXIT
PROJECT="$E2E_TMP/project"
E2E_HOME="$E2E_TMP/home"
case "$MODE" in
  jvm)
    CLI=(java -Duser.home="$E2E_HOME" --enable-native-access=ALL-UNNAMED -jar "$ROOT/target/anatomist.jar")
    ;;
  native)
    test -n "${2:-}" || { echo "usage: $0 native <binary>" >&2; exit 2; }
    CLI=("$2" -Duser.home="$E2E_HOME")
    ;;
  *)
    echo "usage: $0 [jvm|native <binary>]" >&2
    exit 2
    ;;
esac
mkdir -p "$PROJECT/src/main/java/p" "$PROJECT/src/test/java/p" "$E2E_HOME/.anatomist"

printf '%s\n' 'package p; class Keep { void run() {} }' > "$PROJECT/src/main/java/p/Keep.java"
printf '%s\n' 'package p; class Drop { void run() {} }' > "$PROJECT/src/main/java/p/Drop.java"
printf '%s\n' 'package p; class GlobalTest { void test() {} }' > "$PROJECT/src/test/java/p/GlobalTest.java"
printf '%s\n' '<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId><artifactId>e2e</artifactId><version>1</version></project>' > "$PROJECT/pom.xml"
printf '%s\n' '[scan]' 'scopes = ["TEST"]' > "$E2E_HOME/.anatomist/config.toml"

GLOBAL_DB="$E2E_TMP/global.db"
HOME="$E2E_HOME" "${CLI[@]}" index "$PROJECT" --no-classpath --java-version 17 --output "$GLOBAL_DB" >/dev/null
HOME="$E2E_HOME" "${CLI[@]}" search GlobalTest --scope TEST --index "$GLOBAL_DB" | grep -q '"qualified_name":"p.GlobalTest"'
if HOME="$E2E_HOME" "${CLI[@]}" search Keep --index "$GLOBAL_DB" | grep -q '"qualified_name":"p.Keep"'; then
  echo "global TEST-only config unexpectedly indexed main source" >&2
  exit 1
fi

mkdir -p "$PROJECT/.anatomist"
printf '%s\n' '[scan]' 'include = ["src/main/java/**"]' 'exclude = ["**/Drop.java"]' \
  > "$PROJECT/.anatomist/config.toml"
PROJECT_DB="$E2E_TMP/project.db"
HOME="$E2E_HOME" "${CLI[@]}" index "$PROJECT" --no-classpath --java-version 17 --output "$PROJECT_DB" >/dev/null
HOME="$E2E_HOME" "${CLI[@]}" search Keep --index "$PROJECT_DB" | grep -q '"qualified_name":"p.Keep"'
if HOME="$E2E_HOME" "${CLI[@]}" search GlobalTest --scope TEST --index "$PROJECT_DB" | grep -q '"qualified_name":"p.GlobalTest"'; then
  echo "project config did not replace global config" >&2
  exit 1
fi
if HOME="$E2E_HOME" "${CLI[@]}" search Drop --index "$PROJECT_DB" | grep -q '"qualified_name":"p.Drop"'; then
  echo "project scan.exclude did not apply" >&2
  exit 1
fi

CLI_DB="$E2E_TMP/cli.db"
HOME="$E2E_HOME" "${CLI[@]}" index "$PROJECT" --no-classpath --java-version 17 \
  --scan-include 'src/main/java/**' --scan-exclude '**/Keep.java' --output "$CLI_DB" >/dev/null
HOME="$E2E_HOME" "${CLI[@]}" search Drop --index "$CLI_DB" | grep -q '"qualified_name":"p.Drop"'

printf '%s\n' '[scan]' 'include = ["src/main/java/**"]' 'exclude = ["**/Keep.java"]' \
  > "$PROJECT/.anatomist/config.toml"
HOME="$E2E_HOME" "${CLI[@]}" index "$PROJECT" --no-classpath --java-version 17 \
  --incremental --output "$PROJECT_DB" >/dev/null 2>"$E2E_TMP/incremental.err"
grep -q 'scan policy changed' "$E2E_TMP/incremental.err"
HOME="$E2E_HOME" "${CLI[@]}" search Drop --index "$PROJECT_DB" | grep -q '"qualified_name":"p.Drop"'

printf '%s\n' '[scan]' 'exclude = "invalid"' > "$PROJECT/.anatomist/config.toml"
set +e
HOME="$E2E_HOME" "${CLI[@]}" index "$PROJECT" --no-classpath --output "$E2E_TMP/invalid.db" \
  >/dev/null 2>"$E2E_TMP/invalid.err"
INVALID_RC=$?
set -e
test "$INVALID_RC" -eq 2
grep -q 'expected an array of quoted strings' "$E2E_TMP/invalid.err"

echo "config-e2e ($MODE) PASSED"
