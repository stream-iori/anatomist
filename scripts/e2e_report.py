"""Failure-safe reports and owned temporary workspaces for CLI E2E scripts."""
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import traceback
import uuid

_report = {}
_path = None

def flush():
    if _path is not None:
        _path.parent.mkdir(parents=True, exist_ok=True)
        temporary = _path.with_suffix(_path.suffix + ".tmp")
        temporary.write_text(json.dumps(_report, indent=2, default=str) + "\n")
        temporary.replace(_path)

def track(data):
    data.update(_report)
    _report.clear()
    _report.update(data)
    return _report

def run_command(argv, **kwargs):
    _report["last_command"] = list(map(str, argv))
    flush()
    try:
        result = subprocess.run(argv, **kwargs)
    except subprocess.TimeoutExpired as failure:
        _report["last_result"] = {"timeout": failure.timeout, "stdout": str(failure.stdout or "")[-6000:], "stderr": str(failure.stderr or "")[-6000:]}
        raise
    except subprocess.CalledProcessError as failure:
        _report["last_result"] = {"exit": failure.returncode, "stdout": str(failure.stdout or "")[-6000:], "stderr": str(failure.stderr or "")[-6000:]}
        raise
    _report["last_result"] = {"exit": result.returncode, "stdout": str(result.stdout or "")[-6000:], "stderr": str(result.stderr or "")[-6000:]}
    return result

@contextmanager
def workspace(prefix):
    path = Path(tempfile.mkdtemp(prefix=prefix))
    _report["workspace"] = str(path)
    flush()
    failed = False
    try:
        yield str(path)
    except BaseException:
        failed = True
        raise
    finally:
        retained = failed and "--keep-on-failure" in sys.argv
        _report["workspace_retained"] = retained
        if not retained:
            shutil.rmtree(path)

def execute(main, default_report):
    global _path
    def option(name, default=None):
        if name in sys.argv and sys.argv.index(name) + 1 < len(sys.argv):
            return sys.argv[sys.argv.index(name) + 1]
        return next((arg.split("=", 1)[1] for arg in sys.argv if arg.startswith(name + "=")), default)
    _path = Path(option("--report", default_report)).resolve()
    if _path.is_file():
        try:
            previous = json.loads(_path.read_text())
            if previous.get("run_id"):
                history = _path.parent / "e2e-history"
                history.mkdir(parents=True, exist_ok=True)
                shutil.copy2(_path, history / (_path.stem + "-" + previous["run_id"] + ".json"))
        except (ValueError, OSError):
            pass
    _report.update(status="running", run_id=uuid.uuid4().hex, started_at=datetime.now(timezone.utc).isoformat())
    _report["artifacts"] = {}
    for kind in ("jar", "native"):
        value = option("--" + kind)
        if value:
            path = Path(value).resolve()
            _report["artifacts"][kind] = {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None}
    _report["commit"] = subprocess.run(["git", "rev-parse", "HEAD"], cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True).stdout.strip()
    flush()
    try:
        main()
        _report["status"] = "passed"
    except BaseException as failure:
        _report["status"] = "failed"
        _report["failure"] = {"type": type(failure).__name__, "message": str(failure), "traceback": traceback.format_exc()}
        raise
    finally:
        _report["finished_at"] = datetime.now(timezone.utc).isoformat()
        flush()
