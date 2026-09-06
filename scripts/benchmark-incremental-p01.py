#!/usr/bin/env python3
"""Real native baseline for incremental-index P0/P1 changes."""

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


BASELINE_REF = "10c873cf96efef476bbfc60c106d8ff9a2b6b383"
LEAF = "src/test/java/com/anatomist/query/semantic/RelationshipIdentityTest.java"
FANOUT = "src/main/java/com/anatomist/model/Document.java"
SCENARIOS = (
    "incremental_noop",
    "incremental_stat_only",
    "incremental_body_leaf",
    "incremental_contract_leaf",
    "incremental_fanout",
    "incremental_batch16",
)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-ref", default=BASELINE_REF)
    parser.add_argument("--baseline-bin", type=Path)
    parser.add_argument("--candidate-bin", type=Path, default=Path("target/anatomist"))
    parser.add_argument("--project", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path, default=Path("target/benchmarks/incremental-p01"))
    parser.add_argument("--full-runs", type=int, default=3)
    parser.add_argument("--incremental-runs", type=int, default=15)
    parser.add_argument("--query-runs", type=int, default=30)
    parser.add_argument("--warmups", type=int, default=3)
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


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def stats(values: list[float]) -> dict[str, float | int]:
    return {"n": len(values), "p50_ms": round(statistics.median(values), 2),
            "p95_ms": round(percentile(values, .95), 2),
            "min_ms": round(min(values), 2), "max_ms": round(max(values), 2)}


def delta(candidate: float, baseline: float) -> float:
    return round((candidate / baseline - 1) * 100, 2)


def build_baseline(root: Path, revision: str, temporary: Path, cache: Path) -> Path:
    if cache.is_file():
        return cache
    worktree = temporary / "baseline-worktree"
    subprocess.run(["git", "worktree", "add", "--detach", str(worktree), revision],
                   cwd=root, check=True, stdout=subprocess.DEVNULL)
    try:
        execute(["just", "native"], worktree)
        cache.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(worktree / "target/anatomist", cache)
        cache.chmod(0o755)
        return cache
    finally:
        subprocess.run(["git", "worktree", "remove", "--force", str(worktree)],
                       cwd=root, check=True, stdout=subprocess.DEVNULL)


def project_copy(source: Path, target: Path) -> Path:
    ignored = shutil.ignore_patterns(".git", "target", ".idea", "*.db", "*.db-wal", "*.db-shm")
    shutil.copytree(source, target, ignore=ignored)
    execute(["git", "init", "-q"], target)
    execute(["git", "config", "user.email", "benchmark@anatomist.local"], target)
    execute(["git", "config", "user.name", "Anatomist Benchmark"], target)
    execute(["git", "add", "."], target)
    execute(["git", "commit", "-qm", "benchmark snapshot"], target)
    return target


def index_command(binary: Path, project: Path, database: Path, incremental: bool,
                  extra: list[str] | None = None) -> list[str]:
    command = [str(binary), "index", str(project)]
    if incremental:
        command.append("--incremental")
    command.extend(["--include-tests", "--timings", "--format", "json",
                    "--output", str(database)])
    if extra:
        command.extend(extra)
    return command


def full_indexes(variants: dict[str, tuple[Path, Path]], temporary: Path,
                 runs: int) -> tuple[dict, dict[str, Path]]:
    values = {name: [] for name in variants}
    databases: dict[str, Path] = {}
    for run in range(runs):
        order = list(variants) if run % 2 == 0 else list(reversed(variants))
        for name in order:
            binary, project = variants[name]
            database = temporary / f"{name}-full-{run}.db"
            elapsed, _ = timed(index_command(binary, project, database, False))
            values[name].append(elapsed)
            databases[name] = database
    result = {name: stats(samples) for name, samples in values.items()}
    result["p50_delta_pct"] = delta(result["candidate"]["p50_ms"], result["baseline"]["p50_ms"])
    result["p95_delta_pct"] = delta(result["candidate"]["p95_ms"], result["baseline"]["p95_ms"])
    return result, databases


def reset_database(source: Path, target: Path) -> None:
    for suffix in ("", "-wal", "-shm"):
        Path(str(target) + suffix).unlink(missing_ok=True)
    shutil.copy2(source, target)


def database_envelope_digest(database: Path) -> str:
    digest = hashlib.sha256()
    for suffix in ("", "-wal", "-shm"):
        path = Path(str(database) + suffix)
        digest.update(suffix.encode())
        if path.exists():
            digest.update(str(path.stat().st_size).encode())
            digest.update(path.read_bytes())
    return digest.hexdigest()


def mutate(project: Path, scenario: str, run: int) -> list[tuple[Path, bytes, int, int]]:
    if scenario == "incremental_noop":
        return []
    if scenario == "incremental_batch16":
        sources = sorted((project / "src/main/java").rglob("*.java"))[:16]
    else:
        sources = [project / (FANOUT if scenario == "incremental_fanout" else LEAF)]
    originals = []
    for source in sources:
        stat = source.stat()
        original = source.read_bytes()
        originals.append((source, original, stat.st_atime_ns, stat.st_mtime_ns))
        if scenario == "incremental_stat_only":
            os.utime(source, ns=(stat.st_atime_ns, stat.st_mtime_ns + 1_000_000_000))
        elif scenario in {"incremental_contract_leaf", "incremental_fanout"}:
            text = original.decode()
            marker = f"    public String incrementalP01Probe{run};\n"
            source.write_text(text.rsplit("}", 1)[0] + marker + "}\n")
        else:
            source.write_bytes(original + f"\n// incremental-p01-body-{run}\n".encode())
    return originals


def restore(originals: list[tuple[Path, bytes, int, int]]) -> None:
    for source, content, atime_ns, mtime_ns in originals:
        source.write_bytes(content)
        os.utime(source, ns=(atime_ns, mtime_ns))


def incremental_benchmark(variants: dict[str, tuple[Path, Path]], databases: dict[str, Path],
                          temporary: Path, runs: int) -> tuple[dict, dict]:
    results: dict[str, dict] = {}
    raw: dict[str, dict] = {}
    for scenario in SCENARIOS:
        samples = {name: [] for name in variants}
        envelopes = {name: [] for name in variants}
        zero_write = {name: True for name in variants}
        for run in range(runs):
            order = list(variants) if run % 2 == 0 else list(reversed(variants))
            for name in order:
                binary, project = variants[name]
                work_db = temporary / f"{name}-{scenario}.db"
                reset_database(databases[name], work_db)
                originals = mutate(project, scenario, run)
                before = database_envelope_digest(work_db)
                try:
                    elapsed, output = timed(index_command(binary, project, work_db, True))
                finally:
                    restore(originals)
                samples[name].append(elapsed)
                envelopes[name].append(json.loads(output))
                if scenario == "incremental_noop":
                    zero_write[name] &= before == database_envelope_digest(work_db)
        summary = {name: stats(value) for name, value in samples.items()}
        item = {"baseline": summary["baseline"], "candidate": summary["candidate"],
                "p50_delta_pct": delta(summary["candidate"]["p50_ms"], summary["baseline"]["p50_ms"]),
                "p95_delta_pct": delta(summary["candidate"]["p95_ms"], summary["baseline"]["p95_ms"]),
                "zero_write": zero_write}
        results[scenario] = item
        raw[scenario] = envelopes
    return results, raw


def candidate_fast_paths(binary: Path, project: Path, database: Path,
                         temporary: Path, runs: int) -> dict:
    manifest = temporary / "empty-changes.txt"
    manifest.write_text("# authoritative no-op\n")
    result = {}
    for mode in ("manifest", "scan_fallback"):
        samples = []
        zero_write = True
        envelopes = []
        for run in range(runs):
            work_db = temporary / f"candidate-fast-{mode}-{run}.db"
            reset_database(database, work_db)
            extra = ["--changed-files-from", str(manifest)] if mode == "manifest" else []
            if mode == "scan_fallback":
                with sqlite3.connect(work_db) as connection:
                    connection.execute("UPDATE project_meta SET value='' WHERE key='source_git_root'")
                    connection.commit()
                    connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                # sqlite3 may leave an empty WAL and an SHM file after the setup write.
                # Normalize the envelope before timing so their close-time removal is not
                # misclassified as a write by the command under test.
                for suffix in ("-wal", "-shm"):
                    Path(str(work_db) + suffix).unlink(missing_ok=True)
            before = database_envelope_digest(work_db)
            elapsed, output = timed(index_command(binary, project, work_db, True, extra))
            samples.append(elapsed)
            envelopes.append(json.loads(output))
            zero_write &= before == database_envelope_digest(work_db)
        result[mode] = {"timing": stats(samples), "zero_write": zero_write,
                        "envelopes": envelopes}
    return result


def query_commands(binary: Path, database: Path) -> dict[str, list[str]]:
    callable_id = "com.anatomist.query.QueryService#search(java.lang.String,java.lang.String,int)"
    return {
        "search": [str(binary), "search", "QueryService", "--format", "ndjson", "--index", str(database)],
        "overview": [str(binary), "overview", "--format", "ndjson", "--index", str(database)],
        "source": [str(binary), "pipeline", "--format", "ndjson", "--index", str(database), "--",
                   "resolve", callable_id, "--kind", "callable", "--exact", "--unique",
                   "--then", "source", "--limit", "100"],
    }


def semantic_digest(output: bytes) -> str:
    ignored = {"identity", "index_revision_id", "source_snapshot_id", "semantic_profile_id",
               "index_path", "source_root"}

    def scrub(value):
        if isinstance(value, dict):
            return {key: scrub(item) for key, item in value.items() if key not in ignored}
        if isinstance(value, list):
            return [scrub(item) for item in value]
        return value

    records = []
    for line in output.decode().splitlines():
        if not line.strip():
            continue
        value = json.loads(line)
        records.append(scrub(value))
    return hashlib.sha256(json.dumps(records, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def query_benchmark(variants: dict[str, tuple[Path, Path]], databases: dict[str, Path],
                    warmups: int, runs: int) -> dict:
    commands = {name: query_commands(binary, databases[name])
                for name, (binary, _) in variants.items()}
    result = {}
    for workload in commands["baseline"]:
        outputs = {name: execute(commands[name][workload]) for name in variants}
        equal = semantic_digest(outputs["baseline"]) == semantic_digest(outputs["candidate"])
        for _ in range(warmups):
            for name in variants:
                execute(commands[name][workload])
        samples = {name: [] for name in variants}
        for run in range(runs):
            order = list(variants) if run % 2 == 0 else list(reversed(variants))
            for name in order:
                elapsed, _ = timed(commands[name][workload])
                samples[name].append(elapsed)
        summary = {name: stats(value) for name, value in samples.items()}
        result[workload] = {"baseline": summary["baseline"], "candidate": summary["candidate"],
                            "p50_delta_pct": delta(summary["candidate"]["p50_ms"], summary["baseline"]["p50_ms"]),
                            "p95_delta_pct": delta(summary["candidate"]["p95_ms"], summary["baseline"]["p95_ms"]),
                            "semantic_equal": equal}
    return result


def storage(database: Path, temporary: Path) -> dict:
    raw = database.stat().st_size
    vacuum = temporary / f"{database.stem}-vacuum.db"
    shutil.copy2(database, vacuum)
    with sqlite3.connect(vacuum) as connection:
        connection.execute("VACUUM")
        dbstat = {name: size for name, size in connection.execute(
            "SELECT name,sum(pgsize) FROM dbstat GROUP BY name ORDER BY sum(pgsize) DESC")}
    return {"raw_bytes": raw, "vacuum_bytes": vacuum.stat().st_size, "dbstat": dbstat}


def markdown(report: dict) -> str:
    lines = [f"# Incremental index P0/P1 — {'PASS' if report['passed'] else 'FAIL'}", "",
             "| Workload | Baseline p50 | Candidate p50 | p50 Δ | p95 Δ |", "|---|---:|---:|---:|---:|"]
    workloads = {"full_index": report["full_index"], **report["incremental"], **report["queries"]}
    for name, item in workloads.items():
        lines.append(f"| {name} | {item['baseline']['p50_ms']:.2f} ms | "
                     f"{item['candidate']['p50_ms']:.2f} ms | {item['p50_delta_pct']:+.2f}% | "
                     f"{item['p95_delta_pct']:+.2f}% |")
    storage_delta = report["gates"]["storage_delta_pct"]
    lines.extend(["", f"- Storage delta: {storage_delta:+.2f}%",
                  f"- Candidate no-op zero-write: {report['gates']['noop_zero_write']}",
                  f"- Query semantics equal: {report['gates']['queries_equal']}",
                  f"- Overall: {'PASS' if report['passed'] else 'FAIL'}", ""])
    if "candidate_fast_paths" in report:
        lines.extend(["| Candidate fast path | p50 | p95 | Zero write |",
                      "|---|---:|---:|---:|"])
        for name, item in report["candidate_fast_paths"].items():
            timing = item["timing"]
            lines.append(f"| {name} | {timing['p50_ms']:.2f} ms | "
                         f"{timing['p95_ms']:.2f} ms | {item['zero_write']} |")
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    args = arguments()
    root = args.project.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="anatomist-incremental-p01-") as raw:
        temporary = Path(raw)
        baseline = args.baseline_bin.resolve() if args.baseline_bin else build_baseline(
            root, args.baseline_ref, temporary,
            output / f"baseline-anatomist-{args.baseline_ref[:12]}")
        candidate = args.candidate_bin.resolve()
        variants = {
            "baseline": (baseline, project_copy(root, temporary / "baseline-project")),
            "candidate": (candidate, project_copy(root, temporary / "candidate-project")),
        }
        full, databases = full_indexes(variants, temporary, args.full_runs)
        incremental, raw_envelopes = incremental_benchmark(
            variants, databases, temporary, args.incremental_runs)
        fast_paths = candidate_fast_paths(candidate, variants["candidate"][1],
                                          databases["candidate"], temporary,
                                          args.incremental_runs)
        queries = query_benchmark(variants, databases, args.warmups, args.query_runs)
        sizes = {name: storage(database, temporary) for name, database in databases.items()}
        storage_delta = delta(sizes["candidate"]["vacuum_bytes"], sizes["baseline"]["vacuum_bytes"])
        gates = {
            "full": full["p50_delta_pct"] <= 5,
            "noop": incremental["incremental_noop"]["p50_delta_pct"] <= -30,
            "body_leaf": incremental["incremental_body_leaf"]["p50_delta_pct"] <= -20,
            "contract_leaf": incremental["incremental_contract_leaf"]["p50_delta_pct"] <= 5,
            "fanout": incremental["incremental_fanout"]["p50_delta_pct"] <= -15,
            "batch16": incremental["incremental_batch16"]["p50_delta_pct"] <= 5,
            "queries": all(item["p95_delta_pct"] <= 5 for item in queries.values()),
            "queries_equal": all(item["semantic_equal"] for item in queries.values()),
            "storage": storage_delta <= 5,
            "storage_delta_pct": storage_delta,
            "noop_zero_write": incremental["incremental_noop"]["zero_write"]["candidate"],
        }
        passed = all(value for key, value in gates.items()
                     if key not in {"storage_delta_pct"})
        report = {
            "passed": passed,
            "environment": {"platform": platform.platform(), "python": platform.python_version(),
                            "sqlite": sqlite3.sqlite_version, "cpu_count": os.cpu_count()},
            "baseline": {"ref": args.baseline_ref, "binary_sha256": digest_file(baseline)},
            "candidate": {"git_head": execute(["git", "rev-parse", "HEAD"], root).decode().strip(),
                          "binary_sha256": digest_file(candidate)},
            "full_index": full, "incremental": incremental, "queries": queries,
            "candidate_fast_paths": fast_paths, "storage": sizes, "gates": gates,
        }
        (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        (output / "raw-incremental.json").write_text(json.dumps(raw_envelopes, indent=2) + "\n")
        (output / "report.md").write_text(markdown(report))
        print(markdown(report))
        return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
