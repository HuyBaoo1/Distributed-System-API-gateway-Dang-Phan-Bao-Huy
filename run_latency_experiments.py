#!/usr/bin/env python3
"""Run repeatable API Gateway latency experiments.

The gateway and mock backend should already be running, usually via:

    docker compose up --build -d

This runner supports:
  - strategy comparison: in-memory, redis-fixed-window, redis-sliding-window, redis-token-bucket
  - scenario comparison: baseline, delay-100, delay-500, overload, burst, high-concur
  - optional fault-policy comparison: fail-closed, fail-open, local-fallback
  - repeated trials with per-trial client identity isolation
  - optional warm-up requests before each measured trial

Examples:
    python run_latency_experiments.py --strategy-matrix

    python run_latency_experiments.py --strategy-matrix --trials 3 --warmup-requests 20

    python run_latency_experiments.py --fault-policy-matrix --strategies redis-fixed-window

    python run_latency_experiments.py --strategy-targets redis-fixed-window=http://localhost:8082/api/v1/hello
"""

import argparse
import hashlib
import json
import math
import os
import statistics
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
    strategy: str
    target_name: str
    benchmark_url: str
    internal_report_url: str
    fault_policy: str = "fail-closed"


DEFAULT_SCENARIOS: dict[str, Scenario] = {
    "baseline": Scenario("baseline", delay_ms=0, requests=200, concurrency=20),
    "delay-100": Scenario("delay-100", delay_ms=100, requests=200, concurrency=20),
    "delay-500": Scenario("delay-500", delay_ms=500, requests=200, concurrency=20),
    "overload": Scenario("overload", delay_ms=100, requests=500, concurrency=50),
    "burst": Scenario("burst", delay_ms=0, requests=120, concurrency=120),
    "high-concur": Scenario("high-concur", delay_ms=50, requests=400, concurrency=80),
}

DEFAULT_STRATEGY_PORTS: dict[str, int] = {
    "redis-sliding-window": 8080,
    "redis-fixed-window": 8082,
    "redis-token-bucket": 8083,
    "in-memory": 8084,
}

FAULT_POLICY_PORTS: dict[tuple[str, str], int] = {
    ("redis-fixed-window", "fail-closed"): 8082,
    ("redis-fixed-window", "fail-open"): 8090,
    ("redis-fixed-window", "local-fallback"): 8091,
    ("redis-token-bucket", "fail-closed"): 8083,
    ("redis-token-bucket", "fail-open"): 8092,
    ("redis-token-bucket", "local-fallback"): 8093,
    ("redis-sliding-window", "fail-closed"): 8080,
    ("redis-sliding-window", "fail-open"): 8094,
    ("redis-sliding-window", "local-fallback"): 8095,
    ("in-memory", "fail-closed"): 8084,
}

DEFAULT_PATH = "/api/v1/hello"
LOCALHOST = "http://localhost"


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
        help="Fallback gateway URL when no strategy mapping is found.",
    )
    parser.add_argument(
        "--strategies",
        default=os.getenv("EXPERIMENT_STRATEGIES", "redis-sliding-window"),
        help="Comma-separated strategy labels.",
    )
    parser.add_argument(
        "--strategy-matrix",
        action="store_true",
        help="Run all built-in strategies using docker-compose default ports.",
    )
    parser.add_argument(
        "--fault-policy-matrix",
        action="store_true",
        help="Run configured fault policies for selected Redis strategies.",
    )
    parser.add_argument(
        "--fault-policies",
        default=os.getenv("EXPERIMENT_FAULT_POLICIES", "fail-closed,fail-open,local-fallback"),
        help="Comma-separated policies used with --fault-policy-matrix.",
    )
    parser.add_argument(
        "--strategy-targets",
        default=os.getenv("EXPERIMENT_STRATEGY_TARGETS", ""),
        help="Manual name=url overrides. Keys may be strategy or strategy@policy.",
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
    parser.add_argument("--output-dir", default=os.getenv("EXPERIMENT_OUTPUT_DIR", "reports"))
    parser.add_argument("--timeout", type=float, default=float(os.getenv("BENCHMARK_TIMEOUT_SECONDS", "10")))
    parser.add_argument("--internal-report-url", default=os.getenv("GATEWAY_INTERNAL_REPORT_URL", ""))
    parser.add_argument("--skip-internal-report", action="store_true")
    parser.add_argument(
        "--trials",
        type=int,
        default=int(os.getenv("EXPERIMENT_TRIALS", "1")),
        help="Measured repetitions per target/scenario.",
    )
    parser.add_argument(
        "--warmup-requests",
        type=int,
        default=int(os.getenv("BENCHMARK_WARMUP_REQUESTS", "0")),
        help="Unmeasured warm-up requests before each measured trial.",
    )
    parser.add_argument(
        "--warmup-concurrency",
        type=int,
        default=int(os.getenv("BENCHMARK_WARMUP_CONCURRENCY", "0")),
        help="Warm-up concurrency. Defaults to scenario concurrency when omitted.",
    )
    parser.add_argument(
        "--client-id-prefix",
        default=os.getenv("EXPERIMENT_CLIENT_ID_PREFIX", "exp"),
        help="Prefix used to generate X-Forwarded-For values for state isolation.",
    )
    parser.add_argument(
        "--continue-on-error",
        action="store_true",
        help="Continue the matrix when one benchmark subprocess fails.",
    )
    return parser.parse_args()


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


def selected_values(value: str, label: str) -> list[str]:
    selected = [item.strip() for item in value.split(",") if item.strip()]
    if not selected:
        raise SystemExit(f"At least one {label} is required.")
    return selected


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


def url_for_strategy(
    strategy: str,
    policy: str,
    manual_targets: dict[str, str],
    fallback_url: str,
) -> str | None:
    compound_key = f"{strategy}@{policy}"
    if compound_key in manual_targets:
        return manual_targets[compound_key]
    if strategy in manual_targets and policy == "fail-closed":
        return manual_targets[strategy]

    port = FAULT_POLICY_PORTS.get((strategy, policy))
    if port is None and policy == "fail-closed":
        port = DEFAULT_STRATEGY_PORTS.get(strategy)
    if port is not None:
        return f"{LOCALHOST}:{port}{DEFAULT_PATH}"

    return fallback_url if strategy not in DEFAULT_STRATEGY_PORTS else None


def internal_url_for_target(
    strategy: str,
    policy: str,
    benchmark_url: str,
    internal_targets: dict[str, str],
    fallback_url: str,
) -> str:
    compound_key = f"{strategy}@{policy}"
    return (
        internal_targets.get(compound_key)
        or internal_targets.get(strategy)
        or fallback_url
        or derive_internal_report_url(benchmark_url)
    )


def build_strategy_targets(args: argparse.Namespace) -> list[StrategyTarget]:
    manual_targets = parse_name_url_pairs(args.strategy_targets)
    internal_targets = parse_name_url_pairs(args.internal_report_urls)
    fault_policies = selected_values(args.fault_policies, "fault policy")
    strategies = (
        list(DEFAULT_STRATEGY_PORTS.keys())
        if args.strategy_matrix
        else selected_values(args.strategies, "strategy")
    )

    targets: list[StrategyTarget] = []
    if args.fault_policy_matrix:
        for strategy in strategies:
            for policy in fault_policies:
                benchmark_url = url_for_strategy(strategy, policy, manual_targets, args.url)
                if benchmark_url is None:
                    print(f"[WARN] No endpoint mapping for {strategy}@{policy}; skipping.")
                    continue
                target_name = f"{strategy}@{policy}"
                targets.append(
                    StrategyTarget(
                        strategy=strategy,
                        target_name=target_name,
                        benchmark_url=benchmark_url,
                        internal_report_url=internal_url_for_target(
                            strategy,
                            policy,
                            benchmark_url,
                            internal_targets,
                            args.internal_report_url,
                        ),
                        fault_policy=policy,
                    )
                )
    else:
        for strategy in strategies:
            policy = "fail-closed"
            benchmark_url = url_for_strategy(strategy, policy, manual_targets, args.url)
            if benchmark_url is None:
                print(f"[WARN] No endpoint mapping for {strategy}; skipping.")
                continue
            targets.append(
                StrategyTarget(
                    strategy=strategy,
                    target_name=strategy,
                    benchmark_url=benchmark_url,
                    internal_report_url=internal_url_for_target(
                        strategy,
                        policy,
                        benchmark_url,
                        internal_targets,
                        args.internal_report_url,
                    ),
                    fault_policy=policy,
                )
            )

    if not targets:
        raise SystemExit("No runnable strategy targets were resolved.")
    return targets


def slug(value: str) -> str:
    return "".join(char if char.isalnum() or char in ("-", "_") else "-" for char in value).strip("-")


def fetch_internal_report(url: str, timeout: float) -> dict:
    try:
        response = requests.get(url, timeout=timeout)
        response.raise_for_status()
        return response.json()
    except requests.RequestException as exc:
        return {"error": str(exc)}


def generated_client_id(prefix: str, target_name: str, scenario_name: str, trial: int) -> str:
    seed = f"{prefix}:{target_name}:{scenario_name}:{trial}".encode("utf-8")
    digest = hashlib.sha1(seed).hexdigest()
    # 198.18.0.0/15 is reserved for benchmarking and inter-network testing.
    octet3 = int(digest[0:2], 16)
    octet4 = int(digest[2:4], 16)
    return f"198.18.{octet3}.{octet4}"


def run_benchmark(
    url: str,
    timeout: float,
    scenario: Scenario,
    output_path: Path,
    client_id: str,
    warmup_requests: int,
    warmup_concurrency: int,
) -> None:
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
        "--client-id",
        client_id,
        "--warmup-requests",
        str(warmup_requests),
        "--warmup-concurrency",
        str(warmup_concurrency),
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
        "clientP50Ms": safe_metric(report, "clientObserved", "p50Ms"),
        "clientP95Ms": safe_metric(report, "clientObserved", "p95Ms"),
        "clientP99Ms": safe_metric(report, "clientObserved", "p99Ms"),
        "clientMaxMs": safe_metric(report, "clientObserved", "maxMs"),
        "gatewayP50Ms": safe_metric(report, "gatewayHeader", "p50Ms"),
        "gatewayP95Ms": safe_metric(report, "gatewayHeader", "p95Ms"),
        "gatewayP99Ms": safe_metric(report, "gatewayHeader", "p99Ms"),
        "backendP95Ms": safe_metric(report, "backendHeader", "p95Ms"),
        "backendP99Ms": safe_metric(report, "backendHeader", "p99Ms"),
        "rateLimiterP50Ms": safe_metric(report, "rateLimiterHeader", "p50Ms"),
        "rateLimiterP95Ms": safe_metric(report, "rateLimiterHeader", "p95Ms"),
        "rateLimiterP99Ms": safe_metric(report, "rateLimiterHeader", "p99Ms"),
    }


def numeric_values(trial_summaries: list[dict], field: str) -> list[float]:
    return [
        float(summary[field])
        for summary in trial_summaries
        if isinstance(summary.get(field), (int, float))
    ]


def aggregate_field(trial_summaries: list[dict], field: str):
    values = numeric_values(trial_summaries, field)
    if not values:
        return None
    mean = statistics.mean(values)
    stddev = statistics.stdev(values) if len(values) > 1 else 0.0
    ci95 = 1.96 * stddev / math.sqrt(len(values)) if len(values) > 1 else 0.0
    return {
        "mean": round(mean, 4),
        "min": round(min(values), 4),
        "max": round(max(values), 4),
        "stddev": round(stddev, 4),
        "ci95": round(ci95, 4),
    }


def aggregate_trials(trial_summaries: list[dict]) -> dict:
    fields = [
        "durationSeconds",
        "throughputRequestsPerSecond",
        "rejectionRate",
        "clientP50Ms",
        "clientP95Ms",
        "clientP99Ms",
        "clientMaxMs",
        "gatewayP50Ms",
        "gatewayP95Ms",
        "gatewayP99Ms",
        "backendP95Ms",
        "backendP99Ms",
        "rateLimiterP50Ms",
        "rateLimiterP95Ms",
        "rateLimiterP99Ms",
    ]
    aggregate = {field: aggregate_field(trial_summaries, field) for field in fields}
    aggregate["trialCount"] = len(trial_summaries)
    aggregate["totalResponses"] = sum(int(s.get("totalResponses") or 0) for s in trial_summaries)
    aggregate["rejected429"] = sum(int(s.get("rejected429") or 0) for s in trial_summaries)
    aggregate["errorCount"] = sum(int(s.get("errorCount") or 0) for s in trial_summaries)
    return aggregate


def mean_value(aggregate: dict, field: str):
    value = aggregate.get(field)
    if isinstance(value, dict):
        return value.get("mean")
    return value


def main() -> None:
    args = parse_args()
    repo_root = Path(__file__).resolve().parent
    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = repo_root / output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    trials = max(1, args.trials)
    scenarios = selected_scenarios(args.scenarios)
    strategy_targets = build_strategy_targets(args)

    print("\n" + "=" * 72)
    print(f"Experiment matrix: {len(strategy_targets)} targets x {len(scenarios)} scenarios x {trials} trials")
    print(f"Targets: {[target.target_name for target in strategy_targets]}")
    print(f"Scenarios: {[scenario.name for scenario in scenarios]}")
    print(f"Output dir: {output_dir}")
    print("=" * 72 + "\n")

    manifest: dict = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "strategyMatrix": args.strategy_matrix,
        "faultPolicyMatrix": args.fault_policy_matrix,
        "trialCount": trials,
        "warmupRequests": args.warmup_requests,
        "clientIdentityIsolation": "X-Forwarded-For",
        "scenarios": [scenario.__dict__ for scenario in scenarios],
        "targets": [],
        "strategies": [],
        "comparisons": [],
    }

    for target in strategy_targets:
        target_dir = output_dir / slug(target.target_name)
        target_dir.mkdir(parents=True, exist_ok=True)
        target_entry: dict = {
            "targetName": target.target_name,
            "strategy": target.strategy,
            "faultPolicy": target.fault_policy,
            "targetUrl": target.benchmark_url,
            "internalReportUrl": target.internal_report_url,
            "scenarios": [],
        }

        for scenario in scenarios:
            trial_entries = []
            trial_summaries = []
            scenario_dir = target_dir / scenario.name
            scenario_dir.mkdir(parents=True, exist_ok=True)

            for trial in range(1, trials + 1):
                client_id = generated_client_id(
                    args.client_id_prefix,
                    target.target_name,
                    scenario.name,
                    trial,
                )
                output_path = scenario_dir / f"trial-{trial}.json"
                warmup_concurrency = args.warmup_concurrency or scenario.concurrency

                print(
                    f"[RUN] {target.target_name} / {scenario.name} / trial {trial} "
                    f"client={client_id}"
                )

                try:
                    run_benchmark(
                        target.benchmark_url,
                        args.timeout,
                        scenario,
                        output_path,
                        client_id,
                        args.warmup_requests,
                        warmup_concurrency,
                    )
                except subprocess.CalledProcessError as exc:
                    if args.continue_on_error:
                        trial_entries.append({
                            "trial": trial,
                            "clientId": client_id,
                            "reportPath": str(output_path),
                            "error": str(exc),
                        })
                        print(f"[WARN] Benchmark failed and was skipped: {exc}")
                        continue
                    raise

                benchmark_report = load_json(output_path)
                summary = summarize_report(benchmark_report)
                trial_summaries.append(summary)
                trial_entries.append({
                    "trial": trial,
                    "clientId": client_id,
                    "reportPath": str(output_path),
                    "summary": summary,
                })

            aggregate = aggregate_trials(trial_summaries)
            scenario_entry: dict = {
                "name": scenario.name,
                "delayMs": scenario.delay_ms,
                "requests": scenario.requests,
                "concurrency": scenario.concurrency,
                "trials": trial_entries,
                "summary": aggregate,
            }

            if not args.skip_internal_report:
                internal_report = fetch_internal_report(target.internal_report_url, args.timeout)
                internal_report_path = scenario_dir / "gateway-snapshot.json"
                internal_report_path.write_text(json.dumps(internal_report, indent=2), encoding="utf-8")
                scenario_entry["gatewaySnapshotPath"] = str(internal_report_path)

            target_entry["scenarios"].append(scenario_entry)
            manifest["comparisons"].append({
                "targetName": target.target_name,
                "strategy": target.strategy,
                "faultPolicy": target.fault_policy,
                "scenario": scenario.name,
                "trialCount": aggregate["trialCount"],
                "summary": aggregate,
                "durationSeconds": mean_value(aggregate, "durationSeconds"),
                "throughputRequestsPerSecond": mean_value(aggregate, "throughputRequestsPerSecond"),
                "totalResponses": aggregate["totalResponses"],
                "rejected429": aggregate["rejected429"],
                "errorCount": aggregate["errorCount"],
                "rejectionRate": mean_value(aggregate, "rejectionRate"),
                "clientP50Ms": mean_value(aggregate, "clientP50Ms"),
                "clientP95Ms": mean_value(aggregate, "clientP95Ms"),
                "clientP99Ms": mean_value(aggregate, "clientP99Ms"),
                "clientMaxMs": mean_value(aggregate, "clientMaxMs"),
                "gatewayP50Ms": mean_value(aggregate, "gatewayP50Ms"),
                "gatewayP95Ms": mean_value(aggregate, "gatewayP95Ms"),
                "gatewayP99Ms": mean_value(aggregate, "gatewayP99Ms"),
                "backendP95Ms": mean_value(aggregate, "backendP95Ms"),
                "backendP99Ms": mean_value(aggregate, "backendP99Ms"),
                "rateLimiterP50Ms": mean_value(aggregate, "rateLimiterP50Ms"),
                "rateLimiterP95Ms": mean_value(aggregate, "rateLimiterP95Ms"),
                "rateLimiterP99Ms": mean_value(aggregate, "rateLimiterP99Ms"),
            })

        manifest["targets"].append(target_entry)
        manifest["strategies"].append(target_entry)

    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"\nSaved experiment manifest to {manifest_path}")
    print(f"Run: python plot_latency_report.py --manifest {manifest_path}")


if __name__ == "__main__":
    main()
