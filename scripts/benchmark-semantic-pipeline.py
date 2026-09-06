#!/usr/bin/env python3
"""Compare two semantic-stream/v1 implementations on independent indexes."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import re
import shlex
import shutil
import sqlite3
import statistics
import subprocess
import sys
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


def shell_pipeline(binary: Path, db: Path, workload: Workload) -> bytes:
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


def fused_pipeline(binary: Path, db: Path, workload: Workload) -> bytes:
    command = [str(binary), "pipeline", "--index", str(db), "--"]
    for index, stage in enumerate(workload.stages):
        if index:
            command.append("--then")
        command.extend(stage)
    return run(command)


def peak_rss(command: list[str]) -> int | None:
    """Measure one command with the host time(1); return bytes when supported."""
    time_binary = Path("/usr/bin/time")
    if not time_binary.is_file():
        return None
    flag = "-l" if sys.platform == "darwin" else "-v"
    result = subprocess.run(
        [str(time_binary), flag, *command],
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        return None
    report = result.stderr.decode(errors="replace")
    if sys.platform == "darwin":
        match = re.search(r"(\d+)\s+maximum resident set size", report)
        return int(match.group(1)) if match else None
    match = re.search(r"Maximum resident set size \(kbytes\):\s*(\d+)", report)
    return int(match.group(1)) * 1024 if match else None


def shell_command(binary: Path, db: Path, workload: Workload) -> list[str]:
    stages = [shlex.join([str(binary), *stage, "--index", str(db)])
              for stage in workload.stages]
    return ["sh", "-c", " | ".join(stages)]


def fused_command(binary: Path, db: Path, workload: Workload) -> list[str]:
    command = [str(binary), "pipeline", "--index", str(db), "--"]
    for index, stage in enumerate(workload.stages):
        if index:
            command.append("--then")
        command.extend(stage)
    return command


def timed(operation: Callable[[], bytes]) -> float:
    started = time.perf_counter_ns()
    operation()
    return (time.perf_counter_ns() - started) / 1_000_000


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


def semantic_digest(output: bytes) -> str:
    """Compare facts, not framing or intentionally removed redundant fields."""
    ignored = {
        "contract", "identity", "index_revision_id", "source_snapshot_id",
        "semantic_profile_id", "seed_id", "parent_seed_id", "derived_from",
        "resolved_target",
    }
    facts = []
    for record in (json.loads(line) for line in output.decode().splitlines() if line.strip()):
        if record.get("record") in {"stream_header", "evidence"}:
            continue
        facts.append({key: value for key, value in record.items() if key not in ignored})
    canonical = json.dumps(facts, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()


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
            resolve_callable, ("calls",),
        ), frozenset({"call_site", "evidence"})),
        Workload("dispatch_pipeline", (
            resolve_callable, ("calls",), ("dispatch",),
        ), frozenset({"dispatch_target", "evidence"})),
        Workload("calls_high_fanout", (
            resolve_high_fanout, ("calls", "--limit", "100000"),
        ), frozenset({"call_site", "evidence"})),
    ]


def markdown(report: dict) -> str:
    result = "PASS" if report["passed"] else "FAIL"
    rows = [
        "| Workload | Stages | Baseline p50 | Fused p50 | Delta | p95 Delta | Output old/new | Semantic |",
        "|---|---:|---:|---:|---:|---:|---:|:---:|",
    ]
    for name, value in report["benchmarks"].items():
        old_rss = value["baseline_peak_rss_bytes"]
        new_rss = value["candidate_peak_rss_bytes"]
        rows.append(
            f"| {name} | {value['stages']} | {value['baseline']['p50_ms']:.2f} ms | "
            f"{value['candidate']['p50_ms']:.2f} ms | {value['p50_delta_pct']:+.1f}% | "
            f"{value['p95_delta_pct']:+.1f}% | {value['baseline_output_bytes']}/"
            f"{value['candidate_output_bytes']} B | "
            f"{'same' if value['semantic_equal'] else 'DIFF'} |"
        )
    environment = report["environment"]
    return "\n".join([
        "# Semantic pipeline benchmark", "",
        f"结论：**{result}**", "",
        *rows, "",
        f"独立索引：baseline `{report['index']['baseline_bytes']}` B，candidate "
        f"`{report['index']['candidate_bytes']}` B；最高扇出 selector："
        f"`{report['index']['high_fanout_selector']}`（{report['index']['high_fanout_sites']} sites）。",
        "两个索引的 SHA-256 在查询前后均不变。",
        f"Baseline: `{environment['baseline_version']}` / `{environment['baseline_sha256']}`。",
        f"Candidate: `{environment['candidate_version']}` / `{environment['candidate_sha256']}`。",
        "Baseline 使用 Shell 多进程管道，candidate 使用单进程 fused pipeline；"
        "输出按去除 framing 冗余后的语义 digest 比较。", "",
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

        baseline_db = temp / "baseline.db"
        candidate_db = temp / "candidate.db"
        run([str(baseline_bin), "index", str(project), "--no-classpath",
             "--output", str(baseline_db)])
        run([str(candidate_bin), "index", str(project), "--no-classpath",
             "--output", str(candidate_db)])
        baseline_sha_before = sha256(baseline_db)
        candidate_sha_before = sha256(candidate_db)
        fallback = "com.anatomist.query.QueryService#search(java.lang.String,java.lang.String,int)"
        high_fanout_selector, high_fanout_sites = highest_fanout(candidate_db, fallback)

        selected_workloads = workloads(high_fanout_selector)
        operations: dict[str, tuple[Callable[[], bytes], Callable[[], bytes]]] = {}
        outputs: dict[str, tuple[bool, bool, int, int]] = {}
        samples: dict[str, tuple[list[float], list[float]]] = {}
        for workload in selected_workloads:
            baseline_operation = lambda workload=workload: shell_pipeline(
                baseline_bin, baseline_db, workload)
            candidate_operation = lambda workload=workload: fused_pipeline(
                candidate_bin, candidate_db, workload)
            operations[workload.name] = (baseline_operation, candidate_operation)
            old_output = baseline_operation()
            new_output = candidate_operation()
            outputs[workload.name] = (
                semantic_digest(old_output) == semantic_digest(new_output),
                validate_stream(new_output, workload.required_records),
                len(old_output), len(new_output),
            )
            for _ in range(args.warmups):
                baseline_operation()
                candidate_operation()
            samples[workload.name] = ([], [])

        # Round-robin prevents a short host-load or thermal event from being isolated
        # inside one workload. AB/BA order still alternates within every pair.
        for iteration in range(args.runs):
            for workload_index, workload in enumerate(selected_workloads):
                baseline_operation, candidate_operation = operations[workload.name]
                old_samples, new_samples = samples[workload.name]
                if (iteration + workload_index) % 2 == 0:
                    old_samples.append(timed(baseline_operation))
                    new_samples.append(timed(candidate_operation))
                else:
                    new_samples.append(timed(candidate_operation))
                    old_samples.append(timed(baseline_operation))

        benchmark_results: dict[str, dict] = {}
        gates: list[dict] = []
        for workload in selected_workloads:
            old_samples, new_samples = samples[workload.name]
            semantic_equal, stream_valid, old_bytes, new_bytes = outputs[workload.name]
            old_stats = stats(old_samples)
            new_stats = stats(new_samples)
            p50_delta = delta(float(new_stats["p50_ms"]), float(old_stats["p50_ms"]))
            p95_delta = delta(float(new_stats["p95_ms"]), float(old_stats["p95_ms"]))
            benchmark_results[workload.name] = {
                "stages": len(workload.stages),
                "baseline": old_stats,
                "candidate": new_stats,
                "baseline_samples_ms": [round(value, 3) for value in old_samples],
                "candidate_samples_ms": [round(value, 3) for value in new_samples],
                "baseline_peak_rss_bytes": peak_rss(
                    shell_command(baseline_bin, baseline_db, workload)),
                "candidate_peak_rss_bytes": peak_rss(
                    fused_command(candidate_bin, candidate_db, workload)),
                "p50_delta_pct": round(p50_delta, 2),
                "p95_delta_pct": round(p95_delta, 2),
                "baseline_output_bytes": old_bytes,
                "candidate_output_bytes": new_bytes,
                "output_ratio": round(new_bytes / old_bytes, 4),
                "semantic_equal": semantic_equal,
                "stream_valid": stream_valid,
            }
            gates.extend([
                {"name": workload.name + ".semantic_equal", "passed": semantic_equal},
                {"name": workload.name + ".stream_valid", "passed": stream_valid},
                {"name": workload.name + ".p50_regression",
                 "passed": p50_delta <= args.max_p50_regression_pct},
                {"name": workload.name + ".p95_regression",
                 "passed": p95_delta <= args.max_p95_regression_pct},
            ])
            if len(workload.stages) >= 2:
                gates.append({
                    "name": workload.name + ".output_at_most_60pct",
                    "passed": new_bytes <= old_bytes * 0.60,
                })
            if (args.required_calls_improvement_pct > 0
                    and workload.name.startswith("calls_")):
                gates.append({
                    "name": workload.name + ".required_p50_improvement",
                    "passed": p50_delta <= -args.required_calls_improvement_pct,
                })

        baseline_sha_after = sha256(baseline_db)
        candidate_sha_after = sha256(candidate_db)
        gates.append({
            "name": "indexes_unchanged",
            "passed": baseline_sha_before == baseline_sha_after
                      and candidate_sha_before == candidate_sha_after,
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
                "baseline_execution": "shell-pipeline",
                "candidate_execution": "fused-pipeline",
            },
            "settings": {
                "runs": args.runs,
                "warmups": args.warmups,
                "sampling_order": "round-robin AB/BA",
                "max_p50_regression_pct": args.max_p50_regression_pct,
                "max_p95_regression_pct": args.max_p95_regression_pct,
                "required_calls_improvement_pct": args.required_calls_improvement_pct,
            },
            "index": {
                "project": str(project),
                "baseline_bytes": baseline_db.stat().st_size,
                "candidate_bytes": candidate_db.stat().st_size,
                "baseline_sha256_before": baseline_sha_before,
                "baseline_sha256_after": baseline_sha_after,
                "candidate_sha256_before": candidate_sha_before,
                "candidate_sha256_after": candidate_sha_after,
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
