#!/usr/bin/env python3
"""Deterministic contract checks for the complex Agent E2E fixture."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = Path(__file__).resolve().parent / "fixtures" / "complex-mini-spring-shop"


def run(argv: list[str], cwd: Path, *, expect: int = 0) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(argv, cwd=cwd, capture_output=True, text=True, timeout=240)
    if result.returncode != expect:
        raise RuntimeError(
            f"command failed: {' '.join(argv)}\nexpected={expect} actual={result.returncode}\n"
            f"{(result.stdout + result.stderr)[-6000:]}"
        )
    return result


def parse_semantic_stream(stdout: str) -> list[dict[str, object]]:
    records = [json.loads(line) for line in stdout.splitlines() if line.strip()]
    if not records:
        raise RuntimeError("semantic pipeline returned no records")
    header = records[0]
    if (header.get("record") != "stream_header"
            or header.get("contract") != "semantic-stream/v1"):
        raise RuntimeError(f"invalid semantic-stream/v1 header: {header}")
    for value in records[1:]:
        contract = value.get("contract")
        if contract is not None and contract != "semantic-stream/v1":
            raise RuntimeError(f"record overrides semantic stream contract: {value}")
    return records


def pipeline(binary: Path, project: Path, db: Path, *stages: list[str]) -> list[dict[str, object]]:
    """Run the public NDJSON pipeline exactly as an Agent does, without a shell."""
    if len(stages) < 2:
        raise ValueError("fixture checks require a semantic pipeline, not a terminal JSON query")
    processes: list[subprocess.Popen[str]] = []
    previous = None
    try:
        for stage in stages:
            arguments = list(stage)
            if "--index" not in arguments and not any(item.startswith("--index=") for item in arguments):
                arguments.extend(["--index", str(db)])
            process = subprocess.Popen(
                [str(binary), *arguments], cwd=project, stdin=previous, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True,
            )
            if previous is not None:
                previous.close()
            previous = process.stdout
            processes.append(process)
        stdout, stderr = processes[-1].communicate(timeout=60)
        errors = [stderr]
        for process in processes[:-1]:
            process.wait(timeout=10)
            if process.stderr is not None:
                errors.append(process.stderr.read())
        failed = [process.returncode for process in processes if process.returncode != 0]
        if failed:
            raise RuntimeError(
                f"pipeline failed: returncodes={failed}\n{''.join(errors)[-6000:]}"
            )
    finally:
        for process in processes:
            if process.poll() is None:
                process.kill()
                process.wait()
    records = parse_semantic_stream(stdout)
    if not any(item.get("record") == "evidence" and item.get("scope") == "stream"
               for item in records):
        raise RuntimeError("pipeline did not return final stream evidence")
    return records


def stream_text(records: list[dict[str, object]]) -> str:
    return json.dumps(records, ensure_ascii=False)


def main() -> None:
    configured = os.environ.get("ANATOMIST_E2E_BIN")
    binary = Path(configured) if configured else ROOT / "target" / "anatomist"
    binary = binary.resolve()
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise RuntimeError("native Anatomist binary missing; run `just native`")

    with tempfile.TemporaryDirectory(prefix="anatomist-complex-fixture-") as directory:
        workspace = Path(directory)
        project = workspace / "complex-shop"
        shutil.copytree(FIXTURE, project)
        run(["mvn", "-q", "install", "-DskipTests"], project)
        run(["mvn", "-q", "test"], project)
        acceptance = subprocess.run(
            ["mvn", "-q", "-Pacceptance", "verify"],
            cwd=project,
            capture_output=True,
            text=True,
            timeout=240,
        )
        if acceptance.returncode == 0:
            raise RuntimeError("acceptance profile must fail before the Agent change")

        db = project / ".anatomist" / "fixture-contract.db"
        run([
            str(binary), "index", ".", "--output", str(db),
            "--health-policy", "integrity", "--format", "json",
        ], project)

        search = pipeline(
            binary, project, db,
            ["search", "OrderService", "--kind", "type", "--format", "ndjson"],
            ["resolve", "--format", "ndjson"],
        )
        symbols = {str(row.get("qualified_name")) for row in search
                   if row.get("record") == "entity"}
        assert "com.example.shop.application.OrderService" in symbols
        assert "com.example.shop.batch.OrderService" in symbols

        controller = pipeline(
            binary, project, db,
            ["resolve", "com.example.shop.api.OrderController", "--kind", "type", "--exact", "--unique", "--format", "ndjson"],
            ["annotations", "--format", "ndjson"],
        )
        controller_text = stream_text(controller)
        assert "/api/orders" in controller_text

        service = pipeline(
            binary, project, db,
            ["resolve", "com.example.shop.application.OrderService#placeOrder(com.example.shop.domain.CreateOrderRequest)", "--kind", "callable", "--exact", "--unique", "--format", "ndjson"],
            ["calls", "--direction", "outgoing", "--format", "ndjson"],
            ["dispatch", "--format", "ndjson"],
        )
        service_text = stream_text(service)
        assert "OrderRepository#save" in service_text
        assert "OrderEventPublisher#publish" in service_text

        beans = pipeline(
            binary, project, db,
            ["search", "--name", "notification-context.xml", "--kind", "ARTIFACT", "--format", "ndjson"],
            ["resolve", "--unique", "--format", "ndjson"],
            ["members", "--recursive", "--format", "ndjson"],
        )
        labels = [str(item.get("name")) for item in beans if item.get("record") == "entity"]
        order = [labels.index(name) for name in ("auditChannel", "emailChannel", "smsChannel")]
        assert order == sorted(order)

        callback = pipeline(
            binary, project, db,
            ["resolve", "com.example.shop.notification.NotificationDispatcher#dispatch(com.example.shop.domain.OrderCreatedEvent)", "--kind", "callable", "--exact", "--unique", "--format", "ndjson"],
            ["calls", "--direction", "outgoing", "--format", "ndjson"],
            ["source", "--format", "ndjson"],
        )
        assert "channel.send(event)" in stream_text(callback)

        paged = pipeline(
            binary, project, db,
            ["resolve", "com.example.shop.batch.OrderService#reconcile(com.example.shop.batch.ReconcileRequest)", "--kind", "callable", "--exact", "--unique", "--format", "ndjson"],
            ["source", "--format", "ndjson"],
        )
        source = next(item["source"] for item in paged if item.get("record") == "source_slice")
        assert source["truncated"] is True

        continuation = pipeline(
            binary, project, db,
            ["resolve", "com.example.shop.batch.OrderService#reconcile(com.example.shop.batch.ReconcileRequest)", "--kind", "callable", "--exact", "--unique", "--format", "ndjson"],
            ["source", "--offset", "200", "--format", "ndjson"],
        )
        assert "PartialReconciliationException" in stream_text(continuation)

        branches = pipeline(
            binary, project, db,
            ["resolve", "com.example.shop.batch.OrderService#reconcile(com.example.shop.batch.ReconcileRequest)", "--kind", "callable", "--exact", "--unique", "--format", "ndjson"],
            ["regions", "--format", "ndjson"],
            ["sites-in", "--record", "call_site", "--format", "ndjson"],
        )
        branch_text = stream_text(branches)
        assert "InventoryRestorer#release" in branch_text
        assert "BatchAuditTrail#record" in branch_text

    print("complex fixture contract: PASS")


if __name__ == "__main__":
    main()
