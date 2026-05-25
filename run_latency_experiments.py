#!/usr/bin/env python3
"""Run repeatable API Gateway latency experiments.

The gateway and mock backend should already be running. This runner executes a
set of benchmark scenarios and stores one JSON report per scenario.
"""

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import requests


@dataclass(frozen=True)
class Scenario:
    name: str
    delay_ms: int
    requests: int
    concurrency: int


DEFAULT_SCENARIOS = {
    "baseline": Scenario("baseline", delay_ms=0, requests=200, concurrency=20),
    "delay-100": Scenario("delay-100", delay_ms=100, requests=200, concurrency=20),
    "delay-500": Scenario("delay-500", delay_ms=500, requests=200, concurrency=20),
    "overload": Scenario("overload", delay_ms=100, requests=500, concurrency=50),
}


def load_env_file(path=".env"):
    env_path = Path(path)
    if not env_path.exists():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def parse_args():
    load_env_file()
    parser = argparse.ArgumentParser(description="Run gateway latency experiment scenarios.")
    parser.add_argument("--url", default=os.getenv("GATEWAY_URL", "http://localhost:8080/api/v1/hello"))
    parser.add_argument(
        "--scenarios",
        default=os.getenv("EXPERIMENT_SCENARIOS", "baseline,delay-100,delay-500,overload"),
        help=f"Comma-separated scenarios. Available: {', '.join(DEFAULT_SCENARIOS)}",
    )
    parser.add_argument("--output-dir", default=os.getenv("EXPERIMENT_OUTPUT_DIR", "reports"))
    parser.add_argument("--timeout", type=float, default=float(os.getenv("BENCHMARK_TIMEOUT_SECONDS", "10")))
    parser.add_argument(
        "--internal-report-url",
        default=os.getenv("GATEWAY_INTERNAL_REPORT_URL", "http://localhost:8080/internal/latency/report"),
    )
    parser.add_argument("--skip-internal-report", action="store_true")
    return parser.parse_args()


def selected_scenarios(value):
    selected = []
    for raw_name in value.split(","):
        name = raw_name.strip()
        if not name:
            continue
        if name not in DEFAULT_SCENARIOS:
            raise SystemExit(f"Unknown scenario '{name}'. Available: {', '.join(DEFAULT_SCENARIOS)}")
        selected.append(DEFAULT_SCENARIOS[name])
    return selected


def fetch_internal_report(url, timeout):
    try:
        response = requests.get(url, timeout=timeout)
        response.raise_for_status()
        return response.json()
    except requests.RequestException as exc:
        return {"error": str(exc)}


def run_benchmark(url, timeout, scenario, output_path):
    command = [
        sys.executable,
        "gateway_latency_benchmark.py",
        "--url",
        url,
        "--delay-ms",
        str(scenario.delay_ms),
        "--requests",
        str(scenario.requests),
        "--concurrency",
        str(scenario.concurrency),
        "--timeout",
        str(timeout),
        "--output",
        str(output_path),
    ]
    subprocess.run(command, check=True)


def main():
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    manifest = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "targetUrl": args.url,
        "scenarios": [],
    }

    for scenario in selected_scenarios(args.scenarios):
        output_path = output_dir / f"{scenario.name}.json"
        print(f"\n=== Running scenario: {scenario.name} ===")
        run_benchmark(args.url, args.timeout, scenario, output_path)

        scenario_entry = {
            "name": scenario.name,
            "delayMs": scenario.delay_ms,
            "requests": scenario.requests,
            "concurrency": scenario.concurrency,
            "reportPath": str(output_path),
        }

        if not args.skip_internal_report:
            internal_report = fetch_internal_report(args.internal_report_url, args.timeout)
            internal_report_path = output_dir / f"{scenario.name}-gateway-snapshot.json"
            internal_report_path.write_text(json.dumps(internal_report, indent=2), encoding="utf-8")
            scenario_entry["gatewaySnapshotPath"] = str(internal_report_path)

        manifest["scenarios"].append(scenario_entry)

    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"\nSaved experiment manifest to {manifest_path}")


if __name__ == "__main__":
    main()
