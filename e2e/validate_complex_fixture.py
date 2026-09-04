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


def query(binary: Path, project: Path, db: Path, *arguments: str) -> dict[str, object]:
    result = run([str(binary), *arguments, "--index", str(db)], project)
    return json.loads(result.stdout)


def walk_labels(value: object) -> list[str]:
    labels: list[str] = []
    if isinstance(value, dict):
        if isinstance(value.get("label"), str):
            labels.append(value["label"])
        for child in value.values():
            labels.extend(walk_labels(child))
    elif isinstance(value, list):
        for child in value:
            labels.extend(walk_labels(child))
    return labels


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

        search = query(binary, project, db, "search", "OrderService")
        symbols = {row["symbol_id"] for row in search["results"]}
        assert "com.example.shop.application.OrderService" in symbols
        assert "com.example.shop.batch.OrderService" in symbols

        controller = query(binary, project, db, "context", "com.example.shop.api.OrderController")
        controller_text = json.dumps(controller, ensure_ascii=False)
        assert "POST /api/orders" in controller_text

        service = query(binary, project, db, "context", "com.example.shop.application.OrderService")
        service_text = json.dumps(service, ensure_ascii=False)
        assert "strictRiskPolicy" in service_text
        assert "auditedOrderRepository" in service_text

        beans = query(binary, project, db, "bean-config", "notificationRegistry", "--format", "json")
        labels = walk_labels(beans)
        order = [labels.index(name) for name in ("auditChannel", "emailChannel", "smsChannel")]
        assert order == sorted(order)

        callback = query(
            binary, project, db, "call-path",
            "com.example.shop.application.OrderService#placeOrder(com.example.shop.domain.CreateOrderRequest)",
            "com.example.shop.notification.NotificationChannel#send(com.example.shop.domain.OrderCreatedEvent)",
            "--depth", "8", "--through-callbacks",
        )
        assert callback["stats"]["total"] == 3
        assert "$lambda" in json.dumps(callback)

        paged = query(
            binary, project, db, "context",
            "com.example.shop.batch.OrderService#reconcile(com.example.shop.batch.ReconcileRequest)",
            "--source",
        )
        source = paged["results"][0]["source"]
        assert source["truncated"] is True
        assert paged["next_queries"]

        branches = query(
            binary, project, db, "branches-of",
            "com.example.shop.batch.OrderService#reconcile(com.example.shop.batch.ReconcileRequest)",
            "--depth", "2", "--source-window", "2",
        )
        branch_text = json.dumps(branches, ensure_ascii=False)
        assert "case CANCELLED" in branch_text
        assert "InventoryRestorer#release" in branch_text
        assert "BatchAuditTrail#record" in branch_text

    print("complex fixture contract: PASS")


if __name__ == "__main__":
    main()
