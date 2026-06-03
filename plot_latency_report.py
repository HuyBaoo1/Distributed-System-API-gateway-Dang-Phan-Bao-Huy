#!/usr/bin/env python3
"""Plot latency experiment results from run_latency_experiments.py manifests."""

import argparse
import csv
import json
from pathlib import Path


SUPPORTED_METRICS = {
    "clientP95Ms",
    "gatewayP95Ms",
    "backendP95Ms",
    "rateLimiterP95Ms",
    "clientP99Ms",
    "gatewayP99Ms",
    "backendP99Ms",
    "rateLimiterP99Ms",
    "throughputRequestsPerSecond",
    "rejectionRate",
}


def parse_args():
    parser = argparse.ArgumentParser(description="Plot comparative gateway latency reports.")
    parser.add_argument("--manifest", default="reports/manifest.json")
    parser.add_argument("--metric", default="gatewayP95Ms", choices=sorted(SUPPORTED_METRICS))
    parser.add_argument("--secondary-metric", default="rejectionRate", choices=sorted(SUPPORTED_METRICS))
    parser.add_argument("--output", default="reports/latency_comparison.png")
    parser.add_argument("--csv-output", default="reports/latency_comparison.csv")
    return parser.parse_args()


def load_manifest(path):
    manifest_path = Path(path)
    if not manifest_path.exists():
        raise SystemExit(f"Manifest not found: {manifest_path}")
    return json.loads(manifest_path.read_text(encoding="utf-8"))


def ordered_scenarios(manifest):
    scenarios = manifest.get("scenarios", [])
    names = [item["name"] for item in scenarios if item.get("name")]
    if names:
        return names
    return sorted({row["scenario"] for row in manifest.get("comparisons", [])})


def ordered_strategies(manifest):
    strategies = manifest.get("strategies", [])
    names = [item["name"] for item in strategies if item.get("name")]
    if names:
        return names
    return sorted({row["strategy"] for row in manifest.get("comparisons", [])})


def write_csv(rows, output_path):
    Path(output_path).parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "strategy",
        "scenario",
        "durationSeconds",
        "throughputRequestsPerSecond",
        "totalResponses",
        "rejected429",
        "errorCount",
        "rejectionRate",
        "clientP95Ms",
        "gatewayP95Ms",
        "backendP95Ms",
        "rateLimiterP95Ms",
        "clientP99Ms",
        "gatewayP99Ms",
        "backendP99Ms",
        "rateLimiterP99Ms",
    ]
    with Path(output_path).open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def plot_metric(ax, rows_by_key, scenarios, strategies, metric, title):
    width = 0.8 / max(1, len(strategies))
    for strategy_index, strategy in enumerate(strategies):
        positions = [
            scenario_index - 0.4 + width / 2 + strategy_index * width
            for scenario_index, _ in enumerate(scenarios)
        ]
        values = [
            rows_by_key.get((strategy, scenario), {}).get(metric)
            for scenario in scenarios
        ]
        plotted_values = [0 if value is None else value for value in values]
        ax.bar(positions, plotted_values, width=width, label=strategy)

    ax.set_title(title)
    ax.set_xticks(range(len(scenarios)))
    ax.set_xticklabels(scenarios, rotation=20, ha="right")
    ax.set_ylabel(metric)
    ax.grid(axis="y", alpha=0.25)


def main():
    args = parse_args()
    manifest = load_manifest(args.manifest)
    rows = manifest.get("comparisons", [])
    if not rows:
        raise SystemExit("Manifest has no comparison rows. Run run_latency_experiments.py first.")

    write_csv(rows, args.csv_output)

    try:
        import matplotlib.pyplot as plt
    except ImportError as exc:
        raise SystemExit("matplotlib is required. Install dependencies with: pip install -r requirements.txt") from exc

    scenarios = ordered_scenarios(manifest)
    strategies = ordered_strategies(manifest)
    rows_by_key = {
        (row.get("strategy"), row.get("scenario")): row
        for row in rows
    }

    figure, axes = plt.subplots(2, 1, figsize=(12, 8), constrained_layout=True)
    plot_metric(axes[0], rows_by_key, scenarios, strategies, args.metric, f"Comparison: {args.metric}")
    plot_metric(axes[1], rows_by_key, scenarios, strategies, args.secondary_metric, f"Comparison: {args.secondary_metric}")
    axes[0].legend(loc="best")

    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(args.output, dpi=150)
    print(f"Saved plot to {args.output}")
    print(f"Saved CSV to {args.csv_output}")


if __name__ == "__main__":
    main()
