#!/usr/bin/env python3
"""Validate GateShield distributed rate limiting through concurrent requests."""

import argparse
import concurrent.futures
import json
import os
import statistics
import sys
import time
import uuid

import requests


def parse_args():
    parser = argparse.ArgumentParser(description="GateShield distributed rate-limit validation")
    parser.add_argument("--base-url", default=os.getenv("BASE_URL", os.getenv("GATEWAY_BASE_URL", "http://localhost:8100")))
    parser.add_argument("--admin-base-url", default=os.getenv("ADMIN_BASE_URL", os.getenv("GATEWAY_BASE_URL", "http://localhost:8100")))
    parser.add_argument("--admin-token", default=os.getenv("GATESHIELD_ADMIN_TOKEN", ""))
    parser.add_argument("--api-key", default=os.getenv("API_KEY", os.getenv("GATESHIELD_API_KEY", "")))
    parser.add_argument("--route-path", default=os.getenv("ROUTE_PATH", "/api/v1/hello"))
    parser.add_argument("--route-id", default=os.getenv("ROUTE_ID", "stage1-distributed"))
    parser.add_argument("--tenant-id", default=os.getenv("TENANT_ID", "stage1-" + uuid.uuid4().hex[:12]))
    parser.add_argument("--target-url", default=os.getenv("TARGET_URL", "http://mock-backend:8081"))
    parser.add_argument("--total-requests", type=int, default=int(os.getenv("TOTAL_REQUESTS", "60")))
    parser.add_argument("--concurrency", type=int, default=int(os.getenv("CONCURRENCY", "30")))
    parser.add_argument("--expected-limit", type=int, default=int(os.getenv("EXPECTED_LIMIT", "12")))
    parser.add_argument("--window-seconds", type=int, default=int(os.getenv("WINDOW_SECONDS", "60")))
    parser.add_argument("--strategy", default=os.getenv("STRATEGY", os.getenv("SCALED_RATE_LIMIT_STRATEGY", "redis-sliding-window")))
    parser.add_argument("--replicas", type=int, default=int(os.getenv("GATEWAY_REPLICAS", "3")))
    parser.add_argument("--timeout", type=float, default=float(os.getenv("REQUEST_TIMEOUT_SECONDS", "10")))
    parser.add_argument("--allow-overshoot", type=int, default=int(os.getenv("ALLOW_OVERSHOOT", "0")))
    parser.add_argument("--skip-setup", action="store_true", default=os.getenv("SKIP_SETUP", "").lower() == "true")
    return parser.parse_args()


def request_json(method, url, headers=None, body=None, timeout=10):
    response = requests.request(method, url, headers=headers, json=body, timeout=timeout)
    if response.status_code >= 400:
        raise RuntimeError(f"{method} {url} failed with {response.status_code}: {response.text[:200]}")
    return response.json() if response.content else {}


def setup(args):
    if args.skip_setup:
        if not args.api_key:
            raise RuntimeError("--api-key or API_KEY is required when setup is skipped")
        return args.api_key
    if not args.admin_token:
        raise RuntimeError("GATESHIELD_ADMIN_TOKEN is required to create tenant and route")

    headers = {"X-Admin-Token": args.admin_token, "Content-Type": "application/json"}
    route_body = {
        "routeId": args.route_id,
        "pathPattern": args.route_path,
        "targetUrl": args.target_url,
        "allowedMethods": ["GET"],
        "enabled": True,
        "rateLimitRequests": args.expected_limit,
        "rateLimitWindowSeconds": args.window_seconds,
    }
    route_url = f"{args.admin_base_url}/admin/routes"
    try:
        request_json("POST", route_url, headers, route_body, args.timeout)
    except RuntimeError:
        request_json("PUT", f"{route_url}/{args.route_id}", headers, route_body, args.timeout)

    tenant_body = {
        "id": args.tenant_id,
        "name": "Stage 1 Distributed Validation",
        "planName": "stage1",
        "enabled": True,
    }
    tenant = request_json("POST", f"{args.admin_base_url}/admin/tenants", headers, tenant_body, args.timeout)
    api_key = tenant.get("apiKey")
    if not api_key:
        raise RuntimeError("tenant creation did not return a one-time API key")
    return api_key


def percentile(values, pct):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round((pct / 100.0) * (len(ordered) - 1))))
    return ordered[index]


def call_once(url, api_key, timeout):
    started = time.perf_counter()
    try:
        response = requests.get(url, headers={"X-API-Key": api_key}, timeout=timeout)
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {
            "status": response.status_code,
            "latency_ms": elapsed_ms,
            "instance": response.headers.get("X-Gateway-Instance-Id", "unknown"),
            "remaining": response.headers.get("X-RateLimit-Remaining"),
        }
    except requests.RequestException as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {"status": "error", "latency_ms": elapsed_ms, "instance": "unknown", "error": str(exc)}


def run_load(args, api_key):
    url = f"{args.base_url}{args.route_path}"
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [executor.submit(call_once, url, api_key, args.timeout) for _ in range(args.total_requests)]
        for future in concurrent.futures.as_completed(futures):
            results.append(future.result())
    return results


def summarize(args, results):
    latencies = [item["latency_ms"] for item in results if isinstance(item.get("latency_ms"), (int, float))]
    allowed = sum(1 for item in results if item.get("status") == 200)
    rejected = sum(1 for item in results if item.get("status") == 429)
    unexpected = [item for item in results if item.get("status") not in (200, 429)]
    instances = sorted({item.get("instance", "unknown") for item in results if item.get("instance")})
    overshoot = max(0, allowed - args.expected_limit)
    status_counts = {}
    for item in results:
        key = str(item.get("status"))
        status_counts[key] = status_counts.get(key, 0) + 1
    return {
        "replicasExpected": args.replicas,
        "instancesObserved": instances,
        "strategy": args.strategy,
        "configuredLimit": args.expected_limit,
        "totalRequests": args.total_requests,
        "concurrency": args.concurrency,
        "allowed": allowed,
        "rejected429": rejected,
        "unexpectedErrors": len(unexpected),
        "quotaOvershoot": overshoot,
        "statusCounts": status_counts,
        "latencyMs": {
            "p50": round(percentile(latencies, 50), 2),
            "p95": round(percentile(latencies, 95), 2),
            "p99": round(percentile(latencies, 99), 2),
        },
    }


def print_summary(summary):
    print("GateShield Distributed Rate Limit Test")
    print()
    print(f"Gateway replicas expected: {summary['replicasExpected']}")
    print(f"Gateway instances observed: {', '.join(summary['instancesObserved'])}")
    print(f"Strategy:                  {summary['strategy']}")
    print(f"Configured limit:          {summary['configuredLimit']}")
    print(f"Total requests:            {summary['totalRequests']}")
    print(f"Concurrency:               {summary['concurrency']}")
    print(f"Allowed:                   {summary['allowed']}")
    print(f"Rejected with 429:         {summary['rejected429']}")
    print(f"Unexpected errors:         {summary['unexpectedErrors']}")
    print(f"Quota overshoot:           {summary['quotaOvershoot']}")
    print(f"p50 latency:               {summary['latencyMs']['p50']} ms")
    print(f"p95 latency:               {summary['latencyMs']['p95']} ms")
    print(f"p99 latency:               {summary['latencyMs']['p99']} ms")
    print()
    print(json.dumps(summary, indent=2))


def main():
    args = parse_args()
    try:
        api_key = setup(args)
        results = run_load(args, api_key)
        summary = summarize(args, results)
        print_summary(summary)
        if summary["unexpectedErrors"] > 0:
            return 2
        if len([item for item in summary["instancesObserved"] if item != "unknown"]) < min(2, args.replicas):
            return 3
        if summary["quotaOvershoot"] > args.allow_overshoot:
            return 4
        if summary["allowed"] + summary["rejected429"] != args.total_requests:
            return 5
        if args.allow_overshoot == 0 and summary["allowed"] != args.expected_limit:
            return 6
        return 0
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
