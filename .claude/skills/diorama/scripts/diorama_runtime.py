#!/usr/bin/env python3
"""Diorama runtime deterministic helper.

This script is intentionally narrow:
- read task/session state
- run minimum checks
- inspect git working tree
- validate rewind targets

It does not generate business artifacts or make final workflow decisions.
"""

from __future__ import print_function

import argparse
import json
import os
import re
import shutil
import subprocess
import sys

PHASES = ["specify", "plan", "generate", "consolidate"]
REQ_RE = re.compile(r"REQ-\d+", re.MULTILINE)
BR_RE = re.compile(r"BR-\d+", re.MULTILINE)
AC_RE = re.compile(r"AC-\d+", re.MULTILINE)
SCENARIO_RE = re.compile(r"^###\s+S\d+", re.MULTILINE)
SCENARIO_ID_RE = re.compile(r"S\d+", re.MULTILINE)


def fail(error, message, **extra):
    result = {"ok": False, "error": error, "message": message}
    result.update(extra)
    return result


def ok(**extra):
    result = {"ok": True}
    result.update(extra)
    return result


def read_text(path):
    if not os.path.exists(path):
        return None
    with open(path, "r") as f:
        return f.read()


def read_json(path):
    if not os.path.exists(path):
        return fail("file_not_found", "JSON file not found", path=path), None
    with open(path, "r") as f:
        try:
            data = json.load(f)
        except ValueError as exc:
            return fail("invalid_json", str(exc), path=path), None
    return None, data


def file_exists_nonempty(path):
    return os.path.exists(path) and os.path.getsize(path) > 0


def task_file(task_dir, name):
    return os.path.join(task_dir, name)


def find_diorama_root(task_dir):
    return os.path.dirname(os.path.dirname(task_dir))


def session_file_from_task(task_dir):
    return os.path.join(find_diorama_root(task_dir), "session", "current-session.json")


def load_task_json(task_dir):
    err, data = read_json(task_file(task_dir, "task.json"))
    if err is not None:
        return err, None
    return None, data


def load_session_json(task_dir):
    err, data = read_json(session_file_from_task(task_dir))
    if err is not None:
        return err, None
    return None, data


def minimum_check_specify(task_dir):
    # proposal.md must be non-empty (updated during specify)
    proposal_path = task_file(task_dir, "proposal.md")
    if not file_exists_nonempty(proposal_path):
        return fail("minimum_check_failed", "proposal.md is missing or empty", phase="specify")
    path = task_file(task_dir, "design.md")
    text = read_text(path)
    if not text or not text.strip():
        return fail("minimum_check_failed", "design.md is missing or empty", phase="specify")
    if REQ_RE.search(text) or SCENARIO_RE.search(text):
        return ok(phase="specify", passed=True, reason="proposal.md and design.md exist; design.md contains at least one REQ or scenario")
    return fail("minimum_check_failed", "design.md does not contain a REQ- or scenario section", phase="specify")


def minimum_check_plan(task_dir):
    tasks_path = task_file(task_dir, "tasks.md")
    if not file_exists_nonempty(tasks_path):
        return fail("minimum_check_failed", "tasks.md is missing or empty", phase="plan")

    err, task = load_task_json(task_dir)
    if err is not None:
        return err

    anchors = task.get("anchors", {}) if isinstance(task, dict) else {}
    dev_entries = anchors.get("dev_entries") or []
    acceptance_entries = anchors.get("acceptance_entries") or []
    if dev_entries or acceptance_entries:
        return ok(
            phase="plan",
            passed=True,
            reason="tasks.md exists and at least one anchor set is written",
            dev_entries_count=len(dev_entries),
            acceptance_entries_count=len(acceptance_entries),
        )
    return fail(
        "minimum_check_failed",
        "tasks.md exists but both dev_entries and acceptance_entries are empty",
        phase="plan",
        dev_entries_count=0,
        acceptance_entries_count=0,
    )


def minimum_check_generate(task_dir):
    tasks_path = task_file(task_dir, "tasks.md")
    text = read_text(tasks_path)
    if not text or not text.strip():
        return fail("minimum_check_failed", "tasks.md is missing or empty", phase="generate")

    # Task-level Status markers: **Status**: [x] done / **Status**: [ ] done
    phase_statuses = re.findall(r"\*\*Status\*\*:\s*\[( |x|X)\]\s*done", text)
    phase_status_checked = len([s for s in phase_statuses if s.lower() == "x"])
    phase_status_total = len(phase_statuses)

    # Read task_checkpoints from task.json as fallback evidence
    err_t, task = load_task_json(task_dir)
    task_checkpoints = {}
    if err_t is None and isinstance(task, dict):
        task_checkpoints = task.get("phase", {}).get("task_checkpoints", {})

    # At least one task must be marked done (or have a checkpoint) to consider generate completable
    has_checkpoint_evidence = bool(task_checkpoints)
    if phase_status_total > 0 and phase_status_checked == 0 and not has_checkpoint_evidence:
        return fail(
            "minimum_check_failed",
            "no task has Status: [x] done and no task_checkpoints recorded — generate is not far enough along",
            phase="generate",
            phase_status_total=phase_status_total,
            phase_status_checked=0,
        )

    # Sub-phase checkbox progress: parse #### Phase N: sections
    sub_phases = _parse_generate_sub_phases(text)

    # Aggregate checkbox counts across all sub-phases
    checkbox_total = sum(sp["checkbox_total"] for sp in sub_phases)
    checkbox_checked = sum(sp["checkbox_checked"] for sp in sub_phases)

    return ok(
        phase="generate",
        passed=True,
        reason="tasks.md present and at least one task completed",
        phase_status_total=phase_status_total,
        phase_status_checked=phase_status_checked,
        checkbox_total=checkbox_total,
        checkbox_checked=checkbox_checked,
        sub_phases=sub_phases,
        task_checkpoints=task_checkpoints,
    )


def minimum_check_consolidate(task_dir):
    err, task = load_task_json(task_dir)
    if err is not None:
        return err

    consolidate = task.get("consolidate", {}) if isinstance(task, dict) else {}
    noop = consolidate.get("noop", False)
    updated_files = consolidate.get("updated_files") or []

    if noop:
        return ok(phase="consolidate", passed=True, reason="confirmed no incremental knowledge", noop=True)

    if updated_files:
        return ok(
            phase="consolidate",
            passed=True,
            reason="at least one knowledge file updated",
            noop=False,
            updated_files=updated_files,
            updated_count=len(updated_files),
        )

    return fail(
        "minimum_check_failed",
        "no knowledge file updated and no noop confirmation in task.json",
        phase="consolidate",
    )


def _parse_generate_sub_phases(text):
    """Parse generate sub-phase progress from tasks.md content.

    Each #### Phase N: section within a ### T<N>: task is parsed for:
    - Task-level Status marker (**Status**: [x] done)
    - Individual checkbox items (- [ ] / - [x])
    """
    sub_phases = []
    # Match #### Phase N: headings (sub-phase within a task)
    headings = list(re.finditer(r"^####\s+Phase\s+(\d+)\s*:", text, re.MULTILINE))

    for i, m in enumerate(headings):
        phase_num = int(m.group(1))
        start = m.start()
        end = headings[i + 1].start() if i + 1 < len(headings) else len(text)
        section = text[start:end]

        # Task-level Status (from parent ### T<N>: heading above this sub-phase)
        status_done = False
        # Look backwards for the nearest ### T<N>: heading
        pre_text = text[:start]
        task_heading = list(re.finditer(r"^###\s+T\d+\s*:", pre_text, re.MULTILINE))
        if task_heading:
            task_start = task_heading[-1].start()
            # Find the next ### or end of section
            next_task = list(re.finditer(r"^###\s+", text[task_start:], re.MULTILINE))
            task_section = text[task_start:task_start + next_task[1].start()] if len(next_task) > 1 else text[task_start:]
            status_match = re.search(r"\*\*Status\*\*:\s*\[( |x|X)\]\s*done", task_section)
            status_done = status_match is not None and status_match.group(1).lower() == "x"

        # Checkboxes within this sub-phase section
        checkboxes = re.findall(r"^-\s*\[( |x|X)\]\s+", section, re.MULTILINE)
        cb_checked = len([c for c in checkboxes if c.lower() == "x"])
        cb_total = len(checkboxes)

        # Extract label from heading line
        newline_pos = text.find("\n", start)
        heading_line = text[start:newline_pos] if newline_pos != -1 else text[start:]
        # e.g. "#### Phase 1: Skeleton" -> "Skeleton"
        label = heading_line.split(":", 1)[-1].strip() if ":" in heading_line else heading_line.strip()

        # Extract task ID from parent heading
        task_id = ""
        if task_heading:
            task_heading_line = pre_text[task_heading[-1].start():]
            task_id_match = re.match(r"###\s+(T\d+)", task_heading_line)
            if task_id_match:
                task_id = task_id_match.group(1)

        sub_phases.append({
            "task_id": task_id,
            "phase_num": phase_num,
            "label": label,
            "status_done": status_done,
            "checkbox_checked": cb_checked,
            "checkbox_total": cb_total,
        })

    return sub_phases


def _extract_unique(text, pattern):
    """Extract unique matches from text using a regex pattern, preserving order."""
    seen = set()
    result = []
    for m in pattern.finditer(text):
        val = m.group(0)
        if val not in seen:
            seen.add(val)
            result.append(val)
    return result


def _extract_task_req_mapping(text):
    """Extract task-to-REQ/BR mapping from tasks.md.

    Returns a list of dicts: [{"task_id": "T1", "reqs": ["REQ-001"], "brs": ["BR-001"]}, ...]
    """
    mapping = []
    task_headings = list(re.finditer(r"^###\s+(T\d+)\s*:", text, re.MULTILINE))
    for i, m in enumerate(task_headings):
        task_id = m.group(1)
        start = m.start()
        end = task_headings[i + 1].start() if i + 1 < len(task_headings) else len(text)
        section = text[start:end]
        reqs = _extract_unique(section, REQ_RE)
        brs = _extract_unique(section, BR_RE)
        mapping.append({"task_id": task_id, "reqs": reqs, "brs": brs})
    return mapping


def conformance_check(task_dir, repo=None):
    """Run conformance-check: verify design.md REQ/BR/AC traceability and rules index verification.

    Returns a structured result with:
    - design_ids: extracted REQ/BR/AC/scenario IDs from design.md
    - task_traceability: which tasks reference which REQ/BR IDs
    - untraced_ids: IDs from design.md not found in any task
    - rules: rules index verification status (via rules/index.md, if it exists)
    - overall: PASS / CONDITIONAL / FAIL
    """
    design_path = task_file(task_dir, "design.md")
    design_text = read_text(design_path)
    if not design_text or not design_text.strip():
        return fail("design_not_found", "design.md is missing or empty — cannot run conformance-check")

    # 1. Extract IDs from design.md
    design_reqs = _extract_unique(design_text, REQ_RE)
    design_brs = _extract_unique(design_text, BR_RE)
    design_acs = _extract_unique(design_text, AC_RE)
    design_scenarios = _extract_unique(design_text, SCENARIO_ID_RE)

    # 2. Extract task-to-REQ/BR mapping from tasks.md
    tasks_path = task_file(task_dir, "tasks.md")
    tasks_text = read_text(tasks_path)
    task_mapping = _extract_task_req_mapping(tasks_text) if tasks_text else []

    # 3. Build traceability: which design IDs are referenced in tasks
    all_traced_reqs = set()
    all_traced_brs = set()
    for t in task_mapping:
        all_traced_reqs.update(t["reqs"])
        all_traced_brs.update(t["brs"])

    untraced_reqs = [r for r in design_reqs if r not in all_traced_reqs]
    untraced_brs = [b for b in design_brs if b not in all_traced_brs]

    # AC traceability: check if ACs appear in tasks.md (may be in test descriptions)
    all_traced_acs = set()
    if tasks_text:
        all_traced_acs = set(_extract_unique(tasks_text, AC_RE))
    untraced_acs = [a for a in design_acs if a not in all_traced_acs]

    # Scenario traceability
    all_traced_scenarios = set()
    if tasks_text:
        all_traced_scenarios = set(_extract_unique(tasks_text, SCENARIO_ID_RE))
    untraced_scenarios = [s for s in design_scenarios if s not in all_traced_scenarios]

    # 4. Check anchor files exist
    anchor_result = None
    missing_anchors = []
    err, task_json = load_task_json(task_dir)
    if err is None and isinstance(task_json, dict):
        anchors = task_json.get("anchors", {})
        for key in ["dev_entries", "acceptance_entries"]:
            for entry in anchors.get(key, []):
                if repo:
                    full_path = os.path.join(repo, entry)
                else:
                    full_path = entry
                if not os.path.isfile(full_path):
                    missing_anchors.append("%s: %s" % (key, entry))
        anchor_result = {"dev_count": len(anchors.get("dev_entries", [])),
                         "acceptance_count": len(anchors.get("acceptance_entries", [])),
                         "missing": missing_anchors}

    # 5. Rules index verification (via rules/index.md)
    rules_result = None
    diorama_root = find_diorama_root(task_dir)
    rules_index_path = os.path.join(diorama_root, "knowledge", "rules", "index.md")
    rules_index_text = read_text(rules_index_path)
    if rules_index_text and rules_index_text.strip():
        entries = _parse_rules_index(rules_index_text)
        rule_sources = []
        for entry in entries:
            rule_path = entry["path"]
            # Resolve relative paths: experience.md is relative to rules/ dir
            # Other paths (.claude/rules/*, .qoder/rules/*) are relative to project root
            if rule_path == "experience.md":
                full_rule_path = os.path.join(diorama_root, "knowledge", "rules", rule_path)
            elif repo and not os.path.isabs(rule_path):
                full_rule_path = os.path.join(repo, rule_path)
            else:
                full_rule_path = rule_path
            exists = os.path.isfile(full_rule_path)
            rule_sources.append({"path": rule_path, "category": entry.get("category", ""),
                                  "summary": entry.get("summary", ""), "exists": exists})
        rules_result = {"rule_count": len(entries), "rule_sources": rule_sources}

    # 6. Overall assessment
    has_untraced = bool(untraced_reqs or untraced_brs)
    # We flag CONDITIONAL if there are untraced ACs or scenarios (softer than REQ/BR)
    has_soft_issues = bool(untraced_acs or untraced_scenarios or missing_anchors)

    if has_untraced:
        overall = "FAIL"
    elif has_soft_issues:
        overall = "CONDITIONAL"
    else:
        overall = "PASS"

    return ok(
        design_reqs=design_reqs,
        design_brs=design_brs,
        design_acs=design_acs,
        design_scenarios=design_scenarios,
        task_mapping=task_mapping,
        untraced_reqs=untraced_reqs,
        untraced_brs=untraced_brs,
        untraced_acs=untraced_acs,
        untraced_scenarios=untraced_scenarios,
        anchors=anchor_result,
        rules=rules_result,
        overall=overall,
    )



def _parse_rules_index(index_text):
    """Parse rules/index.md and return a list of rule source file paths.

    Each entry in the index follows the format:
        - <file_path> — <category> — <summary>
        - <file_path> — <category> — <summary> — gate: `<cmd>`
    Returns a list of dicts: [{"path": "...", "category": "...", "summary": "...", "gate": "..."}]

    Only parses entries after the last ## heading (the rules section),
    skipping the header and usage instructions.
    """
    entries = []
    if not index_text or not index_text.strip():
        return entries

    # Find the last ## heading — entries after it are the actual rules
    last_section_start = 0
    for m in re.finditer(r"^##\s+", index_text, re.MULTILINE):
        last_section_start = m.end()

    text_after_last_section = index_text[last_section_start:]

    for line in text_after_last_section.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("<!--"):
            continue
        if not line.startswith("- "):
            continue
        content = line[2:].strip()
        parts = content.split(" — ")
        entry = {"path": "", "category": "", "summary": "", "gate": None}
        if len(parts) >= 4:
            entry["path"] = parts[0].strip()
            entry["category"] = parts[1].strip()
            entry["summary"] = parts[2].strip()
            gate_part = parts[3].strip()
            if gate_part.startswith("gate:"):
                gate_cmd = gate_part[5:].strip().strip("`")
                entry["gate"] = gate_cmd
        elif len(parts) == 3:
            entry["path"] = parts[0].strip()
            entry["category"] = parts[1].strip()
            entry["summary"] = parts[2].strip()
        elif len(parts) == 2:
            entry["path"] = parts[0].strip()
            entry["category"] = parts[1].strip()
        elif len(parts) == 1 and parts[0].strip():
            entry["path"] = parts[0].strip()
        else:
            continue
        entries.append(entry)
    return entries


def run_minimum_check(phase, task_dir):
    handlers = {
        "specify": minimum_check_specify,
        "plan": minimum_check_plan,
        "generate": minimum_check_generate,
        "consolidate": minimum_check_consolidate,
    }
    handler = handlers.get(phase)
    if handler is None:
        return fail("invalid_phase", "Unsupported phase", phase=phase)
    return handler(task_dir)


def run_working_tree_check(repo):
    repo = os.path.abspath(repo)
    try:
        proc = subprocess.Popen(["git", "-C", repo, "status", "--porcelain"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        out, err = proc.communicate()
    except OSError as exc:
        return fail("git_unavailable", str(exc), repo=repo)

    if proc.returncode != 0:
        return fail("git_status_failed", err.decode("utf-8", "replace").strip(), repo=repo)

    lines = [line for line in out.decode("utf-8", "replace").splitlines() if line.strip()]
    has_modified = False
    has_untracked = False
    for line in lines:
        if line.startswith("??"):
            has_untracked = True
        else:
            has_modified = True
    return ok(repo=repo, clean=(len(lines) == 0), has_modified=has_modified, has_untracked=has_untracked, entries=lines)


def last_completed_phase(completed):
    for phase in reversed(PHASES):
        if phase in completed:
            return phase
    return ""


def next_phase_of(phase):
    if phase == "specify":
        return "plan"
    if phase == "plan":
        return "generate"
    if phase == "generate":
        return "consolidate"
    if phase == "consolidate":
        return "done"
    return ""


def infer_phase_state(current_phase, completed, session_dirty, working_tree_clean, minimum_result):
    if current_phase == "done":
        return "done"
    if current_phase == "cancelled":
        return "cancelled"
    if current_phase in completed:
        return "ready"
    if session_dirty or (not working_tree_clean):
        return "interrupted"
    if minimum_result is not None and (not minimum_result.get("ok", False)):
        return "interrupted"
    return "in_progress"


def render_phase_progress(progress):
    lines = []
    for item in progress:
        phase = item.get("phase", "")
        state = item.get("state", "")
        checkpoint = item.get("checkpoint", "")
        if checkpoint:
            lines.append("- %s — %s (`%s`)" % (phase, state, checkpoint))
        else:
            lines.append("- %s — %s" % (phase, state))
    return lines


def render_status_markdown(status):
    summary = "Status: unknown"
    phase_state = status.get("phase_state", "")
    current_phase = status.get("current_phase", "")
    next_info = status.get("next", {}) or {}
    minimum = status.get("minimum_check")

    if phase_state == "done":
        summary = "Status: task complete — all phases done"
    elif phase_state == "cancelled":
        summary = "Status: task cancelled — branch preserved for reference"
    elif phase_state == "interrupted" and minimum and not minimum.get("ok", False):
        summary = "Status: interrupted at %s — partial artifacts detected, rewind recommended" % current_phase
    elif phase_state == "interrupted":
        summary = "Status: interrupted at %s — minimum check passed, resume recommended" % current_phase
    elif phase_state == "in_progress":
        summary = "Status: in progress at %s — continue current phase" % current_phase
    elif phase_state == "ready":
        summary = "Status: ready to continue — next phase %s" % next_info.get("target", "")

    lines = ["# Diorama Status", "", summary, "", "## Task"]
    lines.append("- Task: `%s`" % status.get("task", ""))
    lines.append("- Branch: `%s`" % status.get("branch", ""))
    lines.append("")
    lines.append("## Runtime")
    lines.append("- Current Phase: `%s`" % status.get("current_phase", ""))
    lines.append("- Last Completed: `%s`" % status.get("last_completed_phase", ""))
    lines.append("- Last Checkpoint: `%s`" % status.get("last_checkpoint_commit", ""))
    lines.append("- Last Task Checkpoint: `%s`" % status.get("last_task_checkpoint", ""))
    lines.append("- Session Dirty: `%s`" % str(status.get("session_dirty", False)).lower())
    lines.append("- Working Tree: `%s`" % ("clean" if status.get("working_tree_clean") else "dirty"))
    lines.append("- Phase State: `%s`" % phase_state)
    lines.append("")
    lines.append("## Phase Progress")
    lines.extend(render_phase_progress(status.get("progress", [])))
    lines.append("")
    lines.append("## Artifacts")
    if minimum is None:
        lines.append("- Minimum Check — N/A")
    elif minimum.get("ok"):
        lines.append("- Minimum Check — pass")
        lines.append("- Reason — %s" % minimum.get("reason", ""))
    else:
        lines.append("- Minimum Check — fail")
        lines.append("- Reason — %s" % minimum.get("message", ""))
    # Generate sub-phase progress (when applicable)
    sub_phases = minimum.get("sub_phases") if isinstance(minimum, dict) else None
    task_checkpoints = minimum.get("task_checkpoints", {}) if isinstance(minimum, dict) else {}
    if sub_phases:
        lines.append("")
        lines.append("## Generate Progress")
        for sp in sub_phases:
            status_mark = "done" if sp.get("status_done") else "in progress"
            cb_checked = sp.get("checkbox_checked", 0)
            cb_total = sp.get("checkbox_total", 0)
            task_id = sp.get("task_id", "")
            prefix = "%s " % task_id if task_id else ""
            cp_mark = ""
            if task_id and task_id in task_checkpoints:
                cp_hash = task_checkpoints[task_id]
                cp_mark = " \u2713 %s" % (cp_hash[:7] if len(cp_hash) > 7 else cp_hash)
            lines.append("- %sPhase %d (%s): %s — %d/%d items%s" % (
                prefix,
                sp.get("phase_num", 0),
                sp.get("label", ""),
                status_mark,
                cb_checked,
                cb_total,
                cp_mark,
            ))
    # Consolidate progress (when applicable)
    if current_phase == "consolidate" and minimum is not None and isinstance(minimum, dict) and minimum.get("ok"):
        lines.append("")
        lines.append("## Consolidate Progress")
        if minimum.get("noop"):
            lines.append("- Confirmed: no incremental knowledge to update")
        else:
            for f in minimum.get("updated_files", []):
                lines.append("- Updated: `%s`" % f)
    lines.append("")
    lines.append("## Anchors")
    lines.append("- Dev Entries: `%s`" % status.get("anchors", {}).get("dev_entries", []))
    lines.append("- Acceptance Entries: `%s`" % status.get("anchors", {}).get("acceptance_entries", []))
    lines.append("")
    lines.append("## Next")
    if next_info.get("recommended") == "rewind":
        lines.append("- Recommended: `/diorama rewind %s`" % next_info.get("target", current_phase))
    elif next_info.get("recommended") == "resume":
        lines.append("- Recommended: `/diorama` (resume `%s`)" % next_info.get("target", current_phase))
    elif next_info.get("recommended") == "continue":
        lines.append("- Recommended: `/diorama` (continue `%s`)" % next_info.get("target", current_phase))
    elif next_info.get("recommended") == "done":
        lines.append("- Task is complete. All phases including consolidate are done.")
    else:
        lines.append("- Recommended: inspect runtime state")
    return "\n".join(lines) + "\n"


def cmd_status_check(args):
    task_dir = os.path.abspath(args.task)
    if not os.path.isdir(task_dir):
        return fail("invalid_task_path", "Task directory not found", task=task_dir)

    err, task = load_task_json(task_dir)
    if err is not None:
        return err
    err, session = load_session_json(task_dir)
    if err is not None:
        return err

    session_data = session.get("session", {}) if isinstance(session, dict) else {}
    task_task = task.get("task", {}) if isinstance(task, dict) else {}
    task_phase = task.get("phase", {}) if isinstance(task, dict) else {}
    anchors = task.get("anchors", {}) if isinstance(task, dict) else {}

    current_phase = task_phase.get("current", "")
    completed = task_phase.get("completed") or []
    checkpoints = task_phase.get("checkpoints") or {}
    session_dirty = bool(session_data.get("dirty", False))

    wt = run_working_tree_check(args.repo)
    if not wt.get("ok"):
        return wt

    minimum_result = None
    if current_phase in PHASES:
        minimum_result = run_minimum_check(current_phase, task_dir)

    phase_state = infer_phase_state(current_phase=current_phase, completed=completed, session_dirty=session_dirty, working_tree_clean=bool(wt.get("clean", False)), minimum_result=minimum_result)

    progress = []
    for phase in PHASES:
        state = "pending"
        if phase in completed:
            state = "completed"
        if phase == current_phase and phase not in completed:
            state = "interrupted" if phase_state == "interrupted" else "current"
        progress.append({"phase": phase, "state": state, "checkpoint": checkpoints.get(phase, "")})

    if current_phase == "done":
        next_action = {"recommended": "done", "message": "Task is complete. Awaiting Human Confirmation before merge-back."}
    elif current_phase == "cancelled":
        next_action = {"recommended": "cancelled", "message": "Task has been cancelled. Branch preserved for reference. Start a new task with /diorama."}
    elif current_phase in completed:
        next_action = {"recommended": "continue", "target": next_phase_of(current_phase), "message": "Continue with /diorama"}
    elif phase_state == "interrupted":
        if minimum_result is not None and minimum_result.get("ok"):
            next_action = {"recommended": "resume", "target": current_phase, "message": "Resume /diorama to complete phase exit and handoff."}
        else:
            next_action = {"recommended": "rewind", "target": current_phase, "message": "Recommended: /diorama rewind <current-phase>"}
    else:
        next_action = {"recommended": "continue", "target": current_phase, "message": "Continue current phase with /diorama"}

    return ok(
        task=task_task.get("name", ""),
        branch=task_task.get("branch", ""),
        current_phase=current_phase,
        last_completed_phase=last_completed_phase(completed),
        last_checkpoint_commit=session_data.get("last_checkpoint_commit", ""),
        last_task_checkpoint=session_data.get("last_task_checkpoint", ""),
        session_dirty=session_dirty,
        working_tree_clean=bool(wt.get("clean", False)),
        working_tree=wt,
        phase_state=phase_state,
        minimum_check=minimum_result,
        progress=progress,
        anchors={"dev_entries": anchors.get("dev_entries") or [], "acceptance_entries": anchors.get("acceptance_entries") or []},
        next=next_action,
    )


def cmd_minimum_check(args):
    task_dir = os.path.abspath(args.task)
    if not os.path.isdir(task_dir):
        return fail("invalid_task_path", "Task directory not found", task=task_dir, phase=args.phase)
    return run_minimum_check(args.phase, task_dir)


def cmd_resolve_next_phase(args):
    task_dir = os.path.abspath(args.task)
    if not os.path.isdir(task_dir):
        return fail("invalid_task_path", "Task directory not found", task=task_dir)

    err, task = load_task_json(task_dir)
    if err is not None:
        return err

    task_phase = task.get("phase", {}) if isinstance(task, dict) else {}
    current = task_phase.get("current", "")
    completed = task_phase.get("completed") or []

    if current == "done":
        return ok(current=current, next="", kind="done", message="Task is complete")
    if current == "cancelled":
        return ok(current=current, next="", kind="cancelled", message="Task has been cancelled")
    if current in completed:
        return ok(current=current, next=next_phase_of(current), kind="phase")
    return ok(current=current, next=current, kind="resume")


def cmd_working_tree_check(args):
    return run_working_tree_check(args.repo)


def cmd_rewind_target_check(args):
    """Validate rewind target: supports both phase names and task IDs (T1, T2, ...)."""
    task_dir = os.path.abspath(args.task)
    if not os.path.isdir(task_dir):
        return fail("invalid_task_path", "Task directory not found", task=task_dir, target=args.phase)

    err, task = load_task_json(task_dir)
    if err is not None:
        return err

    target = args.phase
    task_phase = task.get("phase", {}) if isinstance(task, dict) else {}
    checkpoints = task_phase.get("checkpoints", {}) if isinstance(task_phase, dict) else {}
    task_checkpoints = task_phase.get("task_checkpoints", {}) if isinstance(task_phase, dict) else {}
    current = task_phase.get("current", "")
    completed = task_phase.get("completed") or []

    if target in PHASES:
        # Phase-level target (existing logic)
        checkpoint = checkpoints.get(target, "")
        if not checkpoint:
            return fail("checkpoint_not_found", "%s checkpoint is empty" % target, target=target, valid=False, current_phase=current, completed=completed)
        return ok(target=target, target_type="phase", valid=True, checkpoint=checkpoint, current_phase=current, completed=completed)

    elif re.match(r'^T\d+$', target):
        # Task-level target validation
        if current != "generate":
            return fail("invalid_rewind_target", "Task-level rewind only valid during generate phase, current: %s" % current, target=target, valid=False, current_phase=current)

        task_num = int(target[1:])
        if task_num == 1:
            checkpoint = checkpoints.get("plan", "")
            if not checkpoint:
                return fail("checkpoint_not_found", "Cannot rewind to T1: plan checkpoint is empty", target=target, valid=False, current_phase=current)
            return ok(target=target, target_type="task", valid=True, checkpoint=checkpoint, current_phase=current, completed=completed)
        else:
            prev_task_id = "T%d" % (task_num - 1)
            checkpoint = task_checkpoints.get(prev_task_id, "")
            if not checkpoint:
                return fail("checkpoint_not_found", "Cannot rewind to %s: %s checkpoint is empty" % (target, prev_task_id), target=target, valid=False, current_phase=current)
            return ok(target=target, target_type="task", valid=True, checkpoint=checkpoint, current_phase=current, completed=completed)

    else:
        return fail("invalid_rewind_target", "Invalid rewind target: %s (expected phase name or task ID like T1, T2)" % target, target=target, valid=False)


def cmd_status_render(args):
    status = cmd_status_check(args)
    if not status.get("ok"):
        print(json.dumps(status, indent=2, sort_keys=True))
        return status
    output = render_status_markdown(status)
    print(output, end="")
    return ok(rendered=True)


# ============================================================
# Write sub-commands (deterministic protocol steps)
# ============================================================

def _write_json(path, data):
    """Write JSON data to file, creating parent dirs if needed."""
    parent = os.path.dirname(path)
    if parent and not os.path.isdir(parent):
        os.makedirs(parent)
    with open(path, "w") as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write("\n")


def _git_commit(repo, message, paths=None):
    """Stage and commit. Returns commit hash on success."""
    if paths:
        for p in paths:
            subprocess.check_call(["git", "-C", repo, "add", p])
    else:
        subprocess.check_call(["git", "-C", repo, "add", "-A"])
    subprocess.check_call(["git", "-C", repo, "commit", "-m", message])
    out = subprocess.check_output(["git", "-C", repo, "rev-parse", "HEAD"])
    return out.decode("utf-8").strip()


def _now_iso():
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def cmd_incept(args):
    """Inception: check working tree → create branch → create task directory + files → inception commit."""
    task_dir = os.path.abspath(args.task)
    name = args.name
    branch = args.branch or ("task/" + name)
    repo = os.path.abspath(args.repo)

    # 1. Check working tree is clean (required before creating branch)
    wt = run_working_tree_check(repo)
    if not wt.get("ok"):
        return wt
    if not wt.get("clean"):
        return fail("working_tree_dirty", "Cannot incept: working tree is not clean. Commit or stash changes first.", repo=repo)

    # 2. Record base branch (the branch we're creating the task from)
    try:
        base_branch = subprocess.check_output(["git", "-C", repo, "rev-parse", "--abbrev-ref", "HEAD"]).decode("utf-8").strip()
    except subprocess.CalledProcessError:
        base_branch = "main"

    # 3. Create and switch to task branch
    try:
        subprocess.check_call(["git", "-C", repo, "checkout", "-b", branch])
    except subprocess.CalledProcessError as exc:
        return fail("branch_create_failed", "Failed to create branch %s: %s" % (branch, str(exc)), branch=branch)

    if os.path.isdir(task_dir):
        return fail("task_already_exists", "Task directory already exists", task=task_dir)

    os.makedirs(task_dir, exist_ok=True)

    # 4. task.json — include base_branch for merge-back later
    task_data = {
        "task": {"name": name, "branch": branch, "base_branch": base_branch},
        "phase": {"current": "specify", "completed": [], "checkpoints": {"specify": "", "plan": "", "generate": "", "consolidate": ""}, "task_checkpoints": {}},
        "anchors": {"dev_entries": [], "acceptance_entries": []},
        "consolidate": {"noop": False, "updated_files": []},
    }

    _write_json(os.path.join(task_dir, "task.json"), task_data)

    # 5. proposal.md — use template if available, otherwise minimal placeholder
    proposal_path = os.path.join(task_dir, "proposal.md")
    diorama_root = find_diorama_root(task_dir)
    if not os.path.exists(proposal_path):
        template_path = os.path.join(diorama_root, "templates", "proposal-template.md")
        if os.path.isfile(template_path):
            shutil.copy2(template_path, proposal_path)
        else:
            with open(proposal_path, "w") as f:
                f.write("# 意图: \n\n## 你想做什么\n\n[你的意图]\n\n## 为什么做\n\n[背景/动机]\n")

    # 6. session — no mode field
    session_path = os.path.join(diorama_root, "session", "current-session.json")
    session_data = {
        "session": {
            "current_task": name,
            "current_phase": "specify",
            "last_checkpoint_commit": "",
            "last_task_checkpoint": "",
            "last_updated_at": _now_iso(),
            "dirty": False,
        }
    }
    _write_json(session_path, session_data)

    # 7. Inception commit — not a phase checkpoint, just to make working tree clean
    try:
        _git_commit(repo, "diorama(incept): %s" % name)
    except subprocess.CalledProcessError:
        pass  # nothing to commit (unlikely but safe)

    # 8. Check knowledge files and add advisory
    knowledge_advisory = ""
    glossary_path = os.path.join(diorama_root, "knowledge", "facts", "glossary.json")
    if not os.path.isfile(glossary_path):
        knowledge_advisory = "Knowledge files not found. Consider running `/diorama survey` first."

    return ok(
        task=name,
        branch=branch,
        base_branch=base_branch,
        initial_phase="specify",
        task_dir=task_dir,
        session_path=session_path,
        knowledge_advisory=knowledge_advisory,
    )


def _phase_input_file(phase, task_dir):
    """Return the prerequisite input file path for a phase."""
    mapping = {
        "specify": "proposal.md",
        "plan": "design.md",
        "generate": "tasks.md",
        "consolidate": "tasks.md",
    }
    filename = mapping.get(phase)
    if filename:
        return task_file(task_dir, filename)
    return None


def cmd_phase_entry(args):
    """Phase Entry: validate branch → validate input → write session dirty=true + task.json current."""
    task_dir = os.path.abspath(args.task)
    phase = args.phase
    repo = os.path.abspath(args.repo)

    # Load task.json first (needed for branch check)
    err, task = load_task_json(task_dir)
    if err is not None:
        return err

    # Validate current git branch matches task branch
    expected_branch = task.get("task", {}).get("branch", "") if isinstance(task, dict) else ""
    if expected_branch:
        try:
            current_branch = subprocess.check_output(
                ["git", "-C", repo, "rev-parse", "--abbrev-ref", "HEAD"]
            ).decode("utf-8").strip()
        except subprocess.CalledProcessError:
            current_branch = ""
        if current_branch != expected_branch:
            return fail(
                "wrong_branch",
                "Cannot enter %s: current branch '%s' does not match task branch '%s'. "
                "Run: git checkout %s" % (phase, current_branch, expected_branch, expected_branch),
                phase=phase,
                current_branch=current_branch,
                expected_branch=expected_branch,
            )

    # Validate prerequisite input exists and is non-empty
    input_path = _phase_input_file(phase, task_dir)
    if input_path and not file_exists_nonempty(input_path):
        return fail(
            "input_not_ready",
            "Cannot enter %s: prerequisite input %s is missing or empty" % (phase, os.path.basename(input_path)),
            phase=phase,
            input_file=os.path.basename(input_path),
        )

    # Update task.json
    if isinstance(task, dict):
        if "phase" not in task:
            task["phase"] = {}
        task["phase"]["current"] = phase
    _write_json(os.path.join(task_dir, "task.json"), task)

    # Update session
    session_path = session_file_from_task(task_dir)
    err_s, session = read_json(session_path)
    if err_s is not None:
        # Create session if missing
        session = {"session": {}}
    if isinstance(session, dict) and "session" in session:
        session["session"]["current_phase"] = phase
        session["session"]["dirty"] = True
        session["session"]["last_updated_at"] = _now_iso()
    _write_json(session_path, session)

    return ok(phase=phase, action="entry", task_dir=task_dir)


def _phase_output_files(phase, task_dir):
    """Return the output artifact paths for a phase."""
    mapping = {
        "specify": ["proposal.md", "design.md"],
        "plan": ["tasks.md"],
    }
    filenames = mapping.get(phase)
    if filenames:
        return [task_file(task_dir, f) for f in filenames]
    return None


def _validate_phase_artifact(phase, task_dir):
    """Validate that the phase's output artifact exists and is non-empty.

    For generate phase, validates that ALL tasks are marked done (stricter than
    minimum_check which only requires at least one task done for status queries).

    Returns an error dict if validation fails, or None if OK.
    """
    if phase == "generate":
        # generate: ALL tasks must have Status: [x] done
        result = minimum_check_generate(task_dir)
        if not result.get("ok"):
            return fail("artifact_not_ready", "Cannot exit %s: %s" % (phase, result.get("message", "artifact validation failed")), phase=phase)
        # minimum_check passed (at least one done), now check ALL are done
        total = result.get("phase_status_total", 0)
        checked = result.get("phase_status_checked", 0)
        if checked < total:
            return fail(
                "artifact_not_ready",
                "Cannot exit %s: %d of %d tasks completed — all tasks must be done before exiting generate" % (phase, checked, total),
                phase=phase,
                tasks_completed=checked,
                tasks_total=total,
            )
        return None

    if phase == "consolidate":
        # consolidate: noop confirmed or at least one knowledge file updated
        result = minimum_check_consolidate(task_dir)
        if not result.get("ok"):
            return fail("artifact_not_ready", "Cannot exit %s: %s" % (phase, result.get("message", "artifact validation failed")), phase=phase)
        return None

    output_paths = _phase_output_files(phase, task_dir)
    if output_paths:
        for output_path in output_paths:
            if not file_exists_nonempty(output_path):
                return fail(
                    "artifact_not_ready",
                    "Cannot exit %s: output artifact %s is missing or empty" % (phase, os.path.basename(output_path)),
                    phase=phase,
                    artifact=os.path.basename(output_path),
                )
    return None


def _validate_task_done(task_dir, task_id):
    """Verify that the specified task_id is marked [x] done in tasks.md.

    Returns (is_done: bool, message: str).
    """
    if not re.match(r'^T\d+$', task_id):
        return False, "Invalid task ID format: %s (expected T<N>)" % task_id

    tasks_path = task_file(task_dir, "tasks.md")
    text = read_text(tasks_path)
    if not text:
        return False, "tasks.md not found or empty"

    # Find the task section: ### T1: ...
    task_re = re.compile(r"^###\s+(%s)\s*:" % re.escape(task_id), re.MULTILINE)
    m = task_re.search(text)
    if not m:
        return False, "Task %s not found in tasks.md" % task_id

    # Extract section until next ### or end of file
    start = m.start()
    rest = text[start + len(m.group(0)):]
    next_heading = re.search(r"^###\s+", rest, re.MULTILINE)
    section = text[start:start + len(m.group(0)) + (next_heading.start() if next_heading else len(rest))]

    # Check **Status**: [x] done
    status_match = re.search(r"\*\*Status\*\*:\s*\[[xX]\]\s*done", section)
    if status_match:
        return True, ""
    return False, "Task %s is not marked as done in tasks.md" % task_id


def cmd_task_checkpoint(args):
    """Task Checkpoint: validate task done -> git commit -> update session/task.json -> record commit.

    Creates two commits per task checkpoint (consistent with phase-exit pattern):
    1. Work commit: captures all code + tasks.md changes
    2. Record commit: captures task.json + session updates with the hash
    """
    task_dir = os.path.abspath(args.task)
    task_id = args.task_id
    repo = os.path.abspath(args.repo)

    # 1. Load task.json
    err, task = load_task_json(task_dir)
    if err is not None:
        return err

    # 2. Validate task_id format
    if not re.match(r'^T\d+$', task_id):
        return fail("invalid_task_id", "Task ID must match T<N> format, got: %s" % task_id, task_id=task_id)

    # 3. Branch check
    expected_branch = task.get("task", {}).get("branch", "") if isinstance(task, dict) else ""
    if expected_branch:
        try:
            current_branch = subprocess.check_output(
                ["git", "-C", repo, "rev-parse", "--abbrev-ref", "HEAD"]
            ).decode("utf-8").strip()
        except subprocess.CalledProcessError:
            current_branch = ""
        if current_branch != expected_branch:
            return fail(
                "wrong_branch",
                "Cannot checkpoint %s: current branch '%s' does not match task branch '%s'. "
                "Run: git checkout %s" % (task_id, current_branch, expected_branch, expected_branch),
                task_id=task_id,
                current_branch=current_branch,
                expected_branch=expected_branch,
            )

    # 4. Validate task is done
    is_done, msg = _validate_task_done(task_dir, task_id)
    if not is_done:
        return fail("task_not_done", msg, task_id=task_id)

    task_name = task.get("task", {}).get("name", "unknown") if isinstance(task, dict) else "unknown"

    # 5. Task checkpoint commit (all code + tasks.md changes)
    checkpoint_hash = ""
    try:
        checkpoint_hash = _git_commit(
            repo,
            "diorama(task): finish %s %s" % (task_id, task_name),
        )
    except subprocess.CalledProcessError:
        return fail("no_changes_to_commit", "Cannot checkpoint %s: no changes to commit" % task_id, task_id=task_id)

    # 6. Update session
    session_path = session_file_from_task(task_dir)
    err_s, session = read_json(session_path)
    if err_s is not None:
        session = {"session": {}}
    if isinstance(session, dict) and "session" in session:
        session["session"]["last_checkpoint_commit"] = checkpoint_hash
        session["session"]["last_task_checkpoint"] = checkpoint_hash
        session["session"]["dirty"] = False
        session["session"]["last_updated_at"] = _now_iso()
    _write_json(session_path, session)

    # 7. Update task.json
    if isinstance(task, dict):
        if "phase" not in task:
            task["phase"] = {}
        task_phase = task["phase"]
        if "task_checkpoints" not in task_phase:
            task_phase["task_checkpoints"] = {}
        task_phase["task_checkpoints"][task_id] = checkpoint_hash
    _write_json(os.path.join(task_dir, "task.json"), task)

    # 8. Task checkpoint record commit
    try:
        _git_commit(
            repo,
            "diorama(task-checkpoint): %s %s" % (task_id, task_name),
            paths=[os.path.join(task_dir, "task.json"), session_path],
        )
    except subprocess.CalledProcessError:
        pass  # nothing to commit (unlikely)

    return ok(
        task_id=task_id,
        checkpoint=checkpoint_hash,
        action="task_checkpoint",
        task_dir=task_dir,
    )


def cmd_phase_exit(args):
    """Phase Exit: validate branch → validate artifact → git checkpoint + update session/task.json + handoff commit."""
    task_dir = os.path.abspath(args.task)
    phase = args.phase
    repo = os.path.abspath(args.repo)

    err, task = load_task_json(task_dir)
    if err is not None:
        return err

    # Validate current git branch matches task branch
    expected_branch = task.get("task", {}).get("branch", "") if isinstance(task, dict) else ""
    if expected_branch:
        try:
            current_branch = subprocess.check_output(
                ["git", "-C", repo, "rev-parse", "--abbrev-ref", "HEAD"]
            ).decode("utf-8").strip()
        except subprocess.CalledProcessError:
            current_branch = ""
        if current_branch != expected_branch:
            return fail(
                "wrong_branch",
                "Cannot exit %s: current branch '%s' does not match task branch '%s'. "
                "Run: git checkout %s" % (phase, current_branch, expected_branch, expected_branch),
                phase=phase,
                current_branch=current_branch,
                expected_branch=expected_branch,
            )

    # Validate output artifact exists and is non-empty
    artifact_err = _validate_phase_artifact(phase, task_dir)
    if artifact_err is not None:
        return artifact_err

    # Validate task_checkpoints for generate phase (mandatory)
    if phase == "generate" and isinstance(task, dict):
        task_checkpoints = task.get("phase", {}).get("task_checkpoints", {})
        if not task_checkpoints:
            return fail(
                "no_task_checkpoints",
                "Cannot exit generate: no task_checkpoints recorded — each task must be checkpointed via task-checkpoint before generate phase exit",
                phase=phase,
            )

    task_name = task.get("task", {}).get("name", "unknown") if isinstance(task, dict) else "unknown"
    next_phase = next_phase_of(phase)

    # For generate phase: code was already committed per task, so we need to
    # write metadata updates to disk FIRST so git has actual changes to commit.
    # For other phases: commit code changes first, then update metadata.
    if phase == "generate":
        # Write task.json checkpoint update before committing
        if isinstance(task, dict):
            task["phase"]["checkpoints"] = task["phase"].get("checkpoints", {})
            task["phase"]["checkpoints"][phase] = ""  # placeholder, will update after commit
            completed = task["phase"].get("completed") or []
            if phase not in completed:
                completed.append(phase)
            task["phase"]["completed"] = completed
            task["phase"]["current"] = next_phase
        _write_json(os.path.join(task_dir, "task.json"), task)

        # Write session update before committing
        session_path = session_file_from_task(task_dir)
        err_s, session = read_json(session_path)
        if err_s is not None:
            session = {"session": {}}
        if isinstance(session, dict) and "session" in session:
            session["session"]["last_checkpoint_commit"] = ""  # placeholder
            session["session"]["dirty"] = False
            session["session"]["last_updated_at"] = _now_iso()
            session["session"]["current_phase"] = next_phase
            session["session"]["last_task_checkpoint"] = ""  # clear: no longer in generate
        _write_json(session_path, session)

        # Now commit the metadata changes
        checkpoint_hash = ""
        try:
            checkpoint_hash = _git_commit(
                repo,
                "diorama(%s): finish %s %s" % (phase, phase, task_name),
            )
        except subprocess.CalledProcessError:
            return fail(
                "no_changes_to_commit",
                "Cannot exit %s: no changes to commit — phase produced no actual artifacts" % phase,
                phase=phase,
            )

        # Update placeholder hashes with actual commit hash
        task["phase"]["checkpoints"][phase] = checkpoint_hash
        _write_json(os.path.join(task_dir, "task.json"), task)
        session["session"]["last_checkpoint_commit"] = checkpoint_hash
        _write_json(session_path, session)

        # Handoff commit
        try:
            _git_commit(
                repo,
                "diorama(handoff): %s → %s" % (phase, next_phase),
                paths=[os.path.join(task_dir, "task.json"), session_path],
            )
        except subprocess.CalledProcessError:
            pass

        return ok(
            phase=phase,
            next_phase=next_phase,
            checkpoint=checkpoint_hash,
            action="exit",
            task_dir=task_dir,
        )

    # --- Non-generate phases: existing order (commit code, then update metadata) ---
    checkpoint_hash = ""
    try:
        checkpoint_hash = _git_commit(
            repo,
            "diorama(%s): finish %s %s" % (phase, phase, task_name),
        )
    except subprocess.CalledProcessError:
        return fail(
            "no_changes_to_commit",
            "Cannot exit %s: no changes to commit — phase produced no actual artifacts" % phase,
            phase=phase,
        )

    # 2. Update session
    session_path = session_file_from_task(task_dir)
    err_s, session = read_json(session_path)
    if err_s is not None:
        session = {"session": {}}
    if isinstance(session, dict) and "session" in session:
        session["session"]["last_checkpoint_commit"] = checkpoint_hash
        session["session"]["dirty"] = False
        session["session"]["last_updated_at"] = _now_iso()
        session["session"]["current_phase"] = next_phase
        # When exiting generate, clear last_task_checkpoint (no longer in generate)
        if phase == "generate":
            session["session"]["last_task_checkpoint"] = ""
    _write_json(session_path, session)

    # 3. Update task.json
    result_extra = {}
    if isinstance(task, dict):
        if "phase" not in task:
            task["phase"] = {}
        task["phase"]["checkpoints"] = task["phase"].get("checkpoints", {})
        task["phase"]["checkpoints"][phase] = checkpoint_hash
        completed = task["phase"].get("completed") or []
        if phase not in completed:
            completed.append(phase)
        task["phase"]["completed"] = completed
        task["phase"]["current"] = next_phase
    _write_json(os.path.join(task_dir, "task.json"), task)

    # 4. Handoff commit
    try:
        _git_commit(
            repo,
            "diorama(handoff): %s → %s" % (phase, next_phase),
            paths=[os.path.join(task_dir, "task.json"), session_path],
        )
    except subprocess.CalledProcessError:
        pass  # nothing to commit

    return ok(
        phase=phase,
        next_phase=next_phase,
        checkpoint=checkpoint_hash,
        action="exit",
        task_dir=task_dir,
    )


def cmd_rewind_exec(args):
    """Execute rewind: working-tree-check -> git reset -> update JSON -> rewind commit.

    Supports both phase-level targets (specify, plan, generate, consolidate)
    and task-level targets (T1, T2, ...) within the generate phase.
    """
    task_dir = os.path.abspath(args.task)
    target = args.phase
    repo = os.path.abspath(args.repo)

    # Determine target type
    if target in PHASES:
        return _rewind_to_phase(task_dir, target, repo)
    elif re.match(r'^T\d+$', target):
        return _rewind_to_task(task_dir, target, repo)
    else:
        return fail("invalid_rewind_target", "Invalid rewind target: %s (expected phase name or task ID like T1, T2)" % target, target=target)


def _rewind_to_phase(task_dir, phase, repo):
    """Execute phase-level rewind (existing logic, updated to clear task_checkpoints)."""
    # 1. Validate working tree is clean
    wt = run_working_tree_check(repo)
    if not wt.get("ok"):
        return wt
    if not wt.get("clean"):
        return fail("working_tree_dirty", "Cannot rewind: working tree is not clean. Commit or stash changes first.", repo=repo)

    err, task = load_task_json(task_dir)
    if err is not None:
        return err

    task_phase = task.get("phase", {}) if isinstance(task, dict) else {}
    checkpoints = task_phase.get("checkpoints", {}) if isinstance(task_phase, dict) else {}
    checkpoint = checkpoints.get(phase, "")

    if not checkpoint:
        return fail("checkpoint_not_found", "Cannot rewind to %s: checkpoint is empty" % phase, target=phase)

    # 2. Git reset --hard <checkpoint>
    try:
        subprocess.check_call(["git", "-C", repo, "reset", "--hard", checkpoint])
    except subprocess.CalledProcessError as exc:
        return fail("git_reset_failed", str(exc), checkpoint=checkpoint)

    # 3. Update task.json
    phase_order = ["specify", "plan", "generate", "consolidate"]
    phase_idx = phase_order.index(phase) if phase in phase_order else 0

    # Remove completed phases after target
    completed = task_phase.get("completed") or []
    completed = [p for p in phase_order[:phase_idx] if p in completed]
    # Note: target phase itself is NOT in completed (we're rewinding to redo it)

    # Clear checkpoints after target
    new_checkpoints = {}
    for p in phase_order:
        if p in checkpoints:
            if phase_order.index(p) < phase_idx:
                new_checkpoints[p] = checkpoints[p]
            else:
                new_checkpoints[p] = ""

    if isinstance(task, dict):
        task["phase"] = task.get("phase", {})
        task["phase"]["current"] = phase
        task["phase"]["completed"] = completed
        task["phase"]["checkpoints"] = new_checkpoints
        task["phase"]["task_checkpoints"] = {}

        # Anchors cleanup
        if phase in ("specify", "plan"):
            task["anchors"] = {"dev_entries": [], "acceptance_entries": []}
        # generate: keep all anchors

        # Reset consolidate tracking on any rewind
        task["consolidate"] = {"noop": False, "updated_files": []}

    _write_json(os.path.join(task_dir, "task.json"), task)

    # 4. Update session
    session_path = session_file_from_task(task_dir)
    err_s, session = read_json(session_path)
    if err_s is not None:
        session = {"session": {}}
    if isinstance(session, dict) and "session" in session:
        session["session"]["current_phase"] = phase
        session["session"]["last_checkpoint_commit"] = checkpoints.get(phase_order[phase_idx - 1], "") if phase_idx > 0 else ""
        session["session"]["last_task_checkpoint"] = ""
        session["session"]["dirty"] = False
        session["session"]["last_updated_at"] = _now_iso()
    _write_json(session_path, session)

    # 5. Rewind commit
    try:
        _git_commit(
            repo,
            "diorama(rewind): → %s" % phase,
            paths=[os.path.join(task_dir, "task.json"), session_path],
        )
    except subprocess.CalledProcessError:
        pass

    return ok(
        target=phase,
        target_type="phase",
        checkpoint_used=checkpoint,
        completed_after_rewind=completed,
        action="rewind",
        task_dir=task_dir,
    )


def _rewind_to_task(task_dir, target, repo):
    """Execute task-level rewind within generate phase.

    rewind T1 -> reset to plan checkpoint, clear all task_checkpoints
    rewind TN -> reset to T(N-1) checkpoint, keep T1..T(N-1), clear TN+
    """
    # 1. Validate working tree is clean
    wt = run_working_tree_check(repo)
    if not wt.get("ok"):
        return wt
    if not wt.get("clean"):
        return fail("working_tree_dirty", "Cannot rewind: working tree is not clean. Commit or stash changes first.", repo=repo)

    err, task = load_task_json(task_dir)
    if err is not None:
        return err

    task_phase = task.get("phase", {}) if isinstance(task, dict) else {}
    checkpoints = task_phase.get("checkpoints", {}) if isinstance(task_phase, dict) else {}
    task_checkpoints = task_phase.get("task_checkpoints", {}) if isinstance(task_phase, dict) else {}
    current = task_phase.get("current", "")

    # 2. Verify we're in generate phase
    if current != "generate":
        return fail("invalid_rewind_target", "Task-level rewind is only valid during generate phase, current: %s" % current, target=target)

    # 3. Determine reset hash
    task_num = int(target[1:])
    if task_num == 1:
        reset_hash = checkpoints.get("plan", "")
        if not reset_hash:
            return fail("checkpoint_not_found", "Cannot rewind to T1: plan checkpoint is empty", target=target)
    else:
        prev_task_id = "T%d" % (task_num - 1)
        reset_hash = task_checkpoints.get(prev_task_id, "")
        if not reset_hash:
            return fail("checkpoint_not_found", "Cannot rewind to %s: %s checkpoint is empty" % (target, prev_task_id), target=target)

    # 4. Compute new task_checkpoints from PRE-RESET state (clear target and subsequent)
    new_task_checkpoints = {}
    for tid, thash in task_checkpoints.items():
        if re.match(r'^T(\d+)$', tid):
            tid_num = int(tid[1:])
            if tid_num < task_num:
                new_task_checkpoints[tid] = thash

    # 5. Git reset --hard <reset_hash>
    try:
        subprocess.check_call(["git", "-C", repo, "reset", "--hard", reset_hash])
    except subprocess.CalledProcessError as exc:
        return fail("git_reset_failed", str(exc), checkpoint=reset_hash)

    # 6. Re-read task.json (post-reset state) for phase state
    err, task = load_task_json(task_dir)
    if err is not None:
        return err

    # 7. Update task.json
    if isinstance(task, dict):
        task["phase"] = task.get("phase", {})
        task["phase"]["current"] = "generate"
        task["phase"]["task_checkpoints"] = new_task_checkpoints
        # anchors: keep all (generate rewind)
        # consolidate: reset
        task["consolidate"] = {"noop": False, "updated_files": []}
    _write_json(os.path.join(task_dir, "task.json"), task)

    # 8. Update session
    session_path = session_file_from_task(task_dir)
    err_s, session = read_json(session_path)
    if err_s is not None:
        session = {"session": {}}

    # Determine last_task_checkpoint from remaining checkpoints
    last_tc = ""
    if new_task_checkpoints:
        max_tid = max(
            [int(k[1:]) for k in new_task_checkpoints.keys() if re.match(r'^T\d+$', k)],
            default=0,
        )
        if max_tid > 0:
            last_tc = new_task_checkpoints.get("T%d" % max_tid, "")

    if isinstance(session, dict) and "session" in session:
        session["session"]["current_phase"] = "generate"
        session["session"]["last_checkpoint_commit"] = reset_hash
        session["session"]["last_task_checkpoint"] = last_tc
        session["session"]["dirty"] = False
        session["session"]["last_updated_at"] = _now_iso()
    _write_json(session_path, session)

    # 9. Rewind commit
    try:
        _git_commit(
            repo,
            "diorama(rewind): → %s" % target,
            paths=[os.path.join(task_dir, "task.json"), session_path],
        )
    except subprocess.CalledProcessError:
        pass

    return ok(
        target=target,
        target_type="task",
        checkpoint_used=reset_hash,
        task_checkpoints_after_rewind=new_task_checkpoints,
        action="rewind",
        task_dir=task_dir,
    )


def cmd_conformance_check(args):
    """Conformance-check: verify design.md → tasks.md → code traceability and rules index verification."""
    task_dir = os.path.abspath(args.task)
    if not os.path.isdir(task_dir):
        return fail("invalid_task_path", "Task directory not found", task=task_dir)
    repo = os.path.abspath(args.repo) if args.repo else None
    return conformance_check(task_dir, repo=repo)


def cmd_design_amend(args):
    """Lightweight design amendment during generate phase."""
    task_dir = os.path.abspath(args.task)
    repo = os.path.abspath(args.repo)
    summary = args.summary

    if not summary or not summary.strip():
        return fail("empty_summary", "Amendment summary is required")

    # 1. Read task.json
    task_path = os.path.join(task_dir, "task.json")
    err_t, task = read_json(task_path)
    if err_t is not None:
        return err_t

    # 2. Validate current phase == generate
    current_phase = task.get("phase", {}).get("current", "")
    if current_phase != "generate":
        return fail("wrong_phase", "design-amend is only allowed during generate phase, current: %s" % current_phase, phase=current_phase)

    # 3. Validate design.md exists
    design_path = os.path.join(task_dir, "design.md")
    if not file_exists_nonempty(design_path):
        return fail("design_not_found", "design.md not found or empty", path=design_path)

    # 4. Append Amendments section to design.md
    design_text = read_text(design_path)
    timestamp = _now_iso()
    amendment_entry = "\n- **%s**: %s\n" % (timestamp, summary.strip())

    if "## Amendments" in design_text:
        design_text = design_text.rstrip("\n") + amendment_entry + "\n"
    else:
        design_text = design_text.rstrip("\n") + "\n\n## Amendments\n" + amendment_entry + "\n"

    with open(design_path, "w") as f:
        f.write(design_text)

    # 5. Update task.json: add amendments array entry
    if "amendments" not in task:
        task["amendments"] = []
    task["amendments"].append({"timestamp": timestamp, "summary": summary.strip()})
    _write_json(task_path, task)

    # 6. Git commit
    commit_hash = _git_commit(repo, "diorama(amend): %s" % summary.strip())

    return ok(
        action="design_amend",
        summary=summary.strip(),
        timestamp=timestamp,
        commit=commit_hash,
    )


def cmd_cancel(args):
    """Cancel current task: mark cancelled, preserve branch, clear session, switch to base_branch."""
    task_dir = os.path.abspath(args.task)
    repo = os.path.abspath(args.repo)

    # 1. Read task.json
    task_path = os.path.join(task_dir, "task.json")
    err_t, task = read_json(task_path)
    if err_t is not None:
        return err_t
    task_task = task.get("task", {})
    task_name = task_task.get("name", "")
    task_branch = task_task.get("branch", "")
    base_branch = task_task.get("base_branch", "")
    current_phase = task.get("phase", {}).get("current", "")

    if not task_branch or not base_branch:
        return fail("missing_branch_info", "task.json missing branch or base_branch field", task_dir=task_dir)

    # 2. Reject if already done or cancelled
    if current_phase == "done":
        return fail("already_done", "Cannot cancel: task is already done", task=task_name)
    if current_phase == "cancelled":
        return fail("already_cancelled", "Cannot cancel: task is already cancelled", task=task_name)

    # 3. If working tree dirty, commit WIP first
    wt = run_working_tree_check(repo)
    if wt.get("ok") and not wt.get("clean"):
        try:
            subprocess.check_call(["git", "-C", repo, "add", "-A"])
            subprocess.check_call(["git", "-C", repo, "commit", "-m", "diorama(cancel-wip): %s" % task_name])
        except subprocess.CalledProcessError:
            pass

    # 4. Update task.json: phase.current = cancelled
    task["phase"]["current"] = "cancelled"
    _write_json(task_path, task)

    # 5. Clear session
    diorama_root = find_diorama_root(task_dir)
    session_path = os.path.join(diorama_root, "session", "current-session.json")
    session_data = {
        "session": {
            "current_task": "",
            "current_phase": "",
            "last_checkpoint_commit": "",
            "last_task_checkpoint": "",
            "last_updated_at": _now_iso(),
            "dirty": False,
        }
    }
    _write_json(session_path, session_data)

    # 6. Commit cancel
    try:
        _git_commit(repo, "diorama(cancel): %s" % task_name)
    except subprocess.CalledProcessError:
        pass

    # 7. Switch to base_branch (preserve task branch)
    try:
        subprocess.check_call(["git", "-C", repo, "checkout", base_branch])
    except subprocess.CalledProcessError as exc:
        return fail("checkout_base_failed", "Failed to checkout base branch %s: %s" % (base_branch, str(exc)), base_branch=base_branch)

    return ok(
        action="cancel",
        task=task_name,
        task_branch=task_branch,
        base_branch=base_branch,
    )


def cmd_merge_back(args):
    """Task completion: merge task branch back to base_branch."""
    task_dir = os.path.abspath(args.task)
    repo = os.path.abspath(args.repo)

    # 1. Read base_branch from task.json
    task_path = os.path.join(task_dir, "task.json")
    err_t, task = read_json(task_path)
    if err_t is not None:
        return err_t
    task_branch = task.get("task", {}).get("branch", "")
    base_branch = task.get("task", {}).get("base_branch", "")
    if not task_branch or not base_branch:
        return fail("missing_branch_info", "task.json missing branch or base_branch field", task_dir=task_dir)

    # 2. Check working tree is clean
    wt = run_working_tree_check(repo)
    if not wt.get("ok"):
        return wt
    if not wt.get("clean"):
        return fail("working_tree_dirty", "Cannot merge-back: working tree is not clean. Commit or stash changes first.", repo=repo)

    # 3. Switch to base_branch
    try:
        subprocess.check_call(["git", "-C", repo, "checkout", base_branch])
    except subprocess.CalledProcessError as exc:
        return fail("checkout_base_failed", "Failed to checkout base branch %s: %s" % (base_branch, str(exc)), base_branch=base_branch)

    # 4. Merge task branch into base_branch (always create merge commit)
    try:
        subprocess.check_call(["git", "-C", repo, "merge", "--no-ff", task_branch])
    except subprocess.CalledProcessError as exc:
        # Merge conflict — abort and report
        subprocess.check_call(["git", "-C", repo, "merge", "--abort"])
        return fail("merge_conflict", "Merge conflict when merging %s into %s. Aborted merge." % (task_branch, base_branch), task_branch=task_branch, base_branch=base_branch)

    # 5. Success
    return ok(
        action="merge_back",
        task_branch=task_branch,
        base_branch=base_branch,
        repo=repo,
    )


def build_parser():
    parser = argparse.ArgumentParser(description="Diorama runtime helper")
    sub = parser.add_subparsers(dest="command")

    p = sub.add_parser("status-check")
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.add_argument("--repo", default=".", help="Repository root")
    p.set_defaults(func=cmd_status_check)

    p = sub.add_parser("status-render")
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.add_argument("--repo", default=".", help="Repository root")
    p.set_defaults(func=cmd_status_render)

    p = sub.add_parser("minimum-check")
    p.add_argument("--phase", required=True, choices=PHASES)
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.set_defaults(func=cmd_minimum_check)

    p = sub.add_parser("resolve-next-phase")
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.set_defaults(func=cmd_resolve_next_phase)

    p = sub.add_parser("working-tree-check")
    p.add_argument("--repo", default=".", help="Repository root")
    p.set_defaults(func=cmd_working_tree_check)

    p = sub.add_parser("rewind-target-check")
    p.add_argument("--phase", required=True, help="Target phase or task ID (e.g., T1, T2)")
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.set_defaults(func=cmd_rewind_target_check)

    p = sub.add_parser("incept")
    p.add_argument("--name", required=True, help="Task name")
    p.add_argument("--branch", default="", help="Git branch (default: task/<name>)")
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.add_argument("--repo", default=".", help="Repository root")
    p.set_defaults(func=cmd_incept)

    p = sub.add_parser("phase-entry")
    p.add_argument("--phase", required=True, choices=PHASES)
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.add_argument("--repo", default=".", help="Repository root")
    p.set_defaults(func=cmd_phase_entry)

    p = sub.add_parser("phase-exit")
    p.add_argument("--phase", required=True, choices=PHASES)
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.add_argument("--repo", default=".", help="Repository root")
    p.set_defaults(func=cmd_phase_exit)

    p = sub.add_parser("rewind-exec")
    p.add_argument("--phase", required=True, help="Target phase or task ID (e.g., T1, T2)")
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.add_argument("--repo", default=".", help="Repository root")
    p.set_defaults(func=cmd_rewind_exec)

    p = sub.add_parser("merge-back")
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.add_argument("--repo", default=".", help="Repository root")
    p.set_defaults(func=cmd_merge_back)

    p = sub.add_parser("cancel")
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.add_argument("--repo", default=".", help="Repository root")
    p.set_defaults(func=cmd_cancel)

    p = sub.add_parser("design-amend")
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.add_argument("--repo", default=".", help="Repository root")
    p.add_argument("--summary", required=True, help="Amendment summary")
    p.set_defaults(func=cmd_design_amend)

    p = sub.add_parser("conformance-check")
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.add_argument("--repo", default=".", help="Repository root")
    p.set_defaults(func=cmd_conformance_check)

    p = sub.add_parser("task-checkpoint")
    p.add_argument("--task-id", required=True, help="Task ID (e.g., T1, T2)")
    p.add_argument("--task", required=True, help="Path to .diorama/tasks/<task>")
    p.add_argument("--repo", default=".", help="Repository root")
    p.set_defaults(func=cmd_task_checkpoint)

    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if not hasattr(args, "func"):
        parser.print_help()
        return 2

    result = args.func(args)
    if args.command != "status-render":
        print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
