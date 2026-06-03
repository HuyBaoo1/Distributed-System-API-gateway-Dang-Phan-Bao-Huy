#!/usr/bin/env python3
"""Plot comparative latency reports from run_latency_experiments.py manifests.

Generates:
  1. Primary metric comparison  (grouped bar chart, strategies × scenarios)
  2. Secondary metric comparison (grouped bar chart)
  3. Rate-limiter overhead chart (rateLimiterP95Ms per strategy × scenario)
  4. Fault-policy comparison     (if manifest contains fault-policy matrix data)
  5. Latency component stacked bar (backend vs rate-limiter overhead per strategy)
  6. Heatmap: strategy × scenario for the primary metric

Usage:
    python plot_latency_report.py --manifest reports/manifest.json

All charts are saved as separate PNG files in the same directory as the manifest.
A combined CSV is always written regardless of matplotlib availability.
"""

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
    "strategy", "faultPolicy", "scenario",
    "durationSeconds", "throughputRequestsPerSecond",
    "totalResponses", "rejected429", "errorCount", "rejectionRate",
    "clientP50Ms", "clientP95Ms", "clientP99Ms", "clientMaxMs",
    "gatewayP50Ms", "gatewayP95Ms", "gatewayP99Ms",
    "backendP95Ms", "backendP99Ms",
    "rateLimiterP50Ms", "rateLimiterP95Ms", "rateLimiterP99Ms",
]

STRATEGY_COLORS = {
    "in-memory":            "#4C9BE8",
    "redis-fixed-window":   "#F4A261",
    "redis-sliding-window": "#2A9D8F",
    "redis-token-bucket":   "#E76F51",
}

POLICY_COLORS = {
    "fail-closed":    "#E63946",
    "fail-open":      "#2A9D8F",
    "local-fallback": "#F4A261",
}


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Plot comparative gateway latency reports.")
    parser.add_argument("--manifest",          default="reports/manifest.json")
    parser.add_argument("--metric",            default="gatewayP95Ms", choices=sorted(SUPPORTED_METRICS))
    parser.add_argument("--secondary-metric",  default="rejectionRate", choices=sorted(SUPPORTED_METRICS))
    parser.add_argument("--output-dir",        default="")
    parser.add_argument("--csv-output",        default="")
    parser.add_argument("--dpi",               type=int, default=150)
    parser.add_argument("--no-show",           action="store_true", help="Do not call plt.show()")
    return parser.parse_args()


# ---------------------------------------------------------------------------
# Data helpers
# ---------------------------------------------------------------------------

def load_manifest(path: str) -> dict:
    p = Path(path)
    if not p.exists():
        raise SystemExit(f"Manifest not found: {p}")
    return json.loads(p.read_text(encoding="utf-8"))


def ordered_scenarios(manifest: dict) -> list[str]:
    scenarios = manifest.get("scenarios", [])
    names = [item["name"] for item in scenarios if item.get("name")]
    if names:
        return names
    return sorted({row["scenario"] for row in manifest.get("comparisons", [])})


def ordered_strategies(manifest: dict) -> list[str]:
    strategies = manifest.get("strategies", [])
    names = [item["name"] for item in strategies if item.get("name")]
    if names:
        return names
    return sorted({row["strategy"] for row in manifest.get("comparisons", [])})


def ordered_policies(rows: list[dict]) -> list[str]:
    seen, result = set(), []
    for row in rows:
        p = row.get("faultPolicy") or "fail-closed"
        if p not in seen:
            seen.add(p)
            result.append(p)
    return result or ["fail-closed"]


def write_csv(rows: list[dict], output_path: str) -> None:
    Path(output_path).parent.mkdir(parents=True, exist_ok=True)
    with Path(output_path).open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=CSV_FIELDNAMES, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def safe(value, default=0):
    return default if value is None else value


# ---------------------------------------------------------------------------
# Plot helpers
# ---------------------------------------------------------------------------

def _bar_width(n_groups: int) -> float:
    return max(0.08, min(0.25, 0.8 / max(1, n_groups)))


def plot_grouped_bars(ax, rows_by_key: dict, scenarios: list[str],
                      groups: list[str], metric: str, title: str,
                      color_map: dict | None = None) -> None:
    width = _bar_width(len(groups))
    for gi, group in enumerate(groups):
        positions = [
            si - (len(groups) - 1) * width / 2 + gi * width
            for si in range(len(scenarios))
        ]
        values = [safe(rows_by_key.get((group, sc), {}).get(metric)) for sc in scenarios]
        color  = (color_map or {}).get(group)
        kw     = dict(width=width, label=group)
        if color:
            kw["color"] = color
        ax.bar(positions, values, **kw)

    ax.set_title(title, fontsize=11, fontweight="bold")
    ax.set_xticks(range(len(scenarios)))
    ax.set_xticklabels(scenarios, rotation=20, ha="right", fontsize=9)
    ax.set_ylabel(metric, fontsize=9)
    ax.grid(axis="y", alpha=0.25, linestyle="--")
    ax.legend(loc="upper left", fontsize=8)


def plot_stacked_component(ax, rows_by_key: dict, scenarios: list[str],
                            strategies: list[str]) -> None:
    """Stacked bar: backend p95 + rate-limiter p95 overhead per strategy (baseline only)."""
    baseline_rows = {
        strat: rows_by_key.get((strat, "baseline"), {})
        for strat in strategies
    }
    backend_vals  = [safe(baseline_rows[s].get("backendP95Ms")) for s in strategies]
    rl_vals       = [safe(baseline_rows[s].get("rateLimiterP95Ms")) for s in strategies]
    x             = range(len(strategies))
    width         = 0.5

    ax.bar(x, backend_vals, width, label="Backend p95ms",      color="#4C9BE8", alpha=0.85)
    ax.bar(x, rl_vals,      width, bottom=backend_vals,
           label="Rate-limiter overhead p95ms", color="#E76F51", alpha=0.85)

    ax.set_title("Latency Components (baseline scenario)", fontsize=11, fontweight="bold")
    ax.set_xticks(list(x))
    ax.set_xticklabels(strategies, rotation=15, ha="right", fontsize=9)
    ax.set_ylabel("ms", fontsize=9)
    ax.grid(axis="y", alpha=0.25, linestyle="--")
    ax.legend(loc="upper right", fontsize=8)


def plot_heatmap(ax, rows_by_key: dict, scenarios: list[str],
                 strategies: list[str], metric: str) -> None:
    import numpy as np
    data = [
        [safe(rows_by_key.get((strat, sc), {}).get(metric), float("nan"))
         for sc in scenarios]
        for strat in strategies
    ]
    data_np = np.array(data, dtype=float)

    im = ax.imshow(data_np, aspect="auto", cmap="YlOrRd")
    ax.set_xticks(range(len(scenarios)))
    ax.set_yticks(range(len(strategies)))
    ax.set_xticklabels(scenarios, rotation=30, ha="right", fontsize=9)
    ax.set_yticklabels(strategies, fontsize=9)
    ax.set_title(f"Heatmap: {metric}", fontsize=11, fontweight="bold")

    for i in range(len(strategies)):
        for j in range(len(scenarios)):
            val = data_np[i, j]
            text = f"{val:.1f}" if not (val != val) else "N/A"   # nan check
            ax.text(j, i, text, ha="center", va="center", fontsize=7,
                    color="black" if val < (data_np[~(data_np != data_np)].max() * 0.6) else "white")
    import matplotlib.pyplot as plt
    # use pyplot.colorbar to avoid direct from-import which some linters
    # may not resolve against source files
    plt.colorbar(im, ax=ax, label=metric)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    args     = parse_args()
    manifest = load_manifest(args.manifest)
    rows     = manifest.get("comparisons", [])

    if not rows:
        raise SystemExit("Manifest has no comparison rows. Run run_latency_experiments.py first.")

    manifest_dir = Path(args.manifest).parent
    out_dir      = Path(args.output_dir) if args.output_dir else manifest_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    csv_path     = args.csv_output or str(out_dir / "latency_comparison.csv")
    write_csv(rows, csv_path)
    print(f"Saved CSV  → {csv_path}")

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError as exc:
        raise SystemExit(
            "matplotlib is required. Install: pip install -r requirements.txt"
        ) from exc

    plt.rcParams.update({"figure.dpi": args.dpi, "font.size": 9})

    scenarios  = ordered_scenarios(manifest)
    strategies = ordered_strategies(manifest)
    policies   = ordered_policies(rows)

    # rows indexed by (strategy, scenario) – using first match if duplicates
    rows_by_key: dict[tuple, dict] = {}
    for row in rows:
        key = (row.get("strategy"), row.get("scenario"))
        rows_by_key.setdefault(key, row)

    # Also build (strategy@policy, scenario) index for fault-policy charts
    rows_by_policy_key: dict[tuple, dict] = {}
    for row in rows:
        key = (f"{row.get('strategy')}@{row.get('faultPolicy','fail-closed')}", row.get("scenario"))
        rows_by_policy_key.setdefault(key, row)

    # -----------------------------------------------------------------------
    # Figure 1 – Primary + secondary metrics
    # -----------------------------------------------------------------------
    fig1, (ax1, ax2) = plt.subplots(2, 1, figsize=(14, 9), constrained_layout=True)
    plot_grouped_bars(ax1, rows_by_key, scenarios, strategies,
                      args.metric, f"Strategy Comparison: {args.metric}",
                      STRATEGY_COLORS)
    plot_grouped_bars(ax2, rows_by_key, scenarios, strategies,
                      args.secondary_metric, f"Strategy Comparison: {args.secondary_metric}",
                      STRATEGY_COLORS)
    path1 = out_dir / "latency_comparison.png"
    fig1.savefig(path1, dpi=args.dpi)
    plt.close(fig1)
    print(f"Saved plot → {path1}")

    # -----------------------------------------------------------------------
    # Figure 2 – Rate-limiter overhead
    # -----------------------------------------------------------------------
    fig2, ax3 = plt.subplots(figsize=(14, 5), constrained_layout=True)
    plot_grouped_bars(ax3, rows_by_key, scenarios, strategies,
                      "rateLimiterP95Ms", "Rate-Limiter Overhead: p95ms",
                      STRATEGY_COLORS)
    path2 = out_dir / "ratelimiter_overhead.png"
    fig2.savefig(path2, dpi=args.dpi)
    plt.close(fig2)
    print(f"Saved plot → {path2}")

    # -----------------------------------------------------------------------
    # Figure 3 – Latency component stacked bar (baseline only)
    # -----------------------------------------------------------------------
    fig3, ax4 = plt.subplots(figsize=(10, 5), constrained_layout=True)
    plot_stacked_component(ax4, rows_by_key, scenarios, strategies)
    path3 = out_dir / "latency_components.png"
    fig3.savefig(path3, dpi=args.dpi)
    plt.close(fig3)
    print(f"Saved plot → {path3}")

    # -----------------------------------------------------------------------
    # Figure 4 – Heatmap
    # -----------------------------------------------------------------------
    try:
        import numpy as np
        fig4, ax5 = plt.subplots(figsize=(12, max(4, len(strategies))), constrained_layout=True)
        plot_heatmap(ax5, rows_by_key, scenarios, strategies, args.metric)
        path4 = out_dir / f"heatmap_{args.metric}.png"
        fig4.savefig(path4, dpi=args.dpi)
        plt.close(fig4)
        print(f"Saved plot → {path4}")
    except Exception as exc:
        print(f"[WARN] Heatmap skipped: {exc}")

    # -----------------------------------------------------------------------
    # Figure 5 – Fault-policy comparison (only if fault-policy matrix ran)
    # -----------------------------------------------------------------------
    if manifest.get("faultPolicyMatrix") and len(policies) > 1:
        unique_strats_with_policies = sorted({
            row.get("strategy") for row in rows if row.get("faultPolicy")
        })
        for strat in unique_strats_with_policies:
            policy_targets = [f"{strat}@{p}" for p in policies]
            fig5, (axA, axB) = plt.subplots(2, 1, figsize=(14, 9), constrained_layout=True)
            plot_grouped_bars(
                axA, rows_by_policy_key, scenarios, policy_targets,
                args.metric, f"[{strat}] Fault Policy: {args.metric}",
                POLICY_COLORS,
            )
            plot_grouped_bars(
                axB, rows_by_policy_key, scenarios, policy_targets,
                "rejectionRate", f"[{strat}] Fault Policy: rejectionRate",
                POLICY_COLORS,
            )
            path5 = out_dir / f"fault_policy_{strat}.png"
            fig5.savefig(path5, dpi=args.dpi)
            plt.close(fig5)
            print(f"Saved plot → {path5}")


if __name__ == "__main__":
    main()
