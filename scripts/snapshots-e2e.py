#!/usr/bin/env python3
"""Snapshot smoke/benchmark shared by the shaded JAR and native executable.

All Git mutations and generated files are confined to a TemporaryDirectory.
The optional --fixture clones an existing local Git repository, without changing it.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import tempfile
import time


def run(command, cwd=None, env=None, stderr_lines=None):
    result = subprocess.run(command, cwd=cwd, env=env, text=True, capture_output=True, timeout=300)
    if stderr_lines is not None:
        stderr_lines.extend(result.stderr.splitlines())
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command}\n{result.stdout}\n{result.stderr}")
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    kind = parser.add_mutually_exclusive_group(required=True)
    kind.add_argument("--jar", type=Path)
    kind.add_argument("--native", type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--files", type=int, default=20)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.files < 2 or args.repeats < 1:
        parser.error("--files must be >= 2 and --repeats >= 1")
    cli = [str(args.native.resolve())] if args.native else [args.java, "-jar", str(args.jar.resolve())]
    report = {"platform": platform.system() + "-" + platform.machine(), "version": run(cli + ["--version"]),
              "fixture": "commons-lang" if args.fixture else "generated", "samples": []}
    with tempfile.TemporaryDirectory(prefix="anatomist-snapshots-") as temporary:
        root = Path(temporary)
        project = root / "project"
        env = os.environ.copy()
        env["ANATOMIST_HOME"] = str(root / "storage")

        def git(*arguments):
            return run(["git", "-C", str(project), *arguments], env=env)

        if args.fixture:
            run(["git", "clone", "--quiet", "--no-hardlinks", str(args.fixture.resolve()), str(project)], env=env)
            report["fixture_commit"] = git("rev-parse", "HEAD")
            probe = project / "src/main/java/org/apache/commons/lang3/StringUtils.java"
        else:
            sources = project / "src/main/java/p"
            sources.mkdir(parents=True)
            (project / "pom.xml").write_text("<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId>"
                                          "<artifactId>snapshot-probe</artifactId><version>1</version></project>")
            for number in range(args.files):
                (sources / f"Probe{number}.java").write_text(
                    f"package p; public class Probe{number} {{ public int value() {{ return {number}; }} }}\n")
            probe = sources / "Probe0.java"
            git("init", "--quiet")
        git("config", "user.name", "Anatomist Snapshot Probe")
        git("config", "user.email", "snapshot-probe@example.test")
        if not args.fixture:
            git("add", ".")
            git("commit", "--quiet", "-m", "initial")
        original_sha = git("rev-parse", "HEAD")
        baseline_text = probe.read_text()

        def index(ref, full=False):
            command = cli + ["index", str(project), "--ref", ref, "--no-classpath", "--java-version", "25", "--format", "json"]
            if full:
                command.append("--full")
            started = time.perf_counter()
            diagnostics = []
            result = json.loads(run(command, env=env, stderr_lines=diagnostics))
            progress = [dict(field.split("=", 1) for field in line.split()[1:])
                        for line in diagnostics if line.startswith("[anatomist-progress] ")]
            if result["metrics"].get("baseline") and not result["reused"]:
                assert len(progress) >= 2, diagnostics
                assert progress[0]["status"] == "started" and progress[-1]["status"] == "completed", progress
                assert progress[-1]["percent"] == "100", progress
                assert int(progress[-1]["total_pages"]) > 0, progress
                assert progress[-1]["copied_pages"] == progress[-1]["total_pages"], progress
                assert all(row["phase"] == "sqlite_backup" for row in progress), progress
                assert all(row["status"] == "running" for row in progress[1:-1]), progress
                report["backup_progress_checked"] = report.get("backup_progress_checked", 0) + 1
            else:
                assert not progress, progress
            return result, round((time.perf_counter() - started) * 1000, 3)

        first, full_ms = index("HEAD")
        assert not first["reused"]
        for iteration in range(args.repeats):
            if args.fixture:
                # Comment-only change keeps the pinned external fixture compilable.
                changed = baseline_text + f"\n// snapshot benchmark iteration {iteration}\n"
            else:
                changed = baseline_text.replace("return 0;", f"return {iteration + 1};")
            probe.write_text(changed)
            git("add", str(probe.relative_to(project)))
            git("commit", "--quiet", "-m", f"increment {iteration}")
            incremental, incremental_ms = index("HEAD")
            cached, cached_ms = index("HEAD")
            assert cached["reused"] and cached["id"] == incremental["id"]
            assert incremental["metrics"]["mode"] == "incremental", incremental["metrics"]
            assert incremental["metrics"]["reparsed_files"] == 1, incremental["metrics"]
            full, rebuilt_ms = index("HEAD", full=True)
            assert full["source_snapshot_id"] == incremental["source_snapshot_id"]
            assert full["semantic_profile_id"] == incremental["semantic_profile_id"]
            report["samples"].append({"full_ms": rebuilt_ms, "incremental_ms": incremental_ms,
                                      "cached_ms": cached_ms, "metrics": incremental["metrics"]})
        sha = git("rev-parse", "HEAD")
        status_before = git("status", "--porcelain")
        comparison = json.loads(run(cli + ["diff", "--project", str(project), "--base", original_sha,
                                           "--target", sha, "--no-build", "--impact", "--format", "json"], env=env))
        assert comparison["contract"] == "anatomist-diff/v2"
        assert any(row["record"] == "file_change" for row in comparison["changes"])
        if not args.fixture:
            assert any(row["record"] == "declaration_change" for row in comparison["changes"])
            source = run(cli + ["pipeline", "--project", str(project), "--snapshot", first["id"], "--",
                                "resolve", "p.Probe0#value()", "--kind", "callable", "--exact", "--unique", "--then", "source"], env=env)
            assert "return 0" in source
        assert git("rev-parse", "HEAD") == sha and git("status", "--porcelain") == status_before
        probe.write_text(baseline_text + "\n// uncommitted capture\n")
        worktree, worktree_ms = index("WORKTREE")
        assert git("rev-parse", "HEAD") == sha
        run(cli + ["snapshots", "pin", first["id"], "--project", str(project)], env=env)
        preview = json.loads(run(cli + ["snapshots", "gc", "--project", str(project), "--keep", "0"], env=env))
        assert preview["execute"] is False and Path(first["index"]).exists()
        collected = json.loads(run(cli + ["snapshots", "gc", "--project", str(project), "--keep", "0", "--execute"], env=env))
        assert Path(first["index"]).exists() and Path(worktree["index"]).exists()
        health = json.loads(run(cli + ["doctor", "--project", str(project), "--snapshot", first["id"], "--format", "json"], env=env))
        assert health["source_snapshot"]["match"] is True
        report.update({"initial_full_ms": full_ms, "worktree_ms": worktree_ms,
                       "main_java_files": len(list((project / "src/main/java").rglob("*.java"))),
                       "java_files": len(list(project.rglob("*.java"))),
                       "gc_deleted": collected["deleted"], "post_gc_bytes": sum(
                           file.stat().st_size for file in (root / "storage").rglob("*") if file.is_file())})
        for metric in ("full_ms", "incremental_ms", "cached_ms"):
            values = sorted(sample[metric] for sample in report["samples"])
            report[metric] = {"median": statistics.median(values), "max": max(values)}
    text = json.dumps(report, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text)
    print(text)


if __name__ == "__main__":
    main()
