import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class ReportTest(unittest.TestCase):
    def test_failure_replaces_stale_success_and_retains_only_when_requested(self):
        scripts = Path(__file__).resolve().parents[1] / "scripts"
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "report.json"
            probe = Path(temporary) / "probe.py"
            probe.write_text(f'''import sys
sys.path.insert(0, {str(scripts)!r})
from e2e_report import execute, workspace
def main():
    with workspace("anatomist-report-test-"):
        raise RuntimeError("intentional probe failure")
execute(main, {str(report)!r})
''')
            for keep in (False, True):
                report.write_text('{"status":"passed","old":true}')
                result = subprocess.run([sys.executable, str(probe)] + (["--keep-on-failure"] if keep else []), capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                data = json.loads(report.read_text())
                self.assertEqual("failed", data["status"])
                self.assertNotIn("old", data)
                self.assertEqual(keep, Path(data["workspace"]).exists())
                if keep:
                    shutil.rmtree(data["workspace"])
