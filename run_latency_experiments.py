#!/usr/bin/env python3
"""Run repeatable API Gateway latency experiments across strategies and fault policies.

The gateway and mock backend should already be running via docker compose.
This script executes a configurable matrix of:
  - rate-limiter strategies  (in-memory, redis-fixed-window, redis-sliding-window, redis-token-bucket)
  - backend delay scenarios  (baseline, delay-100, delay-500, overload)
  - [optional] fault-tolerance policies (fail-closed, fail-open, local-fallback)

Usage – run the full default matrix (4 strategies × 4 scenarios):
    python run_latency_experiments.py

Run with strategy matrix flag (all strategies automatically mapped to their ports):
    python run_latency_experiments.py --strategy-matrix

Run fault-tolerance policy comparison (fixed-window × 3 policies):
    python run_latency_experiments.py --fault-policy-matrix --strategy redis-fixed-window

Override strategy→URL mappings for non-default port layouts:
    python run_latency_experiments.py \\
      --strategy-targets redis-fixed-window=http://localhost:8082/api/v1/hello \\
      --strategies redis-fixed-window,redis-token-bucket

"""

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

import requests


# ---------------------------------------------------------------------------
# Domain models
# ---------------------------------------------------------------------------

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
    fault_policy: str = "fail-closed"


# ---------------------------------------------------------------------------
# Built-in scenario catalogue
# ---------------------------------------------------------------------------

DEFAULT_SCENARIOS: dict[str, Scenario] = {
    "baseline":    Scenario("baseline",    delay_ms=0,   requests=200, concurrency=20),
    "delay-100":   Scenario("delay-100",   delay_ms=100, requests=200, concurrency=20),
    "delay-500":   Scenario("delay-500",   delay_ms=500, requests=200, concurrency=20),
    "overload":    Scenario("overload",    delay_ms=100, requests=500, concurrency=50),
    "burst":       Scenario("burst",       delay_ms=0,   requests=120, concurrency=120),
    "high-concur": Scenario("high-concur", delay_ms=50,  requests=400, concurrency=80),
}

# Default port mapping: strategy name → docker-compose port (fail-closed policy)
DEFAULT_STRATEGY_PORTS: dict[str, int] = {
    "redis-sliding-window": 8080,
    "redis-fixed-window":   8082,
    "redis-token-bucket":   8083,
    "in-memory":            8084,
}

# Fault-tolerance policy port mapping: (strategy, policy) → port
FAULT_POLICY_PORTS: dict[tuple[str, str], int] = {
    ("redis-fixed-window",   "fail-closed"):    8082,
    ("redis-fixed-window",   "fail-open"):      8090,
    ("redis-fixed-window",   "local-fallback"): 8091,
    ("redis-token-bucket",   "fail-closed"):    8083,
    ("redis-token-bucket",   "fail-open"):      8092,
    ("redis-token-bucket",   "local-fallback"): 8093,
    ("redis-sliding-window", "fail-closed"):    8080,
    ("redis-sliding-window", "fail-open"):      8094,
    ("redis-sliding-window", "local-fallback"): 8095,
    ("in-memory",            "fail-closed"):    8084,
}

DEFAULT_PATH = "/api/v1/hello"
LOCALHOST    = "http://localhost"


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def load_env_file(path: str = ".env") -> None:
    env_path = Path(path)
    if not env_path.exists():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def parse_args() -> argparse.Namespace:
    load_env_file()
    parser = argparse.ArgumentParser(
        description="Run gateway latency experiment matrix.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "--url",
        default=os.getenv("GATEWAY_URL", f"{LOCALHOST}:8080{DEFAULT_PATH}"),
        help="Default gateway URL (used when --strategy-targets is not set).",
    )
    parser.add_argument(
        "--strategies",
        default=os.getenv("EXPERIMENT_STRATEGIES", "redis-sliding-window"),
        help="Comma-separated strategy labels.",
    )
    parser.add_argument(
        "--strategy-matrix",
        action="store_true",
        help="Run ALL four built-in strategies using their default docker-compose ports.",
    )
    parser.add_argument(
        "--fault-policy-matrix",
        action="store_true",
        help="Run fail-closed/fail-open/local-fallback variants for selected strategies.",
    )
    parser.add_argument(
        "--fault-policies",
        default="fail-closed,fail-open,local-fallback",
        help="Comma-separated fault policies to include when --fault-policy-matrix is active.",
    )
    parser.add_argument(
        "--strategy-targets",
        default=os.getenv("EXPERIMENT_STRATEGY_TARGETS", ""),
        help="Manual name=url overrides, e.g. redis-fixed-window=http://localhost:8082/api/v1/hello",
    )
    parser.add_argument(
        "--internal-report-urls",
        default=os.getenv("EXPERIMENT_INTERNAL_REPORT_URLS", ""),
        help="Optional name=url pairs for /internal/latency/report endpoints.",
    )
    parser.add_argument(
        "--scenarios",
        default=os.getenv("EXPERIMENT_SCENARIOS", "baseline,delay-100,delay-500,overload"),
        help=f"Comma-separated scenarios. Available: {', '.join(DEFAULT_SCENARIOS)}",
    )
    parser.add_argument(
        "--output-dir",
        default=os.getenv("EXPERIMENT_OUTPUT_DIR", "reports"),
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=float(os.getenv("BENCHMARK_TIMEOUT_SECONDS", "10")),
    )
    parser.add_argument(
        "--internal-report-url",
        default=os.getenv("GATEWAY_INTERNAL_REPORT_URL", ""),
    )
    parser.add_argument("--skip-internal-report", action="store_true")
    return parser.parse_args()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def selected_scenarios(value: str) -> list[Scenario]:
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


def selected_strategies(value: str) -> list[str]:
    strategies = [s.strip() for s in value.split(",") if s.strip()]
    if not strategies:
        raise SystemExit("At least one strategy is required.")
    return strategies


def parse_name_url_pairs(value: str) -> dict[str, str]:
    pairs: dict[str, str] = {}
    for raw_pair in value.split(","):
        pair = raw_pair.strip()
        if not pair:
            continue
        if "=" not in pair:
            raise SystemExit(f"Invalid name=url pair: '{pair}'")
        name, url = pair.split("=", 1)
        pairs[name.strip()] = url.strip()
    return pairs


def derive_internal_report_url(benchmark_url: str) -> str:
    parts = urlsplit(benchmark_url)
    return urlunsplit((parts.scheme, parts.netloc, "/internal/latency/report", "", ""))


def strategy_url(strategy: str, policy: str, manual_targets: dict[str, str], fallback_url: str) -> str:
    """Resolve the benchmark URL for a (strategy, policy) combination."""
    compound_key = f"{strategy}@{policy}"
    if compound_key in manual_targets:
        return manual_targets[compound_key]
    if strategy in manual_targets:
        return manual_targets[strategy]
    port = FAULT_POLICY_PORTS.get((strategy, policy)) or DEFAULT_STRATEGY_PORTS.get(strategy)
    if port:
        return f"{LOCALHOST}:{port}{DEFAULT_PATH}"
    return fallback_url


def build_strategy_targets(args: argparse.Namespace) -> list[StrategyTarget]:
    manual_targets  = parse_name_url_pairs(args.strategy_targets)
    internal_targets = parse_name_url_pairs(args.internal_report_urls)
    fault_policies   = [p.strip() for p in args.fault_policies.split(",") if p.strip()]

    strategies = (
        list(DEFAULT_STRATEGY_PORTS.keys())
        if args.strategy_matrix
        else selected_strategies(args.strategies)
    )

    targets: list[StrategyTarget] = []

    if args.fault_policy_matrix:
        for strategy in strategies:
            for policy in fault_policies:
                url = strategy_url(strategy, policy, manual_targets, args.url)
                internal_url = (
                    internal_targets.get(f"{strategy}@{policy}")
                    or internal_targets.get(strategy)
                    or args.internal_report_url
                    or derive_internal_report_url(url)
                )
                targets.append(StrategyTarget(
                    name=f"{strategy}@{policy}",
                    benchmark_url=url,
                    internal_report_url=internal_url,
                    fault_policy=policy,
                ))
    else:
        for strategy in strategies:
            policy = "fail-closed"
            url = strategy_url(strategy, policy, manual_targets, args.url)
            internal_url = (
                internal_targets.get(strategy)
                or args.internal_report_url
                or derive_internal_report_url(url)
            )
            targets.append(StrategyTarget(
                name=strategy,
                benchmark_url=url,
                internal_report_url=internal_url,
                fault_policy=policy,
            ))

    return targets


def slug(value: str) -> str:
    return "".join(
        c if c.isalnum() or c in ("-", "_") else "-" for c in value
    ).strip("-")


def fetch_internal_report(url: str, timeout: float) -> dict:
    try:
        response = requests.get(url, timeout=timeout)
        response.raise_for_status()
        return response.json()
    except requests.RequestException as exc:
        return {"error": str(exc)}


def run_benchmark(url: str, timeout: float, scenario: Scenario, output_path: Path) -> None:
    repo_root        = Path(__file__).resolve().parent
    benchmark_script = repo_root / "gateway_latency_benchmark.py"
    command = [
        sys.executable, str(benchmark_script),
        "--url", url,
        "--delay-ms", str(scenario.delay_ms),
        "--requests", str(scenario.requests),
        "--concurrency", str(scenario.concurrency),
        "--timeout", str(timeout),
        "--output", str(output_path),
    ]
    subprocess.run(command, check=True, cwd=repo_root)


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def safe_metric(report: dict, metric_name: str, field: str):
    metric = report.get("metrics", {}).get(metric_name)
    if not metric:
        return None
    return metric.get(field)


def summarize_report(report: dict) -> dict:
    status_counts = report.get("statusCounts", {})
    total    = sum(status_counts.values())
    rejected = int(status_counts.get("429", 0))
    errors   = len(report.get("errors", []))
    return {
        "durationSeconds":             report.get("durationSeconds"),
        "throughputRequestsPerSecond": report.get("throughputRequestsPerSecond"),
        "totalResponses":              total,
        "rejected429":                 rejected,
        "errorCount":                  errors,
        "rejectionRate":               round(rejected / total, 4) if total else None,
        "clientP50Ms":   safe_metric(report, "clientObserved",   "p50Ms"),
        "clientP95Ms":   safe_metric(report, "clientObserved",   "p95Ms"),
        "clientP99Ms":   safe_metric(report, "clientObserved",   "p99Ms"),
        "clientMaxMs":   safe_metric(report, "clientObserved",   "maxMs"),
        "gatewayP50Ms":  safe_metric(report, "gatewayHeader",    "p50Ms"),
        "gatewayP95Ms":  safe_metric(report, "gatewayHeader",    "p95Ms"),
        "gatewayP99Ms":  safe_metric(report, "gatewayHeader",    "p99Ms"),
        "backendP95Ms":  safe_metric(report, "backendHeader",    "p95Ms"),
        "backendP99Ms":  safe_metric(report, "backendHeader",    "p99Ms"),
        "rateLimiterP50Ms": safe_metric(report, "rateLimiterHeader", "p50Ms"),
        "rateLimiterP95Ms": safe_metric(report, "rateLimiterHeader", "p95Ms"),
        "rateLimiterP99Ms": safe_metric(report, "rateLimiterHeader", "p99Ms"),
    }


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    args        = parse_args()
    repo_root   = Path(__file__).resolve().parent
    output_dir  = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = repo_root / output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    scenarios        = selected_scenarios(args.scenarios)
    strategy_targets = build_strategy_targets(args)

    print(f"\n{'='*60}")
    print(f"Experiment matrix: {len(strategy_targets)} targets × {len(scenarios)} scenarios")
    print(f"  Strategies: {[t.name for t in strategy_targets]}")
    print(f"  Scenarios:  {[s.name for s in scenarios]}")
    print(f"  Output dir: {output_dir}")
    print(f"{'='*60}\n")

    manifest: dict = {
        "generatedAt":       datetime.now(timezone.utc).isoformat(),
        "strategyMatrix":    args.strategy_matrix,
        "faultPolicyMatrix": args.fault_policy_matrix,
        "scenarios":         [s.__dict__ for s in scenarios],
        "strategies":        [],
        "comparisons":       [],
    }

    for target in strategy_targets:
        strategy_dir = output_dir / slug(target.name)
        strategy_dir.mkdir(parents=True, exist_ok=True)

        strategy_entry: dict = {
            "name":              target.name,
            "targetUrl":         target.benchmark_url,
            "internalReportUrl": target.internal_report_url,
            "faultPolicy":       target.fault_policy,
            "scenarios":         [],
        }

        for scenario in scenarios:
            output_path = strategy_dir / f"{scenario.name}.json"
            print(f"→ {target.name} / {scenario.name}  ({target.benchmark_url})")

            try:
                run_benchmark(target.benchmark_url, args.timeout, scenario, output_path)
            except subprocess.CalledProcessError as exc:
                print(f"  [WARN] benchmark failed: {exc}")
                continue

            benchmark_report = load_json(output_path)
            summary          = summarize_report(benchmark_report)

            scenario_entry: dict = {
                "name":        scenario.name,
                "delayMs":     scenario.delay_ms,
                "requests":    scenario.requests,
                "concurrency": scenario.concurrency,
                "reportPath":  str(output_path),
                "summary":     summary,
            }

            if not args.skip_internal_report:
                internal_report      = fetch_internal_report(target.internal_report_url, args.timeout)
                internal_report_path = strategy_dir / f"{scenario.name}-gateway-snapshot.json"
                internal_report_path.write_text(
                    json.dumps(internal_report, indent=2), encoding="utf-8"
                )
                scenario_entry["gatewaySnapshotPath"] = str(internal_report_path)

            strategy_entry["scenarios"].append(scenario_entry)
            manifest["comparisons"].append({
                "strategy":    target.name,
                "faultPolicy": target.fault_policy,
                "scenario":    scenario.name,
                **summary,
            })

        manifest["strategies"].append(strategy_entry)

    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"\n✓ Saved experiment manifest → {manifest_path}")
    print(f"  Run: python plot_latency_report.py --manifest {manifest_path}")


if __name__ == "__main__":
    main()
