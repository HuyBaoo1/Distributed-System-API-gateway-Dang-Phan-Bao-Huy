#!/usr/bin/env python3
"""Fault-tolerance policy experiment: simulate Redis failure and measure behavior.

This script:
1. Runs a baseline benchmark against all three fault policies (fail-closed,
   fail-open, local-fallback) while Redis is **healthy**.
2. Pauses and instructs the operator to stop Redis (or optionally does it
   via docker stop if --docker-redis-container is provided).
3. Re-runs the same benchmark with Redis **down**.
4. Restarts Redis (if managed) and records recovery behavior.
5. Writes a structured JSON report comparing all conditions.

Usage:
    # Manual Redis control
    python fault_tolerance_experiment.py \\
        --strategies redis-fixed-window,redis-token-bucket \\
        --output reports/fault-tolerance/report.json

    # Automatic Redis control via docker
    python fault_tolerance_experiment.py \\
        --docker-redis-container gateway-redis \\
        --strategies redis-fixed-window \\
        --output reports/fault-tolerance/report.json
"""

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests


# Default strategy → (port, fault_policy) mappings matching docker-compose.yml
STRATEGY_POLICY_URLS = {
    "redis-fixed-window@fail-closed":    "http://localhost:8082/api/v1/hello",
    "redis-fixed-window@fail-open":      "http://localhost:8090/api/v1/hello",
    "redis-fixed-window@local-fallback": "http://localhost:8091/api/v1/hello",
    "redis-token-bucket@fail-closed":    "http://localhost:8083/api/v1/hello",
    "redis-token-bucket@fail-open":      "http://localhost:8092/api/v1/hello",
    "redis-token-bucket@local-fallback": "http://localhost:8093/api/v1/hello",
    "redis-sliding-window@fail-closed":  "http://localhost:8080/api/v1/hello",
    "redis-sliding-window@fail-open":    "http://localhost:8094/api/v1/hello",
    "redis-sliding-window@local-fallback":"http://localhost:8095/api/v1/hello",
}

POLICIES = ["fail-closed", "fail-open", "local-fallback"]


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
    parser = argparse.ArgumentParser(description="Fault-tolerance policy experiment.")
    parser.add_argument("--strategies",
                        default="redis-fixed-window,redis-token-bucket",
                        help="Comma-separated base strategy names.")
    parser.add_argument("--requests",    type=int,   default=100)
    parser.add_argument("--concurrency", type=int,   default=20)
    parser.add_argument("--delay-ms",    type=int,   default=0)
    parser.add_argument("--timeout",     type=float, default=5.0)
    parser.add_argument("--docker-redis-container", default="",
                        help="If set, use `docker stop/start` to control Redis automatically.")
    parser.add_argument("--redis-down-seconds", type=int, default=15,
                        help="Seconds to keep Redis down during the fault injection phase.")
    parser.add_argument("--output",      default="reports/fault-tolerance/report.json")
    parser.add_argument("--skip-recovery-phase", action="store_true")
    return parser.parse_args()


def quick_bench(url: str, n_requests: int, concurrency: int,
                delay_ms: int, timeout: float) -> dict:
    """Run benchmark subprocess and return parsed JSON report."""
    import tempfile
    repo_root = Path(__file__).resolve().parent
    benchmark = repo_root / "gateway_latency_benchmark.py"
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as tmp:
        tmp_path = tmp.name
    cmd = [
        sys.executable, str(benchmark),
        "--url", url,
        "--requests", str(n_requests),
        "--concurrency", str(concurrency),
        "--delay-ms", str(delay_ms),
        "--timeout", str(timeout),
        "--output", tmp_path,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, cwd=repo_root)
    if result.returncode != 0:
        return {"error": result.stderr or result.stdout}
    try:
        data = json.loads(Path(tmp_path).read_text(encoding="utf-8"))
        Path(tmp_path).unlink(missing_ok=True)
        return data
    except Exception as exc:
        return {"error": str(exc)}


def summarize(report: dict) -> dict:
    def safe(m, f):
        mm = report.get("metrics", {}).get(m)
        return mm.get(f) if mm else None

    sc    = report.get("statusCounts", {})
    total = sum(sc.values()) or 1
    r429  = int(sc.get("429", 0))
    return {
        "totalResponses": total,
        "rejected429":    r429,
        "rejectionRate":  round(r429 / total, 4),
        "errorCount":     len(report.get("errors", [])),
        "throughputRps":  report.get("throughputRequestsPerSecond"),
        "gatewayP95Ms":   safe("gatewayHeader",    "p95Ms"),
        "rateLimP95Ms":   safe("rateLimiterHeader", "p95Ms"),
        "clientP95Ms":    safe("clientObserved",    "p95Ms"),
    }


def docker_stop_redis(container: str) -> None:
    print(f"  [docker] Stopping Redis container '{container}'...")
    subprocess.run(["docker", "stop", container], check=True, capture_output=True)


def docker_start_redis(container: str) -> None:
    print(f"  [docker] Starting Redis container '{container}'...")
    subprocess.run(["docker", "start", container], check=True, capture_output=True)
    time.sleep(3)  # give Redis time to accept connections


def wait_for_operator(message: str) -> None:
    print(f"\n  *** {message}")
    input("  Press ENTER when ready...")


def main() -> None:
    args       = parse_args()
    out_path   = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    strategies = [s.strip() for s in args.strategies.split(",") if s.strip()]
    targets    = [
        (strategy, policy, STRATEGY_POLICY_URLS.get(f"{strategy}@{policy}", ""))
        for strategy in strategies
        for policy in POLICIES
        if f"{strategy}@{policy}" in STRATEGY_POLICY_URLS
    ]

    print(f"\n{'='*60}")
    print(f"Fault-Tolerance Experiment")
    print(f"  Strategies: {strategies}")
    print(f"  Policies: {POLICIES}")
    print(f"  Requests per run: {args.requests}, concurrency: {args.concurrency}")
    print(f"{'='*60}\n")

    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "phases": [],
    }

    # -----------------------------------------------------------------------
    # Phase 1: Redis healthy baseline
    # -----------------------------------------------------------------------
    print("PHASE 1: Redis HEALTHY baseline\n")
    phase1_results = []
    for strategy, policy, url in targets:
        if not url:
            continue
        print(f"  [{strategy}@{policy}] benching {url}")
        raw    = quick_bench(url, args.requests, args.concurrency, args.delay_ms, args.timeout)
        result = {
            "strategy": strategy,
            "policy":   policy,
            "url":      url,
            "phase":    "redis-healthy",
            "summary":  summarize(raw),
        }
        phase1_results.append(result)
        print(f"    → rejection_rate={result['summary']['rejectionRate']}, "
              f"client_p95={result['summary']['clientP95Ms']}")

    report["phases"].append({"name": "redis-healthy", "results": phase1_results})

    # -----------------------------------------------------------------------
    # Phase 2: Redis DOWN
    # -----------------------------------------------------------------------
    print("\nPHASE 2: Redis DOWN\n")

    if args.docker_redis_container:
        docker_stop_redis(args.docker_redis_container)
    else:
        wait_for_operator("Please STOP Redis now (e.g. docker stop gateway-redis).")

    time.sleep(2)  # let connections time out

    phase2_results = []
    for strategy, policy, url in targets:
        if not url:
            continue
        print(f"  [{strategy}@{policy}] benching {url}")
        raw    = quick_bench(url, args.requests, args.concurrency, args.delay_ms, args.timeout)
        result = {
            "strategy": strategy,
            "policy":   policy,
            "url":      url,
            "phase":    "redis-down",
            "summary":  summarize(raw),
        }
        phase2_results.append(result)
        print(f"    → rejection_rate={result['summary']['rejectionRate']}, "
              f"error_count={result['summary']['errorCount']}")

    report["phases"].append({"name": "redis-down", "results": phase2_results})

    # -----------------------------------------------------------------------
    # Phase 3: Recovery (optional)
    # -----------------------------------------------------------------------
    if not args.skip_recovery_phase:
        print("\nPHASE 3: Redis RECOVERY\n")

        if args.docker_redis_container:
            docker_start_redis(args.docker_redis_container)
        else:
            wait_for_operator("Please START Redis again.")

        time.sleep(2)

        phase3_results = []
        for strategy, policy, url in targets:
            if not url:
                continue
            print(f"  [{strategy}@{policy}] benching {url}")
            raw    = quick_bench(url, args.requests, args.concurrency, args.delay_ms, args.timeout)
            result = {
                "strategy": strategy,
                "policy":   policy,
                "url":      url,
                "phase":    "redis-recovered",
                "summary":  summarize(raw),
            }
            phase3_results.append(result)
            print(f"    → rejection_rate={result['summary']['rejectionRate']}")

        report["phases"].append({"name": "redis-recovered", "results": phase3_results})

    # -----------------------------------------------------------------------
    # Comparison summary table
    # -----------------------------------------------------------------------
    print("\n" + "="*60)
    print("COMPARISON SUMMARY")
    print("="*60)
    fmt = "{:<35} {:<10} {:<12} {:<12} {:<12}"
    print(fmt.format("Target", "Phase", "RejRate", "ClientP95", "ErrCount"))
    print("-"*80)
    for phase in report["phases"]:
        for r in phase["results"]:
            s = r["summary"]
            print(fmt.format(
                f"{r['strategy']}@{r['policy']}",
                phase["name"],
                str(s.get("rejectionRate", "N/A")),
                str(s.get("clientP95Ms",   "N/A")),
                str(s.get("errorCount",    "N/A")),
            ))

    out_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\nSaved fault-tolerance report → {out_path}")


if __name__ == "__main__":
    main()
