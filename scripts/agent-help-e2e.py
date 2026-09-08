#!/usr/bin/env python3
"""Execute the documented agent routes using a JAR, native binary, or both.

All indexing and Git mutations use temporary projects. With both runners, query
each index through both executables and compare parsed evidence without dropping
semantic fields. No model service is used; executable paths are explicit.
"""
import argparse
from e2e_report import execute, track, run_command, workspace
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile


SCENES = "core topics explore source trace relations spring versions maintenance".split()


def fixture(project):
    def write(name, content):
        path = project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    write("pom.xml", """<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId>
<artifactId>help-probe</artifactId><version>1</version>
<properties><maven.compiler.release>17</maven.compiler.release></properties></project>""")
    write(".anatomist/config.toml", """[scan]
scopes = ["MAIN", "TEST"]
""")
    write("src/main/java/p/Api.java", "package p; public interface Api { void run(); }\n")
    write("src/main/java/p/Impl.java", "package p; public class Impl implements Api { public void run() { Helper.work(); } }\n")
    write("src/main/java/p/Client.java", "package p; public class Client { public Service service = new Service(); public void invoke() { service.run(); } }\n")
    write("src/main/java/p/Helper.java", """package p;
public class Helper {
    public static void work() { }
    public static int choose(boolean flag) { if (flag) return 1; return 2; }
}
""")
    write("src/main/java/org/springframework/stereotype/Service.java",
          "package org.springframework.stereotype; public @interface Service { }\n")
    write("src/main/java/p/Service.java", """package p;
@org.springframework.stereotype.Service
public class Service {
    public int value = 1;
    public Api api = new Impl();
    public void run() {
        if (value > 0) {
            api.run();
            Helper.work();
        }
        value++;
    }
    public void setValue(int value) { this.value = value; }
    public void overload(int value) { }
    public void overload(String value) { }
    public int large() {
        int total = 0;
""" + "".join(f"        total += {i};\n" for i in range(260)) + "        return total;\n    }\n}\n")
    write("src/test/java/p/ServiceTest.java", "package p; public class ServiceTest { public void verify() { new Service().run(); } }\n")
    write("src/main/resources/beans.xml", """<beans xmlns="http://www.springframework.org/schema/beans">
  <bean id="serviceBean" class="p.Service"><property name="value" value="2"/></bean>
</beans>""")
    write("docs/service.md", "# Service\n`p.Service` and `p.Service#run()` own the service behavior.\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--native", type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--keep-on-failure", action="store_true", help="Retain the owned fixture after failure.")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--validate-schema", action="store_true", help="Validate diff v2 JSON/NDJSON (requires jsonschema).")
    args = parser.parse_args()
    validator = None
    if args.validate_schema:
        from jsonschema import Draft202012Validator
        schema = json.loads((Path(__file__).resolve().parents[1] / "docs/schema/diff-v2.schema.json").read_text())
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema)
    if not args.jar and not args.native:
        parser.error("provide --jar, --native, or both")
    runners = {}
    if args.jar:
        runners["jar"] = [args.java, "--enable-native-access=ALL-UNNAMED", "-jar", str(args.jar.resolve())]
    if args.native:
        runners["native"] = [str(args.native.resolve())]
    report = {"runners": {}, "checks": [], "query_comparisons": 0}
    report = track(report)
    with workspace(prefix="anatomist-help-e2e-") as temporary:
        root = Path(temporary)
        project = root / "project"
        fixture(project)
        env = os.environ.copy()
        env["ANATOMIST_HOME"] = str(root / "storage")

        def run(mode, argv, expected=0):
            result = run_command(runners[mode] + list(map(str, argv)), cwd=project,
                                    env=env, text=True, capture_output=True, timeout=120)
            assert result.returncode == expected, (mode, argv, result.returncode, result.stdout, result.stderr)
            return result

        def check(name):
            report["checks"].append(name)

        def stream(text):
            rows = [json.loads(line) for line in text.splitlines() if line.strip()]
            assert rows[0]["record"] == "stream_header", rows[:1]
            assert rows[-1]["record"] == "evidence" and rows[-1]["scope"] == "stream", rows[-1:]
            return rows

        def records(rows, kind):
            return [row for row in rows if row["record"] == kind]

        def pipeline(db, *stages, scope="MAIN"):
            return ["pipeline", "--index", db, "--scope", scope, "--", *stages]

        def resolve(selector="p.Service#run()", kind="callable"):
            return ["resolve", selector, "--kind", kind, "--exact", "--unique"]

        helps = {}
        catalogs = {}
        for mode in runners:
            print(f"[{mode}] help, catalog and skill", flush=True)
            version = run(mode, ["--version"]).stdout.strip()
            catalog = json.loads(run(mode, ["operations"]).stdout)
            catalogs[mode] = catalog
            assert catalog["contract"] == "anatomist-operation-catalog/v1"
            assert {"--ref", "--snapshot", "--project"} <= set(catalog["global_options"])
            commands = [op["id"] for op in catalog["operations"]]
            commands += "index index-docs diff snapshots skill pipeline operations annotate doctor".split()
            root_help = run(mode, ["--help"]).stdout
            helps[mode] = {"root": root_help}
            for command in commands:
                help_text = run(mode, [command, "--help"]).stdout
                assert help_text == run(mode, ["help", command]).stdout, command
                assert re.search(r"^  " + re.escape(command) + r"\s", root_help, re.M), command
                helps[mode][command] = help_text
            for op in catalog["operations"]:
                text = helps[mode][op["id"]]
                assert all(arg.get("description") for arg in op["arguments"]), op
                accepts = next(line for line in text.splitlines() if line.startswith("Accepts:"))
                assert all(record in accepts for record in op["input"]["records"]), op
                emits = " ".join(line for line in text.splitlines() if line.startswith("Emits:"))
                assert all(record in emits for variant in op["output"]["variants"] for record in variant["records"]), op
            assert run(mode, ["skill"]).stdout == run(mode, ["skill", "core"]).stdout
            for scene in SCENES:
                text = run(mode, ["skill", scene]).stdout
                for link in re.findall(r"skill ([a-z]+)", text):
                    assert link in SCENES, (scene, link)
                helps[mode]["skill-" + scene] = text
            for old in ("branch", "flow"):
                assert "skill source" in run(mode, ["skill", old], 2).stderr
            report["runners"][mode] = {"version": version, "root_help_lines": len(root_help.splitlines()),
                                        "root_help_bytes": len(root_help.encode()), "commands": len(commands),
                                        "semantic_operations": len(catalog["operations"]), "scenes": len(SCENES)}
        if len(runners) == 2:
            assert helps["jar"] == helps["native"], "JAR/native help or skill mismatch"
            assert catalogs["jar"] == catalogs["native"], "JAR/native catalog mismatch"
        check("all help entry points, record contracts, parameter descriptions, skills and removed scenes")

        def query(argv):
            outputs = [stream(run(mode, argv).stdout) for mode in runners]
            if len(outputs) > 1:
                assert outputs[0] == outputs[1], ("JAR/native evidence mismatch", argv)
                report["query_comparisons"] += 1
            return outputs[0]

        for builder in runners:
            print(f"[{builder}] build index and execute routes", flush=True)
            db = root / (builder + ".db")
            summary = json.loads(run(builder, ["index", project, "--no-classpath",
                                               "--java-version", "17", "--include-tests", "--spring-xml",
                                               "--output", db, "--format", "json"]).stdout)
            assert summary["status"] == "ok" and summary["index_state"] == "committed", summary
            run(builder, ["index-docs", project, "--index", db])
            assert json.loads(run(builder, ["doctor", "--index", db, "--format", "json"]).stdout)["index_exists"]
            for op in catalogs[builder]["operations"]:
                text = helps[builder][op["id"]]
                examples = [shlex.split(line.strip())[1:] for line in text.splitlines()
                            if line.strip().startswith("anatomist ")]
                assert len(examples) == 1, (op["id"], examples)
                argv = examples[0]
                argv[1:1] = ["--index", str(db)]
                rows = query(argv)
                assert any(row["record"] not in ("stream_header", "evidence") for row in rows), op["id"]
            check(builder + ": all 20 semantic help examples execute with nonempty results")

            body = query(pipeline(db, *resolve(), "--then", "source"))
            assert "void run()" in records(body, "source_slice")[0]["snippet"]
            for tail in (["calls", "--then", "source"], ["calls", "--then", "dispatch", "--then", "source"]):
                sites = query(pipeline(db, *resolve(), "--then", *tail))
                snippets = [row["snippet"] for row in records(sites, "source_slice")]
                assert any("Helper.work()" in text or "api.run()" in text for text in snippets), snippets
                assert all("void run()" not in text and "void work()" not in text for text in snippets), snippets
            declaration = query(pipeline(db, *resolve(), "--then", "describe", "--then", "source"))
            assert records(declaration, "source_slice")[0]["snippet"] == records(body, "source_slice")[0]["snippet"]
            check(builder + ": declaration source versus call/dispatch source location")

            offset = 0
            pages = []
            identity = None
            while True:
                page = query(pipeline(db, *resolve("p.Service#large()"), "--then", "source",
                                      "--limit", "100", "--offset", str(offset)))
                identity = identity or page[0]
                assert page[0] == identity
                row = records(page, "source_slice")[0]
                pages.append(row["snippet"])
                assert row["source"]["offset"] == offset
                if not row["source"]["truncated"]:
                    break
                assert not page[-1]["negative_conclusion_safe"]
                offset += 100
                assert offset < 1000
            assert len(pages) == 3 and "return total;" in pages[-1]
            check(builder + ": complete source pagination with stable identity")

            incoming = query(pipeline(db, *resolve(), "--then", "calls", "--direction", "incoming", scope="ALL"))
            assert records(incoming, "call_site"), incoming
            tests = query(["search", "ServiceTest", "--index", db, "--scope", "TEST"])
            assert records(tests, "entity_candidate"), tests
            none = query(["search", "ServiceTest", "--index", db])
            assert not records(none, "entity_candidate"), none
            writes = query(pipeline(db, *resolve("p.Service#value", "value"), "--then", "accesses", "--mode", "writes"))
            assert records(writes, "access_site"), writes
            contexts = query(pipeline(db, *resolve("p.Helper#choose(boolean)"), "--then", "regions"))
            assert not records(contexts, "control_region"), contexts
            raw = query(pipeline(db, *resolve("p.Helper#choose(boolean)"), "--then", "source"))
            assert "if (flag)" in records(raw, "source_slice")[0]["snippet"]
            bindings = query(pipeline(db, "search", "serviceBean", "--kind", "component", "--then", "resolve", "--unique",
                                      "--then", "bindings", "--semantic", "member"))
            assert records(bindings, "binding_relation"), bindings
            check(builder + ": scope, callers, writes, syntax-only branch and XML members")

            for mode in runners:
                for bad in ([*resolve(), "--then", "trace", "--to", "p.Helper#work()", "--then", "source"],
                            ["search", "Service", "--count", "--then", "resolve"]):
                    error = run(mode, pipeline(db, *bad), 5)
                    assert json.loads(error.stderr)["code"] == "PIPELINE_INCOMPATIBLE_STAGES"
                    assert not error.stdout
                ambiguous = run(mode, ["resolve", "p.Service#overload", "--kind", "callable", "--unique", "--index", db], 2)
                assert json.loads(ambiguous.stderr)["contract"] == "anatomist-error/v1"
            check(builder + ": incompatible combinations and overload ambiguity fail explicitly")

        print("[diff] frozen navigation, caller origins and v2 contracts", flush=True)
        diff_project = root / "diff-project"
        def write_diff(name, text):
            path = diff_project / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)

        def git_diff(*argv):
            return run_command(["git", "-C", str(diff_project), *argv], env=env,
                                  capture_output=True, text=True, check=True).stdout.strip()

        write_diff("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId><artifactId>diff</artifactId><version>1</version></project>")
        write_diff(".anatomist/config.toml", '[scan]\nscopes = ["MAIN", "TEST"]\n')
        source = "package p;\npublic class A {\n public int a() { return 1; }\n public int b() { return 2; }\n}\n"
        write_diff("src/main/java/p/A.java", source)
        write_diff("src/test/java/p/Check.java", "package p; public class Check { public int check() { A a=new A(); return a.a()+a.b(); } }")
        git_diff("init", "-q")
        git_diff("add", ".")
        git_diff("-c", "user.name=Diff Test", "-c", "user.email=diff@example.test", "commit", "-qm", "base")
        first_mode = next(iter(runners))
        base = json.loads(run(first_mode, ["index", diff_project, "--ref", "HEAD", "--no-classpath", "--java-version", "25", "--format", "json"]).stdout)
        write_diff("src/main/java/p/A.java", source.replace("return 1", "return 3").replace("return 2", "return 4"))
        last_mode = list(runners)[-1]
        target = json.loads(run(last_mode, ["index", diff_project, "--ref", "WORKTREE", "--no-classpath", "--java-version", "25", "--format", "json"]).stdout)
        selection = ["diff", "--project", diff_project, "--base", "snapshot:" + base["id"], "--target", "snapshot:" + target["id"],
                     "--no-build", "--impact", "--impact-scope", "TEST"]
        comparisons = []
        for mode in runners:
            result = json.loads(run(mode, [*selection, "--format", "json"]).stdout)
            comparisons.append(result)
            assert result["contract"] == "anatomist-diff/v2"
            assert "negative_conclusion_safe" not in result["evidence"]
            ndjson = [json.loads(line) for line in run(mode, [*selection, "--format", "ndjson"]).stdout.splitlines()]
            assert ndjson == [result["comparison"], *result["changes"], result["evidence"]]
            table = run(mode, [*selection, "--format", "table"]).stdout
            assert "BASE " in table and "impact" in table and "null" not in table
            impacts = records(result["changes"], "impact")
            assert len(impacts) == 4, impacts
            assert all(row["caller"]["scope"] == "TEST" for row in impacts)
            changes = records(result["changes"], "declaration_change")
            assert len(changes) == 2, changes
            for row in changes:
                for side in ("before", "after"):
                    anchor = row[side]
                    route = ["pipeline", "--project", diff_project, "--snapshot", anchor["snapshot_id"], "--scope", anchor["scope"],
                             "--", "resolve", anchor["id"], "--unique", "--then", "source"]
                    slices = records(stream(run(mode, route).stdout), "source_slice")
                    assert slices and "return " in slices[0]["snippet"]
            if validator:
                validator.validate(result)
                for row in ndjson:
                    validator.validate(row)
                invalid = json.loads(json.dumps(changes[0]))
                del invalid["after"]["id"]
                assert not validator.is_valid(invalid), "schema accepted an unnavigable entity anchor"
            error = run(mode, ["diff", "--base", "HEAD", "--target", "WORKTREE", "--impact-scope", "TEST"], 2)
            assert json.loads(error.stderr)["code"] == "INVALID_ARGUMENT"
        assert all(value == comparisons[0] for value in comparisons)
        report["diff_executions"] = len(comparisons)
        report["diff_comparisons"] = int(len(comparisons) > 1)
        report["diff_schema_validated"] = bool(validator)
        check("diff v2: two origins per caller, both snapshots navigable, JSON/NDJSON/table, JAR/native equality")

        print("[branches] diverged tips, polymorphic callers and calls view", flush=True)
        write_diff("src/main/java/p/I.java", "package p; public interface I { int work(); }\n")
        worker = "package p; public class Worker implements I { public int work() { return 1; } }\n"
        write_diff("src/main/java/p/Worker.java", worker)
        write_diff("src/main/java/p/Caller.java", "package p; public class Caller { public int call(I i) { return i.work(); } }\n")
        git_diff("config", "user.name", "Diff Test")
        git_diff("config", "user.email", "diff@example.test")
        git_diff("add", ".")
        git_diff("commit", "-qm", "common ancestor")
        ancestor = git_diff("rev-parse", "HEAD")
        git_diff("branch", "base-tip")
        git_diff("checkout", "-qb", "feature")
        write_diff("src/main/java/p/Worker.java", worker.replace("return 1", "return 2"))
        git_diff("add", ".")
        git_diff("commit", "-qm", "feature work")
        feature_tip = git_diff("rev-parse", "HEAD")
        git_diff("checkout", "-q", "base-tip")
        write_diff("src/main/java/p/A.java", source.replace("return 1", "return 99"))
        git_diff("add", ".")
        git_diff("commit", "-qm", "base advances")
        base_tip = git_diff("rev-parse", "HEAD")
        # Warm a capture on another branch before returning to the feature checkout.
        run(first_mode, ["index", diff_project, "--ref", "HEAD", "--no-classpath", "--java-version", "25"])
        git_diff("checkout", "-q", "feature")
        branch_results = []
        for merge_base in (True, False):
            argv = ["diff", "--project", diff_project, "--base", "base-tip", "--target", "HEAD",
                    "--impact", "--no-classpath", "--java-version", "25"]
            if merge_base:
                argv.append("--merge-base")
            paired = []
            for mode in runners:
                focused = json.loads(run(mode, [*argv, "--view", "calls", "--format", "json"]).stdout)
                paired.append(focused)
                header = focused["comparison"]
                assert header["base"]["commit"] == (ancestor if merge_base else base_tip)
                assert header["target"]["commit"] == feature_tip
                assert header["request"]["base"] == {"selector": "base-tip", "commit": base_tip}
                assert header["request"]["mode"] == ("merge_base" if merge_base else "endpoints")
                assert header["impact"]["dispatch"] == "auto"
                impacts = [r for r in records(focused["changes"], "impact") if r["origin"]["symbol"] == "p.Worker#work()"]
                assert len(impacts) == 2, impacts
                assert all(r["contains_possible_dispatch"] for r in impacts)
                for impact in impacts:
                    edge = impact["edges"][0]
                    assert edge["candidate_kind"] == "possible" and (edge["proof"] or edge["type_proof"])
                    anchor = impact["origin"]
                    route = ["pipeline", "--project", diff_project, "--snapshot", anchor["snapshot_id"],
                             "--scope", anchor["scope"], "--", "resolve", anchor["id"], "--unique", "--then", "source"]
                    snippet = records(stream(run(mode, route).stdout), "source_slice")[0]["snippet"]
                    assert ("return 1" if impact["side"] == "base" else "return 2") in snippet
                assert not focused["evidence"]["capabilities"]["impact"]["negative_conclusion_safe"]
                assert not focused["evidence"]["capabilities"]["impact"]["truncated"]
                all_rows = json.loads(run(mode, [*argv, "--format", "json"]).stdout)
                expected = [r for r in all_rows["changes"] if r["record"] in ("declaration_change", "impact")
                            or r["record"] == "relation_change" and r["relationship"]["relation"] == "CALLS"]
                assert focused["changes"] == expected
                assert focused["evidence"]["capabilities"] == all_rows["evidence"]["capabilities"]
                assert header["output"]["hidden"] == len(all_rows["changes"]) - len(expected)
                resolved = json.loads(run(mode, [*argv, "--view", "calls", "--impact-dispatch", "resolved", "--format", "json"]).stdout)
                assert not any(r["origin"]["symbol"] == "p.Worker#work()" for r in records(resolved["changes"], "impact"))
                ndjson = [json.loads(line) for line in run(mode, [*argv, "--view", "calls"]).stdout.splitlines()]
                assert ndjson == [header, *focused["changes"], focused["evidence"]]
                table = run(mode, [*argv, "--view", "calls", "--format", "table"]).stdout
                assert "dispatch=possible" in table
                assert ("merge_base" if merge_base else "endpoints") in table
                if validator:
                    validator.validate(focused)
                    for row in ndjson:
                        validator.validate(row)
                    invalid = json.loads(json.dumps(impacts[0]))
                    invalid["edges"][0]["proof"] = []
                    invalid["edges"][0]["type_proof"] = []
                    assert not validator.is_valid(invalid), "schema accepted a candidate without proof"
            # Automatic captures have runtime-specific request identities. Compare
            # both readers against the same fixed instances, preserving all fields.
            fixed = ["diff", "--project", diff_project, "--base", "snapshot:" + paired[0]["comparison"]["base"]["id"],
                     "--target", "snapshot:" + paired[0]["comparison"]["target"]["id"], "--view", "calls", "--impact", "--format", "json"]
            readers = [json.loads(run(reader, fixed).stdout) for reader in runners]
            assert all(value == readers[0] for value in readers)
            branch_results.extend(paired)
        assert git_diff("rev-parse", "HEAD") == feature_tip
        assert git_diff("status", "--porcelain") == ""
        write_diff("src/main/java/p/Worker.java", worker.replace("return 1", "return 3"))
        disk = json.loads(run(last_mode, ["diff", "--project", diff_project, "--base", "base-tip", "--target", "WORKTREE",
                                       "--merge-base", "--view", "calls", "--impact", "--no-classpath", "--java-version", "25", "--format", "json"]).stdout)
        assert disk["comparison"]["base"]["commit"] == ancestor
        assert disk["comparison"]["target"]["id"] != branch_results[0]["comparison"]["target"]["id"]
        if validator:
            validator.validate(disk)
        captures = {last_mode: disk}
        for mode in runners:
            if mode != last_mode:
                captures[mode] = json.loads(run(mode, ["diff", "--project", diff_project, "--base", "base-tip", "--target", "WORKTREE",
                    "--merge-base", "--view", "calls", "--impact", "--no-classpath", "--java-version", "25", "--format", "json"]).stdout)
        git_diff("restore", "src/main/java/p/Worker.java")
        git_diff("checkout", "-q", "base-tip")
        cached_comparisons = []
        for mode in runners:
            cached = json.loads(run(mode, ["diff", "--project", diff_project, "--base", "feature", "--target", "WORKTREE",
                                          "--merge-base", "--view", "calls", "--impact", "--no-build", "--no-classpath", "--java-version", "25", "--format", "json"]).stdout)
            assert cached["comparison"]["base"]["commit"] == feature_tip
            assert cached["comparison"]["target"]["id"] == captures[mode]["comparison"]["target"]["id"]
            assert cached["comparison"]["request"]["target"]["commit"] == feature_tip
            if validator:
                validator.validate(cached)
            cached_comparisons.append(cached)
        assert git_diff("rev-parse", "HEAD") == base_tip
        report["cached_worktree_executions"] = len(cached_comparisons)
        report["branch_executions"] = len(branch_results)
        report["branch_comparisons"] = 2 if len(runners) > 1 else 0
        check("branches: merge-base versus tips, branch-switch isolation, polymorphic evidence, view invariance, WORKTREE and frozen source")

    for mode, path in (("jar", args.jar), ("native", args.native)):
        if path:
            report["runners"][mode]["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    execute(main, "target/agent-help-e2e.json")
