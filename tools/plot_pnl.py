"""Plots the latency sweep written by `gradlew latencySweep`.

Usage: python tools/plot_pnl.py [build/reports/sim]
Needs matplotlib. Writes pnl_vs_latency.png and pnl_timeseries.png next to the CSVs.
"""
import csv
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt


def label(ns: int) -> str:
    if ns == 0:
        return "0"
    return f"{ns // 1000} µs" if ns < 1_000_000 else f"{ns // 1_000_000} ms"


def main() -> None:
    directory = Path(sys.argv[1] if len(sys.argv) > 1 else "build/reports/sim")

    with open(directory / "pnl_vs_latency.csv", newline="") as f:
        rows = list(csv.DictReader(f))
    labels = [label(int(r["latency_ns"])) for r in rows]
    pnl = [float(r["pnl_dollars"]) for r in rows]
    fill_rate = [100 * float(r["fill_rate"]) for r in rows]

    fig, left = plt.subplots(figsize=(8, 4.5))
    left.bar(labels, pnl, color="#4a7bd0")
    left.axhline(0, color="black", linewidth=0.8)
    left.set_xlabel("one-way latency")
    left.set_ylabel("P&L ($)")
    right = left.twinx()
    right.plot(labels, fill_rate, color="#d0704a", marker="o")
    right.set_ylabel("orders filled (%)")
    left.set_title("Sample market maker: P&L and fill rate vs latency")
    fig.tight_layout()
    fig.savefig(directory / "pnl_vs_latency.png", dpi=150)

    series = defaultdict(lambda: ([], []))
    with open(directory / "pnl_timeseries.csv", newline="") as f:
        for r in csv.DictReader(f):
            seconds, dollars = series[int(r["latency_ns"])]
            seconds.append(int(r["seconds_since_open"]) / 3600)
            dollars.append(float(r["pnl_dollars"]))

    fig, ax = plt.subplots(figsize=(8, 4.5))
    for ns, (hours, dollars) in sorted(series.items()):
        ax.plot(hours, dollars, label=label(ns), linewidth=1)
    ax.axhline(0, color="black", linewidth=0.8)
    ax.set_xlabel("hours since the open")
    ax.set_ylabel("P&L, marked to mid ($)")
    ax.set_title("Sample market maker: P&L through the session")
    ax.legend(title="latency")
    fig.tight_layout()
    fig.savefig(directory / "pnl_timeseries.png", dpi=150)
    print(f"Wrote {directory / 'pnl_vs_latency.png'} and {directory / 'pnl_timeseries.png'}")


if __name__ == "__main__":
    main()
