#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-jvm}"
NATIVE_BIN="${2:-target/anatomist}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/anatomist-incremental-e2e.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

PROJECT="$TMP_DIR/project"
cp -R "$ROOT/fixtures/mini-spring-shop" "$PROJECT"
SOURCES="$PROJECT/api/src/main/java:$PROJECT/domain/src/main/java:$PROJECT/service/src/main/java"
DB="$TMP_DIR/incremental.db"
FULL_DB="$TMP_DIR/full.db"

if [[ "$MODE" == "native" ]]; then
  if [[ "$NATIVE_BIN" = /* ]]; then
    CLI=("$NATIVE_BIN")
  else
    CLI=("$ROOT/$NATIVE_BIN")
  fi
else
  SDKMAN_ROOT="${SDKMAN_DIR:-${HOME}/.sdkman}"
  if [[ -s "$SDKMAN_ROOT/bin/sdkman-init.sh" ]]; then
    # shellcheck disable=SC1091
    set +u
    source "$SDKMAN_ROOT/bin/sdkman-init.sh"
    sdk env >/dev/null
    set -u
  fi
  CLI=("$(command -v java)" -jar "$ROOT/target/anatomist.jar")
fi

index() {
  "${CLI[@]}" index "$PROJECT" --project-source "$SOURCES" --no-classpath \
    --spring-xml --include-tests --format json --timings --output "$1" "${@:2}"
}

index "$DB" > "$TMP_DIR/full.json"
REVISION_BEFORE="$(sqlite3 "$DB" "select value from project_meta where key='index_revision_id'")"
INDEXED_AT_BEFORE="$(sqlite3 "$DB" "select value from project_meta where key='indexed_at'")"
index "$DB" --incremental > "$TMP_DIR/noop.json"
[[ "$REVISION_BEFORE" == "$(sqlite3 "$DB" "select value from project_meta where key='index_revision_id'")" ]]
[[ "$INDEXED_AT_BEFORE" == "$(sqlite3 "$DB" "select value from project_meta where key='indexed_at'")" ]]

SERVICE="$PROJECT/service/src/main/java/com/example/shop/service/OrderService.java"
printf '\n// incremental-e2e body-only\n' >> "$SERVICE"
index "$DB" --incremental > "$TMP_DIR/body.json"
python3 - "$TMP_DIR/body.json" <<'PY'
import json, sys
value = json.load(open(sys.argv[1]))
stats = value["stats"]
assert stats["attempted_files"] == 1, stats
assert stats["realigned_dependents"] == 0, stats
assert "publish_lock_wait" in value["timings_ms"], value
assert "publish_transaction" in value["timings_ms"], value
PY

index "$FULL_DB" > "$TMP_DIR/rebuilt.json"
python3 - "$DB" "$FULL_DB" <<'PY'
import sqlite3, sys

def rows(path, sql):
    with sqlite3.connect(path) as db:
        return db.execute(sql).fetchall()

queries = {
    "nodes": "select id,symbol_id,label,kind,qualified_name,package,source_file,module,scope,metadata,producer_id,declaration_kind,type_kind,visibility,modifiers from nodes order by id",
    "edges": "select source_id,target_id,external_target_fqn,relation,semantic,mechanism,call_kind,confidence,resolution,context,is_external,source_file,metadata,producer_id from edges order by 1,2,3,4,12,13",
    "annotations": "select node_id,annotation_fqn,raw_name,attributes,target_kind,target_path,language,provider_id,mechanism,resolution_status,source_file,producer_id from annotations order by 1,2,3,11",
    "calls": "select o.caller_id,o.source_file,hex(s.stable_hash),t.target_id,t.external_target_fqn,t.resolution_status,t.confidence,t.producer_id from call_sites s join call_site_owners o on o.owner_pk=s.owner_pk join call_site_targets t on t.call_site_pk=s.site_pk order by 1,2,3,4,5",
}
for name, sql in queries.items():
    left, right = rows(sys.argv[1], sql), rows(sys.argv[2], sql)
    assert left == right, f"{name} mismatch: {len(left)} != {len(right)}"
PY

"${CLI[@]}" doctor --health-policy integrity --format json --index "$DB" > "$TMP_DIR/doctor.json"
python3 - "$TMP_DIR/doctor.json" <<'PY'
import json, sys
value = json.load(open(sys.argv[1]))
assert value["gate"]["passed"], value
PY

if compgen -G "$DB.stage-*" > /dev/null; then
  echo "staging sidecar leaked" >&2
  exit 1
fi

echo "incremental-e2e ($MODE): PASS"
