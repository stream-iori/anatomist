import unittest
import hashlib
import sqlite3
import tempfile
from pathlib import Path
from types import SimpleNamespace

from anatomist_jury_adapter import (
    _any_match,
    _indexed_content_mismatches,
    _matches_any,
    anatomist_invocations,
    anatomist_subcommands,
    contains_sequence,
    executed_anatomist_invocations,
    followed_next_query,
    trace_commands,
)


class TraceCommandTest(unittest.TestCase):
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
                    name="`/tmp/bin/anatomist context 'sample.A#run()' --source`",
                    arguments={},
                    raw_input={},
                )
            ]
        )

        commands = trace_commands(trace)

        self.assertEqual(["context"], anatomist_subcommands(commands))
        self.assertTrue(
            contains_sequence(
                ["skill", "doctor", "index", "context"], ["doctor", "index", "context"]
            )
        )
        self.assertFalse(
            contains_sequence(
                ["index", "doctor", "context"], ["doctor", "index", "context"]
            )
        )

    def test_invocations_keep_duplicate_context_calls_and_outputs(self):
        trace = SimpleNamespace(
            tool_events=[
                SimpleNamespace(
                    name="command_execution",
                    arguments={},
                    raw_input={
                        "command_actions": [{"command": "anatomist context A#run --source"}],
                        "exit_code": 0,
                    },
                    raw_output='{"results":[{"source":{"truncated":true}}],'
                    '"next_queries":["context A#run --source --source-offset 200"]}',
                ),
                SimpleNamespace(
                    name="command_execution",
                    arguments={"cmd": "anatomist context A#run --source --source-offset 200"},
                    raw_input={"exit_code": 0},
                    raw_output='{"results":[{"source":{"truncated":false}}]}',
                ),
            ]
        )

        invocations = anatomist_invocations(trace)

        self.assertEqual(["context", "context"], [item["subcommand"] for item in invocations])
        self.assertTrue(followed_next_query(invocations, r"A#run"))

    def test_followed_next_query_rejects_unadvertised_offset(self):
        invocations = [
            {
                "subcommand": "context",
                "command": "anatomist context A#run --source",
                "output": '{"truncated":true,"next_queries":['
                '"context A#run --source --source-offset 200"]}',
            },
            {
                "subcommand": "context",
                "command": "anatomist context A#run --source --source-offset 100",
                "output": "{}",
            },
        ]

        self.assertFalse(followed_next_query(invocations, r"A#run"))

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
