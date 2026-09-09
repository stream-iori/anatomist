"""Independent fixture facts and navigation checks for version evidence."""

def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def validate_diff(document, facts, scenario, *, tests=True, dispatch="auto"):
    header, changes = document["comparison"], document["changes"]
    expected_base = facts["ancestor"] if scenario == "work" else facts["base"]
    require(header["base"]["commit"] == expected_base, "wrong comparison base")
    require(header["target"]["commit"] == facts["feature"], "wrong comparison target")
    if scenario == "boundary":
        require([header[side]["id"] for side in ("base", "target")] == facts["fixed_ids"], "fixed snapshots were replaced")
    impacts = [row for row in changes if row["record"] == "impact"]
    def callers(origin, caller):
        return {row["side"] for row in impacts if row["origin"]["symbol"] == origin and row["caller"]["symbol"] == caller}
    require(callers(facts["origin"], facts["direct_caller"]) == {"base", "target"}, "missing direct caller on one or both sides")
    if tests:
        require(callers(facts["origin"], facts["test_caller"]) == {"base", "target"}, "missing TEST caller on one or both sides")
    if dispatch == "auto":
        require(callers(facts["possible_origin"], facts["possible_caller"]) == {"base", "target"}, "missing possible caller on one or both sides")
    for row in impacts:
        path, edges = row["path"], row["edges"]
        require(path[0] == row["caller"]["id"] and path[-1] == row["origin"]["id"], "impact path endpoints do not match anchors")
        require(len(edges) == len(path) - 1, "impact path length mismatch")
        for index, edge in enumerate(edges):
            require(edge["source"] == path[index] and edge["target"] == path[index + 1], "disconnected impact path")
            if edge["candidate_kind"] == "possible":
                require(edge.get("proof") or edge.get("type_proof"), "possible dispatch lacks proof")
                require(edge.get("call_site") and edge.get("resolved_targets"), "possible dispatch lacks call-site navigation")
        require(row["contains_possible_dispatch"] == any(edge["candidate_kind"] == "possible" for edge in edges), "wrong dispatch summary")
    declarations = [row for row in changes if row["record"] == "declaration_change"]
    base_only = any("p.BaseOnly#version()" in str(row) for row in declarations)
    require(base_only == (scenario != "work"), "Base-only work attributed to wrong comparison")
    calls = [row for row in changes if row["record"] == "relation_change" and row["relationship"]["relation"] == "CALLS"]
    for change, method in (("deleted", "oldValue"), ("added", "newValue")):
        require(any(row["change"] == change and row["relationship"]["target"].endswith("p.A#" + method + "()") for row in calls), "missing call retargeting evidence")
    capability = document["evidence"]["capabilities"]["impact"]
    if dispatch == "auto":
        require(not capability["negative_conclusion_safe"], "open-world impact marked negative-safe")
    if scenario == "boundary":
        require(any("SOURCE" in reason or "SCOPE" in reason or "SCAN" in reason for reason in capability["reasons"]), "missing coverage limitation")
    return {"base": header["base"]["id"], "target": header["target"]["id"], "impacts": len(impacts), "capability": capability}


def navigation_sides(document, streams):
    observed = set()
    for side in ("base", "target"):
        identity = document["comparison"][side]
        anchors = {row[role]["id"] for row in document["changes"] for role in ("origin", "caller", "before", "after")
                   if isinstance(row.get(role), dict) and row[role].get("snapshot_id") == identity["id"] and "id" in row[role]}
        for rows in streams:
            actual = rows[0].get("identity", {})
            if not all(actual.get(key) == identity[key] for key in ("index_revision_id", "source_snapshot_id", "semantic_profile_id")):
                continue
            if any(row.get("record") == "source_slice" and row.get("subject", {}).get("id") in anchors
                   and row.get("snippet") and row.get("resolution_status") == "exact" for row in rows):
                observed.add(side)
    return observed


def validate_capture(document, facts):
    header = document["comparison"]
    require(header.get("output", {}).get("view") == "all", "capture check requires visible file evidence")
    require(header.get("capture", {}).get("target") == "git-inputs-v2", "capture policy not disclosed")
    require(not any(row.get("path") == facts["ignored_report"] for row in document["changes"]), "ignored report appeared as a file change")
    return {"capture_policy": header["capture"]["target"], "excluded_report": facts["ignored_report"]}


def validate_storage(documents, facts):
    stats = [item for item in documents if item.get("command") == "snapshots stats"]
    gc = [item for item in documents if item.get("command") == "snapshots gc"]
    require(stats, "storage statistics not consumed")
    require(any(item.get("execute") is False for item in gc), "GC preview not consumed")
    executed = [item for item in gc if item.get("execute") is True]
    require(executed, "executed GC evidence missing")
    require(any(row.get("id") == facts["stale_id"] for item in executed for row in item.get("candidates", [])), "unused snapshot was not collected")
    require(any(row.get("reason") == "pin" for item in executed for row in item.get("protected", [])), "pin protection not established")
    return {"gc_executions": len(executed), "statistics": len(stats)}
