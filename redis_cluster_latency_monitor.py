#!/usr/bin/env python3
"""Latency evaluator for Redis cluster APIs and direct Redis endpoint ping.

This script is designed to use a real Redis provider API. Provide your API base URL
and API token via environment variables or command-line arguments.

Example:
  set REDIS_API_TOKEN=your-token
  python redis_cluster_latency_monitor.py \
    --api-base-url https://api.example.com \
    --cluster-id your-cluster-id

If you also have direct Redis endpoints, use `--direct-endpoints` to measure TCP latency.
"""

import argparse
import os
import sys
import time
from pathlib import Path

import requests
from redis import Redis
from redis.exceptions import RedisError


def parse_args():
    load_env_file()
    parser = argparse.ArgumentParser(description="Measure Redis cluster latency and gateway impact.")
    parser.add_argument("--api-base-url", help="Base URL of the Redis provider API.")
    parser.add_argument("--cluster-id", help="Cluster ID to query from the provider API.")
    parser.add_argument("--direct-endpoints", help="Comma-separated Redis endpoints host:port to ping directly.")
    parser.add_argument("--timeout", type=float, default=5.0, help="HTTP/request timeout in seconds.")
    return parser.parse_args()


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


def get_auth_header():
    api_token = os.environ.get("REDIS_API_TOKEN")
    if not api_token:
        print("ERROR: REDIS_API_TOKEN environment variable is not set.", file=sys.stderr)
        sys.exit(1)
    return {"Authorization": f"Bearer {api_token}"}


def fetch_cluster_latency(api_base_url, cluster_id, timeout):
    url = f"{api_base_url.rstrip('/')}/clusters/{cluster_id}/metrics/latency"
    headers = get_auth_header()
    response = requests.get(url, headers=headers, timeout=timeout)
    response.raise_for_status()
    return response.json()


def fetch_cluster_nodes(api_base_url, cluster_id, timeout):
    url = f"{api_base_url.rstrip('/')}/clusters/{cluster_id}/nodes"
    headers = get_auth_header()
    response = requests.get(url, headers=headers, timeout=timeout)
    response.raise_for_status()
    return response.json()


def measure_direct_latency(endpoints, timeout):
    results = []
    redis_password = os.environ.get("REDIS_PASSWORD") or os.environ.get("REDIS_TOKEN")
    redis_username = os.environ.get("REDIS_USERNAME") or None
    for endpoint in endpoints.split(","):
        endpoint = endpoint.strip()
        if not endpoint:
            continue
        if ":" not in endpoint:
            print(f"Skipping invalid endpoint format: {endpoint}")
            continue
        host, port = endpoint.split(":", 1)
        try:
            start = time.perf_counter()
            client = Redis(
                host=host,
                port=int(port),
                username=redis_username,
                password=redis_password or None,
                socket_connect_timeout=timeout,
                socket_timeout=timeout,
            )
            client.ping()
            elapsed_ms = (time.perf_counter() - start) * 1000.0
            results.append((endpoint, True, elapsed_ms, None))
        except RedisError as ex:
            results.append((endpoint, False, None, str(ex)))
    return results


def print_json_summary(value):
    import json
    print(json.dumps(value, indent=2, ensure_ascii=False))


def main():
    args = parse_args()
    if args.api_base_url and args.cluster_id:
        print("Fetching Redis cluster latency metrics from provider API...")
        try:
            payload = fetch_cluster_latency(args.api_base_url, args.cluster_id, args.timeout)
            print("Provider API latency metrics:")
            print_json_summary(payload)
        except Exception as ex:
            print(f"Failed to fetch cluster latency metrics: {ex}", file=sys.stderr)

        print("\nFetching cluster node list from provider API... (optional)")
        try:
            nodes = fetch_cluster_nodes(args.api_base_url, args.cluster_id, args.timeout)
            print("Provider API cluster node info:")
            print_json_summary(nodes)
        except Exception as ex:
            print(f"Failed to fetch cluster node list: {ex}", file=sys.stderr)

    if args.direct_endpoints:
        print("\nMeasuring direct Redis network latency...")
        direct_results = measure_direct_latency(args.direct_endpoints, args.timeout)
        for endpoint, success, latency_ms, error in direct_results:
            if success:
                print(f"{endpoint} -> PONG in {latency_ms:.2f} ms")
            else:
                print(f"{endpoint} -> failed: {error}")

    if not args.api_base_url and not args.direct_endpoints:
        print("No action taken. Provide --api-base-url/--cluster-id or --direct-endpoints.")


if __name__ == "__main__":
    main()
