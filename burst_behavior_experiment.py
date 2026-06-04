#!/usr/bin/env python3
"""Measure burst behavior around a rate-limit window boundary.

Upgrades over original:
  - --concurrent mode: fire all burst requests simultaneously (ThreadPoolExecutor)
    to stress-test atomic Lua scripts under true concurrency
  - --multi-strategy shorthand: run the experiment against all four default
    gateway ports in one invocation, writing separate JSON per strategy
  - Per-request timestamp tracking for timeline analysis
  - richer summary: p50/p95/p99, accepted counts, retry-after values

Usage examples:

    # Single strategy, align to window boundary
    python burst_behavior_experiment.py \\
        --label redis-fixed-window \\
        --url http://localhost:8082/api/v1/hello \\
        --align-to-window \\
        --output reports/burst/burst-fixed-window.json

    # Concurrent burst (all requests at once)
    python burst_behavior_experiment.py \\
        --label redis-token-bucket \\
        --url http://localhost:8083/api/v1/hello \\
        --concurrent \\
        --align-to-window \\
        --output reports/burst/burst-token-bucket.json

    # Compare all strategies in one run
    python burst_behavior_experiment.py \\
        --multi-strategy \\
        --align-to-window \\
        --output-dir reports/burst
"""

import argparse
import concurrent.futures
import hashlib
import json
import os
import statistics
import time
from datetime import datetime, timezone
from pathlib import Path

import requests


# ---------------------------------------------------------------------------
# Default strategy → port mapping (same as docker-compose.yml)
# ---------------------------------------------------------------------------
DEFAULT_STRATEGY_URLS: dict[str, str] = {
    "redis-sliding-window": "http://localhost:8080/api/v1/hello",
    "redis-fixed-window":   "http://localhost:8082/api/v1/hello",
    "redis-token-bucket":   "http://localhost:8083/api/v1/hello",
    "in-memory":            "http://localhost:8084/api/v1/hello",
}


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
        description="Run a two-phase burst experiment against one or all gateway strategies.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--url",            default=os.getenv("GATEWAY_URL", "http://localhost:8080/api/v1/hello"))
    parser.add_argument("--label",          default=os.getenv("BURST_LABEL", "gateway"))
    parser.add_argument("--window-seconds", type=int,   default=int(os.getenv("RATE_LIMIT_WINDOW_SECONDS", "60")))
    parser.add_argument("--burst-size",     type=int,   default=int(os.getenv("BURST_SIZE", os.getenv("RATE_LIMIT_REQUESTS_PER_MINUTE", "60"))))
    parser.add_argument("--delay-ms",       type=int,   default=int(os.getenv("BACKEND_DELAY_MS", "0")))
    parser.add_argument("--timeout",        type=float, default=float(os.getenv("BENCHMARK_TIMEOUT_SECONDS", "10")))
    parser.add_argument("--gap-ms",         type=int,   default=int(os.getenv("BURST_GAP_MS", "400")))
    parser.add_argument("--pre-boundary-ms",type=int,   default=int(os.getenv("BURST_PRE_BOUNDARY_MS", "200")))
    parser.add_argument("--align-to-window",action="store_true",
                        help="Sleep until ~200ms before the window boundary, then fire burst.")
    parser.add_argument("--concurrent",     action="store_true",
                        help="Fire all burst requests concurrently (ThreadPoolExecutor) instead of sequentially.")
    parser.add_argument("--multi-strategy", action="store_true",
                        help="Run against all four default gateway ports in one invocation.")
    parser.add_argument("--client-id",      default=os.getenv("BURST_CLIENT_ID", ""),
                        help="Optional X-Forwarded-For value for rate-limit state isolation.")
    parser.add_argument("--client-id-prefix", default=os.getenv("BURST_CLIENT_ID_PREFIX", "burst"),
                        help="Prefix used when generating a client id automatically.")
    parser.add_argument("--output",         default=os.getenv("BURST_OUTPUT", "reports/burst_behavior.json"))
    parser.add_argument("--output-dir",     default="reports/burst",
                        help="Output directory used when --multi-strategy is active.")
    return parser.parse_args()


# ---------------------------------------------------------------------------
# Low-level request helper
# ---------------------------------------------------------------------------

def to_float_header(headers, name: str):
    value = headers.get(name)
    if value is None:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def generated_client_id(prefix: str, label: str) -> str:
    digest = hashlib.sha1(f"{prefix}:{label}:{time.time_ns()}".encode("utf-8")).hexdigest()
    return f"198.20.{int(digest[0:2], 16)}.{int(digest[2:4], 16)}"


def request_headers(client_id: str) -> dict[str, str]:
    return {"X-Forwarded-For": client_id} if client_id else {}


def call_gateway(url: str, delay_ms: int, timeout: float,
                 phase: str, sequence: int, client_id: str) -> dict:
    params = {}
    if delay_ms > 0:
        params["delayMs"] = delay_ms

    started    = time.perf_counter()
    wall_start = datetime.now(timezone.utc).isoformat()
    try:
        response   = requests.get(url, params=params, headers=request_headers(client_id), timeout=timeout)
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {
            "phase":              phase,
            "sequence":           sequence,
            "timestampUtc":       wall_start,
            "ok":                 True,
            "status":             response.status_code,
            "clientLatencyMs":    elapsed_ms,
            "gatewayLatencyMs":   to_float_header(response.headers, "X-Gateway-Latency-Ms"),
            "backendLatencyMs":   to_float_header(response.headers, "X-Backend-Latency-Ms"),
            "rateLimitLatencyMs": to_float_header(response.headers, "X-RateLimit-Latency-Ms"),
            "rateLimitRemaining": response.headers.get("X-RateLimit-Remaining"),
            "rateLimitReset":     response.headers.get("X-RateLimit-Reset"),
            "retryAfter":         response.headers.get("Retry-After"),
        }
    except requests.RequestException as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {
            "phase":           phase,
            "sequence":        sequence,
            "timestampUtc":    wall_start,
            "ok":              False,
            "status":          "error",
            "clientLatencyMs": elapsed_ms,
            "error":           str(exc),
        }


# ---------------------------------------------------------------------------
# Burst execution modes
# ---------------------------------------------------------------------------

def run_burst_sequential(url: str, delay_ms: int, timeout: float,
                          burst_size: int, phase: str, client_id: str) -> list[dict]:
    return [
        call_gateway(url, delay_ms, timeout, phase, seq, client_id)
        for seq in range(1, burst_size + 1)
    ]


def run_burst_concurrent(url: str, delay_ms: int, timeout: float,
                          burst_size: int, phase: str, client_id: str) -> list[dict]:
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=burst_size) as executor:
        futures = {
            executor.submit(call_gateway, url, delay_ms, timeout, phase, seq, client_id): seq
            for seq in range(1, burst_size + 1)
        }
        for future in concurrent.futures.as_completed(futures):
            results.append(future.result())
    return sorted(results, key=lambda r: r["sequence"])


def run_burst(url: str, delay_ms: int, timeout: float,
              burst_size: int, phase: str, concurrent: bool, client_id: str) -> list[dict]:
    if concurrent:
        return run_burst_concurrent(url, delay_ms, timeout, burst_size, phase, client_id)
    return run_burst_sequential(url, delay_ms, timeout, burst_size, phase, client_id)


# ---------------------------------------------------------------------------
# Summary helpers
# ---------------------------------------------------------------------------

def percentile(values: list[float], pct: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    idx = max(0, min(len(ordered) - 1, round((pct / 100.0) * (len(ordered) - 1))))
    return ordered[idx]


def summarize(records: list[dict]) -> dict:
    status_counts: dict[str, int] = {}
    latencies: list[float] = []
    retry_afters: list[float] = []

    for r in records:
        status_counts[str(r["status"])] = status_counts.get(str(r["status"]), 0) + 1
        if isinstance(r.get("clientLatencyMs"), (int, float)):
            latencies.append(r["clientLatencyMs"])
        ra = r.get("retryAfter")
        if ra is not None:
            try:
                retry_afters.append(float(ra))
            except ValueError:
                pass

    rejected429 = int(status_counts.get("429", 0))
    total       = len(records)

    return {
        "count":                total,
        "statusCounts":         status_counts,
        "accepted":             total - rejected429 - int(status_counts.get("error", 0)),
        "rejected429":          rejected429,
        "rejectionRate":        round(rejected429 / total, 4) if total else None,
        "clientLatencyAvgMs":   round(statistics.mean(latencies), 2) if latencies else None,
        "clientLatencyP50Ms":   round(percentile(latencies, 50), 2) if latencies else None,
        "clientLatencyP95Ms":   round(percentile(latencies, 95), 2) if latencies else None,
        "clientLatencyP99Ms":   round(percentile(latencies, 99), 2) if latencies else None,
        "retryAfterAvgS":       round(statistics.mean(retry_afters), 2) if retry_afters else None,
    }


# ---------------------------------------------------------------------------
# Window alignment
# ---------------------------------------------------------------------------

def wait_until_boundary(window_seconds: int, pre_boundary_ms: int) -> float:
    if window_seconds <= 0:
        return 0.0
    now              = time.time()
    time_to_boundary = window_seconds - (now % window_seconds)
    sleep_seconds    = max(0.0, time_to_boundary - pre_boundary_ms / 1000.0)
    if sleep_seconds > 0:
        time.sleep(sleep_seconds)
    return sleep_seconds


# ---------------------------------------------------------------------------
# Core experiment runner
# ---------------------------------------------------------------------------

def run_experiment(label: str, url: str, args: argparse.Namespace) -> dict:
    client_id = args.client_id or generated_client_id(args.client_id_prefix, label)
    slept_seconds = 0.0
    if args.align_to_window:
        print(f"  [align] Waiting for window boundary for '{label}'...")
        slept_seconds = wait_until_boundary(args.window_seconds, args.pre_boundary_ms)

    started = time.perf_counter()

    print(f"  [burst1] Firing before-boundary burst ({args.burst_size} req)...")
    first_burst = run_burst(url, args.delay_ms, args.timeout,
                             args.burst_size, "before-boundary", args.concurrent, client_id)

    time.sleep(max(0, args.gap_ms) / 1000.0)

    print(f"  [burst2] Firing after-boundary burst ({args.burst_size} req)...")
    second_burst = run_burst(url, args.delay_ms, args.timeout,
                              args.burst_size, "after-boundary", args.concurrent, client_id)

    duration_seconds = time.perf_counter() - started
    records          = first_burst + second_burst

    return {
        "generatedAt":            datetime.now(timezone.utc).isoformat(),
        "label":                  label,
        "targetUrl":              url,
        "clientId":               client_id,
        "configuredBackendDelayMs": args.delay_ms,
        "windowSeconds":          args.window_seconds,
        "burstSize":              args.burst_size,
        "gapMs":                  args.gap_ms,
        "alignToWindow":          args.align_to_window,
        "concurrentMode":         args.concurrent,
        "sleptBeforeStartSeconds": round(slept_seconds, 3),
        "durationSeconds":        round(duration_seconds, 3),
        "summary": {
            "all":            summarize(records),
            "beforeBoundary": summarize(first_burst),
            "afterBoundary":  summarize(second_burst),
        },
        "records": records,
    }


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> None:
    args = parse_args()

    if args.multi_strategy:
        out_dir = Path(args.output_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        for label, url in DEFAULT_STRATEGY_URLS.items():
            print(f"\n=== Burst experiment: {label} ===")
            report = run_experiment(label, url, args)
            path   = out_dir / f"burst-{label}.json"
            path.write_text(json.dumps(report, indent=2), encoding="utf-8")
            print(f"  Saved → {path}")
            print(json.dumps(report["summary"], indent=2))
    else:
        out_path = Path(args.output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        print(f"\n=== Burst experiment: {args.label} ===")
        report = run_experiment(args.label, args.url, args)
        out_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report["summary"], indent=2))
        print(f"\nSaved burst report → {out_path}")


if __name__ == "__main__":
    main()
