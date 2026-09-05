#!/usr/bin/env python3
"""Compare the 1.0 semantic pipeline with a released or git-built 0.1x baseline."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import re
import shutil
import sqlite3
import statistics
import subprocess
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable


QUERY_RUNS = 30
INDEX_RUNS = 5
NOOP_RUNS = 20
RSS_RUNS = 5


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    baseline = parser.add_mutually_exclusive_group()
    baseline.add_argument("--baseline-bin", type=Path,
                          help="Released baseline binary (default ~/.local/bin/anatomist).")
    baseline.add_argument("--baseline-ref",
                          help="Build the baseline from a detached git worktree.")
    parser.add_argument("--candidate-bin", type=Path, default=Path("target/anatomist"))
    parser.add_argument("--output", type=Path, default=Path("target/benchmarks/query-refactor"))
    parser.add_argument("--query-runs", type=int, default=QUERY_RUNS)
    parser.add_argument("--index-runs", type=int, default=INDEX_RUNS)
    parser.add_argument("--noop-runs", type=int, default=NOOP_RUNS)
    parser.add_argument("--rss-runs", type=int, default=RSS_RUNS)
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked(command: list[str], *, cwd: Path | None = None,
            capture: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command, cwd=cwd, text=True, check=True,
        stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
        stderr=subprocess.PIPE if capture else None,
    )


def run_command(command: list[str]) -> bytes:
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise RuntimeError(
            f"benchmark command failed (exit {result.returncode}): {' '.join(command)}\n"
            + result.stderr.decode(errors="replace")
        )
    return result.stdout


def run_pipeline(commands: list[list[str]]) -> bytes:
    processes: list[subprocess.Popen[bytes]] = []
    previous = None
    for command in commands:
        process = subprocess.Popen(
            command,
            stdin=previous.stdout if previous else None,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if previous and previous.stdout:
            previous.stdout.close()
        processes.append(process)
        previous = process
    output, last_error = processes[-1].communicate()
    failures: list[str] = []
    if processes[-1].returncode:
        failures.append(last_error.decode(errors="replace"))
    for process in reversed(processes[:-1]):
        return_code = process.wait()
        error = process.stderr.read().decode(errors="replace") if process.stderr else ""
        if return_code:
            failures.append(error)
    if failures:
        raise RuntimeError("benchmark pipeline failed:\n" + "\n".join(reversed(failures)))
    return output


def timed(operation: Callable[[], bytes | None]) -> float:
    started = time.perf_counter_ns()
    operation()
    return (time.perf_counter_ns() - started) / 1_000_000


def alternate(runs: int, baseline: Callable[[], float],
              candidate: Callable[[], float]) -> tuple[list[float], list[float]]:
    old: list[float] = []
    new: list[float] = []
    for iteration in range(runs):
        order = ((old, baseline), (new, candidate)) if iteration % 2 == 0 else (
            (new, candidate), (old, baseline)
        )
        for samples, operation in order:
            samples.append(operation())
    return old, new


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def stats(values: list[float]) -> dict[str, float | int]:
    return {
        "n": len(values),
        "p50_ms": round(statistics.median(values), 2),
        "p95_ms": round(percentile(values, .95), 2),
        "mean_ms": round(statistics.mean(values), 2),
        "min_ms": round(min(values), 2),
        "max_ms": round(max(values), 2),
    }


def delta(candidate: float, baseline: float) -> float:
    return (candidate / baseline - 1.0) * 100.0


def clear_db(path: Path) -> None:
    for suffix in ("", "-wal", "-shm", ".lock"):
        candidate = Path(str(path) + suffix)
        if candidate.exists():
            candidate.unlink()


def index_command(binary: Path, root: Path, db: Path,
                  incremental: bool = False) -> list[str]:
    command = [str(binary), "index", str(root), "--no-classpath", "--output", str(db)]
    if incremental:
        command.append("--incremental")
    return command


def index_once(binary: Path, root: Path, db: Path) -> float:
    clear_db(db)
    return timed(lambda: run_command(index_command(binary, root, db)))


def peak_rss(binary: Path, command: list[str]) -> int:
    if platform.system() == "Darwin":
        measured = subprocess.run(
            ["/usr/bin/time", "-l", str(binary), *command], text=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True,
        )
        match = re.search(r"(?m)^\s*(\d+)\s+maximum resident set size$", measured.stderr)
    else:
        measured = subprocess.run(
            ["/usr/bin/time", "-v", str(binary), *command], text=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True,
        )
        match = re.search(r"Maximum resident set size \(kbytes\):\s*(\d+)", measured.stderr)
        if match:
            return int(match.group(1)) * 1024
    if not match:
        raise RuntimeError("could not parse peak RSS from /usr/bin/time")
    return int(match.group(1))


def table_exists(connection: sqlite3.Connection, name: str) -> bool:
    return bool(connection.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)
    ).fetchone())


def db_facts(path: Path) -> dict[str, int]:
    with sqlite3.connect(path) as connection:
        facts = {
            "bytes": path.stat().st_size,
            "nodes": connection.execute("SELECT count(*) FROM nodes").fetchone()[0],
            "edges": connection.execute("SELECT count(*) FROM edges").fetchone()[0],
            "edges_calls": connection.execute(
                "SELECT count(*) FROM edges WHERE relation='CALLS'"
            ).fetchone()[0],
        }
        if table_exists(connection, "call_sites"):
            facts["call_sites"] = connection.execute(
                "SELECT count(*) FROM call_sites"
            ).fetchone()[0]
        if table_exists(connection, "call_site_owners"):
            facts["call_site_owners"] = connection.execute(
                "SELECT count(*) FROM call_site_owners"
            ).fetchone()[0]
        if table_exists(connection, "call_site_targets"):
            facts["call_site_targets"] = connection.execute(
                "SELECT count(*) FROM call_site_targets"
            ).fetchone()[0]
        return facts


def semantic_records(output: bytes) -> list[dict]:
    return [json.loads(line) for line in output.decode().splitlines() if line.strip()]


def semantic_check(output: bytes, required: set[str], contains: str) -> bool:
    records = semantic_records(output)
    kinds = {str(record.get("record")) for record in records}
    final = next((record for record in reversed(records)
                  if record.get("record") == "evidence"
                  and record.get("scope") == "stream"), None)
    return required <= kinds and final is not None and contains in output.decode()


def markdown(report: dict) -> str:
    rows = [
        "| Gate | Baseline | Candidate | Delta | Limit | Result |",
        "|---|---:|---:|---:|---:|:---:|",
    ]
    for gate in report["gates"]:
        if gate["kind"] == "boolean":
            rows.append(
                f"| {gate['name']} | required | {str(gate['value']).lower()} | — | true | {gate['status']} |"
            )
        else:
            rows.append(
                "| {name} | {baseline:.2f} | {candidate:.2f} | {delta_pct:+.1f}% | "
                "{limit_pct:+.0f}% | {status} |".format(**gate)
            )
    conclusion = "PASS" if report["passed"] else "FAIL"
    database = report["artifacts"]["database"]
    return "\n".join([
        "# Anatomist 1.0 benchmark", "",
        f"结论：**{conclusion}**（baseline `{report['environment']['baseline_label']}`）", "",
        *rows, "",
        "| DB facts | Baseline | Candidate |", "|---|---:|---:|",
        f"| bytes | {database['old']['bytes']} | {database['new']['bytes']} |",
        f"| edges(CALLS) | {database['old']['edges_calls']} | {database['new']['edges_calls']} |",
        f"| call-site owners | {database['old'].get('call_site_owners', 0)} | {database['new'].get('call_site_owners', 0)} |",
        f"| call sites | {database['old'].get('call_sites', 0)} | {database['new'].get('call_sites', 0)} |",
        f"| call targets | {database['old'].get('call_site_targets', 0)} | {database['new'].get('call_site_targets', 0)} |",
        "",
        "共同 search 直接同比；type/calls 是同一用户任务的旧聚合命令与 1.0 多进程管道同比。",
        "功能门禁检查 candidate 的 record、目标文本与 final stream evidence，不比较不兼容的 JSON 外观。",
        f"Baseline: `{report['environment']['baseline_version']}` / `{report['environment']['baseline_sha256']}`。",
        f"Candidate: `{report['environment']['candidate_version']}` / `{report['environment']['candidate_sha256']}`。",
        "时间单位 ms；DB、binary、RSS 单位 byte。", "",
    ])


def main() -> int:
    args = parse_args()
    root = Path(__file__).resolve().parents[1]
    candidate_bin = (root / args.candidate_bin).resolve() \
        if not args.candidate_bin.is_absolute() else args.candidate_bin.resolve()
    if not candidate_bin.is_file():
        raise SystemExit(f"candidate binary missing: {candidate_bin}; run `just native`")
    output = (root / args.output).resolve() \
        if not args.output.is_absolute() else args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    temp = Path(tempfile.mkdtemp(prefix="anatomist-query-bench."))
    worktree = temp / "baseline"
    baseline_added = False
    try:
        if args.baseline_ref:
            checked(["git", "worktree", "add", "--detach", str(worktree),
                     args.baseline_ref], cwd=root)
            baseline_added = True
            checked(["just", "native"], cwd=worktree)
            baseline_bin = (worktree / "target/anatomist").resolve()
            baseline_label = "git:" + args.baseline_ref
        else:
            source = (args.baseline_bin or Path.home() / ".local/bin/anatomist").expanduser()
            source = source.resolve()
            if not source.is_file():
                raise SystemExit(f"baseline binary missing: {source}")
            baseline_bin = temp / "baseline-anatomist"
            shutil.copy2(source, baseline_bin)
            baseline_label = "binary:" + str(source)

        baseline_version = run_command([str(baseline_bin), "--version"]).decode().strip()
        candidate_version = run_command([str(candidate_bin), "--version"]).decode().strip()

        old_db = temp / "baseline.db"
        new_db = temp / "candidate.db"
        index_samples = alternate(
            args.index_runs,
            lambda: index_once(baseline_bin, root, old_db),
            lambda: index_once(candidate_bin, root, new_db),
        )
        index_once(baseline_bin, root, old_db)
        index_once(candidate_bin, root, new_db)

        selector = "com.anatomist.query.QueryService"
        callable_selector = selector + "#search(java.lang.String,java.lang.String,int)"

        old_commands = {
            "startup": lambda: run_command([str(baseline_bin), "--version"]),
            "search": lambda: run_command([
                str(baseline_bin), "search", "QueryService", "--index", str(old_db)
            ]),
            "type_workflow": lambda: run_command([
                str(baseline_bin), "context", selector, "--index", str(old_db)
            ]),
            "calls_workflow": lambda: run_command([
                str(baseline_bin), "callees-of", callable_selector,
                "--depth", "1", "--index", str(old_db)
            ]),
        }
        new_commands = {
            "startup": lambda: run_command([str(candidate_bin), "--version"]),
            "search": lambda: run_command([
                str(candidate_bin), "search", "QueryService", "--index", str(new_db)
            ]),
            "type_workflow": lambda: run_pipeline([
                [str(candidate_bin), "resolve", selector, "--kind", "type", "--unique",
                 "--index", str(new_db)],
                [str(candidate_bin), "describe", "--index", str(new_db)],
            ]),
            "calls_workflow": lambda: run_pipeline([
                [str(candidate_bin), "resolve", callable_selector, "--kind", "callable",
                 "--exact", "--unique", "--index", str(new_db)],
                [str(candidate_bin), "calls", "--index", str(new_db)],
                [str(candidate_bin), "dispatch", "--index", str(new_db)],
            ]),
        }

        benchmarks: dict[str, dict] = {
            "full_index": {"old": stats(index_samples[0]), "new": stats(index_samples[1])}
        }
        for name in old_commands:
            timed(old_commands[name])
            timed(new_commands[name])
            samples = alternate(
                args.query_runs,
                lambda name=name: timed(old_commands[name]),
                lambda name=name: timed(new_commands[name]),
            )
            benchmarks[name] = {"old": stats(samples[0]), "new": stats(samples[1])}

        noop = alternate(
            args.noop_runs,
            lambda: timed(lambda: run_command(index_command(baseline_bin, root, old_db, True))),
            lambda: timed(lambda: run_command(index_command(candidate_bin, root, new_db, True))),
        )
        benchmarks["noop_incremental"] = {"old": stats(noop[0]), "new": stats(noop[1])}

        functional = {
            "search_stream": semantic_check(new_commands["search"](), {"entity_candidate", "evidence"}, "QueryService"),
            "type_stream": semantic_check(new_commands["type_workflow"](), {"declaration", "evidence"}, selector),
            "calls_stream": semantic_check(new_commands["calls_workflow"](), {"dispatch_target", "evidence"}, "search"),
        }
        removed = subprocess.run(
            [str(candidate_bin), "context"],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
        )
        functional["legacy_command_removed"] = removed.returncode != 0
        functional["candidate_edges_calls_zero"] = db_facts(new_db)["edges_calls"] == 0

        rss = alternate(
            args.rss_runs,
            lambda: float(peak_rss(baseline_bin, ["search", "QueryService", "--index", str(old_db)])),
            lambda: float(peak_rss(candidate_bin, ["search", "QueryService", "--index", str(new_db)])),
        )
        artifacts = {
            "database": {"old": db_facts(old_db), "new": db_facts(new_db)},
            "binary_bytes": {"old": baseline_bin.stat().st_size, "new": candidate_bin.stat().st_size},
            "peak_rss_bytes": {
                "old": int(statistics.median(rss[0])),
                "new": int(statistics.median(rss[1])),
            },
        }

        gates: list[dict] = []

        def add_numeric(name: str, old: float, new: float, limit: float) -> None:
            change = delta(new, old)
            gates.append({
                "kind": "numeric", "name": name, "baseline": old, "candidate": new,
                "delta_pct": round(change, 2), "limit_pct": limit,
                "status": "PASS" if change <= limit else "FAIL",
            })

        limits = {
            "startup": (15.0, 25.0),
            "search": (15.0, 25.0),
            "type_workflow": (200.0, 250.0),
            "calls_workflow": (250.0, 300.0),
            "full_index": (15.0, 20.0),
            "noop_incremental": (15.0, 25.0),
        }
        for name, (p50_limit, p95_limit) in limits.items():
            add_numeric(name + ".p50", benchmarks[name]["old"]["p50_ms"],
                        benchmarks[name]["new"]["p50_ms"], p50_limit)
            add_numeric(name + ".p95", benchmarks[name]["old"]["p95_ms"],
                        benchmarks[name]["new"]["p95_ms"], p95_limit)
        add_numeric("database.bytes", artifacts["database"]["old"]["bytes"],
                    artifacts["database"]["new"]["bytes"], -30.0)
        add_numeric("binary.bytes", artifacts["binary_bytes"]["old"],
                    artifacts["binary_bytes"]["new"], 10.0)
        add_numeric("peak_rss.bytes", artifacts["peak_rss_bytes"]["old"],
                    artifacts["peak_rss_bytes"]["new"], 10.0)
        for name, value in functional.items():
            gates.append({
                "kind": "boolean", "name": name, "value": value,
                "status": "PASS" if value else "FAIL",
            })

        report = {
            "environment": {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "system": platform.platform(),
                "machine": platform.machine(),
                "baseline_label": baseline_label,
                "baseline_version": baseline_version,
                "baseline_sha256": sha256(baseline_bin),
                "candidate_version": candidate_version,
                "candidate_sha256": sha256(candidate_bin),
                "candidate_head": checked(
                    ["git", "rev-parse", "HEAD"], cwd=root, capture=True
                ).stdout.strip(),
                "working_tree_dirty": bool(checked(
                    ["git", "status", "--porcelain"], cwd=root, capture=True
                ).stdout),
            },
            "runs": {
                "query": args.query_runs, "index": args.index_runs,
                "noop": args.noop_runs, "rss": args.rss_runs,
            },
            "benchmarks": benchmarks,
            "functional": functional,
            "artifacts": artifacts,
            "gates": gates,
            "passed": all(gate["status"] == "PASS" for gate in gates),
        }
        (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        (output / "report.md").write_text(markdown(report))
        print(markdown(report))
        print(f"Reports: {output / 'results.json'}  {output / 'report.md'}")
        return 0 if report["passed"] else 1
    finally:
        if baseline_added:
            subprocess.run(
                ["git", "worktree", "remove", "--force", str(worktree)], cwd=root,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        shutil.rmtree(temp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
