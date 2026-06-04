#!/usr/bin/env python3
"""Run Redis fault-tolerance policy experiments.

The script benchmarks policy variants while Redis is healthy, then while Redis
is down, then optionally after Redis recovers. It uses a distinct
X-Forwarded-For value per target/phase so rate-limit state does not leak
between phases.
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


STRATEGY_POLICY_URLS = {
    "redis-fixed-window@fail-closed": "http://localhost:8082/api/v1/hello",
    "redis-fixed-window@fail-open": "http://localhost:8090/api/v1/hello",
    "redis-fixed-window@local-fallback": "http://localhost:8091/api/v1/hello",
    "redis-token-bucket@fail-closed": "http://localhost:8083/api/v1/hello",
    "redis-token-bucket@fail-open": "http://localhost:8092/api/v1/hello",
    "redis-token-bucket@local-fallback": "http://localhost:8093/api/v1/hello",
    "redis-sliding-window@fail-closed": "http://localhost:8080/api/v1/hello",
    "redis-sliding-window@fail-open": "http://localhost:8094/api/v1/hello",
    "redis-sliding-window@local-fallback": "http://localhost:8095/api/v1/hello",
}

DEFAULT_POLICIES = ["fail-closed", "fail-open", "local-fallback"]


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
    parser.add_argument("--strategies", default="redis-fixed-window,redis-token-bucket")
    parser.add_argument("--policies", default=",".join(DEFAULT_POLICIES))
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=20)
    parser.add_argument("--delay-ms", type=int, default=0)
    parser.add_argument("--timeout", type=float, default=5.0)
    parser.add_argument("--warmup-requests", type=int, default=0)
    parser.add_argument("--warmup-concurrency", type=int, default=0)
    parser.add_argument("--client-id-prefix", default="fault")
    parser.add_argument(
        "--docker-redis-container",
        default="",
        help="If set, use docker stop/start to control Redis automatically.",
    )
    parser.add_argument(
        "--output",
        default="reports/fault-tolerance/report.json",
    )
    parser.add_argument("--skip-recovery-phase", action="store_true")
    return parser.parse_args()


def selected_values(value: str, label: str) -> list[str]:
    selected = [item.strip() for item in value.split(",") if item.strip()]
    if not selected:
        raise SystemExit(f"At least one {label} is required.")
    return selected


def generated_client_id(prefix: str, target_name: str, phase: str) -> str:
    digest = hashlib.sha1(f"{prefix}:{target_name}:{phase}".encode("utf-8")).hexdigest()
    return f"198.19.{int(digest[0:2], 16)}.{int(digest[2:4], 16)}"


def quick_bench(
    url: str,
    requests_count: int,
    concurrency: int,
    delay_ms: int,
    timeout: float,
    client_id: str,
    warmup_requests: int,
    warmup_concurrency: int,
) -> dict:
    import tempfile

    repo_root = Path(__file__).resolve().parent
    benchmark = repo_root / "gateway_latency_benchmark.py"
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as tmp:
        tmp_path = tmp.name

    command = [
        sys.executable,
        str(benchmark),
        "--url",
        url,
        "--requests",
        str(requests_count),
        "--concurrency",
        str(concurrency),
        "--delay-ms",
        str(delay_ms),
        "--timeout",
        str(timeout),
        "--client-id",
        client_id,
        "--warmup-requests",
        str(warmup_requests),
        "--warmup-concurrency",
        str(warmup_concurrency),
        "--output",
        tmp_path,
    ]
    result = subprocess.run(command, capture_output=True, text=True, cwd=repo_root)
    if result.returncode != 0:
        return {"error": result.stderr or result.stdout}

    try:
        data = json.loads(Path(tmp_path).read_text(encoding="utf-8"))
        Path(tmp_path).unlink(missing_ok=True)
        return data
    except Exception as exc:
        return {"error": str(exc)}


def summarize(report: dict) -> dict:
    if report.get("error"):
        return {
            "totalResponses": 0,
            "rejected429": 0,
            "rejectionRate": None,
            "errorCount": 1,
            "throughputRps": None,
            "gatewayP95Ms": None,
            "rateLimiterP95Ms": None,
            "clientP95Ms": None,
            "error": report["error"],
        }

    def safe_metric(metric_name: str, field: str):
        metric = report.get("metrics", {}).get(metric_name)
        return metric.get(field) if metric else None

    status_counts = report.get("statusCounts", {})
    total = sum(status_counts.values())
    rejected = int(status_counts.get("429", 0))
    return {
        "totalResponses": total,
        "rejected429": rejected,
        "rejectionRate": round(rejected / total, 4) if total else None,
        "errorCount": len(report.get("errors", [])),
        "throughputRps": report.get("throughputRequestsPerSecond"),
        "gatewayP95Ms": safe_metric("gatewayHeader", "p95Ms"),
        "rateLimiterP95Ms": safe_metric("rateLimiterHeader", "p95Ms"),
        "clientP95Ms": safe_metric("clientObserved", "p95Ms"),
    }


def docker_stop(container: str) -> None:
    print(f"[docker] Stopping Redis container: {container}")
    subprocess.run(["docker", "stop", container], check=True, capture_output=True)


def docker_start(container: str) -> None:
    print(f"[docker] Starting Redis container: {container}")
    subprocess.run(["docker", "start", container], check=True, capture_output=True)
    time.sleep(3)


def wait_for_operator(message: str) -> None:
    print(f"\n*** {message}")
    input("Press ENTER when ready...")


def run_phase(phase_name: str, targets: list[tuple[str, str, str]], args: argparse.Namespace) -> list[dict]:
    results = []
    warmup_concurrency = args.warmup_concurrency or args.concurrency
    for strategy, policy, url in targets:
        target_name = f"{strategy}@{policy}"
        client_id = generated_client_id(args.client_id_prefix, target_name, phase_name)
        print(f"[{phase_name}] {target_name} -> {url} client={client_id}")
        raw = quick_bench(
            url,
            args.requests,
            args.concurrency,
            args.delay_ms,
            args.timeout,
            client_id,
            args.warmup_requests,
            warmup_concurrency,
        )
        results.append({
            "strategy": strategy,
            "policy": policy,
            "targetName": target_name,
            "url": url,
            "phase": phase_name,
            "clientId": client_id,
            "summary": summarize(raw),
        })
    return results


def main() -> None:
    args = parse_args()
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    strategies = selected_values(args.strategies, "strategy")
    policies = selected_values(args.policies, "policy")
    targets = [
        (strategy, policy, STRATEGY_POLICY_URLS[f"{strategy}@{policy}"])
        for strategy in strategies
        for policy in policies
        if f"{strategy}@{policy}" in STRATEGY_POLICY_URLS
    ]
    if not targets:
        raise SystemExit("No strategy/policy endpoints resolved.")

    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "requests": args.requests,
        "concurrency": args.concurrency,
        "delayMs": args.delay_ms,
        "warmupRequests": args.warmup_requests,
        "phases": [],
    }

    print("\nPHASE 1: Redis healthy")
    report["phases"].append({
        "name": "redis-healthy",
        "results": run_phase("redis-healthy", targets, args),
    })

    print("\nPHASE 2: Redis down")
    if args.docker_redis_container:
        docker_stop(args.docker_redis_container)
    else:
        wait_for_operator("Stop Redis now, for example: docker stop gateway-redis")
    time.sleep(2)
    report["phases"].append({
        "name": "redis-down",
        "results": run_phase("redis-down", targets, args),
    })

    if not args.skip_recovery_phase:
        print("\nPHASE 3: Redis recovered")
        if args.docker_redis_container:
            docker_start(args.docker_redis_container)
        else:
            wait_for_operator("Start Redis again.")
        time.sleep(2)
        report["phases"].append({
            "name": "redis-recovered",
            "results": run_phase("redis-recovered", targets, args),
        })

    output_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\nSaved fault-tolerance report to {output_path}")


if __name__ == "__main__":
    main()
