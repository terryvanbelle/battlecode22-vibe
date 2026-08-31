#!/usr/bin/env python3
"""
Two alternate progress views, built from this project's own Gauntlet
run history (gauntlet/<run-id>/results.csv -- git-ignored/ephemeral,
but retained locally across a session, which is enough history to plot).

1. Peer win-rate spread: for each "full" peer Gauntlet run (at least
   MIN_PEERS distinct peer opponents, to exclude 1-8-peer reproduction
   samples and single-game motivating-case checks), the best and worst
   per-opponent win rate in that run, over time. Unlike the cumulative-
   iterations or benchmark-win-count charts, this shows dispersion --
   is progress broad (both ends rising together) or lopsided (a growing
   gap between the easiest and hardest peer matchup)?

2. Benchmark game length: average round count per full 20-game
   benchmark tally (sample_camelcase, sample_afinals), over time.
   Win/loss alone discards this -- a benchmark game that used to end
   in 300 rounds and now takes 800 is real progress even while still
   a loss (see TRAINING_ALGORITHM.md's own "round-count metric" note,
   same idea applied here to the benchmark bots specifically).

Usage:
    tools/.venv/bin/python3 tools/plot_alt_metrics.py

Requires matplotlib in tools/.venv. Run from the repo root.
"""
import csv
import glob
import os
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

REPO_ROOT = Path(__file__).resolve().parent.parent
PACIFIC = ZoneInfo("America/Los_Angeles")
UTC = ZoneInfo("UTC")

# Below this many distinct peer opponents, treat a run as a reproduction
# sample or single-game check, not a real full-Gauntlet snapshot.
MIN_PEERS = 14
# Below this many games for a benchmark opponent, treat as a partial/
# spot-check tally, not a full 10-map x 2-side (20-game) one.
MIN_BENCHMARK_GAMES = 15

BENCHMARK_BOTS = ("sample_camelcase", "sample_afinals")


def run_timestamp(rundir: Path):
    # gauntlet.sh names each run directory via the system's local `date`
    # (RUN_ID="$(date +%Y%m%d-%H%M%S)"), which on this project's dev
    # machine is UTC -- confirmed directly (`date +%Z` -> UTC).
    dt = datetime.strptime(rundir.name, "%Y%m%d-%H%M%S").replace(tzinfo=UTC)
    return dt.astimezone(PACIFIC)


def load_results(path: Path):
    with open(path) as f:
        return list(csv.DictReader(f))


def peer_spread_series():
    xs, maxs, mins = [], [], []
    for rundir in sorted(Path(REPO_ROOT / "gauntlet").glob("*/")):
        p = rundir / "results.csv"
        if not p.exists():
            continue
        per_opp = defaultdict(lambda: [0, 0])  # opponent -> [wins, total]
        for row in load_results(p):
            opp = row["opponent"]
            if opp in BENCHMARK_BOTS:
                continue
            per_opp[opp][1] += 1
            if row["bot_result"] == "win":
                per_opp[opp][0] += 1
        if len(per_opp) < MIN_PEERS:
            continue
        pcts = [w / t for w, t in per_opp.values() if t > 0]
        if not pcts:
            continue
        xs.append(run_timestamp(rundir))
        maxs.append(100 * max(pcts))
        mins.append(100 * min(pcts))
    return xs, maxs, mins


def benchmark_length_series():
    data = defaultdict(list)  # opponent -> [(timestamp, avg_rounds), ...]
    for rundir in sorted(Path(REPO_ROOT / "gauntlet").glob("*/")):
        p = rundir / "results.csv"
        if not p.exists():
            continue
        per_opp_rounds = defaultdict(list)
        for row in load_results(p):
            opp = row["opponent"]
            if opp not in BENCHMARK_BOTS:
                continue
            per_opp_rounds[opp].append(int(row["rounds"]))
        ts = run_timestamp(rundir)
        for opp, rounds in per_opp_rounds.items():
            if len(rounds) < MIN_BENCHMARK_GAMES:
                continue
            data[opp].append((ts, sum(rounds) / len(rounds)))
    for opp in data:
        data[opp].sort()
    return data


def main():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.dates as mdates

    out_dir = REPO_ROOT / "progress"
    out_dir.mkdir(parents=True, exist_ok=True)

    # ---- Chart 1: peer win-rate spread ----
    xs, maxs, mins = peer_spread_series()
    fig, ax = plt.subplots(figsize=(13, 7))
    ax.fill_between(xs, mins, maxs, color="#94a3b8", alpha=0.15, label="spread")
    ax.plot(xs, maxs, color="#16a34a", marker="o", markersize=3, linewidth=1.6,
             label="best peer matchup (win %)")
    ax.plot(xs, mins, color="#dc2626", marker="o", markersize=3, linewidth=1.6,
             label="worst peer matchup (win %)")
    ax.set_title(f"Peer win-rate spread over time (full Gauntlet runs, ≥{MIN_PEERS} peer opponents)")
    ax.set_xlabel("Date (Pacific Time)")
    ax.set_ylabel("Win rate vs. a single peer opponent (%)")
    ax.set_ylim(-5, 105)
    ax.grid(True, alpha=0.3)
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%m-%d %H:%M", tz=PACIFIC))
    fig.autofmt_xdate(rotation=30)
    ax.legend(loc="lower left", fontsize=9)
    fig.tight_layout()
    out1 = out_dir / "peer_win_spread.png"
    fig.savefig(out1, dpi=150)
    print(f"wrote {out1} ({len(xs)} qualifying runs)")

    # ---- Chart 2: benchmark game length ----
    data = benchmark_length_series()
    fig2, ax2 = plt.subplots(figsize=(13, 7))
    colors = {"sample_camelcase": "#b91c1c", "sample_afinals": "#059669"}
    markers = {"sample_camelcase": "o", "sample_afinals": "s"}
    for opp, pts in data.items():
        xs2 = [p[0] for p in pts]
        ys2 = [p[1] for p in pts]
        ax2.plot(xs2, ys2, color=colors.get(opp, "#333"), marker=markers.get(opp, "o"),
                  markersize=5, linewidth=1.6, label=opp)
    ax2.set_title(f"Average game length vs. benchmark bots over time (full ≥20-game tallies)")
    ax2.set_xlabel("Date (Pacific Time)")
    ax2.set_ylabel("Average rounds per game")
    ax2.grid(True, alpha=0.3)
    ax2.xaxis.set_major_formatter(mdates.DateFormatter("%m-%d %H:%M", tz=PACIFIC))
    fig2.autofmt_xdate(rotation=30)
    ax2.legend(loc="best", fontsize=9)
    fig2.tight_layout()
    out2 = out_dir / "benchmark_game_length.png"
    fig2.savefig(out2, dpi=150)
    print(f"wrote {out2}")
    for opp, pts in data.items():
        print(f"  {opp}: {len(pts)} qualifying runs")


if __name__ == "__main__":
    main()
