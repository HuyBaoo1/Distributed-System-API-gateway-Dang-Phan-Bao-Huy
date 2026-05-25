#!/usr/bin/env python3
"""Benchmark API Gateway latency and summarize the impact of backend latency.

Examples:
  python gateway_latency_benchmark.py --url http://localhost:8080/api/v1/hello --requests 200 --concurrency 20
  python gateway_latency_benchmark.py --delay-ms 100 --requests 200 --concurrency 20
"""

import argparse
import concurrent.futures
import json
import os
import statistics
import time
from pathlib import Path

import requests


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
    parser = argparse.ArgumentParser(description="Measure API Gateway latency under controlled load.")
    parser.add_argument("--url", default=os.getenv("GATEWAY_URL", "http://localhost:8080/api/v1/hello"))
    parser.add_argument("--requests", type=int, default=int(os.getenv("BENCHMARK_REQUESTS", "200")))
    parser.add_argument("--concurrency", type=int, default=int(os.getenv("BENCHMARK_CONCURRENCY", "20")))
    parser.add_argument("--delay-ms", type=int, default=int(os.getenv("BACKEND_DELAY_MS", "0")))
    parser.add_argument("--timeout", type=float, default=float(os.getenv("BENCHMARK_TIMEOUT_SECONDS", "10")))
    parser.add_argument("--output", default=os.getenv("BENCHMARK_OUTPUT", "latency_report.json"))
    return parser.parse_args()


def percentile(values, percent):
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round((percent / 100.0) * (len(ordered) - 1))))
    return ordered[index]


def to_float_header(headers, name):
    value = headers.get(name)
    if value is None:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def call_gateway(url, delay_ms, timeout):
    params = {}
    if delay_ms > 0:
        params["delayMs"] = delay_ms

    started = time.perf_counter()
    try:
        response = requests.get(url, params=params, timeout=timeout)
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {
            "ok": True,
            "status": response.status_code,
            "clientLatencyMs": elapsed_ms,
            "gatewayLatencyMs": to_float_header(response.headers, "X-Gateway-Latency-Ms"),
            "backendLatencyMs": to_float_header(response.headers, "X-Backend-Latency-Ms"),
            "rateLimitLatencyMs": to_float_header(response.headers, "X-RateLimit-Latency-Ms"),
            "rateLimitRemaining": response.headers.get("X-RateLimit-Remaining"),
        }
    except requests.RequestException as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {
            "ok": False,
            "status": "error",
            "clientLatencyMs": elapsed_ms,
            "error": str(exc),
        }


def summarize_metric(results, field):
    values = [item[field] for item in results if isinstance(item.get(field), (int, float))]
    if not values:
        return None
    return {
        "count": len(values),
        "avgMs": round(statistics.mean(values), 2),
        "p50Ms": round(percentile(values, 50), 2),
        "p95Ms": round(percentile(values, 95), 2),
        "p99Ms": round(percentile(values, 99), 2),
        "maxMs": round(max(values), 2),
    }


def main():
    args = parse_args()
    started = time.perf_counter()
    results = []

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [
            executor.submit(call_gateway, args.url, args.delay_ms, args.timeout)
            for _ in range(args.requests)
        ]
        for future in concurrent.futures.as_completed(futures):
            results.append(future.result())

    duration_seconds = time.perf_counter() - started
    status_counts = {}
    for item in results:
        status_counts[str(item["status"])] = status_counts.get(str(item["status"]), 0) + 1

    report = {
        "targetUrl": args.url,
        "requests": args.requests,
        "concurrency": args.concurrency,
        "configuredBackendDelayMs": args.delay_ms,
        "durationSeconds": round(duration_seconds, 2),
        "throughputRequestsPerSecond": round(len(results) / duration_seconds, 2) if duration_seconds > 0 else 0,
        "statusCounts": status_counts,
        "metrics": {
            "clientObserved": summarize_metric(results, "clientLatencyMs"),
            "gatewayHeader": summarize_metric(results, "gatewayLatencyMs"),
            "backendHeader": summarize_metric(results, "backendLatencyMs"),
            "rateLimiterHeader": summarize_metric(results, "rateLimitLatencyMs"),
        },
        "errors": [item.get("error") for item in results if item.get("error")],
    }

    Path(args.output).write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"\nSaved report to {args.output}")


if __name__ == "__main__":
    main()
