import copy
import unittest
from version_checks import validate_diff, navigation_sides, validate_capture, validate_storage


class VersionChecksTest(unittest.TestCase):
    def fixture(self):
        facts = dict(ancestor="fork", feature="feature", base="base", origin="A", possible_origin="W", direct_caller="D", possible_caller="C", test_caller="T")
        changes = []
        header = {}
        for side, commit in (("base", "fork"), ("target", "feature")):
            header[side] = dict(id=side, commit=commit, index_revision_id=side, source_snapshot_id=side, semantic_profile_id=side)
            for origin, caller, possible in (("A", "D", False), ("A", "T", False), ("W", "C", True)):
                anchor = lambda symbol: dict(id=symbol, symbol=symbol, snapshot_id=side)
                edge = dict(source=caller, target=origin, candidate_kind="possible" if possible else "resolved")
                if possible:
                    edge.update(proof=["evidence"], call_site={"id": "site"}, resolved_targets=["I"])
                changes.append(dict(record="impact", side=side, origin=anchor(origin), caller=anchor(caller), path=[caller, origin], edges=[edge], contains_possible_dispatch=possible))
        for change, method in (("deleted", "oldValue"), ("added", "newValue")):
            changes.append(dict(record="relation_change", change=change, relationship=dict(relation="CALLS", target="p.A#" + method + "()")))
        return facts, dict(comparison=header, changes=changes, evidence={"capabilities": {"impact": {"negative_conclusion_safe": False}}})

    def test_empty_impact_does_not_pass_resolved_check(self):
        facts, document = self.fixture()
        document["changes"] = []
        with self.assertRaisesRegex(RuntimeError, "direct caller"):
            validate_diff(document, facts, "work", dispatch="resolved")

    def test_independent_positive_and_negative_graph_assertions(self):
        facts, document = self.fixture()
        validate_diff(document, facts, "work")
        for mutation in (lambda d: d["comparison"]["base"].update(commit="wrong"),
                         lambda d: d["changes"][0]["path"].__setitem__(0, "wrong"),
                         lambda d: d["changes"][2]["edges"][0].update(proof=[])):
            invalid = copy.deepcopy(document)
            mutation(invalid)
            with self.assertRaises(RuntimeError):
                validate_diff(invalid, facts, "work")

    def test_navigation_requires_both_entity_and_snapshot_identity(self):
        _, document = self.fixture()
        streams = []
        for side in ("base", "target"):
            identity = {key: value for key, value in document["comparison"][side].items() if key not in ("id", "commit")}
            streams.append([dict(record="stream_header", identity=identity), dict(record="source_slice", subject={"id": "A"}, snippet="return 1", resolution_status="exact"), dict(record="evidence", scope="stream")])
        self.assertEqual({"base", "target"}, navigation_sides(document, streams))
        streams[1][1]["subject"]["id"] = "wrong"
        self.assertEqual({"base"}, navigation_sides(document, streams))
        streams[0][0]["identity"]["source_snapshot_id"] = "current checkout"
        self.assertEqual(set(), navigation_sides(document, streams))


class LifecycleChecksTest(unittest.TestCase):
    def test_ignored_file_requires_unfiltered_evidence(self):
        facts = {"ignored_report": "target/report.txt"}
        doc = {"comparison": {"output": {"view": "all"}, "capture": {"target": "git-inputs-v2"}}, "changes": []}
        validate_capture(doc, facts)
        doc["changes"] = [{"path": "target/report.txt"}]
        with self.assertRaises(RuntimeError): validate_capture(doc, facts)
        doc["changes"] = []
        doc["comparison"]["output"]["view"] = "calls"
        with self.assertRaises(RuntimeError): validate_capture(doc, facts)

    def test_gc_claims_require_preview_execution_and_protection(self):
        facts = {"stale_id": "old"}
        docs = [{"command": "snapshots stats"}, {"command": "snapshots gc", "execute": False},
                {"command": "snapshots gc", "execute": True, "candidates": [{"id": "old"}], "protected": [{"reason": "pin"}]}]
        validate_storage(docs, facts)
        with self.assertRaises(RuntimeError): validate_storage(docs[1:], facts)
        with self.assertRaises(RuntimeError): validate_storage([docs[0], docs[2]], facts)
