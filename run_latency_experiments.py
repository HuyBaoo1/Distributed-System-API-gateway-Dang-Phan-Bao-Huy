#!/usr/bin/env python3
"""Run repeatable API Gateway latency experiments across strategies.

The gateway and mock backend should already be running. This runner executes a
matrix of rate-limiter strategies and latency scenarios, then stores one JSON
benchmark report per strategy/scenario pair plus a manifest for comparison.
"""

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

import requests


@dataclass(frozen=True)
class Scenario:
    name: str
    delay_ms: int
    requests: int
    concurrency: int


@dataclass(frozen=True)
class StrategyTarget:
    name: str
    benchmark_url: str
    internal_report_url: str


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
        "--strategies",
        default=os.getenv("EXPERIMENT_STRATEGIES", "redis-sliding-window"),
        help="Comma-separated strategy labels to run.",
    )
    parser.add_argument(
        "--strategy-targets",
        default=os.getenv("EXPERIMENT_STRATEGY_TARGETS", ""),
        help="Comma-separated name=url pairs, for example redis-sliding-window=http://localhost:8080/api/v1/hello.",
    )
    parser.add_argument(
        "--internal-report-urls",
        default=os.getenv("EXPERIMENT_INTERNAL_REPORT_URLS", ""),
        help="Optional comma-separated name=url pairs for /internal/latency/report endpoints.",
    )
    parser.add_argument(
        "--scenarios",
        default=os.getenv("EXPERIMENT_SCENARIOS", "baseline,delay-100,delay-500,overload"),
        help=f"Comma-separated scenarios. Available: {', '.join(DEFAULT_SCENARIOS)}",
    )
    parser.add_argument("--output-dir", default=os.getenv("EXPERIMENT_OUTPUT_DIR", "reports"))
    parser.add_argument("--timeout", type=float, default=float(os.getenv("BENCHMARK_TIMEOUT_SECONDS", "10")))
    parser.add_argument("--internal-report-url", default=os.getenv("GATEWAY_INTERNAL_REPORT_URL", ""))
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
    if not selected:
        raise SystemExit("At least one scenario is required.")
    return selected


def selected_strategies(value):
    strategies = [item.strip() for item in value.split(",") if item.strip()]
    if not strategies:
        raise SystemExit("At least one strategy is required.")
    return strategies


def parse_name_url_pairs(value):
    pairs = {}
    for raw_pair in value.split(","):
        pair = raw_pair.strip()
        if not pair:
            continue
        if "=" not in pair:
            raise SystemExit(f"Invalid name=url pair: '{pair}'")
        name, url = pair.split("=", 1)
        pairs[name.strip()] = url.strip()
    return pairs


def derive_internal_report_url(benchmark_url):
    parts = urlsplit(benchmark_url)
    return urlunsplit((parts.scheme, parts.netloc, "/internal/latency/report", "", ""))


def build_strategy_targets(args):
    targets = parse_name_url_pairs(args.strategy_targets)
    internal_targets = parse_name_url_pairs(args.internal_report_urls)
    strategies = selected_strategies(args.strategies)
    strategy_targets = []

    for strategy in strategies:
        benchmark_url = targets.get(strategy, args.url)
        internal_report_url = internal_targets.get(strategy)
        if internal_report_url is None:
            internal_report_url = args.internal_report_url or derive_internal_report_url(benchmark_url)
        strategy_targets.append(StrategyTarget(strategy, benchmark_url, internal_report_url))

    return strategy_targets


def slug(value):
    return "".join(char if char.isalnum() or char in ("-", "_") else "-" for char in value).strip("-")


def fetch_internal_report(url, timeout):
    try:
        response = requests.get(url, timeout=timeout)
        response.raise_for_status()
        return response.json()
    except requests.RequestException as exc:
        return {"error": str(exc)}


def run_benchmark(url, timeout, scenario, output_path):
    repo_root = Path(__file__).resolve().parent
    benchmark_script = repo_root / "gateway_latency_benchmark.py"
    command = [
        sys.executable,
        str(benchmark_script),
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
    subprocess.run(command, check=True, cwd=repo_root)


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def safe_metric(report, metric_name, field):
    metric = report.get("metrics", {}).get(metric_name)
    if not metric:
        return None
    return metric.get(field)


def summarize_report(report):
    status_counts = report.get("statusCounts", {})
    total = sum(status_counts.values())
    rejected = int(status_counts.get("429", 0))
    errors = len(report.get("errors", []))

    return {
        "durationSeconds": report.get("durationSeconds"),
        "throughputRequestsPerSecond": report.get("throughputRequestsPerSecond"),
        "totalResponses": total,
        "rejected429": rejected,
        "errorCount": errors,
        "rejectionRate": round(rejected / total, 4) if total else None,
        "clientP95Ms": safe_metric(report, "clientObserved", "p95Ms"),
        "gatewayP95Ms": safe_metric(report, "gatewayHeader", "p95Ms"),
        "backendP95Ms": safe_metric(report, "backendHeader", "p95Ms"),
        "rateLimiterP95Ms": safe_metric(report, "rateLimiterHeader", "p95Ms"),
        "clientP99Ms": safe_metric(report, "clientObserved", "p99Ms"),
        "gatewayP99Ms": safe_metric(report, "gatewayHeader", "p99Ms"),
        "backendP99Ms": safe_metric(report, "backendHeader", "p99Ms"),
        "rateLimiterP99Ms": safe_metric(report, "rateLimiterHeader", "p99Ms"),
    }


def main():
    args = parse_args()
    repo_root = Path(__file__).resolve().parent
    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = repo_root / output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    scenarios = selected_scenarios(args.scenarios)
    strategy_targets = build_strategy_targets(args)

    manifest = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "scenarios": [scenario.__dict__ for scenario in scenarios],
        "strategies": [],
        "comparisons": [],
    }

    for target in strategy_targets:
        strategy_dir = output_dir / slug(target.name)
        strategy_dir.mkdir(parents=True, exist_ok=True)
        strategy_entry = {
            "name": target.name,
            "targetUrl": target.benchmark_url,
            "internalReportUrl": target.internal_report_url,
            "scenarios": [],
        }

        for scenario in scenarios:
            output_path = strategy_dir / f"{scenario.name}.json"
            print(f"\n=== Running {target.name} / {scenario.name} ===")
            run_benchmark(target.benchmark_url, args.timeout, scenario, output_path)
            benchmark_report = load_json(output_path)

            scenario_entry = {
                "name": scenario.name,
                "delayMs": scenario.delay_ms,
                "requests": scenario.requests,
                "concurrency": scenario.concurrency,
                "reportPath": str(output_path),
                "summary": summarize_report(benchmark_report),
            }

            if not args.skip_internal_report:
                internal_report = fetch_internal_report(target.internal_report_url, args.timeout)
                internal_report_path = strategy_dir / f"{scenario.name}-gateway-snapshot.json"
                internal_report_path.write_text(json.dumps(internal_report, indent=2), encoding="utf-8")
                scenario_entry["gatewaySnapshotPath"] = str(internal_report_path)

            strategy_entry["scenarios"].append(scenario_entry)
            manifest["comparisons"].append({
                "strategy": target.name,
                "scenario": scenario.name,
                **scenario_entry["summary"],
            })

        manifest["strategies"].append(strategy_entry)

    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"\nSaved experiment manifest to {manifest_path}")


if __name__ == "__main__":
    main()
