#!/usr/bin/env python3
"""Compare two semantic-stream/v1 implementations on one immutable index."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import shutil
import sqlite3
import statistics
import subprocess
import tempfile
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable


@dataclass(frozen=True)
class Workload:
    name: str
    stages: tuple[tuple[str, ...], ...]
    required_records: frozenset[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    baseline = parser.add_mutually_exclusive_group(required=True)
    baseline.add_argument("--baseline-bin", type=Path,
                          help="Frozen, pre-change semantic-stream/v1 binary.")
    baseline.add_argument("--baseline-ref",
                          help="Git revision to build in a detached worktree.")
    parser.add_argument("--candidate-bin", type=Path, default=Path("target/anatomist"))
    parser.add_argument("--project", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path,
                        default=Path("target/benchmarks/semantic-pipeline"))
    parser.add_argument("--runs", type=int, default=30)
    parser.add_argument("--warmups", type=int, default=5)
    parser.add_argument("--max-p50-regression-pct", type=float, default=15.0)
    parser.add_argument("--max-p95-regression-pct", type=float, default=25.0)
    parser.add_argument("--required-calls-improvement-pct", type=float, default=0.0,
                        help="Optional minimum p50 improvement for 3-stage calls workloads.")
    return parser.parse_args()


def run(command: list[str]) -> bytes:
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise RuntimeError(
            f"command failed (exit {result.returncode}): {' '.join(command)}\n"
            + result.stderr.decode(errors="replace")
        )
    return result.stdout


def checked(command: list[str], cwd: Path) -> None:
    subprocess.run(command, cwd=cwd, check=True, stdout=subprocess.DEVNULL)


def pipeline(binary: Path, db: Path, workload: Workload) -> bytes:
    processes: list[subprocess.Popen[bytes]] = []
    previous = None
    for stage in workload.stages:
        command = [str(binary), *stage, "--index", str(db)]
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
    errors: list[str] = []
    if processes[-1].returncode:
        errors.append(last_error.decode(errors="replace"))
    for process in reversed(processes[:-1]):
        return_code = process.wait()
        error = process.stderr.read().decode(errors="replace") if process.stderr else ""
        if return_code:
            errors.append(error)
    if errors:
        raise RuntimeError(
            f"pipeline failed: {workload.name}\n" + "\n".join(reversed(errors))
        )
    return output


def timed(operation: Callable[[], bytes]) -> float:
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


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def version(binary: Path) -> str:
    return run([str(binary), "--version"]).decode().strip()


def validate_stream(output: bytes, required: frozenset[str]) -> bool:
    records = [json.loads(line) for line in output.decode().splitlines() if line.strip()]
    kinds = {str(record.get("record")) for record in records}
    final = next((record for record in reversed(records)
                  if record.get("record") == "evidence"
                  and record.get("scope") == "stream"), None)
    return required <= kinds and final is not None


def highest_fanout(db: Path, fallback: str) -> tuple[str, int]:
    with sqlite3.connect(db) as connection:
        row = connection.execute(
            "SELECT owners.caller_id, count(*) AS sites "
            "FROM call_sites sites "
            "JOIN call_site_owners owners ON owners.owner_pk=sites.owner_pk "
            "GROUP BY owners.caller_id ORDER BY sites DESC, owners.caller_id LIMIT 1"
        ).fetchone()
    return (str(row[0]), int(row[1])) if row else (fallback, 0)


def workloads(high_fanout_selector: str) -> list[Workload]:
    type_selector = "com.anatomist.query.QueryService"
    callable_selector = type_selector + "#search(java.lang.String,java.lang.String,int)"
    resolve_callable = (
        "resolve", callable_selector, "--kind", "callable", "--exact", "--unique",
    )
    resolve_high_fanout = (
        "resolve", high_fanout_selector, "--kind", "callable", "--exact", "--unique",
    )
    return [
        Workload("resolve_one", (resolve_callable,), frozenset({"entity", "evidence"})),
        Workload("type_pipeline", (
            ("resolve", type_selector, "--kind", "type", "--unique"),
            ("describe",),
        ), frozenset({"declaration", "evidence"})),
        Workload("calls_pipeline", (
            resolve_callable, ("calls",), ("dispatch",),
        ), frozenset({"dispatch_target", "evidence"})),
        Workload("calls_high_fanout", (
            resolve_high_fanout, ("calls", "--limit", "100000"),
            ("dispatch", "--limit", "100000"),
        ), frozenset({"dispatch_target", "evidence"})),
    ]


def markdown(report: dict) -> str:
    result = "PASS" if report["passed"] else "FAIL"
    rows = [
        "| Workload | Stages | Baseline p50 | Candidate p50 | Delta | p95 Delta | Output |",
        "|---|---:|---:|---:|---:|---:|:---:|",
    ]
    for name, value in report["benchmarks"].items():
        rows.append(
            f"| {name} | {value['stages']} | {value['baseline']['p50_ms']:.2f} ms | "
            f"{value['candidate']['p50_ms']:.2f} ms | {value['p50_delta_pct']:+.1f}% | "
            f"{value['p95_delta_pct']:+.1f}% | "
            f"{'same' if value['output_equal'] else 'DIFF'} |"
        )
    environment = report["environment"]
    return "\n".join([
        "# Semantic pipeline benchmark", "",
        f"结论：**{result}**", "",
        *rows, "",
        f"固定索引：`{report['index']['bytes']}` bytes；最高扇出 selector："
        f"`{report['index']['high_fanout_selector']}`（{report['index']['high_fanout_sites']} sites）。",
        f"索引 SHA-256：`{report['index']['sha256_before']}`（查询前后不变）。",
        f"Baseline: `{environment['baseline_version']}` / `{environment['baseline_sha256']}`。",
        f"Candidate: `{environment['candidate_version']}` / `{environment['candidate_sha256']}`。",
        "Baseline 与 candidate 查询同一个只读索引；输出按原始 NDJSON 字节比较。", "",
    ])


def main() -> int:
    args = parse_args()
    if args.runs < 1 or args.warmups < 0:
        raise SystemExit("--runs must be >= 1 and --warmups must be >= 0")

    root = Path(__file__).resolve().parents[1]
    candidate_bin = (root / args.candidate_bin).resolve() \
        if not args.candidate_bin.is_absolute() else args.candidate_bin.resolve()
    project = (root / args.project).resolve() \
        if not args.project.is_absolute() else args.project.resolve()
    output_dir = (root / args.output).resolve() \
        if not args.output.is_absolute() else args.output.resolve()
    if not candidate_bin.is_file():
        raise SystemExit(f"candidate binary missing: {candidate_bin}; run `just native`")
    if not project.is_dir():
        raise SystemExit(f"project missing: {project}")

    temp = Path(tempfile.mkdtemp(prefix="anatomist-pipeline-bench."))
    worktree = temp / "baseline"
    baseline_added = False
    try:
        if args.baseline_ref:
            checked(["git", "worktree", "add", "--detach", str(worktree),
                     args.baseline_ref], root)
            baseline_added = True
            checked(["just", "native"], worktree)
            baseline_bin = (worktree / "target/anatomist").resolve()
            baseline_label = "git:" + args.baseline_ref
        else:
            source = args.baseline_bin.expanduser().resolve()
            if not source.is_file():
                raise SystemExit(f"baseline binary missing: {source}")
            baseline_bin = temp / "baseline-anatomist"
            shutil.copy2(source, baseline_bin)
            baseline_label = "binary:" + str(source)

        db = temp / "pipeline.db"
        run([str(baseline_bin), "index", str(project), "--no-classpath",
             "--output", str(db)])
        db_sha256_before = sha256(db)
        fallback = "com.anatomist.query.QueryService#search(java.lang.String,java.lang.String,int)"
        high_fanout_selector, high_fanout_sites = highest_fanout(db, fallback)

        benchmark_results: dict[str, dict] = {}
        gates: list[dict] = []
        for workload in workloads(high_fanout_selector):
            baseline_operation = lambda workload=workload: pipeline(
                baseline_bin, db, workload)
            candidate_operation = lambda workload=workload: pipeline(
                candidate_bin, db, workload)
            old_output = baseline_operation()
            new_output = candidate_operation()
            output_equal = old_output == new_output
            stream_valid = validate_stream(new_output, workload.required_records)

            for _ in range(args.warmups):
                baseline_operation()
                candidate_operation()
            old_samples, new_samples = alternate(
                args.runs,
                lambda: timed(baseline_operation),
                lambda: timed(candidate_operation),
            )
            old_stats = stats(old_samples)
            new_stats = stats(new_samples)
            p50_delta = delta(float(new_stats["p50_ms"]), float(old_stats["p50_ms"]))
            p95_delta = delta(float(new_stats["p95_ms"]), float(old_stats["p95_ms"]))
            benchmark_results[workload.name] = {
                "stages": len(workload.stages),
                "baseline": old_stats,
                "candidate": new_stats,
                "p50_delta_pct": round(p50_delta, 2),
                "p95_delta_pct": round(p95_delta, 2),
                "output_equal": output_equal,
                "stream_valid": stream_valid,
            }
            gates.extend([
                {"name": workload.name + ".output_equal", "passed": output_equal},
                {"name": workload.name + ".stream_valid", "passed": stream_valid},
                {"name": workload.name + ".p50_regression",
                 "passed": p50_delta <= args.max_p50_regression_pct},
                {"name": workload.name + ".p95_regression",
                 "passed": p95_delta <= args.max_p95_regression_pct},
            ])
            if (args.required_calls_improvement_pct > 0
                    and workload.name.startswith("calls_")):
                gates.append({
                    "name": workload.name + ".required_p50_improvement",
                    "passed": p50_delta <= -args.required_calls_improvement_pct,
                })

        db_sha256_after = sha256(db)
        gates.append({
            "name": "shared_index_unchanged",
            "passed": db_sha256_before == db_sha256_after,
        })

        report = {
            "environment": {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "system": platform.platform(),
                "machine": platform.machine(),
                "baseline_label": baseline_label,
                "baseline_version": version(baseline_bin),
                "baseline_sha256": sha256(baseline_bin),
                "candidate_version": version(candidate_bin),
                "candidate_sha256": sha256(candidate_bin),
            },
            "settings": {
                "runs": args.runs,
                "warmups": args.warmups,
                "max_p50_regression_pct": args.max_p50_regression_pct,
                "max_p95_regression_pct": args.max_p95_regression_pct,
                "required_calls_improvement_pct": args.required_calls_improvement_pct,
            },
            "index": {
                "project": str(project),
                "bytes": db.stat().st_size,
                "sha256_before": db_sha256_before,
                "sha256_after": db_sha256_after,
                "high_fanout_selector": high_fanout_selector,
                "high_fanout_sites": high_fanout_sites,
            },
            "benchmarks": benchmark_results,
            "gates": gates,
            "passed": all(gate["passed"] for gate in gates),
        }
        output_dir.mkdir(parents=True, exist_ok=True)
        (output_dir / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        (output_dir / "report.md").write_text(markdown(report))
        print(markdown(report))
        print(f"Reports: {output_dir / 'results.json'}  {output_dir / 'report.md'}")
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
