#!/usr/bin/env python3
"""Compare gateway latency between two deployments (e.g., in-memory vs redis-sliding-window).

Usage example:
  # Ensure .env contains GATEWAY_URL and GATEWAY_URL_ALT or pass URLs directly
  python compare_strategies.py --url-a http://localhost:8080/api/v1/hello --label-a redis --url-b http://localhost:8082/api/v1/hello --label-b inmemory --requests 200 --concurrency 20 --delay-ms 100 --output compare_report.json

The script runs concurrent requests against each target and summarizes clientObserved, gatewayHeader, backendHeader, rateLimiterHeader metrics.
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
    p = Path(path)
    if not p.exists():
        return
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


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
    except Exception:
        return None


def call_gateway(url, delay_ms, timeout):
    params = {}
    if delay_ms > 0:
        params["delayMs"] = delay_ms
    started = time.perf_counter()
    try:
        r = requests.get(url, params=params, timeout=timeout)
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {
            "ok": True,
            "status": r.status_code,
            "clientLatencyMs": elapsed_ms,
            "gatewayLatencyMs": to_float_header(r.headers, "X-Gateway-Latency-Ms"),
            "backendLatencyMs": to_float_header(r.headers, "X-Backend-Latency-Ms"),
            "rateLimitLatencyMs": to_float_header(r.headers, "X-RateLimit-Latency-Ms"),
        }
    except requests.RequestException as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {"ok": False, "status": "error", "clientLatencyMs": elapsed_ms, "error": str(exc)}


def summarize_metric(results, field):
    vals = [it[field] for it in results if isinstance(it.get(field), (int, float))]
    if not vals:
        return None
    return {
        "count": len(vals),
        "avgMs": round(statistics.mean(vals), 2),
        "p50Ms": round(percentile(vals, 50), 2),
        "p95Ms": round(percentile(vals, 95), 2),
        "p99Ms": round(percentile(vals, 99), 2),
        "maxMs": round(max(vals), 2),
    }


def run_benchmark(url, requests_count, concurrency, delay_ms, timeout):
    results = []
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as ex:
        futures = [ex.submit(call_gateway, url, delay_ms, timeout) for _ in range(requests_count)]
        for fut in concurrent.futures.as_completed(futures):
            results.append(fut.result())
    duration = time.perf_counter() - started
    status_counts = {}
    for it in results:
        status_counts[str(it.get("status"))] = status_counts.get(str(it.get("status")), 0) + 1
    report = {
        "targetUrl": url,
        "requests": requests_count,
        "concurrency": concurrency,
        "configuredBackendDelayMs": delay_ms,
        "durationSeconds": round(duration, 2),
        "throughputRps": round(len(results) / duration, 2) if duration > 0 else 0,
        "statusCounts": status_counts,
        "metrics": {
            "clientObserved": summarize_metric(results, "clientLatencyMs"),
            "gatewayHeader": summarize_metric(results, "gatewayLatencyMs"),
            "backendHeader": summarize_metric(results, "backendLatencyMs"),
            "rateLimiterHeader": summarize_metric(results, "rateLimitLatencyMs"),
        },
        "errors": [it.get("error") for it in results if it.get("error")],
    }
    return report


def compare_reports(a, b):
    return {"a": a, "b": b}


def parse_args():
    load_env_file()
    parser = argparse.ArgumentParser(description="Compare two gateway deployments (e.g., redis vs in-memory)")
    parser.add_argument("--url-a", default=os.getenv("GATEWAY_URL"))
    parser.add_argument("--label-a", default=os.getenv("LABEL_A", "a"))
    parser.add_argument("--url-b", default=os.getenv("GATEWAY_URL_ALT"))
    parser.add_argument("--label-b", default=os.getenv("LABEL_B", "b"))
    parser.add_argument("--requests", type=int, default=int(os.getenv("BENCHMARK_REQUESTS", "100")))
    parser.add_argument("--concurrency", type=int, default=int(os.getenv("BENCHMARK_CONCURRENCY", "10")))
    parser.add_argument("--delay-ms", type=int, default=int(os.getenv("BACKEND_DELAY_MS", "0")))
    parser.add_argument("--timeout", type=float, default=float(os.getenv("BENCHMARK_TIMEOUT_SECONDS", "10")))
    parser.add_argument("--output", default=os.getenv("BENCHMARK_OUTPUT", "compare_report.json"))
    return parser.parse_args()


def main():
    args = parse_args()
    if not args.url_a or not args.url_b:
        print("Both --url-a and --url-b must be provided (or set GATEWAY_URL and GATEWAY_URL_ALT in .env)")
        return
    print(f"Running benchmark A ({args.label_a}): {args.url_a}")
    rep_a = run_benchmark(args.url_a, args.requests, args.concurrency, args.delay_ms, args.timeout)
    print(f"Running benchmark B ({args.label_b}): {args.url_b}")
    rep_b = run_benchmark(args.url_b, args.requests, args.concurrency, args.delay_ms, args.timeout)
    combined = {
        "labelA": args.label_a,
        "labelB": args.label_b,
        "reportA": rep_a,
        "reportB": rep_b,
        "comparisonSummary": {
            "gatewayHeaderDiffP95Ms": (rep_a["metrics"]["gatewayHeader"]["p95Ms"] if rep_a["metrics"]["gatewayHeader"] else None) - (rep_b["metrics"]["gatewayHeader"]["p95Ms"] if rep_b["metrics"]["gatewayHeader"] else 0)
        }
    }
    Path(args.output).write_text(json.dumps(combined, indent=2), encoding="utf-8")
    print(json.dumps(combined, indent=2))
    print(f"Saved combined report to {args.output}")


if __name__ == "__main__":
    main()
