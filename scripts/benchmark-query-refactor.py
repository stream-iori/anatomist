#!/usr/bin/env python3
"""Reproducible local performance gate for the query/semantic-stream refactor."""

from __future__ import annotations

import argparse
import json
import math
import os
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


QUERY_RUNS = 30
INDEX_RUNS = 5
NOOP_RUNS = 20
RSS_RUNS = 5


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-ref", default="dd2e575")
    parser.add_argument("--candidate-bin", type=Path, default=Path("target/anatomist"))
    parser.add_argument("--output", type=Path, default=Path("target/benchmarks/query-refactor"))
    parser.add_argument("--query-runs", type=int, default=QUERY_RUNS)
    parser.add_argument("--index-runs", type=int, default=INDEX_RUNS)
    parser.add_argument("--noop-runs", type=int, default=NOOP_RUNS)
    parser.add_argument("--rss-runs", type=int, default=RSS_RUNS)
    return parser.parse_args()


def checked(command: list[str], *, cwd: Path | None = None,
            capture: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, text=True, check=True,
                          stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                          stderr=subprocess.PIPE if capture else None)


def timed(command: list[str]) -> float:
    started = time.perf_counter_ns()
    completed = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                               text=True)
    if completed.returncode != 0:
        raise RuntimeError("benchmark command failed (exit {}): {}\n{}".format(
            completed.returncode, " ".join(command), completed.stderr))
    return (time.perf_counter_ns() - started) / 1_000_000


def timed_pipeline(commands: list[list[str]]) -> float:
    """Time a real multi-process NDJSON pipeline without a shell."""
    started = time.perf_counter_ns()
    processes: list[subprocess.Popen] = []
    previous = None
    for index, command in enumerate(commands):
        process = subprocess.Popen(command,
                                   stdin=previous.stdout if previous else None,
                                   stdout=subprocess.DEVNULL if index == len(commands) - 1
                                   else subprocess.PIPE,
                                   stderr=subprocess.PIPE)
        if previous and previous.stdout:
            previous.stdout.close()
        processes.append(process)
        previous = process
    failures: list[str] = []
    for process in reversed(processes):
        return_code = process.wait()
        if return_code != 0:
            error = process.stderr.read().decode(errors="replace") if process.stderr else ""
            failures.append("exit {}: {}\n{}".format(
                return_code, " ".join(process.args), error))
    if failures:
        raise RuntimeError("benchmark pipeline failed:\n" + "\n".join(reversed(failures)))
    return (time.perf_counter_ns() - started) / 1_000_000


def alternate(runs: int, baseline, candidate) -> tuple[list[float], list[float]]:
    old: list[float] = []
    new: list[float] = []
    for iteration in range(runs):
        order = ((old, baseline), (new, candidate)) if iteration % 2 == 0 else (
            (new, candidate), (old, baseline))
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


def index_command(binary: Path, root: Path, db: Path, incremental: bool = False) -> list[str]:
    command = [str(binary), "index", str(root), "--no-classpath", "--output", str(db)]
    if incremental:
        command.append("--incremental")
    return command


def index_once(binary: Path, root: Path, db: Path) -> float:
    clear_db(db)
    return timed(index_command(binary, root, db))


def normalize_json_output(text: str, paths: list[Path]):
    value = json.loads(text)
    replacements = list(dict.fromkeys(
        value for path in paths for value in (str(path), str(path.resolve()))))

    def normalize(item):
        if isinstance(item, dict):
            return {key: normalize(child) for key, child in item.items()
                    if key not in {"elapsed_ms", "timings"}}
        if isinstance(item, list):
            return [normalize(child) for child in item]
        if isinstance(item, str):
            for path in replacements:
                item = item.replace(path, "<INDEX>")
            return item
        return item

    return normalize(value)


def peak_rss(binary: Path, command: list[str]) -> int:
    if platform.system() == "Darwin":
        measured = subprocess.run(["/usr/bin/time", "-l", str(binary), *command],
                                  text=True, stdout=subprocess.DEVNULL,
                                  stderr=subprocess.PIPE, check=True)
        match = re.search(r"(?m)^\s*(\d+)\s+maximum resident set size$", measured.stderr)
    else:
        measured = subprocess.run(["/usr/bin/time", "-v", str(binary), *command],
                                  text=True, stdout=subprocess.DEVNULL,
                                  stderr=subprocess.PIPE, check=True)
        match = re.search(r"Maximum resident set size \(kbytes\):\s*(\d+)", measured.stderr)
        if match:
            return int(match.group(1)) * 1024
    if not match:
        raise RuntimeError("could not parse peak RSS from /usr/bin/time")
    return int(match.group(1))


def db_facts(path: Path) -> dict[str, int]:
    with sqlite3.connect(path) as connection:
        facts = {
            "bytes": path.stat().st_size,
            "nodes": connection.execute("SELECT count(*) FROM nodes").fetchone()[0],
            "edges": connection.execute("SELECT count(*) FROM edges").fetchone()[0],
        }
        if connection.execute("SELECT count(*) FROM sqlite_master WHERE type='table' "
                              "AND name='call_sites'").fetchone()[0]:
            facts["call_sites"] = connection.execute(
                "SELECT count(*) FROM call_sites").fetchone()[0]
        return facts


def markdown(report: dict) -> str:
    rows = ["| Gate | Baseline | Candidate | Delta | Limit | Result |",
            "|---|---:|---:|---:|---:|:---:|"]
    for gate in report["gates"]:
        rows.append("| {name} | {baseline:.2f} | {candidate:.2f} | {delta_pct:+.1f}% | "
                    "+{limit_pct:.0f}% | {status} |".format(**gate))
    conclusion = "PASS" if report["passed"] else "FAIL"
    semantic_rows = ["| Candidate-only semantic pipeline | p50 | p95 |",
                     "|---|---:|---:|"]
    for name, result in report.get("semantic_benchmarks", {}).items():
        semantic_rows.append(f"| {name} | {result['p50_ms']:.2f} ms | "
                             f"{result['p95_ms']:.2f} ms |")
    return "\n".join([
        "# Query refactor benchmark", "",
        f"结论：**{conclusion}**（baseline `{report['environment']['baseline_ref']}`）", "",
        *rows, "",
        *semantic_rows, "",
        "新语义管道在旧版本不存在，因此只报告 candidate 延迟，不伪造同比门禁。", "",
        "时间单位为 ms；数据库、二进制和 RSS 的数值单位为 byte。", "",
    ])


def main() -> int:
    args = parse_args()
    root = Path(__file__).resolve().parents[1]
    candidate_bin = (root / args.candidate_bin).resolve() if not args.candidate_bin.is_absolute() \
        else args.candidate_bin.resolve()
    if not candidate_bin.is_file():
        raise SystemExit(f"candidate binary missing: {candidate_bin}; run `just native`")
    output = (root / args.output).resolve() if not args.output.is_absolute() else args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    temp = Path(tempfile.mkdtemp(prefix="anatomist-query-bench."))
    worktree = temp / "baseline"
    baseline_added = False
    try:
        checked(["git", "worktree", "add", "--detach", str(worktree), args.baseline_ref], cwd=root)
        baseline_added = True
        checked(["just", "native"], cwd=worktree)
        baseline_bin = (worktree / "target/anatomist").resolve()

        old_db = temp / "baseline.db"
        new_db = temp / "candidate.db"
        index_samples = alternate(args.index_runs,
                lambda: index_once(baseline_bin, root, old_db),
                lambda: index_once(candidate_bin, root, new_db))
        # Leave one complete database per implementation for query/no-op measurements.
        index_once(baseline_bin, root, old_db)
        index_once(candidate_bin, root, new_db)

        selector = "com.anatomist.query.QueryService"
        callable_selector = selector + "#search(java.lang.String,java.lang.String,int)"
        query_cases = {
            "startup_version_native": lambda binary, db: [str(binary), "--version"],
            "legacy_search_native": lambda binary, db: [str(binary), "search", "QueryService",
                                                           "--index", str(db)],
            "legacy_context_native": lambda binary, db: [str(binary), "context", selector,
                                                            "--index", str(db)],
            "legacy_callees_native": lambda binary, db: [str(binary), "callees-of",
                    callable_selector, "--depth", "1", "--index", str(db)],
        }
        benchmarks: dict[str, dict] = {
            "full_index_native": {"old": stats(index_samples[0]),
                                  "new": stats(index_samples[1])}
        }
        for name, make_command in query_cases.items():
            for _ in range(2):
                timed(make_command(baseline_bin, old_db))
                timed(make_command(candidate_bin, new_db))
            samples = alternate(args.query_runs,
                    lambda command=make_command: timed(command(baseline_bin, old_db)),
                    lambda command=make_command: timed(command(candidate_bin, new_db)))
            benchmarks[name] = {"old": stats(samples[0]), "new": stats(samples[1])}

        noop = alternate(args.noop_runs,
                lambda: timed(index_command(baseline_bin, root, old_db, True)),
                lambda: timed(index_command(candidate_bin, root, new_db, True)))
        benchmarks["noop_incremental_native"] = {
            "old": stats(noop[0]), "new": stats(noop[1])}

        def candidate(command: str, *arguments: str) -> list[str]:
            return [str(candidate_bin), command, *arguments, "--index", str(new_db)]

        semantic_cases = {
            "type_relations": [
                candidate("search", "--name", "QueryService", "--kind", "type",
                          "--format", "ndjson"),
                candidate("resolve", "--unique"),
                candidate("type-relations", "--direction", "outgoing"),
            ],
            "calls_dispatch": [
                candidate("resolve", callable_selector, "--kind", "callable", "--exact", "--unique"),
                candidate("calls", "--direction", "outgoing"),
                candidate("dispatch", "--algorithm", "auto"),
            ],
        }
        semantic_benchmarks: dict[str, dict] = {}
        for name, commands in semantic_cases.items():
            timed_pipeline(commands)
            semantic_benchmarks[name] = stats([
                timed_pipeline(commands) for _ in range(args.query_runs)
            ])

        contracts: dict[str, bool] = {}
        for name, make_command in query_cases.items():
            if name == "startup_version_native":
                continue
            old_output = checked(make_command(baseline_bin, old_db), capture=True).stdout
            new_output = checked(make_command(candidate_bin, new_db), capture=True).stdout
            old_normalized = normalize_json_output(old_output, [old_db, new_db])
            new_normalized = normalize_json_output(new_output, [old_db, new_db])
            contracts[name] = old_normalized == new_normalized
            (output / f"{name}-baseline.json").write_text(
                json.dumps(old_normalized, indent=2) + "\n")
            (output / f"{name}-candidate.json").write_text(
                json.dumps(new_normalized, indent=2) + "\n")

        rss_command_old = ["search", "QueryService", "--index", str(old_db)]
        rss_command_new = ["search", "QueryService", "--index", str(new_db)]
        rss = alternate(args.rss_runs,
                lambda: float(peak_rss(baseline_bin, rss_command_old)),
                lambda: float(peak_rss(candidate_bin, rss_command_new)))
        artifacts = {
            "database": {"old": db_facts(old_db), "new": db_facts(new_db)},
            "binary_bytes": {"old": baseline_bin.stat().st_size,
                             "new": candidate_bin.stat().st_size},
            "peak_rss_bytes": {"old": int(statistics.median(rss[0])),
                               "new": int(statistics.median(rss[1]))},
        }

        gates: list[dict] = []

        def add_gate(name: str, old: float, new: float, limit: float) -> None:
            change = delta(new, old)
            gates.append({"name": name, "baseline": old, "candidate": new,
                          "delta_pct": round(change, 2), "limit_pct": limit,
                          "status": "PASS" if change <= limit else "FAIL"})

        for name in ("legacy_search_native", "legacy_context_native", "legacy_callees_native",
                     "full_index_native", "noop_incremental_native"):
            add_gate(name + ".p50", benchmarks[name]["old"]["p50_ms"],
                     benchmarks[name]["new"]["p50_ms"], 5.0)
            add_gate(name + ".p95", benchmarks[name]["old"]["p95_ms"],
                     benchmarks[name]["new"]["p95_ms"], 10.0)
        add_gate("database.bytes", artifacts["database"]["old"]["bytes"],
                 artifacts["database"]["new"]["bytes"], 35.0)
        add_gate("binary.bytes", artifacts["binary_bytes"]["old"],
                 artifacts["binary_bytes"]["new"], 5.0)
        add_gate("peak_rss.bytes", artifacts["peak_rss_bytes"]["old"],
                 artifacts["peak_rss_bytes"]["new"], 5.0)
        for name, equal in contracts.items():
            gates.append({"name": name + ".normalized_output", "baseline": 1.0,
                          "candidate": 1.0 if equal else 0.0,
                          "delta_pct": 0.0 if equal else -100.0, "limit_pct": 0.0,
                          "status": "PASS" if equal else "FAIL"})

        report = {
            "environment": {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "system": platform.platform(),
                "machine": platform.machine(),
                "baseline_ref": args.baseline_ref,
                "candidate_head": checked(["git", "rev-parse", "HEAD"], cwd=root,
                                          capture=True).stdout.strip(),
                "working_tree_dirty": bool(checked(["git", "status", "--porcelain"], cwd=root,
                                                    capture=True).stdout),
            },
            "runs": {"query": args.query_runs, "index": args.index_runs,
                     "noop": args.noop_runs, "rss": args.rss_runs},
            "benchmarks": benchmarks,
            "semantic_benchmarks": semantic_benchmarks,
            "contracts": contracts,
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
            subprocess.run(["git", "worktree", "remove", "--force", str(worktree)],
                           cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        shutil.rmtree(temp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
