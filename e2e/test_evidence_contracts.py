import json
import os
import unittest
import tempfile
from pathlib import Path
from types import SimpleNamespace as S
from unittest.mock import patch
from evidence_contracts import semantic_frames, embedded_semantic_frames, frames
from anatomist_jury_adapter import _resolve_binary, _semantic_records, _record_types
from anatomist_jury_adapter import consumed_evidence_files, redirected_evidence
from anatomist_jury_adapter import AnatomistJuryAdapter

HEADER = {"record": "stream_header", "contract": "semantic-stream/v1"}
FOOTER = {"record": "evidence", "scope": "stream"}
def lines(*rows):
    return "\n".join(json.dumps(row) for row in rows)

class EvidenceContractsTest(unittest.TestCase):
    def test_semantic_pipeline_accepts_its_later_consumed_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve()
            (root/'project').mkdir()
            (root/'evidence').mkdir()
            (root/'evidence/source.ndjson').write_text(lines(HEADER,{'record':'source_slice'},FOOTER))
            event=lambda cmd: S(arguments={'cmd':cmd},raw_input={'exit_code':0},raw_output='')
            trace=S(tool_events=[event('anatomist pipeline -- resolve A --then source > ../evidence/source.ndjson'),event('cat ../evidence/source.ndjson')])
            provider=AnatomistJuryAdapter().extension_check_providers()['anatomist']
            state={'workspace':root,'project':root/'project'}
            config={'check':'semantic_pipeline','commands':['resolve','source'],'records':['source_slice','evidence']}
            provider._execute(config,S(trace=trace),state)
            trace.tool_events.pop()
            with self.assertRaises(RuntimeError):provider._execute(config,S(trace=trace),state)

    def test_python_labels_and_summaries_do_not_pollute_complete_source_frames(self):
        stream = lines(HEADER, {"record":"source_slice"}, FOOTER)
        output = "base Router " + stream + '\n{"record":"impact"}\n'
        self.assertEqual([[HEADER, {"record":"source_slice"}, FOOTER]], embedded_semantic_frames(output))
        self.assertEqual([], embedded_semantic_frames(lines(HEADER, {"record":"source_slice"})))
        self.assertEqual([], embedded_semantic_frames(lines(HEADER) + '\nmalformed\n' + lines(FOOTER)))

    def test_redirected_evidence_requires_later_read_in_owned_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            (root / "project").mkdir()
            (root / "evidence").mkdir()
            path = root / "evidence/diff.json"
            path.write_text('{"fixture":true}')
            event = lambda cmd, code=0: S(arguments={"cmd":cmd}, raw_input={"exit_code":code})
            trace = S(tool_events=[event("anatomist diff --base main --target HEAD > ../evidence/diff.json")])
            execution = {"args":[">","../evidence/diff.json"],"event_index":0,"output":""}
            self.assertEqual([], consumed_evidence_files(trace,root))
            trace.tool_events.append(event("cat ../evidence/diff.json"))
            consumed = consumed_evidence_files(trace,root)
            self.assertEqual(path.read_text(),redirected_evidence(execution,root / "project",consumed))
            execution["event_index"] = 2
            self.assertEqual("",redirected_evidence(execution,root / "project",consumed))
            trace.tool_events[-1].raw_input["exit_code"] = 1
            self.assertEqual([],consumed_evidence_files(trace,root))
            path.unlink()
            outside = root / "external.json"
            outside.write_text("unowned")
            path.symlink_to(outside)
            trace.tool_events[-1].raw_input["exit_code"] = 0
            self.assertEqual([],consumed_evidence_files(trace,root))

    def test_explicit_invalid_binary_never_falls_back(self):
        with patch.dict(os.environ, {"ANATOMIST_E2E_BIN": "/nonexistent/anatomist"}):
            with self.assertRaises(RuntimeError):
                _resolve_binary()

    def test_headers_and_terminal_evidence_are_required(self):
        for output in (lines(FOOTER), lines(HEADER), lines(HEADER, HEADER, FOOTER),
                       lines(HEADER, {"contract": "anatomist-diff/v2"}, FOOTER)):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                frames(output)

    def test_sequential_contracts_do_not_leak(self):
        output = lines(HEADER, FOOTER,
                       {"record": "diff_header", "contract": "anatomist-diff/v2"},
                       {"record": "impact"}, {"record": "evidence", "scope": "comparison"})
        self.assertEqual([[HEADER, FOOTER]], semantic_frames(output))
        self.assertNotIn("impact", _record_types(_semantic_records(output)))

    def test_nested_record_does_not_satisfy_top_level_contract(self):
        rows = [HEADER, {"record": "source_slice", "subject": {"record": "dispatch_target"}}, FOOTER]
        self.assertNotIn("dispatch_target", _record_types(rows))

    def test_multiple_streams_cannot_be_attributed_to_every_command(self):
        with self.assertRaises(RuntimeError):
            _semantic_records(lines(HEADER, FOOTER, HEADER, FOOTER))

    def test_malformed_midstream_is_not_discarded(self):
        with self.assertRaises(RuntimeError):
            frames(lines(HEADER) + '\n{"record": broken}\n' + lines(FOOTER))

    def test_records_after_footer_are_rejected(self):
        with self.assertRaises(RuntimeError):
            frames(lines(HEADER, FOOTER, {"record": "source_slice"}))
