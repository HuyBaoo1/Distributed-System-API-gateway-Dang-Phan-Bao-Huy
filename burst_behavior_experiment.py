#!/usr/bin/env python3
"""Measure burst behavior around a rate-limit window boundary.

This experiment is useful for comparing fixed-window, sliding-window, and
token-bucket limiters under short request bursts. It does not invent results:
the output JSON records every observed response and derived summaries only.
"""

import argparse
import json
import os
import statistics
import time
from datetime import datetime, timezone
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
    parser = argparse.ArgumentParser(description="Run a two-phase burst experiment against one gateway.")
    parser.add_argument("--url", default=os.getenv("GATEWAY_URL", "http://localhost:8080/api/v1/hello"))
    parser.add_argument("--label", default=os.getenv("BURST_LABEL", "gateway"))
    parser.add_argument("--window-seconds", type=int, default=int(os.getenv("RATE_LIMIT_WINDOW_SECONDS", "60")))
    parser.add_argument("--burst-size", type=int, default=int(os.getenv("BURST_SIZE", os.getenv("RATE_LIMIT_REQUESTS_PER_MINUTE", "60"))))
    parser.add_argument("--delay-ms", type=int, default=int(os.getenv("BACKEND_DELAY_MS", "0")))
    parser.add_argument("--timeout", type=float, default=float(os.getenv("BENCHMARK_TIMEOUT_SECONDS", "10")))
    parser.add_argument("--gap-ms", type=int, default=int(os.getenv("BURST_GAP_MS", "400")))
    parser.add_argument("--pre-boundary-ms", type=int, default=int(os.getenv("BURST_PRE_BOUNDARY_MS", "200")))
    parser.add_argument("--align-to-window", action="store_true")
    parser.add_argument("--output", default=os.getenv("BURST_OUTPUT", "reports/burst_behavior.json"))
    return parser.parse_args()


def to_float_header(headers, name):
    value = headers.get(name)
    if value is None:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def percentile(values, percent):
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round((percent / 100.0) * (len(ordered) - 1))))
    return ordered[index]


def summarize(records):
    status_counts = {}
    latencies = []
    for record in records:
        status_counts[str(record["status"])] = status_counts.get(str(record["status"]), 0) + 1
        if isinstance(record.get("clientLatencyMs"), (int, float)):
            latencies.append(record["clientLatencyMs"])

    return {
        "count": len(records),
        "statusCounts": status_counts,
        "clientLatencyAvgMs": round(statistics.mean(latencies), 2) if latencies else None,
        "clientLatencyP95Ms": round(percentile(latencies, 95), 2) if latencies else None,
        "rejected429": int(status_counts.get("429", 0)),
    }


def wait_until_boundary(window_seconds, pre_boundary_ms):
    if window_seconds <= 0:
        return 0
    now = time.time()
    time_to_boundary = window_seconds - (now % window_seconds)
    sleep_seconds = max(0, time_to_boundary - pre_boundary_ms / 1000.0)
    if sleep_seconds > 0:
        time.sleep(sleep_seconds)
    return sleep_seconds


def call_gateway(url, delay_ms, timeout, phase, sequence):
    params = {}
    if delay_ms > 0:
        params["delayMs"] = delay_ms

    started = time.perf_counter()
    wall_started = datetime.now(timezone.utc).isoformat()
    try:
        response = requests.get(url, params=params, timeout=timeout)
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {
            "phase": phase,
            "sequence": sequence,
            "timestampUtc": wall_started,
            "ok": True,
            "status": response.status_code,
            "clientLatencyMs": elapsed_ms,
            "gatewayLatencyMs": to_float_header(response.headers, "X-Gateway-Latency-Ms"),
            "backendLatencyMs": to_float_header(response.headers, "X-Backend-Latency-Ms"),
            "rateLimitLatencyMs": to_float_header(response.headers, "X-RateLimit-Latency-Ms"),
            "rateLimitRemaining": response.headers.get("X-RateLimit-Remaining"),
            "rateLimitReset": response.headers.get("X-RateLimit-Reset"),
        }
    except requests.RequestException as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {
            "phase": phase,
            "sequence": sequence,
            "timestampUtc": wall_started,
            "ok": False,
            "status": "error",
            "clientLatencyMs": elapsed_ms,
            "error": str(exc),
        }


def run_burst(url, delay_ms, timeout, burst_size, phase):
    return [
        call_gateway(url, delay_ms, timeout, phase, sequence)
        for sequence in range(1, burst_size + 1)
    ]


def main():
    args = parse_args()
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)

    slept_seconds = 0
    if args.align_to_window:
        slept_seconds = wait_until_boundary(args.window_seconds, args.pre_boundary_ms)

    started = time.perf_counter()
    first_burst = run_burst(args.url, args.delay_ms, args.timeout, args.burst_size, "before-boundary")
    time.sleep(max(0, args.gap_ms) / 1000.0)
    second_burst = run_burst(args.url, args.delay_ms, args.timeout, args.burst_size, "after-boundary")
    duration_seconds = time.perf_counter() - started

    records = first_burst + second_burst
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "label": args.label,
        "targetUrl": args.url,
        "configuredBackendDelayMs": args.delay_ms,
        "windowSeconds": args.window_seconds,
        "burstSize": args.burst_size,
        "gapMs": args.gap_ms,
        "alignToWindow": args.align_to_window,
        "sleptBeforeStartSeconds": round(slept_seconds, 3),
        "durationSeconds": round(duration_seconds, 3),
        "summary": {
            "all": summarize(records),
            "beforeBoundary": summarize(first_burst),
            "afterBoundary": summarize(second_burst),
        },
        "records": records,
    }

    Path(args.output).write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report["summary"], indent=2))
    print(f"\nSaved burst report to {args.output}")


if __name__ == "__main__":
    main()
