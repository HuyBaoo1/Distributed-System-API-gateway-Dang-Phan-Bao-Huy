#!/usr/bin/env python3
"""Plot comparative latency reports from run_latency_experiments.py manifests."""

import argparse
import csv
import json
from pathlib import Path


SUPPORTED_METRICS = {
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
    "throughputRequestsPerSecond",
    "rejectionRate",
}

CSV_FIELDNAMES = [
    "targetName",
    "strategy",
    "faultPolicy",
    "scenario",
    "trialCount",
    "durationSeconds",
    "throughputRequestsPerSecond",
    "totalResponses",
    "rejected429",
    "errorCount",
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

STRATEGY_COLORS = {
    "in-memory": "#4C9BE8",
    "redis-fixed-window": "#F4A261",
    "redis-sliding-window": "#2A9D8F",
    "redis-token-bucket": "#E76F51",
}

POLICY_COLORS = {
    "fail-closed": "#E63946",
    "fail-open": "#2A9D8F",
    "local-fallback": "#F4A261",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Plot comparative gateway latency reports.")
    parser.add_argument("--manifest", default="reports/manifest.json")
    parser.add_argument("--metric", default="gatewayP95Ms", choices=sorted(SUPPORTED_METRICS))
    parser.add_argument("--secondary-metric", default="rejectionRate", choices=sorted(SUPPORTED_METRICS))
    parser.add_argument("--output-dir", default="")
    parser.add_argument("--csv-output", default="")
    parser.add_argument("--dpi", type=int, default=150)
    return parser.parse_args()


def load_manifest(path: str) -> dict:
    manifest_path = Path(path)
    if not manifest_path.exists():
        raise SystemExit(f"Manifest not found: {manifest_path}")
    return json.loads(manifest_path.read_text(encoding="utf-8"))


def ordered_scenarios(manifest: dict) -> list[str]:
    scenarios = manifest.get("scenarios", [])
    names = [item["name"] for item in scenarios if item.get("name")]
    if names:
        return names
    return sorted({row["scenario"] for row in manifest.get("comparisons", [])})


def ordered_targets(manifest: dict) -> list[str]:
    target_entries = manifest.get("targets") or manifest.get("strategies", [])
    names = [
        item.get("targetName") or item.get("name") or item.get("strategy")
        for item in target_entries
        if item.get("targetName") or item.get("name") or item.get("strategy")
    ]
    if names:
        return names
    return sorted({
        row.get("targetName") or row.get("strategy")
        for row in manifest.get("comparisons", [])
        if row.get("targetName") or row.get("strategy")
    })


def ordered_policies(rows: list[dict]) -> list[str]:
    seen = set()
    ordered = []
    for row in rows:
        policy = row.get("faultPolicy") or "fail-closed"
        if policy not in seen:
            seen.add(policy)
            ordered.append(policy)
    return ordered or ["fail-closed"]


def write_csv(rows: list[dict], output_path: str) -> None:
    Path(output_path).parent.mkdir(parents=True, exist_ok=True)
    with Path(output_path).open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDNAMES, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def safe(value, default=0):
    return default if value is None else value


def color_for_group(color_map: dict | None, group: str):
    if not color_map:
        return None
    if group in color_map:
        return color_map[group]
    if "@" in group:
        left, right = group.split("@", 1)
        return color_map.get(left) or color_map.get(right)
    return None


def bar_width(group_count: int) -> float:
    return max(0.08, min(0.25, 0.8 / max(1, group_count)))


def plot_grouped_bars(ax, rows_by_key: dict, scenarios: list[str],
                      groups: list[str], metric: str, title: str,
                      color_map: dict | None = None) -> None:
    width = bar_width(len(groups))
    for group_index, group in enumerate(groups):
        positions = [
            scenario_index - (len(groups) - 1) * width / 2 + group_index * width
            for scenario_index in range(len(scenarios))
        ]
        values = [safe(rows_by_key.get((group, scenario), {}).get(metric)) for scenario in scenarios]
        kwargs = {"width": width, "label": group}
        color = color_for_group(color_map, group)
        if color:
            kwargs["color"] = color
        ax.bar(positions, values, **kwargs)

    ax.set_title(title, fontsize=11, fontweight="bold")
    ax.set_xticks(range(len(scenarios)))
    ax.set_xticklabels(scenarios, rotation=20, ha="right", fontsize=9)
    ax.set_ylabel(metric, fontsize=9)
    ax.grid(axis="y", alpha=0.25, linestyle="--")
    ax.legend(loc="upper left", fontsize=8)


def plot_stacked_component(ax, rows_by_key: dict, targets: list[str]) -> None:
    baseline_rows = {
        target: rows_by_key.get((target, "baseline"), {})
        for target in targets
    }
    backend_values = [safe(baseline_rows[target].get("backendP95Ms")) for target in targets]
    rate_limiter_values = [safe(baseline_rows[target].get("rateLimiterP95Ms")) for target in targets]
    x_positions = range(len(targets))

    ax.bar(x_positions, backend_values, 0.5, label="Backend p95ms", color="#4C9BE8", alpha=0.85)
    ax.bar(
        x_positions,
        rate_limiter_values,
        0.5,
        bottom=backend_values,
        label="Rate-limiter p95ms",
        color="#E76F51",
        alpha=0.85,
    )

    ax.set_title("Latency components, baseline scenario", fontsize=11, fontweight="bold")
    ax.set_xticks(list(x_positions))
    ax.set_xticklabels(targets, rotation=15, ha="right", fontsize=9)
    ax.set_ylabel("ms", fontsize=9)
    ax.grid(axis="y", alpha=0.25, linestyle="--")
    ax.legend(loc="upper right", fontsize=8)


def plot_heatmap(ax, rows_by_key: dict, scenarios: list[str],
                 targets: list[str], metric: str) -> None:
    import numpy as np
    import matplotlib.pyplot as plt

    data = [
        [safe(rows_by_key.get((target, scenario), {}).get(metric), float("nan")) for scenario in scenarios]
        for target in targets
    ]
    data_np = np.array(data, dtype=float)
    image = ax.imshow(data_np, aspect="auto", cmap="YlOrRd")

    ax.set_xticks(range(len(scenarios)))
    ax.set_yticks(range(len(targets)))
    ax.set_xticklabels(scenarios, rotation=30, ha="right", fontsize=9)
    ax.set_yticklabels(targets, fontsize=9)
    ax.set_title(f"Heatmap: {metric}", fontsize=11, fontweight="bold")

    valid = data_np[~np.isnan(data_np)]
    threshold = valid.max() * 0.6 if valid.size else 0
    for i in range(len(targets)):
        for j in range(len(scenarios)):
            value = data_np[i, j]
            label = f"{value:.1f}" if not np.isnan(value) else "N/A"
            ax.text(
                j,
                i,
                label,
                ha="center",
                va="center",
                fontsize=7,
                color="black" if np.isnan(value) or value < threshold else "white",
            )

    plt.colorbar(image, ax=ax, label=metric)


def main() -> None:
    args = parse_args()
    manifest = load_manifest(args.manifest)
    rows = manifest.get("comparisons", [])
    if not rows:
        raise SystemExit("Manifest has no comparison rows. Run run_latency_experiments.py first.")

    manifest_dir = Path(args.manifest).parent
    output_dir = Path(args.output_dir) if args.output_dir else manifest_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    csv_path = args.csv_output or str(output_dir / "latency_comparison.csv")
    write_csv(rows, csv_path)
    print(f"Saved CSV to {csv_path}")

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError as exc:
        raise SystemExit("matplotlib is required. Install: pip install -r requirements.txt") from exc

    plt.rcParams.update({"figure.dpi": args.dpi, "font.size": 9})

    scenarios = ordered_scenarios(manifest)
    targets = ordered_targets(manifest)
    policies = ordered_policies(rows)

    rows_by_target: dict[tuple, dict] = {}
    for row in rows:
        target_name = row.get("targetName") or row.get("strategy")
        rows_by_target.setdefault((target_name, row.get("scenario")), row)

    rows_by_policy: dict[tuple, dict] = {}
    for row in rows:
        policy_target = f"{row.get('strategy')}@{row.get('faultPolicy', 'fail-closed')}"
        rows_by_policy.setdefault((policy_target, row.get("scenario")), row)

    fig1, (ax1, ax2) = plt.subplots(2, 1, figsize=(14, 9), constrained_layout=True)
    plot_grouped_bars(
        ax1,
        rows_by_target,
        scenarios,
        targets,
        args.metric,
        f"Target comparison: {args.metric}",
        STRATEGY_COLORS,
    )
    plot_grouped_bars(
        ax2,
        rows_by_target,
        scenarios,
        targets,
        args.secondary_metric,
        f"Target comparison: {args.secondary_metric}",
        STRATEGY_COLORS,
    )
    path1 = output_dir / "latency_comparison.png"
    fig1.savefig(path1, dpi=args.dpi)
    plt.close(fig1)
    print(f"Saved plot to {path1}")

    fig2, ax3 = plt.subplots(figsize=(14, 5), constrained_layout=True)
    plot_grouped_bars(
        ax3,
        rows_by_target,
        scenarios,
        targets,
        "rateLimiterP95Ms",
        "Rate-limiter overhead: p95ms",
        STRATEGY_COLORS,
    )
    path2 = output_dir / "ratelimiter_overhead.png"
    fig2.savefig(path2, dpi=args.dpi)
    plt.close(fig2)
    print(f"Saved plot to {path2}")

    fig3, ax4 = plt.subplots(figsize=(10, 5), constrained_layout=True)
    plot_stacked_component(ax4, rows_by_target, targets)
    path3 = output_dir / "latency_components.png"
    fig3.savefig(path3, dpi=args.dpi)
    plt.close(fig3)
    print(f"Saved plot to {path3}")

    try:
        fig4, ax5 = plt.subplots(figsize=(12, max(4, len(targets))), constrained_layout=True)
        plot_heatmap(ax5, rows_by_target, scenarios, targets, args.metric)
        path4 = output_dir / f"heatmap_{args.metric}.png"
        fig4.savefig(path4, dpi=args.dpi)
        plt.close(fig4)
        print(f"Saved plot to {path4}")
    except Exception as exc:
        print(f"[WARN] Heatmap skipped: {exc}")

    if manifest.get("faultPolicyMatrix") and len(policies) > 1:
        strategies = sorted({row.get("strategy") for row in rows if row.get("strategy")})
        for strategy in strategies:
            policy_targets = [f"{strategy}@{policy}" for policy in policies]
            fig5, (ax_a, ax_b) = plt.subplots(2, 1, figsize=(14, 9), constrained_layout=True)
            plot_grouped_bars(
                ax_a,
                rows_by_policy,
                scenarios,
                policy_targets,
                args.metric,
                f"[{strategy}] Fault policy: {args.metric}",
                POLICY_COLORS,
            )
            plot_grouped_bars(
                ax_b,
                rows_by_policy,
                scenarios,
                policy_targets,
                "rejectionRate",
                f"[{strategy}] Fault policy: rejectionRate",
                POLICY_COLORS,
            )
            path5 = output_dir / f"fault_policy_{strategy}.png"
            fig5.savefig(path5, dpi=args.dpi)
            plt.close(fig5)
            print(f"Saved plot to {path5}")


if __name__ == "__main__":
    main()
