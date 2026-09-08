import unittest
import hashlib
import sqlite3
import tempfile
import os
import subprocess
import shutil
from unittest.mock import patch
from pathlib import Path
from types import SimpleNamespace

from anatomist_jury_adapter import (
    AnatomistJuryAdapter,
    _pipeline_is_ndjson,
    _any_match,
    _indexed_content_mismatches,
    _matches_any,
    anatomist_invocations,
    anatomist_subcommands,
    contains_contiguous_sequence,
    contains_sequence,
    executed_anatomist_invocations,
    semantic_pipelines,
    trace_commands,
)
from validate_complex_fixture import parse_semantic_stream


class TraceCommandTest(unittest.TestCase):
    def test_search_defaults_to_ndjson_in_both_pipeline_forms(self):
        for command, expected in (("anatomist pipeline -- search A --then resolve", True),
                                  ("anatomist pipeline --format json -- search A --then resolve", False),
                                  ("anatomist search A | anatomist resolve", True)):
            trace = SimpleNamespace(tool_events=[SimpleNamespace(
                arguments={"cmd": command}, raw_input={}, raw_output="")])
            self.assertEqual(expected, _pipeline_is_ndjson(semantic_pipelines(trace)[0]))

    def test_tee_preserves_evidence_but_filters_are_rejected(self):
        for middle, expected in (("tee evidence.ndjson", 1), ("jq .", 0), ("head -n 2", 0)):
            trace = SimpleNamespace(tool_events=[SimpleNamespace(
                arguments={"cmd": f"anatomist search A | {middle} | anatomist resolve | tee final.ndjson"},
                raw_input={}, raw_output="")])
            self.assertEqual(expected, len(semantic_pipelines(trace)))
        unrelated = SimpleNamespace(tool_events=[SimpleNamespace(
            arguments={"cmd": "rg 'logs | trace' ."}, raw_input={}, raw_output="")])
        self.assertEqual([], semantic_pipelines(unrelated))
        fused = SimpleNamespace(tool_events=[SimpleNamespace(
            arguments={"cmd": "anatomist pipeline -- search A --then resolve | tee final.ndjson"},
            raw_input={}, raw_output="")])
        self.assertEqual(1, len(semantic_pipelines(fused)))

    def test_workspace_pins_a_custom_binary_even_in_login_shells(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            binary = root / "custom-snapshot"
            binary.write_text("#!/bin/sh\necho 'anatomist 1.2.0-SNAPSHOT'\n")
            binary.chmod(0o755)
            workspace = root / "workspace"
            workspace.mkdir()
            with patch("anatomist_jury_adapter._resolve_binary", return_value=binary):
                prepared = AnatomistJuryAdapter().prepare_workspace(
                    SimpleNamespace(extension=lambda _: {"project_root": "project"}), str(workspace))
            for shell in ("zsh", "bash"):
                executable = shutil.which(shell)
                if executable:
                    result = subprocess.run([executable, "-lc", "command -v anatomist"],
                                            cwd=workspace, env={**os.environ, **prepared.env},
                                            capture_output=True, text=True, check=True)
                    self.assertEqual(binary, Path(result.stdout.strip()).resolve())
            self.assertEqual(str(workspace / "anatomist-storage"), prepared.env["ANATOMIST_HOME"])
            self.assertTrue((workspace / "anatomist-skill.md").is_file())

    def test_semantic_stream_contract_is_declared_by_header(self):
        records = parse_semantic_stream(
            '{"record":"stream_header","contract":"semantic-stream/v1"}\n'
            '{"record":"entity","id":"sample.A"}\n'
            '{"record":"evidence","scope":"stream"}\n'
        )

        self.assertEqual(["stream_header", "entity", "evidence"],
                         [record["record"] for record in records])

    def test_sequential_pipelines_receive_only_their_own_frame(self):
        header = '{"record":"stream_header","contract":"semantic-stream/v1"}\n'
        footer = '{"record":"evidence","scope":"stream","coverage":"complete"}\n'
        output = header + '{"record":"source_slice","id":"first"}\n' + footer
        output += header + '{"record":"entity","id":"second"}\n' + footer
        trace = SimpleNamespace(tool_events=[SimpleNamespace(
            name="command_execution", arguments={"cmd": "anatomist pipeline -- resolve A --then source\nanatomist pipeline -- search B --format ndjson --then resolve"},
            raw_input={"exit_code": 0}, raw_output=output)])
        pipelines = semantic_pipelines(trace)
        self.assertEqual(2, len(pipelines))
        self.assertIn('"first"', pipelines[0]["output"])
        self.assertNotIn('"second"', pipelines[0]["output"])
        self.assertIn('"second"', pipelines[1]["output"])
        self.assertNotIn('"first"', pipelines[1]["output"])
        provider = AnatomistJuryAdapter().extension_check_providers()["anatomist"]
        provider._execute({"check": "semantic_pipeline", "commands": ["resolve", "source"], "records": ["source_slice"]}, SimpleNamespace(trace=trace), {})
        trace.tool_events[0].raw_input["exit_code"] = 1
        with self.assertRaises(RuntimeError):
            provider._execute({"check": "semantic_pipeline", "commands": ["resolve", "source"]}, SimpleNamespace(trace=trace), {})

    def test_extracts_codex_sdk_command_execution(self):
        trace = SimpleNamespace(
            tool_events=[
                SimpleNamespace(
                    name="command_execution",
                    arguments={},
                    raw_input={
                        "type": "commandExecution",
                        "command": "/bin/zsh -lc 'anatomist doctor --agent-preflight'",
                        "command_actions": [
                            {
                                "command": "anatomist doctor --agent-preflight",
                                "type": "unknown",
                            }
                        ],
                    },
                ),
                SimpleNamespace(
                    name="command_execution",
                    arguments={"cmd": "anatomist index . --incremental"},
                    raw_input={},
                ),
            ]
        )

        commands = trace_commands(trace)

        self.assertEqual(
            ["anatomist doctor --agent-preflight", "anatomist index . --incremental"],
            commands,
        )
        self.assertEqual(["doctor", "index"], anatomist_subcommands(commands))

    def test_extracts_acp_command_name_and_keeps_order(self):
        trace = SimpleNamespace(
            tool_events=[
                SimpleNamespace(
                    name="`/tmp/bin/anatomist resolve 'sample.A#run()' --kind callable --exact`",
                    arguments={},
                    raw_input={},
                )
            ]
        )

        commands = trace_commands(trace)

        self.assertEqual(["resolve"], anatomist_subcommands(commands))
        self.assertTrue(
            contains_sequence(
                ["skill", "doctor", "index", "resolve"], ["doctor", "index", "resolve"]
            )
        )
        self.assertFalse(
            contains_sequence(
                ["index", "doctor", "resolve"], ["doctor", "index", "resolve"]
            )
        )

    def test_path_glob_helpers_distinguish_value_and_pattern_lists(self):
        changed = [
            "application/src/main/java/sample/Policy.java",
            "application/src/test/java/sample/PolicyTest.java",
        ]
        self.assertTrue(_any_match(changed, "application/src/main/java/**"))
        self.assertTrue(_matches_any(changed[0], ["application/src/main/java/**"]))
        self.assertFalse(_matches_any("pom.xml", ["application/src/**"]))

    def test_execution_checks_ignore_help_only_invocations(self):
        trace = SimpleNamespace(
            tool_events=[
                SimpleNamespace(
                    name="command_execution",
                    arguments={},
                    raw_input={
                        "command_actions": [
                            {"command": "anatomist index --help"},
                            {"command": "anatomist index . --incremental"},
                        ]
                    },
                    raw_output="ok",
                )
            ]
        )

        invocations = executed_anatomist_invocations(trace)

        self.assertEqual(1, len(invocations))
        self.assertEqual("anatomist index . --incremental", invocations[0]["command"])

    def test_pipeline_segments_preserve_all_semantic_operations(self):
        commands = [
            "anatomist search Gateway --kind type --format ndjson | "
            "anatomist resolve --unique | anatomist runtime-implementations | anatomist source"
        ]
        self.assertEqual(
            ["search", "resolve", "runtime-implementations", "source"],
            anatomist_subcommands(commands),
        )

    def test_semantic_pipelines_keep_the_actual_pipe_boundary(self):
        trace = SimpleNamespace(
            tool_events=[
                SimpleNamespace(
                    name="command_execution",
                    arguments={},
                    raw_input={"command_actions": [{"command": (
                        "anatomist search Gateway --kind type --format ndjson | "
                        "anatomist resolve --unique | anatomist runtime-implementations | "
                        "anatomist source"
                    )}]},
                    raw_output='{"record":"source_slice","contract":"semantic-stream/v1"}\n'
                    '{"record":"evidence","contract":"semantic-stream/v1","scope":"stream"}',
                ),
                SimpleNamespace(
                    name="command_execution",
                    arguments={"cmd": "anatomist resolve Gateway --unique"},
                    raw_input={},
                    raw_output="",
                ),
            ]
        )

        pipelines = semantic_pipelines(trace)

        self.assertEqual(1, len(pipelines))
        self.assertEqual(
            ["search", "resolve", "runtime-implementations", "source"],
            [segment["subcommand"] for segment in pipelines[0]["segments"]],
        )
        self.assertTrue(contains_contiguous_sequence(
            ["search", "resolve", "runtime-implementations", "source"],
            ["resolve", "runtime-implementations", "source"],
        ))
        self.assertFalse(contains_contiguous_sequence(
            ["search", "resolve", "source"],
            ["resolve", "runtime-implementations", "source"],
        ))

    def test_fused_pipeline_exposes_logical_stages_and_inherited_contract(self):
        trace = SimpleNamespace(tool_events=[
            SimpleNamespace(
                name="command_execution",
                arguments={"cmd": (
                    "anatomist pipeline --format ndjson --index index.db -- "
                    "resolve sample.A --exact --unique --then source"
                )},
                raw_input={},
                raw_output=(
                    '{"record":"stream_header","contract":"semantic-stream/v1"}\n'
                    '{"record":"source_slice","id":"source:1"}\n'
                    '{"record":"evidence","scope":"stream","coverage":"complete"}'
                ),
            )
        ])

        pipeline = semantic_pipelines(trace)[0]

        self.assertEqual(["resolve", "source"],
                         [part["subcommand"] for part in pipeline["segments"]])
        self.assertEqual(["pipeline", "resolve", "source"],
                         [item["subcommand"] for item in executed_anatomist_invocations(trace)])

    def test_semantic_pipeline_check_rejects_separate_modern_commands(self):
        provider = AnatomistJuryAdapter().extension_check_providers()["anatomist"]
        output = (
            '{"record":"stream_header","contract":"semantic-stream/v1"}\n'
            '{"record":"source_slice","contract":"semantic-stream/v1",'
            '"subject":{"record":"dispatch_target","id":"dispatch:1"}}\n'
            '{"record":"evidence","contract":"semantic-stream/v1","scope":"stream",'
            '"coverage":"complete","negative_conclusion_safe":false}'
        )
        separate = SimpleNamespace(
            trace=SimpleNamespace(tool_events=[
                SimpleNamespace(name="command_execution", arguments={
                    "cmd": "anatomist resolve Gateway --unique --format ndjson"}, raw_input={}, raw_output=output),
                SimpleNamespace(name="command_execution", arguments={
                    "cmd": "anatomist source --format ndjson"}, raw_input={}, raw_output=output),
            ])
        )
        config = {"check": "semantic_pipeline", "commands": ["resolve", "source"],
                  "records": ["source_slice", "evidence"]}

        with self.assertRaisesRegex(RuntimeError, "one NDJSON pipe chain"):
            provider._execute(config, separate, {})

        piped = SimpleNamespace(
            trace=SimpleNamespace(tool_events=[
                SimpleNamespace(name="command_execution", arguments={"cmd": (
                    "anatomist resolve Gateway --unique --format ndjson | "
                    "anatomist source --format ndjson"
                )}, raw_input={}, raw_output=output),
            ])
        )
        evidence = provider._execute(config, piped, {})
        self.assertIn(" | ", evidence["pipeline"])

    def test_source_page_check_requires_a_pipe_continuation(self):
        provider = AnatomistJuryAdapter().extension_check_providers()["anatomist"]
        first_output = (
            '{"record":"stream_header","contract":"semantic-stream/v1"}\n'
            '{"record":"source_slice","contract":"semantic-stream/v1",'
            '"source":{"truncated":true}}\n'
            '{"record":"evidence","contract":"semantic-stream/v1","scope":"stream",'
            '"coverage":"unknown","negative_conclusion_safe":false}'
        )
        final_output = (
            '{"record":"stream_header","contract":"semantic-stream/v1"}\n'
            '{"record":"source_slice","contract":"semantic-stream/v1",'
            '"source":{"truncated":false}}\n'
            '{"record":"evidence","contract":"semantic-stream/v1","scope":"stream",'
            '"coverage":"complete","negative_conclusion_safe":false}'
        )
        trace = SimpleNamespace(tool_events=[
            SimpleNamespace(name="command_execution", arguments={"cmd": (
                "anatomist resolve sample.A#run --exact --format ndjson | "
                "anatomist source --format ndjson"
            )}, raw_input={}, raw_output=first_output),
            SimpleNamespace(name="command_execution", arguments={"cmd": (
                "anatomist resolve sample.A#run --exact --format ndjson | "
                "anatomist source --offset 200 --format ndjson"
            )}, raw_input={}, raw_output=final_output),
        ])

        evidence = provider._execute(
            {"check": "source_page_followed", "target": r"sample\.A#run"},
            SimpleNamespace(trace=trace), {},
        )

        self.assertIn("--offset 200", evidence["continuation"])

    def test_indexed_content_mismatches_uses_cached_sha256(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            source = project / "src/main/java/sample/Policy.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Policy {}\n", encoding="utf-8")
            index = project / "index.db"
            with sqlite3.connect(index) as connection:
                connection.execute("CREATE TABLE file_cache(source_file TEXT, hash TEXT)")
                connection.execute(
                    "INSERT INTO file_cache VALUES (?, ?)",
                    (
                        "src/main/java/sample/Policy.java",
                        hashlib.sha256(source.read_bytes()).hexdigest(),
                    ),
                )

            self.assertEqual([], _indexed_content_mismatches(project, index))
            source.write_text("class Policy { int changed; }\n", encoding="utf-8")
            self.assertEqual(
                ["src/main/java/sample/Policy.java"],
                _indexed_content_mismatches(project, index),
            )


if __name__ == "__main__":
    unittest.main()
