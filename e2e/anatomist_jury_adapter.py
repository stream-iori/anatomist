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


SOURCE_ROOT = Path(__file__).resolve().parents[1]
_COMMAND_KEYS = {"argv", "cmd", "command", "commands", "script"}
_QUERY_COMMANDS = {
    "doctor",
    "search",
    "context",
    "declarations-of",
    "callees-of",
    "callers-of",
    "branches-of",
    "bean-config",
    "hierarchy",
    "implementors-of",
    "deps-of",
    "used-by",
    "field-access",
    "call-path",
    "overview",
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
    installed = shutil.which("anatomist")
    if installed:
        candidates.append(Path(installed))
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
    return [part.strip() for part in re.split(r"&&|\|\||[;\n]", command) if part.strip()]


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
    return [
        invocation
        for invocation in anatomist_invocations(trace)
        if "--help" not in invocation["args"]
    ]


def followed_next_query(invocations: list[dict[str, object]], target_pattern: str) -> bool:
    """Whether a truncated context result's advertised source offset was followed."""
    target = re.compile(target_pattern, re.IGNORECASE)
    for index, invocation in enumerate(invocations):
        command = str(invocation["command"])
        output = str(invocation["output"])
        if invocation["subcommand"] != "context" or target.search(command) is None:
            continue
        if re.search(r'"truncated"\s*:\s*true', output) is None:
            continue
        offsets = re.findall(r"--source-offset\s+(\d+)", output)
        if not offsets:
            continue
        for later in invocations[index + 1 :]:
            later_command = str(later["command"])
            if later["subcommand"] != "context" or target.search(later_command) is None:
                continue
            if any(re.search(rf"--source-offset(?:=|\s+){offset}(?:\s|$)", later_command)
                   for offset in offsets):
                return True
    return False


def anatomist_subcommands(commands: list[str]) -> list[str]:
    """Return Anatomist subcommands in observed execution order."""
    result: list[str] = []
    for command in commands:
        for segment in re.split(r"&&|\|\||[;\n]", command):
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
        key = str(workspace)
        self._workspaces[key] = {
            "project": project,
            "binary": binary,
            "extension": extension,
        }
        path = os.pathsep.join([str(binary.parent), os.environ.get("PATH", "")])
        return _AdapterWorkspace(
            agent_cwd=str(project),
            env={"PATH": path, "ANATOMIST_E2E_BIN": str(binary)},
            metadata={
                "adapter": "anatomist",
                "project_root": str(project),
                "binary": str(binary),
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
        target = Path(run_dir) / "anatomist-evidence.json"
        target.write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return {"anatomist_evidence": str(target)}

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
        commands = trace_commands(context.trace)
        executions = executed_anatomist_invocations(context.trace)
        observed = [str(item["subcommand"]) for item in executions]
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
        if check == "followed_next_query":
            target = str(config.get("target", ""))
            invocations = executions
            if not followed_next_query(invocations, target):
                raise RuntimeError(
                    f"no advertised context continuation followed for target={target!r}"
                )
            return {"target": target, "followed": True}
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
