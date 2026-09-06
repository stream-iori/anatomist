#!/usr/bin/env python3
"""Compare the v24 declarations table with the v25 embedded node facet."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import platform
import shutil
import sqlite3
import statistics
import subprocess
import tempfile
import time
from pathlib import Path


BASELINE_REF = "a2c5ce7cbc7fe84e25ff7ca8907c365fbf2f21d7"
DECLARATION_COLUMNS = (
    "symbol_id,domain,language,provider_id,entity_kind,language_kind,qualified_name,label,kind,"
    "declaration_kind,type_kind,visibility,modifiers,declared_modifiers,implicit_modifiers,"
    "declaring_type,namespace,source_file,source_location,begin_line,begin_column,end_line,end_column,"
    "module,scope,nesting_depth,direct_member,synthetic,binding_resolved,producer_id"
)
CANDIDATE_DECLARATION_COLUMNS = DECLARATION_COLUMNS.replace(
    "source_location,begin_line,begin_column,end_line,end_column",
    "declaration_source_location,declaration_begin_line,declaration_begin_column,"
    "declaration_end_line,declaration_end_column",
).replace("declaring_type,namespace", "declaring_type,declaration_namespace")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-ref", default=BASELINE_REF)
    parser.add_argument("--baseline-bin", type=Path)
    parser.add_argument("--candidate-bin", type=Path, default=Path("target/anatomist"))
    parser.add_argument("--project", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path,
                        default=Path("target/benchmarks/storage-p1"))
    parser.add_argument("--full-runs", type=int, default=3)
    parser.add_argument("--incremental-runs", type=int, default=10)
    parser.add_argument("--query-runs", type=int, default=30)
    parser.add_argument("--warmups", type=int, default=5)
    return parser.parse_args()


def execute(command: list[str], cwd: Path | None = None) -> bytes:
    result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(command)}\n"
                           + result.stderr.decode(errors="replace"))
    return result.stdout


def timed(command: list[str], cwd: Path | None = None) -> tuple[float, bytes]:
    started = time.perf_counter_ns()
    output = execute(command, cwd)
    return (time.perf_counter_ns() - started) / 1_000_000, output


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def stats(values: list[float]) -> dict[str, float | int]:
    return {
        "n": len(values),
        "p50_ms": round(statistics.median(values), 2),
        "p95_ms": round(percentile(values, .95), 2),
        "min_ms": round(min(values), 2),
        "max_ms": round(max(values), 2),
    }


def delta(candidate: float, baseline: float) -> float:
    return round((candidate / baseline - 1) * 100, 2)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_json(value: object) -> str:
    canonical = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode()).hexdigest()


def build_baseline(root: Path, revision: str, temporary: Path) -> Path:
    worktree = temporary / "baseline-worktree"
    subprocess.run(["git", "worktree", "add", "--detach", str(worktree), revision],
                   cwd=root, check=True, stdout=subprocess.DEVNULL)
    try:
        execute(["just", "native"], worktree)
        binary = temporary / "anatomist-baseline"
        shutil.copy2(worktree / "target/anatomist", binary)
        binary.chmod(0o755)
        return binary
    finally:
        subprocess.run(["git", "worktree", "remove", "--force", str(worktree)],
                       cwd=root, check=True, stdout=subprocess.DEVNULL)


def project_copy(source: Path, target: Path) -> Path:
    ignored = shutil.ignore_patterns(".git", "target", ".idea", "*.db", "*.db-wal", "*.db-shm")
    shutil.copytree(source, target, ignore=ignored)
    return target


def index_command(binary: Path, project: Path, database: Path, incremental: bool) -> list[str]:
    command = [str(binary), "index", str(project)]
    if incremental:
        command.append("--incremental")
    command.extend(["--include-tests", "--timings", "--format", "json",
                    "--output", str(database)])
    return command


def full_indexes(variants: dict[str, tuple[Path, Path]], directory: Path,
                 count: int) -> tuple[dict[str, list[float]], dict[str, Path], dict[str, list[dict]]]:
    samples = {name: [] for name in variants}
    databases: dict[str, Path] = {}
    timings = {name: [] for name in variants}
    for run_index in range(count):
        order = list(variants) if run_index % 2 == 0 else list(reversed(variants))
        for name in order:
            binary, project = variants[name]
            database = directory / f"{name}-full-{run_index}.db"
            elapsed, output = timed(index_command(binary, project, database, False))
            samples[name].append(elapsed)
            databases[name] = database
            envelope = json.loads(output)
            timings[name].append(envelope.get("timings_ms", {}))
    return samples, databases, timings


def reset_database(source: Path, target: Path) -> None:
    for suffix in ("", "-wal", "-shm"):
        Path(str(target) + suffix).unlink(missing_ok=True)
    shutil.copy2(source, target)


def incremental_samples(binary: Path, project: Path, base_database: Path,
                        work_database: Path, relative_file: str | None, count: int) -> list[float]:
    source = project / relative_file if relative_file else None
    original = source.read_bytes() if source else None
    samples: list[float] = []
    try:
        for run_index in range(count):
            reset_database(base_database, work_database)
            if source:
                source.write_bytes(original + f"\n// storage-p1-benchmark-{run_index}\n".encode())
            elapsed, _ = timed(index_command(binary, project, work_database, True))
            samples.append(elapsed)
            if source:
                source.write_bytes(original)
    finally:
        if source and original is not None:
            source.write_bytes(original)
    return samples


def remove_identity(value: object) -> object:
    ignored = {"identity", "index_revision_id", "semantic_profile_id", "source_snapshot_id"}
    if isinstance(value, dict):
        return {key: remove_identity(item) for key, item in value.items() if key not in ignored}
    if isinstance(value, list):
        return [remove_identity(item) for item in value]
    return value


def semantic_digest(output: bytes) -> str:
    records = [json.loads(line) for line in output.decode().splitlines() if line.strip()]
    return sha256_json(remove_identity(records))


def query_commands(binary: Path, database: Path) -> dict[str, list[str]]:
    callable_id = "com.anatomist.query.QueryService#search(java.lang.String,java.lang.String,int)"
    base = [str(binary)]
    return {
        "declarations": base + ["declarations-of", "--file",
            "src/main/java/com/anatomist/query/semantic/SemanticRecord.java", "--scope", "ALL",
            "--limit", "1000", "--format", "ndjson", "--index", str(database)],
        "source": base + ["pipeline", "--format", "ndjson", "--index", str(database), "--",
            "resolve", callable_id, "--kind", "callable", "--exact", "--unique",
            "--then", "source", "--limit", "100"],
        "dispatch": base + ["pipeline", "--format", "ndjson", "--index", str(database), "--",
            "resolve", callable_id, "--kind", "callable", "--exact", "--unique",
            "--then", "calls", "--then", "dispatch"],
        "overview": base + ["overview", "--format", "ndjson", "--index", str(database)],
        "search": base + ["search", "QueryService", "--format", "ndjson", "--index", str(database)],
    }


def query_benchmark(variants: dict[str, tuple[Path, Path]], databases: dict[str, Path],
                    warmups: int, runs: int) -> tuple[dict, dict[str, bool], bool]:
    database_hashes = {name: sha256_file(database) for name, database in databases.items()}
    commands = {name: query_commands(binary, databases[name])
                for name, (binary, _) in variants.items()}
    result: dict[str, dict] = {}
    equality: dict[str, bool] = {}
    for workload in commands["baseline"]:
        outputs = {name: execute(commands[name][workload]) for name in variants}
        equality[workload] = semantic_digest(outputs["baseline"]) == semantic_digest(outputs["candidate"])
        for _ in range(warmups):
            for name in variants:
                execute(commands[name][workload])
        samples = {name: [] for name in variants}
        for run_index in range(runs):
            order = list(variants) if run_index % 2 == 0 else list(reversed(variants))
            for name in order:
                elapsed, _ = timed(commands[name][workload])
                samples[name].append(elapsed)
        baseline_stats = stats(samples["baseline"])
        candidate_stats = stats(samples["candidate"])
        result[workload] = {
            "baseline": baseline_stats,
            "candidate": candidate_stats,
            "p50_delta_pct": delta(candidate_stats["p50_ms"], baseline_stats["p50_ms"]),
            "p95_delta_pct": delta(candidate_stats["p95_ms"], baseline_stats["p95_ms"]),
            "semantic_equal": equality[workload],
        }
    read_only_unchanged = all(sha256_file(databases[name]) == digest
                              for name, digest in database_hashes.items())
    return result, equality, read_only_unchanged


def health(binary: Path, database: Path) -> dict:
    output = execute([str(binary), "doctor", "--health-policy", "integrity",
                      "--format", "json", "--index", str(database)])
    envelope = json.loads(output)
    return {"health": envelope.get("health"), "gate": envelope.get("gate", {}),
            "passed": bool(envelope.get("gate", {}).get("passed"))}


def database_facts(database: Path, candidate: bool) -> dict:
    with sqlite3.connect(database) as connection:
        source = "nodes WHERE declaration_kind IS NOT NULL" if candidate else "declarations"
        columns = CANDIDATE_DECLARATION_COLUMNS if candidate else DECLARATION_COLUMNS
        declarations = connection.execute(
            f"SELECT {columns} FROM {source} ORDER BY provider_id,symbol_id,module,scope,source_file,producer_id"
        ).fetchall()
        counts = {}
        for table in ("nodes", "edges", "annotations", "annotation_meta_relations",
                      "semantic_annotations", "call_sites", "call_site_targets"):
            counts[table] = connection.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
        tables = {row[0] for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
    return {"declaration_count": len(declarations), "declaration_digest": sha256_json(declarations),
            "_declarations": declarations,
            "counts": counts, "has_declarations_table": "declarations" in tables}


def storage(database: Path, temporary: Path) -> dict:
    raw = database.stat().st_size
    vacuumed = temporary / (database.stem + "-vacuum.db")
    shutil.copy2(database, vacuumed)
    with sqlite3.connect(vacuumed) as connection:
        connection.execute("VACUUM")
        try:
            rows = connection.execute(
                "SELECT name,sum(pgsize) FROM dbstat GROUP BY name ORDER BY sum(pgsize) DESC"
            ).fetchall()
            dbstat = {str(name): int(size) for name, size in rows}
        except sqlite3.OperationalError:
            dbstat = {}
    return {"raw_bytes": raw, "vacuum_bytes": vacuumed.stat().st_size, "dbstat": dbstat}


def report_markdown(report: dict) -> str:
    lines = [f"# SQLite declaration facet P1 — {'PASS' if report['passed'] else 'FAIL'}", "",
             "## Storage", "", "| Metric | Baseline | Candidate | Delta |", "|---|---:|---:|---:|"]
    for key, label in (("raw_bytes", "Raw DB"), ("vacuum_bytes", "VACUUM DB")):
        old = report["storage"]["baseline"][key]
        new = report["storage"]["candidate"][key]
        lines.append(f"| {label} | {old:,} | {new:,} | {delta(new, old):+.2f}% |")
    lines.extend(["", "## Performance", "",
                  "| Workload | Baseline p50 | Candidate p50 | p50 delta | p95 delta | Gate |",
                  "|---|---:|---:|---:|---:|:---:|"])
    groups = {"full_index": report["full_index"], **report["incremental"], **report["queries"]}
    for name, value in groups.items():
        lines.append(f"| {name} | {value['baseline']['p50_ms']:.2f} ms | "
                     f"{value['candidate']['p50_ms']:.2f} ms | {value['p50_delta_pct']:+.2f}% | "
                     f"{value['p95_delta_pct']:+.2f}% | {'PASS' if value['passed'] else 'FAIL'} |")
    lines.extend(["", "## Correctness", "",
                  f"- Declaration facts: {'same' if report['correctness']['declarations_equal'] else 'DIFF'}",
                  f"- Structural row counts: {'same' if report['correctness']['counts_equal'] else 'DIFF'}",
                  f"- Query facts: {'same' if report['correctness']['queries_equal'] else 'DIFF'}",
                  f"- Read-only DB hashes: {'unchanged' if report['correctness']['read_only_hashes_unchanged'] else 'CHANGED'}",
                  f"- Candidate declarations table absent: {not report['correctness']['candidate_has_declarations_table']}", ""])
    return "\n".join(lines)


def main() -> int:
    args = arguments()
    root = args.project.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="anatomist-storage-p1-") as raw_temp:
        temporary = Path(raw_temp)
        if args.baseline_bin:
            baseline_binary = args.baseline_bin.resolve()
        else:
            cached_baseline = output / f"baseline-anatomist-{args.baseline_ref[:12]}"
            if not cached_baseline.is_file():
                built_baseline = build_baseline(root, args.baseline_ref, temporary)
                shutil.copy2(built_baseline, cached_baseline)
                cached_baseline.chmod(0o755)
            baseline_binary = cached_baseline
        candidate_binary = args.candidate_bin.resolve()
        if not candidate_binary.is_file():
            raise RuntimeError(f"candidate binary not found: {candidate_binary}")
        variants = {
            "baseline": (baseline_binary, project_copy(root, temporary / "baseline-project")),
            "candidate": (candidate_binary, project_copy(root, temporary / "candidate-project")),
        }
        full_raw, databases, phase_timings = full_indexes(
            variants, temporary, args.full_runs)
        full = {name: stats(values) for name, values in full_raw.items()}
        full_result = {"baseline": full["baseline"], "candidate": full["candidate"],
                       "p50_delta_pct": delta(full["candidate"]["p50_ms"], full["baseline"]["p50_ms"]),
                       "p95_delta_pct": delta(full["candidate"]["p95_ms"], full["baseline"]["p95_ms"])}
        full_result["passed"] = full_result["p50_delta_pct"] <= 5

        scenarios = {
            "incremental_noop": None,
            "incremental_leaf": "src/test/java/com/anatomist/query/semantic/RelationshipIdentityTest.java",
            "incremental_fanout": "src/main/java/com/anatomist/model/Document.java",
        }
        incremental: dict[str, dict] = {}
        for scenario, relative_file in scenarios.items():
            values = {}
            for name, (binary, project) in variants.items():
                values[name] = incremental_samples(binary, project, databases[name],
                    temporary / f"{name}-{scenario}.db", relative_file, args.incremental_runs)
            summary = {name: stats(samples) for name, samples in values.items()}
            item = {"baseline": summary["baseline"], "candidate": summary["candidate"],
                    "p50_delta_pct": delta(summary["candidate"]["p50_ms"], summary["baseline"]["p50_ms"]),
                    "p95_delta_pct": delta(summary["candidate"]["p95_ms"], summary["baseline"]["p95_ms"])}
            item["passed"] = item["p50_delta_pct"] <= 10 and item["p95_delta_pct"] <= 20
            incremental[scenario] = item

        query_results, query_equality, read_only_unchanged = query_benchmark(
            variants, databases, args.warmups, args.query_runs)
        for item in query_results.values():
            item["passed"] = (item["semantic_equal"] and item["p50_delta_pct"] <= 10
                              and item["p95_delta_pct"] <= 20)

        facts = {name: database_facts(databases[name], name == "candidate") for name in variants}
        health_result = {name: health(binary, databases[name])
                         for name, (binary, _) in variants.items()}
        declaration_mismatches = []
        for baseline_row, candidate_row in zip(facts["baseline"]["_declarations"],
                                               facts["candidate"]["_declarations"]):
            if baseline_row != candidate_row:
                declaration_mismatches.append({"baseline": baseline_row, "candidate": candidate_row})
                if len(declaration_mismatches) == 3:
                    break
        for value in facts.values():
            value.pop("_declarations")
        storage_result = {name: storage(databases[name], temporary) for name in variants}
        correctness = {
            "declarations_equal": facts["baseline"]["declaration_digest"] == facts["candidate"]["declaration_digest"],
            "counts_equal": facts["baseline"]["counts"] == facts["candidate"]["counts"],
            "queries_equal": all(query_equality.values()),
            "read_only_hashes_unchanged": read_only_unchanged,
            "candidate_has_declarations_table": facts["candidate"]["has_declarations_table"],
            "declaration_mismatch_samples": declaration_mismatches,
            "facts": facts,
        }
        size_raw_delta = delta(storage_result["candidate"]["raw_bytes"], storage_result["baseline"]["raw_bytes"])
        size_vacuum_delta = delta(storage_result["candidate"]["vacuum_bytes"], storage_result["baseline"]["vacuum_bytes"])
        storage_passed = size_raw_delta <= 0 and size_vacuum_delta <= -5
        health_passed = all(value["passed"] for value in health_result.values())
        correctness_passed = (correctness["declarations_equal"] and correctness["counts_equal"]
                              and correctness["queries_equal"]
                              and correctness["read_only_hashes_unchanged"]
                              and not correctness["candidate_has_declarations_table"])
        passed = (storage_passed and correctness_passed and health_passed and full_result["passed"]
                  and all(item["passed"] for item in incremental.values())
                  and all(item["passed"] for item in query_results.values()))
        report = {
            "passed": passed,
            "environment": {"platform": platform.platform(), "python": platform.python_version(),
                            "sqlite": sqlite3.sqlite_version, "cpu_count": os.cpu_count()},
            "baseline": {"ref": args.baseline_ref, "binary_sha256": sha256_file(baseline_binary)},
            "candidate": {"git_head": execute(["git", "rev-parse", "HEAD"], root).decode().strip(),
                          "binary_sha256": sha256_file(candidate_binary)},
            "full_index": full_result,
            "phase_timings": phase_timings,
            "incremental": incremental,
            "queries": query_results,
            "correctness": correctness,
            "health": health_result,
            "storage": storage_result,
            "gates": {"storage_passed": storage_passed, "correctness_passed": correctness_passed,
                      "health_passed": health_passed,
                      "raw_size_delta_pct": size_raw_delta, "vacuum_size_delta_pct": size_vacuum_delta},
        }
        (output / "results.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
        (output / "report.md").write_text(report_markdown(report))
        print(report_markdown(report))
        return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
