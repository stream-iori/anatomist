"""Small Anatomist-specific checks for optional Jury Agent E2E runs."""

from __future__ import annotations

import json
import fnmatch
import hashlib
import os
import re
import shlex
import shutil
import sqlite3
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from evidence_contracts import semantic_frames, embedded_semantic_frames, diff_documents
from version_checks import validate_diff, navigation_sides


SOURCE_ROOT = Path(__file__).resolve().parents[1]
_COMMAND_KEYS = {"argv", "cmd", "command", "commands", "script"}
_QUERY_COMMANDS = {
    "doctor",
    "search",
    "declarations-of",
    "overview",
    "resolve",
    "describe",
    "members",
    "type-relations",
    "runtime-implementations",
    "callable-relations",
    "bindings",
    "annotations",
    "related-docs",
    "references",
    "calls",
    "dispatch",
    "accesses",
    "regions",
    "sites-in",
    "trace",
    "source",
}
_SEMANTIC_PIPELINE_COMMANDS = {
    "search",
    "resolve",
    "describe",
    "members",
    "type-relations",
    "runtime-implementations",
    "callable-relations",
    "bindings",
    "annotations",
    "related-docs",
    "references",
    "calls",
    "dispatch",
    "accesses",
    "regions",
    "sites-in",
    "trace",
    "source",
}


@dataclass(frozen=True)
class _AdapterWorkspace:
    agent_cwd: str | None = None
    env: dict[str, str | None] = field(default_factory=dict)
    metadata: dict[str, object] = field(default_factory=dict)


def _safe_relative(value: object, name: str) -> Path:
    candidate = Path(str(value or ""))
    if not value or candidate.is_absolute() or ".." in candidate.parts:
        raise ValueError(f"anatomist.{name} must be a safe relative path")
    return candidate


def _resolve_binary() -> Path:
    configured = os.environ.get("ANATOMIST_E2E_BIN")
    candidates = (
        [Path(configured)] if configured else [SOURCE_ROOT / "target/anatomist"]
    )
    for candidate in candidates:
        resolved = candidate.expanduser().resolve()
        if resolved.is_file() and os.access(resolved, os.X_OK):
            return resolved
    raise RuntimeError(
        "no executable Anatomist binary; run `just native` or set ANATOMIST_E2E_BIN"
    )


def _command_values(value: object) -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key in _COMMAND_KEYS:
                if isinstance(child, str):
                    found.append(child)
                elif isinstance(child, list) and all(
                    isinstance(item, str) for item in child
                ):
                    found.append(shlex.join(child))
            if isinstance(child, (dict, list)):
                found.extend(_command_values(child))
    elif isinstance(value, list):
        for child in value:
            if isinstance(child, (dict, list)):
                found.extend(_command_values(child))
    return found


def trace_commands(trace: object) -> list[str]:
    """Extract terminal commands without treating command output as an invocation."""
    commands: list[str] = []
    for event in getattr(trace, "tool_events", []):
        candidates: list[str] = []
        name = str(getattr(event, "name", "") or "")
        if name and name not in {"command_execution", "exec_command", "shell"}:
            candidates.append(name.strip("`"))
        argument_commands = _command_values(getattr(event, "arguments", {}))
        raw_input = getattr(event, "raw_input", {})
        action_commands = []
        if isinstance(raw_input, dict):
            action_commands = _command_values(raw_input.get("command_actions", []))
        candidates.extend(argument_commands)
        candidates.extend(action_commands or _command_values(raw_input))
        for candidate in candidates:
            if candidate and candidate not in commands:
                commands.append(candidate)
    return commands


def _event_commands(event: object) -> list[str]:
    raw_input = getattr(event, "raw_input", {})
    if isinstance(raw_input, dict):
        actions = _command_values(raw_input.get("command_actions", []))
        if actions:
            return actions
    arguments = _command_values(getattr(event, "arguments", {}))
    if arguments:
        return arguments
    return _command_values(raw_input)


def _segments(command: str) -> list[str]:
    return [part.strip() for part in re.split(r"&&|\|\||(?<!\|)\|(?!\|)|[;\n]", command) if part.strip()]


def _anatomist_segment(segment: str) -> tuple[str, list[str]] | None:
    try:
        tokens = shlex.split(segment)
    except ValueError:
        tokens = segment.split()
    for index, token in enumerate(tokens):
        executable = token.strip("'\"`)")
        if Path(executable).name != "anatomist":
            continue
        args = tokens[index + 1 :]
        for argument in args:
            if not argument.startswith("-"):
                return argument, args
        return None
    return None


def _fused_pipeline_segments(command: str) -> list[dict[str, object]]:
    parsed = _anatomist_segment(command)
    if parsed is None or parsed[0] != "pipeline":
        return []
    args = parsed[1]
    try:
        separator = args.index("--")
    except ValueError:
        return []
    global_args = args[1:separator]
    stages: list[list[str]] = [[]]
    for token in args[separator + 1:]:
        if token == "--then":
            stages.append([])
        else:
            stages[-1].append(token)
    if len(stages) < 2 or any(not stage for stage in stages):
        return []
    return [
        {
            "command": shlex.join(stage),
            "subcommand": stage[0],
            "args": (global_args if index == 0 else []) + stage[1:],
        }
        for index, stage in enumerate(stages)
    ]


def anatomist_invocations(trace: object) -> list[dict[str, object]]:
    """Return ordered Anatomist invocations with the output visible to the Agent."""
    invocations: list[dict[str, object]] = []
    for event_index, event in enumerate(getattr(trace, "tool_events", [])):
        raw_input = getattr(event, "raw_input", {})
        aggregated = raw_input.get("aggregated_output", "") if isinstance(raw_input, dict) else ""
        output = str(
            getattr(event, "raw_output", "")
            or aggregated
        )
        exit_code = raw_input.get("exit_code") if isinstance(raw_input, dict) else None
        for command in _event_commands(event):
            for segment in _segments(command):
                parsed = _anatomist_segment(segment)
                if parsed is None:
                    continue
                subcommand, args = parsed
                invocations.append(
                    {
                        "event_index": event_index,
                        "command": segment,
                        "subcommand": subcommand,
                        "args": args,
                        "output": output,
                        "exit_code": exit_code,
                    }
                )
    return invocations


def executed_anatomist_invocations(trace: object) -> list[dict[str, object]]:
    """Return real command executions, excluding syntax-only ``--help`` reads."""
    result: list[dict[str, object]] = []
    for invocation in anatomist_invocations(trace):
        if "--help" in invocation["args"]:
            continue
        result.append(invocation)
        for segment in _fused_pipeline_segments(str(invocation["command"])):
            result.append({**invocation, **segment})
    return result


def anatomist_subcommands(commands: list[str]) -> list[str]:
    """Return Anatomist subcommands in observed execution order."""
    result: list[str] = []
    for command in commands:
        for segment in re.split(r"&&|\|\||(?<!\|)\|(?!\|)|[;\n]", command):
            try:
                tokens = shlex.split(segment)
            except ValueError:
                tokens = segment.split()
            for index, token in enumerate(tokens):
                executable = token.strip("'\"`)")
                if Path(executable).name != "anatomist":
                    continue
                for argument in tokens[index + 1 :]:
                    if not argument.startswith("-"):
                        result.append(argument)
                        break
                break
    return result


def semantic_pipelines(trace: object) -> list[dict[str, object]]:
    """Return actual shell pipelines made exclusively of semantic CLI commands.

    A flat subcommand trace is insufficient evidence: an Agent could run modern
    commands separately and never pass a framed stream between them.  Keep the
    original pipe boundary from the command event and attach the terminal output
    that was visible to the Agent.
    """
    pipelines: list[dict[str, object]] = []
    for event_index, event in enumerate(getattr(trace, "tool_events", [])):
        raw_input = getattr(event, "raw_input", {})
        aggregated = raw_input.get("aggregated_output", "") if isinstance(raw_input, dict) else ""
        output = str(getattr(event, "raw_output", "") or aggregated)
        exit_code = raw_input.get("exit_code") if isinstance(raw_input, dict) else None
        for command in _event_commands(event):
            for chain in re.split(r"&&|\|\||[;\n]", command):
                parts = [part.strip() for part in re.split(r"(?<!\|)\|(?!\|)", chain) if part.strip()]
                # tee preserves the full stream while saving evidence; filters
                # such as jq/head do not and remain unsupported.
                parts = [part for part in parts if part.split(maxsplit=1)[0] != "tee"]
                if len(parts) == 1:
                    segments = _fused_pipeline_segments(parts[0])
                    if segments and all(item["subcommand"] in _SEMANTIC_PIPELINE_COMMANDS
                                        for item in segments):
                        pipelines.append({
                            "event_index": event_index,
                            "command": chain.strip(),
                            "segments": segments,
                            "output": output,
                            "exit_code": exit_code,
                        })
                    continue
                if len(parts) < 2:
                    continue
                parsed = [_anatomist_segment(part) for part in parts]
                if any(item is None for item in parsed):
                    continue
                segments = [
                    {"command": part, "subcommand": item[0], "args": item[1]}
                    for part, item in zip(parts, parsed)
                    if item is not None
                ]
                if not all(item["subcommand"] in _SEMANTIC_PIPELINE_COMMANDS for item in segments):
                    continue
                pipelines.append(
                    {
                        "event_index": event_index,
                        "command": chain.strip(),
                        "segments": segments,
                        "output": output,
                        "exit_code": exit_code,
                    }
                )
    # Sequential pipelines in one tool call each own one complete frame. Never
    # attach all frames to every command, or guess when their counts disagree.
    groups = {}
    for pipeline in pipelines:
        if _pipeline_is_ndjson(pipeline):
            groups.setdefault(pipeline["event_index"], []).append(pipeline)
    for group in groups.values():
        if len(group) < 2:
            continue
        try:
            output_frames = semantic_frames(group[0]["output"])
        except RuntimeError:
            continue
        if len(output_frames) == len(group):
            for pipeline, rows in zip(group, output_frames):
                pipeline["output"] = "\n".join(json.dumps(row) for row in rows)
        else:
            for pipeline in group:
                pipeline["output"] = ""
    return pipelines


def contains_contiguous_sequence(observed: list[str], required: list[str]) -> bool:
    if not required:
        return True
    width = len(required)
    return any(observed[index : index + width] == required
               for index in range(len(observed) - width + 1))


def _pipeline_is_ndjson(pipeline: dict[str, object]) -> bool:
    segments = pipeline["segments"]
    if not isinstance(segments, list) or not segments:
        return False
    for segment in segments:
        args = segment["args"]
        if not isinstance(args, list):
            return False
        for position, argument in enumerate(args):
            if argument == "--format":
                if position + 1 >= len(args) or args[position + 1] != "ndjson":
                    return False
            elif argument.startswith("--format=") and argument != "--format=ndjson":
                return False
    return True


def _semantic_records(output: str) -> list[dict[str, object]]:
    parsed = semantic_frames(output)
    if len(parsed) != 1:
        raise RuntimeError("expected exactly one attributable semantic stream")
    return parsed[0]


def consumed_evidence_files(trace, workspace):
    """Only owned files explicitly read by a successful Agent tool event."""
    root = Path(workspace).resolve() / "evidence"
    if not root.is_dir() or root.is_symlink():
        return []
    files = [p for p in root.iterdir() if p.is_file() and not p.is_symlink()
             and p.suffix in (".json", ".ndjson")]
    consumed = []
    for index, event in enumerate(getattr(trace, "tool_events", [])):
        raw = getattr(event, "raw_input", {})
        if raw.get("exit_code") not in (0, None):
            continue
        for command in _event_commands(event):
            if not re.search(r"\bcat\s|read_text\s*\(|json\.load\s*\(|\bopen\s*\(", command):
                continue
            patterns = re.findall(r"[\"']([^\"']*\*[^\"']*)[\"']", command)
            for path in files:
                if path.name in command or any(fnmatch.fnmatch(path.name, pattern) for pattern in patterns):
                    consumed.append({"event_index": index, "path": path.resolve(), "output": path.read_text()})
    return consumed


def redirected_evidence(execution, project, consumed):
    """Bind a diff redirection to its later consumed, owned artifact."""
    args = execution["args"]
    target = next((args[i + 1] for i, arg in enumerate(args[:-1]) if arg == ">"), None)
    if target is None:
        target = next((arg[1:] for arg in args if arg.startswith(">") and not arg.startswith(">>") and len(arg) > 1), None)
    if target is None:
        return execution["output"]
    path = (Path(project) / target).resolve()
    matches = [item for item in consumed if item["path"] == path and item["event_index"] >= execution["event_index"]]
    return matches[-1]["output"] if matches else ""


def _record_types(value: object) -> set[str]:
    return {row["record"] for row in value if isinstance(row, dict) and isinstance(row.get("record"), str)} if isinstance(value, list) else set()


def contains_sequence(observed: list[str], required: list[str]) -> bool:
    position = 0
    for item in observed:
        if position < len(required) and item == required[position]:
            position += 1
    return position == len(required)


class AnatomistJuryAdapter:
    def __init__(self) -> None:
        self._workspaces: dict[str, dict[str, object]] = {}
        self._evidence: dict[str, dict[str, object]] = {}

    def prepare_workspace(
        self, context: object, workspace_cwd: str
    ) -> _AdapterWorkspace:
        extension = context.extension("anatomist")
        workspace = Path(workspace_cwd).resolve()
        project = (
            workspace / _safe_relative(extension.get("project_root"), "project_root")
        ).resolve()
        if os.path.commonpath([str(workspace), str(project)]) != str(workspace):
            raise ValueError("anatomist.project_root escapes workspace")
        binary = _resolve_binary()
        command_directory = workspace / "anatomist-bin"
        command_directory.mkdir()
        (command_directory / "anatomist").symlink_to(binary)
        path = os.pathsep.join([str(command_directory), os.environ.get("PATH", "")])
        shell_directory = workspace / "anatomist-shell"
        shell_directory.mkdir()
        for name in (".zshenv", ".zprofile", ".bash_env"):
            (shell_directory / name).write_text("export PATH=" + shlex.quote(path) + "\n")
        skill = workspace / "anatomist-skill.md"
        skill.write_text((SOURCE_ROOT / "SKILL.md").read_text())
        (workspace / "AGENTS.md").write_text(
            "Use the Anatomist skill supplied for this test at " + str(skill)
            + ". CLI --help is the syntax source of truth.\n")
        environment = {**os.environ, "ANATOMIST_HOME": str(workspace / "anatomist-storage"),
                       "ANATOMIST_E2E_BIN": str(binary),
                       "PATH": path, "ZDOTDIR": str(shell_directory), "BASH_ENV": str(shell_directory / ".bash_env")}
        identity = {"binary": str(binary), "sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
                    "version": subprocess.run([str(binary), "--version"], capture_output=True, text=True, check=True, timeout=30).stdout.strip(),
                    "skill_sha256": hashlib.sha256(skill.read_bytes()).hexdigest(),
                    "commit": subprocess.run(["git", "rev-parse", "HEAD"], cwd=SOURCE_ROOT, capture_output=True, text=True, check=True).stdout.strip()}
        if not identity["version"].startswith("anatomist "):
            raise RuntimeError("selected executable did not identify itself as Anatomist")
        identity["command_path"] = str(command_directory / "anatomist")
        identity["worktree_dirty"] = bool(subprocess.run(["git", "status", "--porcelain"], cwd=SOURCE_ROOT, capture_output=True, text=True, check=True).stdout.strip())
        key = str(workspace)
        self._workspaces[key] = {
            "project": project,
            "binary": binary,
            "extension": extension,
            "env": environment,
            "identity": identity,
            "workspace": workspace,
        }
        return _AdapterWorkspace(
            agent_cwd=str(project),
            env={key: environment[key] for key in ("PATH", "ANATOMIST_E2E_BIN", "ANATOMIST_HOME", "ZDOTDIR", "BASH_ENV")},
            metadata={
                "adapter": "anatomist",
                "project_root": str(project),
                "binary": str(binary),
                "identity": identity,
            },
        )

    def postcheck(
        self,
        _context: object,
        workspace_cwd: str,
        _agent_cwd: str,
        trace: object,
        _result: object,
        _duration_sec: float,
    ) -> list[object]:
        commands = trace_commands(trace)
        invocations = anatomist_invocations(trace)
        executions = executed_anatomist_invocations(trace)
        self._evidence[str(Path(workspace_cwd).resolve())] = {
            "consumed_evidence": [
                {"path": str(item["path"]), "read_event": item["event_index"],
                 "sha256": hashlib.sha256(item["output"].encode()).hexdigest()}
                for item in consumed_evidence_files(trace, workspace_cwd)
            ],
            "commands": commands,
            "anatomist_subcommands": [item["subcommand"] for item in executions],
            "anatomist_invocations": [
                {
                    "command": item["command"],
                    "subcommand": item["subcommand"],
                    "help_only": "--help" in item["args"],
                    "exit_code": item["exit_code"],
                }
                for item in invocations
            ],
        }
        return []

    def collect_artifacts(
        self,
        _context: object,
        workspace_cwd: str,
        _agent_cwd: str,
        run_dir: str,
    ) -> dict[str, object]:
        evidence = self._evidence.get(str(Path(workspace_cwd).resolve()), {})
        workspace = self._workspaces.get(str(Path(workspace_cwd).resolve()), {})
        project = workspace.get("project")
        if isinstance(project, Path):
            evidence = dict(evidence)
            evidence["git"] = _git_evidence(project)
            evidence["identity"] = workspace.get("identity", {})
        target = Path(run_dir) / "anatomist-evidence.json"
        artifacts = {"anatomist_evidence": str(target)}
        for item in evidence.get("consumed_evidence", []):
            source = Path(item["path"])
            copied = Path(run_dir) / "anatomist-consumed-evidence" / source.name
            copied.parent.mkdir(exist_ok=True)
            shutil.copy2(source, copied)
            artifacts["consumed_" + source.name] = str(copied)
        target.write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return artifacts

    def extension_check_providers(self) -> dict[str, object]:
        return {"anatomist": _AnatomistCheckProvider(self)}


class _AnatomistCheckProvider:
    def __init__(self, adapter: AnatomistJuryAdapter) -> None:
        self._adapter = adapter

    def execute(self, spec: object, context: object) -> list[object]:
        from jury.checks.engine import CheckResult

        config = spec.config
        workspace_key = str(Path(context.workspace_cwd or ".").resolve())
        state = self._adapter._workspaces.get(workspace_key, {})
        try:
            evidence = self._execute(config, context, state)
        except Exception as exc:  # noqa: BLE001 - failure is reported as case evidence
            return [
                CheckResult(
                    name=spec.id,
                    passed=False,
                    severity=spec.severity,
                    message=str(exc),
                    evidence="",
                )
            ]
        return [
            CheckResult(
                name=spec.id,
                passed=True,
                severity=spec.severity,
                message="",
                evidence=json.dumps(evidence, ensure_ascii=False, sort_keys=True),
            )
        ]

    def _execute(
        self, config: dict[str, object], context: object, state: dict[str, object]
    ) -> object:
        check = config.get("check")
        executions = executed_anatomist_invocations(context.trace)
        observed = [str(item["subcommand"]) for item in executions]
        if check in ("diff_evidence", "snapshot_navigation", "version_checkout_unchanged"):
            facts = json.loads((Path(state["workspace"]) / "version-oracle.json").read_text())
            if check == "version_checkout_unchanged":
                project = Path(state["project"])
                head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=project, capture_output=True, text=True, check=True).stdout.strip()
                status = subprocess.run(["git", "status", "--porcelain"], cwd=project, capture_output=True, text=True, check=True).stdout.strip()
                refs = subprocess.run(["git", "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads"], cwd=project, capture_output=True, text=True, check=True).stdout.strip()
                if head != facts["head"] or status != facts["status"] or refs != facts["refs"]:
                    raise RuntimeError("Agent changed checkout or project files")
                return {"head": head, "status": status}
            candidates = []
            errors = []
            consumed = consumed_evidence_files(context.trace, state["workspace"])
            for execution in executions:
                if execution["subcommand"] != "diff" or execution["exit_code"] not in (0, None):
                    continue
                try:
                    for document in diff_documents(redirected_evidence(execution, state["project"], consumed)):
                        evidence = validate_diff(document, facts, facts["scenario"], tests=facts["scenario"] != "boundary")
                        if facts["scenario"] != "boundary":
                            if "--include-tests" not in execution["args"] or "--no-build" in execution["args"]:
                                raise RuntimeError("expected diff to prepare TEST coverage")
                            if any(item["subcommand"] == "index" for item in executions):
                                raise RuntimeError("Agent used index orchestration instead of automatic diff preparation")
                        candidates.append((execution, document, evidence))
                except (ValueError, RuntimeError) as failure:
                    errors.append(str(failure))
            if not candidates:
                raise RuntimeError("no valid branch diff consumed: " + "; ".join(errors))
            if check == "diff_evidence":
                return candidates[-1][2]
            for execution, document, evidence in candidates:
                streams = []
                for item in consumed:
                    if item["event_index"] >= execution["event_index"]:
                        try:
                            streams.extend(semantic_frames(item["output"]))
                        except RuntimeError:
                            pass
                # Python subprocess loops can emit complete source frames too.
                for index, event in enumerate(getattr(context.trace, "tool_events", [])):
                    raw = getattr(event, "raw_input", {})
                    if index < execution["event_index"] or raw.get("exit_code") not in (0, None):
                        continue
                    try:
                        streams.extend(embedded_semantic_frames(str(getattr(event, "raw_output", "") or raw.get("aggregated_output", ""))))
                    except RuntimeError:
                        pass
                for query in executions:
                    if query["event_index"] < execution["event_index"] or query["exit_code"] not in (0, None):
                        continue
                    if query["subcommand"] not in ("source", "pipeline"):
                        continue
                    try:
                        streams.extend(semantic_frames(query["output"]))
                    except RuntimeError:
                        continue
                if navigation_sides(document, streams) == {"base", "target"}:
                    return {**evidence, "navigated_sides": ["base", "target"]}
            raise RuntimeError("missing exact source navigation in both comparison snapshots")
        if check == "commands_present":
            required = [str(item) for item in config.get("commands", [])]
            missing = [item for item in required if item not in observed]
            if missing:
                raise RuntimeError(
                    f"missing Anatomist commands: {missing}; observed={observed}"
                )
            return {"required": required, "observed": observed}
        if check == "commands_any":
            choices = [str(item) for item in config.get("commands", [])]
            matched = [item for item in choices if item in observed]
            if not matched:
                raise RuntimeError(
                    f"none of Anatomist commands {choices} observed; observed={observed}"
                )
            return {"choices": choices, "matched": matched, "observed": observed}
        if check == "command_sequence":
            required = [str(item) for item in config.get("commands", [])]
            if not contains_sequence(observed, required):
                raise RuntimeError(
                    f"expected command sequence {required}; observed={observed}"
                )
            return {"required": required, "observed": observed}
        if check == "command_output_regex":
            command_pattern = str(config.get("command", ""))
            required_patterns = [str(item) for item in config.get("all", [])]
            forbidden_patterns = [str(item) for item in config.get("none", [])]
            candidates = [
                item for item in executions
                if re.search(command_pattern, str(item["command"]), re.IGNORECASE)
            ]
            for candidate in candidates:
                output = str(candidate["output"])
                if all(re.search(pattern, output, re.IGNORECASE | re.DOTALL)
                       for pattern in required_patterns) and not any(
                           re.search(pattern, output, re.IGNORECASE | re.DOTALL)
                           for pattern in forbidden_patterns
                       ):
                    return {
                        "command": candidate["command"],
                        "required_patterns": required_patterns,
                    }
            raise RuntimeError(
                f"no command output matched command={command_pattern!r}, "
                f"required={required_patterns}, forbidden={forbidden_patterns}"
            )
        if check == "semantic_pipeline":
            required_commands = [str(item) for item in config.get("commands", [])]
            candidates = [
                pipeline for pipeline in semantic_pipelines(context.trace)
                if pipeline["exit_code"] in (0, None) and _pipeline_is_ndjson(pipeline)
                and contains_contiguous_sequence(
                    [str(segment["subcommand"]) for segment in pipeline["segments"]],
                    required_commands,
                )
            ]
            if not candidates:
                observed_pipelines = [
                    [str(segment["subcommand"]) for segment in pipeline["segments"]]
                    for pipeline in semantic_pipelines(context.trace)
                ]
                raise RuntimeError(
                    f"expected one NDJSON pipe chain containing {required_commands}; "
                    f"observed_pipelines={observed_pipelines}"
                )
            required_records = {str(item) for item in config.get("records", [])}
            failures = []
            consumed = consumed_evidence_files(context.trace, state["workspace"]) if state.get("workspace") else []
            for pipeline in reversed(candidates):
                try:
                    output = redirected_evidence(
                        {**pipeline, "args": pipeline["segments"][-1]["args"]},
                        state.get("project", "."), consumed)
                    decoded = _semantic_records(str(output))
                    found = _record_types(decoded)
                    missing = sorted(required_records - found)
                    final = decoded[-1]
                    if missing or final.get("record") != "evidence" or final.get("scope") != "stream":
                        raise RuntimeError(f"semantic output missing records={missing} or final stream evidence")
                    if final.get("negative_conclusion_safe") is True and final.get("coverage") != "complete":
                        raise RuntimeError("incomplete semantic stream incorrectly marked negative-safe")
                    return {"commands": required_commands, "pipeline": pipeline["command"],
                            "records": sorted(found), "final": final}
                except RuntimeError as failure:
                    failures.append(str(failure))
            raise RuntimeError("; ".join(failures))
        if check == "semantic_pipeline_any":
            failures = []
            for alternative in config["alternatives"]:
                try:
                    return self._execute({**alternative, "check": "semantic_pipeline"}, context, state)
                except RuntimeError as failure:
                    failures.append(str(failure))
            raise RuntimeError("no supported impact pipeline: " + "; ".join(failures))
        if check == "source_page_followed":
            target = re.compile(str(config.get("target", "")), re.IGNORECASE)
            first_page = None
            continuation = None
            for pipeline in semantic_pipelines(context.trace):
                command = str(pipeline["command"])
                subcommands = [str(segment["subcommand"]) for segment in pipeline["segments"]]
                if target.search(command) is None or "source" not in subcommands:
                    continue
                source_segments = [segment for segment in pipeline["segments"]
                                   if segment["subcommand"] == "source"]
                offsets = [
                    argument for segment in source_segments for argument in segment["args"]
                    if argument.startswith("--offset=")
                ]
                has_offset = bool(offsets) or any(
                    argument == "--offset"
                    for segment in source_segments for argument in segment["args"]
                )
                records = _semantic_records(str(pipeline["output"]))
                truncated = any(
                    item.get("record") == "source_slice"
                    and isinstance(item.get("source"), dict)
                    and item["source"].get("truncated") is True
                    for item in records
                )
                if truncated and not has_offset:
                    first_page = pipeline
                if has_offset:
                    continuation = pipeline
            if first_page is None or continuation is None:
                raise RuntimeError(
                    f"expected a truncated source pipeline and a later --offset continuation for {target.pattern!r}"
                )
            return {"target": target.pattern, "first": first_page["command"],
                    "continuation": continuation["command"]}
        if check == "response_regex":
            response = context.result.final_response or ""
            required_patterns = [str(item) for item in config.get("all", [])]
            forbidden_patterns = [str(item) for item in config.get("none", [])]
            missing = [
                pattern
                for pattern in required_patterns
                if re.search(pattern, response, re.IGNORECASE | re.DOTALL) is None
            ]
            forbidden = [
                pattern
                for pattern in forbidden_patterns
                if re.search(pattern, response, re.IGNORECASE | re.DOTALL) is not None
            ]
            if missing or forbidden:
                raise RuntimeError(
                    f"response contract failed: missing={missing}, forbidden={forbidden}"
                )
            return {
                "required_patterns": required_patterns,
                "forbidden_patterns": forbidden_patterns,
            }
        if check == "git_clean":
            project = Path(state["project"])
            result = subprocess.run(
                ["git", "status", "--porcelain"],
                cwd=project,
                env=state.get("env"),
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
            if result.returncode != 0 or result.stdout.strip():
                raise RuntimeError(
                    f"Agent changed project files: rc={result.returncode}, status={result.stdout.strip()}"
                )
            return {"clean": True}
        if check == "git_change_contract":
            project = Path(state["project"])
            changed = _git_changed_paths(project)
            required = [str(item) for item in config.get("required", [])]
            allowed = [str(item) for item in config.get("allowed", [])]
            forbidden = [str(item) for item in config.get("forbidden", [])]
            missing = [pattern for pattern in required if not _any_match(changed, pattern)]
            disallowed = [path for path in changed if not _matches_any(path, allowed)]
            blocked = [path for path in changed if _matches_any(path, forbidden)]
            if missing or disallowed or blocked:
                raise RuntimeError(
                    f"git change contract failed: changed={changed}, missing={missing}, "
                    f"disallowed={disallowed}, forbidden={blocked}"
                )
            return {"changed": changed, "required": required, "allowed": allowed}
        if check == "maven_verify":
            project = Path(state["project"])
            commands = config.get("commands", [["-q", "test"]])
            results = []
            for arguments in commands:
                if not isinstance(arguments, list) or not all(
                    isinstance(item, str) for item in arguments
                ):
                    raise ValueError("maven_verify.commands must be lists of strings")
                result = subprocess.run(
                    ["mvn", *arguments],
                    cwd=project,
                    env=state.get("env"),
                    capture_output=True,
                    text=True,
                    timeout=int(config.get("timeout_sec", 180)),
                    check=False,
                )
                results.append({"arguments": arguments, "returncode": result.returncode})
                if result.returncode != 0:
                    tail = (result.stdout + "\n" + result.stderr)[-4000:]
                    raise RuntimeError(
                        f"maven {' '.join(arguments)} failed: rc={result.returncode}\n{tail}"
                    )
            return {"commands": results}
        if check == "query_output_regex":
            project = Path(state["project"])
            binary = Path(state["binary"])
            arguments = [str(item) for item in config.get("arguments", [])]
            if not arguments or arguments[0] not in _QUERY_COMMANDS:
                raise ValueError("query_output_regex requires a supported query command")
            index = project / str(config.get("index", ".anatomist/index.db"))
            if "--index" not in arguments and not any(
                item.startswith("--index=") for item in arguments
            ):
                arguments.extend(["--index", str(index)])
            result = subprocess.run(
                [str(binary), *arguments],
                cwd=project,
                env=state.get("env"),
                capture_output=True,
                text=True,
                timeout=int(config.get("timeout_sec", 30)),
                check=False,
            )
            if result.returncode != 0:
                raise RuntimeError(
                    f"query failed: rc={result.returncode}, stderr={result.stderr[-2000:]}"
                )
            required_patterns = [str(item) for item in config.get("all", [])]
            missing = [
                pattern for pattern in required_patterns
                if re.search(pattern, result.stdout, re.IGNORECASE | re.DOTALL) is None
            ]
            if missing:
                raise RuntimeError(f"query output missing patterns: {missing}")
            return {"arguments": arguments, "required_patterns": required_patterns}
        if check == "semantic_query_output_regex":
            project = Path(state["project"])
            binary = Path(state["binary"])
            raw_pipeline = config.get("pipeline", [])
            if not isinstance(raw_pipeline, list) or len(raw_pipeline) < 2:
                raise ValueError("semantic_query_output_regex.pipeline requires at least two commands")
            commands: list[list[str]] = []
            index = project / str(config.get("index", ".anatomist/index.db"))
            for raw_arguments in raw_pipeline:
                if not isinstance(raw_arguments, list) or not raw_arguments or not all(
                    isinstance(item, str) for item in raw_arguments
                ):
                    raise ValueError("semantic_query_output_regex.pipeline commands must be string lists")
                arguments = list(raw_arguments)
                if arguments[0] not in _SEMANTIC_PIPELINE_COMMANDS:
                    raise ValueError("semantic_query_output_regex only accepts semantic commands")
                if "--index" not in arguments and not any(item.startswith("--index=") for item in arguments):
                    arguments.extend(["--index", str(index)])
                commands.append([str(binary), *arguments])
            processes: list[subprocess.Popen[str]] = []
            previous = None
            try:
                for command in commands:
                    process = subprocess.Popen(
                        command, cwd=project, stdin=previous, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, text=True,
                    )
                    if previous is not None:
                        previous.close()
                    previous = process.stdout
                    processes.append(process)
                stdout, stderr = processes[-1].communicate(timeout=int(config.get("timeout_sec", 30)))
                errors = [stderr]
                for process in processes[:-1]:
                    process.wait(timeout=5)
                    if process.stderr is not None:
                        errors.append(process.stderr.read())
                failed = [process.returncode for process in processes if process.returncode != 0]
                if failed:
                    raise RuntimeError(
                        f"semantic pipeline failed: returncodes={failed}, stderr={' '.join(errors)[-2000:]}"
                    )
            finally:
                for process in processes:
                    if process.poll() is None:
                        process.kill()
                        process.wait()
            required_patterns = [str(item) for item in config.get("all", [])]
            missing = [pattern for pattern in required_patterns
                       if re.search(pattern, stdout, re.IGNORECASE | re.DOTALL) is None]
            if missing:
                raise RuntimeError(f"semantic pipeline output missing patterns: {missing}")
            return {"pipeline": commands, "required_patterns": required_patterns}
        if check == "index_fresh":
            project = Path(state["project"])
            binary = Path(state["binary"])
            index = project / str(config.get("index", ".anatomist/index.db"))
            result = subprocess.run(
                [
                    str(binary),
                    "doctor",
                    "--agent-preflight",
                    "--format",
                    "json",
                    "--index",
                    str(index),
                ],
                cwd=project,
                env=state.get("env"),
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )
            if result.returncode != 0:
                raise RuntimeError(
                    f"doctor failed: rc={result.returncode}, stderr={result.stderr.strip()}"
                )
            payload = json.loads(result.stdout)
            preflight = payload.get("agent_preflight") or {}
            snapshot = payload.get("source_snapshot") or {}
            blockers = preflight.get("blockers") or []
            mismatches = _indexed_content_mismatches(project, index)
            if blockers or snapshot.get("match") is False or mismatches:
                raise RuntimeError(
                    f"index is not fresh: blockers={blockers}, "
                    f"snapshot_match={snapshot.get('match')}, "
                    f"content_mismatches={mismatches}"
                )
            return {
                "status": preflight.get("status"),
                "blockers": blockers,
                "snapshot_match": snapshot.get("match"),
                "content_mismatches": mismatches,
            }
        raise RuntimeError(f"unknown Anatomist extension check: {check}")


def _git_changed_paths(project: Path) -> list[str]:
    result = subprocess.run(
        ["git", "status", "--porcelain=v1", "-z"],
        cwd=project,
        capture_output=True,
        timeout=10,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"git status failed: rc={result.returncode}")
    paths: list[str] = []
    entries = result.stdout.decode("utf-8", errors="replace").split("\0")
    index = 0
    while index < len(entries):
        entry = entries[index]
        index += 1
        if not entry:
            continue
        status = entry[:2]
        path = entry[3:]
        if status[0] in {"R", "C"} and index < len(entries):
            path = entries[index]
            index += 1
        paths.append(path)
    return sorted(set(paths))


def _any_match(values: list[str], pattern: str) -> bool:
    return any(fnmatch.fnmatch(value, pattern) for value in values)


def _matches_any(value: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatch(value, pattern) for pattern in patterns)


def _indexed_content_mismatches(project: Path, index: Path) -> list[str]:
    """Compare cached source hashes with disk without mutating the index."""
    if not index.is_file():
        return ["<index-missing>"]
    mismatches: list[str] = []
    with sqlite3.connect(f"file:{index.resolve()}?mode=ro", uri=True) as connection:
        rows = connection.execute("SELECT source_file, hash FROM file_cache").fetchall()
    for source_file, expected_hash in rows:
        raw_path = Path(source_file)
        path = raw_path if raw_path.is_absolute() else project / raw_path
        if not path.is_file():
            mismatches.append(str(source_file))
            continue
        actual_hash = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual_hash != expected_hash:
            mismatches.append(str(source_file))
    return sorted(mismatches)


def _git_evidence(project: Path) -> dict[str, object]:
    try:
        changed = _git_changed_paths(project)
        result = subprocess.run(
            ["git", "diff", "--no-ext-diff", "--unified=2"],
            cwd=project,
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        return {"changed_paths": changed, "diff": result.stdout[:50_000]}
    except Exception as exc:  # noqa: BLE001 - artifact collection must not mask the run
        return {"error": str(exc)}


def create_adapter() -> AnatomistJuryAdapter:
    return AnatomistJuryAdapter()
