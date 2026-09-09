#!/usr/bin/env python3
"""Real Git/Maven automatic preparation, caller evidence and runtime parity."""
import argparse
import json
import os
from pathlib import Path
import sqlite3
import sys
from e2e_report import execute, track, run_command, workspace

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e2e"))
from version_fixture import create
from version_checks import validate_diff, navigation_sides
from evidence_contracts import diff_documents, semantic_frames


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--native", type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--keep-on-failure", action="store_true")
    parser.add_argument("--no-maven", action="store_true", help="Fast structural probe; not the Maven acceptance gate.")
    args = parser.parse_args()
    if not args.jar and not args.native:
        parser.error("provide --jar, --native or both")
    runners = {}
    if args.jar:
        runners["jar"] = [args.java, "--enable-native-access=ALL-UNNAMED", "-jar", str(args.jar.resolve())]
    if args.native:
        runners["native"] = [str(args.native.resolve())]
    report = track({"scenarios": [], "executions": 0, "comparisons": 0, "schema_validated": True, "maven": not args.no_maven})
    report["versions"] = {mode: run_command(command + ["--version"], capture_output=True, text=True, check=True, timeout=30).stdout.strip() for mode, command in runners.items()}
    with workspace("anatomist-diff-e2e-") as temporary:
        root = Path(temporary)
        project = root / "project"
        facts = create(project, maven=not args.no_maven, executor=lambda argv, cwd:
                       run_command(argv, cwd=cwd, capture_output=True, text=True, check=True, timeout=600).stdout.strip())
        env = dict(os.environ, ANATOMIST_HOME=str(root / "storage"))
        def run(mode, argv, expected=0):
            result = run_command(runners[mode] + list(map(str, argv)), cwd=project, env=env, capture_output=True, text=True, timeout=240)
            assert result.returncode == expected, (mode, argv, result.returncode, result.stderr)
            report["executions"] += 1
            return result
        def git(*argv):
            return run_command(["git", *argv], cwd=project, env=env, capture_output=True, text=True, check=True, timeout=60).stdout.strip()
        def select(argv, **options):
            argv = list(argv)
            for name, value in options.items():
                flag = "--" + name.replace("_", "-")
                if flag in argv:
                    argv[argv.index(flag) + 1] = value
                else:
                    argv.extend([flag, value])
            return argv
        build_options = ["--java-version", "25"] + (["--no-classpath"] if args.no_maven else [])
        common = ["diff", "--project", project, "--base", "base-tip", "--target", "feature", "--scope", "ALL", "--impact-scope", "ALL", "--impact", *build_options]
        documents = []
        for mode in runners:
            print(f"[{mode}] automatic preparation and matching", flush=True)
            main_only = diff_documents(run(mode, [*common, "--format", "json"]).stdout)[0]
            assert not any(row["record"] == "impact" and row["caller"]["scope"] == "TEST" for row in main_only["changes"])
            error = json.loads(run(mode, [*common, "--include-tests", "--no-build"], 3).stderr)
            assert error["code"] == "SNAPSHOT_CONFIG_MISMATCH" and error["details"]["candidate_ids"]
            for scenario in ("work", "tips"):
                argv = [*common, "--include-tests"] + (["--merge-base"] if scenario == "work" else [])
                document = diff_documents(run(mode, [*argv, "--format", "json"]).stdout)[0]
                validate_diff(document, facts, scenario)
                documents.append(document)
                cached = diff_documents(run(mode, [*argv, "--no-build"]).stdout)[0]
                assert cached == document
                focused = diff_documents(run(mode, [*argv, "--view", "calls"]).stdout)[0]
                hidden = [row for row in document["changes"] if row["record"] == "file_change" or row["record"] == "relation_change" and row["relationship"]["relation"] != "CALLS"]
                assert any(row["record"] == "relation_change" for row in hidden), "vacuous calls-view fixture"
                assert focused["changes"] == [row for row in document["changes"] if row not in hidden]
                assert focused["evidence"]["capabilities"] == document["evidence"]["capabilities"]
                assert focused["comparison"]["output"]["hidden"] == len(hidden)
                resolved = diff_documents(run(mode, [*argv, "--impact-dispatch", "resolved"]).stdout)[0]
                validate_diff(resolved, facts, scenario, dispatch="resolved")
                assert not any(row["record"] == "impact" and row["origin"]["symbol"] == facts["possible_origin"] for row in resolved["changes"])
                streams = []
                for side in ("base", "target"):
                    anchor = next(row["origin"] for row in document["changes"] if row["record"] == "impact" and row["side"] == side and row["origin"]["symbol"] == facts["origin"])
                    streams.extend(semantic_frames(run(mode, ["pipeline", "--project", project, "--snapshot", anchor["snapshot_id"], "--scope", anchor["scope"], "--", "resolve", anchor["id"], "--unique", "--then", "source"]).stdout))
                    expected = "return 1" if side == "base" else "return 2"
                    assert any(expected in row.get("snippet", "") for row in streams[-1])
                assert navigation_sides(document, streams) == {"base", "target"}
                shallow = diff_documents(run(mode, [*argv, "--impact-depth", "1"]).stdout)[0]
                deep = diff_documents(run(mode, [*argv, "--impact-depth", "10"]).stdout)[0]
                assert shallow["evidence"]["capabilities"]["impact"]["truncated"]
                assert not deep["evidence"]["capabilities"]["impact"]["truncated"]
                assert "DISPATCH_OPEN_WORLD" in deep["evidence"]["capabilities"]["impact"]["reasons"]
                test_only = diff_documents(run(mode, select(argv, impact_scope="TEST", impact_module="checks")).stdout)[0]
                assert any(row["record"] == "impact" and row["origin"]["symbol"] == facts["origin"] and len(row["path"]) >= 3 for row in test_only["changes"])
                assert "dispatch=possible" in run(mode, [*argv, "--format", "table"]).stdout
                report["scenarios"].append(mode + ":" + scenario)
            # Newer unrelated snapshots must not shadow the matching request.
            for ref in ("base-tip", "feature"):
                run(mode, ["index", project, "--ref", ref, *build_options, "--include-tests", "--spring-xml", "--format", "json"])
            assert diff_documents(run(mode, [*common, "--include-tests", "--no-build"]).stdout)[0] == documents[-1]
            fixed = ["diff", "--project", project, "--base", "snapshot:" + main_only["comparison"]["base"]["id"], "--target", "snapshot:" + main_only["comparison"]["target"]["id"], "--include-tests"]
            assert json.loads(run(mode, fixed, 3).stderr)["code"] == "SNAPSHOT_COVERAGE_MISMATCH"
            linked = root / (mode + "-linked")
            git("worktree", "add", "--detach", str(linked), "feature")
            try:
                command = list(common)
                command[2] = linked
                assert diff_documents(run(mode, [*command, "--include-tests", "--no-build"]).stdout)[0] == documents[-1]
            finally:
                git("worktree", "remove", "--force", str(linked))
            # Dirty capture remains bound to its captured commit after switching branches.
            source = project / "api/src/main/java/p/A.java"
            original = source.read_text()
            source.write_text(original.replace("return 2", "return 3"))
            dirty = diff_documents(run(mode, [*select(common, target="WORKTREE"), "--include-tests", "--merge-base"]).stdout)[0]
            source.write_text(original)
            git("checkout", "-q", "base-tip")
            cached = diff_documents(run(mode, [*select(common, base="feature", target="WORKTREE"), "--include-tests", "--merge-base", "--no-build"]).stdout)[0]
            assert cached["comparison"]["target"]["id"] == dirty["comparison"]["target"]["id"]
            assert cached["comparison"]["request"]["target"]["commit"] == facts["feature"]
            git("checkout", "-q", "feature")
            worker = project / "app/src/main/java/p/Worker.java"
            original_worker = worker.read_text()
            worker.write_text(original_worker.replace("implements I, Tag", "implements Tag").replace("return 2", "return 3"))
            try:
                topology = diff_documents(run(mode, [*select(common, base="feature", target="WORKTREE"), "--include-tests"]).stdout)[0]
                candidates = [row for row in topology["changes"] if row["record"] == "impact" and row["origin"]["symbol"] == facts["possible_origin"]]
                assert any(row["side"] == "base" and row["caller"]["symbol"] == facts["possible_caller"] for row in candidates)
                assert not any(row["side"] == "target" for row in candidates)
                assert not any(row["record"] == "relation_change" and row["relationship"]["relation"] == "CALLS" for row in topology["changes"]), "candidate removal invented a recorded CALLS delta"
            finally:
                worker.write_text(original_worker)
            config = project / ".anatomist/config.toml"
            original = config.read_text()
            config.write_text('[scan]\nsource_roots = ["api@MAIN=api/src/main/java"]\n')
            try:
                result = run(mode, [*select(common, target="WORKTREE"), "--include-tests"], 3)
                assert json.loads(result.stderr.splitlines()[-1])["code"] == "SNAPSHOT_COVERAGE_MISMATCH"
            finally:
                config.write_text(original)
            assert len([line for line in git("worktree", "list", "--porcelain").splitlines() if line.startswith("worktree ")]) == 1
            catalogs = list((root / "storage/versions").glob("*/catalog.db"))
            assert len(catalogs) == 1
            with sqlite3.connect(catalogs[0]) as db:
                failed = db.execute("SELECT id FROM snapshots WHERE status='FAILED'").fetchall()
                assert failed
                assert not db.execute("SELECT id FROM snapshots WHERE status='BUILDING'").fetchall()
            for (snapshot,) in failed:
                assert not (catalogs[0].parent / "snapshots" / snapshot / "index.db").exists()
            assert not (catalogs[0].parent / "workspace").exists()
            if not args.no_maven:
                detections = []
                for database in (catalogs[0].parent / "snapshots").glob("*/index.db"):
                    with sqlite3.connect(database) as db:
                        meta = dict(db.execute("SELECT key, value FROM project_meta"))
                    status = meta.get("classpath_detection_status")
                    assert status in ("full", "cache_hit"), (database, status)
                    detections.append(meta)
                assert any(meta.get("classpath_detection_maven_exit") == "0" for meta in detections), "no successful Maven classpath detection"
                report["maven_snapshots_checked"] = len(detections)
            report["scenarios"].extend([mode + ":cache-selection", mode + ":worktree", mode + ":candidate-removal", mode + ":failure-cleanup"])
        # Exact-instance queries compare complete output, including identity and evidence.
        for document in documents:
            argv = ["diff", "--project", project, "--base", "snapshot:" + document["comparison"]["base"]["id"], "--target", "snapshot:" + document["comparison"]["target"]["id"], "--scope", "ALL", "--impact-scope", "ALL", "--impact", "--no-build"]
            outputs = [diff_documents(run(mode, argv).stdout)[0] for mode in runners]
            assert all(output == outputs[0] for output in outputs)
            report["comparisons"] += int(len(outputs) > 1)
        assert git("rev-parse", "HEAD") == facts["head"]
        assert git("status", "--porcelain") == ""
        report["scenario_count"] = len(report["scenarios"])


if __name__ == "__main__":
    execute(main, "target/diff-e2e.json")
