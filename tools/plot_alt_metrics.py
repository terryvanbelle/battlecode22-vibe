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

2. Benchmark game length: average round count of *losing* games only,
   per full 20-game benchmark tally (sample_camelcase, sample_afinals),
   over time. Losing-games-only on purpose -- a longer loss is real
   progress (getting closer to winning), but a longer win isn't the
   same signal (could just as easily be a slower, messier win), so
   mixing the two would muddy the trend. Win/loss alone discards this
   kind of progress -- a loss that used to end in 300 rounds and now
   takes 800 is real even while still technically a loss (see
   TRAINING_ALGORITHM.md's own "round-count metric" note,
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


def full_runs():
    """(rundir, timestamp, {opponent -> [wins, total]}) for every run with
    >= MIN_PEERS distinct peer opponents, sorted by time. Shared by the
    peer-spread series and retirement-event detection below, so both read
    the same notion of "a real full-Gauntlet snapshot"."""
    runs = []
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
        runs.append((rundir, run_timestamp(rundir), per_opp))
    return runs


def peer_spread_series(runs):
    xs, maxs, mins = [], [], []
    for _, ts, per_opp in runs:
        pcts = [w / t for w, t in per_opp.values() if t > 0]
        if not pcts:
            continue
        xs.append(ts)
        maxs.append(100 * max(pcts))
        mins.append(100 * min(pcts))
    return xs, maxs, mins


def retirement_events(runs):
    """A peer present in one full-Gauntlet run and absent from the next is
    inferred as retired somewhere in between (the 80%-domination-for-two-
    consecutive-Gauntlets rule, see TRAINING_ALGORITHM.md's "Retiring bots
    from the Gauntlet") -- there's no separate retirement log, so this is
    reconstructed directly from which opponents disappear between
    consecutive full runs. Returns [(timestamp, [retired_names]), ...],
    one entry per run where at least one peer dropped out."""
    events = []
    for i in range(1, len(runs)):
        prev_opps = set(runs[i - 1][2].keys())
        cur_opps = set(runs[i][2].keys())
        removed = sorted(prev_opps - cur_opps)
        if removed:
            events.append((runs[i][1], removed))
    return events


def benchmark_length_series():
    # Losing games only: a longer game is only "progress" when it's a loss
    # that took longer to lose (getting closer to winning). A longer WIN
    # doesn't indicate improvement the same way -- it could just as easily
    # mean a slower, messier win, so mixing the two in one average would
    # muddy the signal this chart is meant to show.
    data = defaultdict(list)  # opponent -> [(timestamp, avg_losing_rounds), ...]
    for rundir in sorted(Path(REPO_ROOT / "gauntlet").glob("*/")):
        p = rundir / "results.csv"
        if not p.exists():
            continue
        per_opp_total = defaultdict(int)
        per_opp_loss_rounds = defaultdict(list)
        for row in load_results(p):
            opp = row["opponent"]
            if opp not in BENCHMARK_BOTS:
                continue
            per_opp_total[opp] += 1
            if row["bot_result"] == "loss":
                per_opp_loss_rounds[opp].append(int(row["rounds"]))
        ts = run_timestamp(rundir)
        for opp, total in per_opp_total.items():
            # gate "is this a full tally" on the *total* game count (wins
            # included), not just the loss count, so a strong tally with
            # few losses isn't penalized for having less loss data.
            if total < MIN_BENCHMARK_GAMES:
                continue
            losses = per_opp_loss_rounds[opp]
            if not losses:
                continue
            data[opp].append((ts, sum(losses) / len(losses)))
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
    runs = full_runs()
    xs, maxs, mins = peer_spread_series(runs)
    fig, ax = plt.subplots(figsize=(13, 7))
    ax.fill_between(xs, mins, maxs, color="#94a3b8", alpha=0.15, label="spread")
    ax.plot(xs, maxs, color="#16a34a", marker="o", markersize=3, linewidth=1.6,
             label="best peer matchup (win %)")
    ax.plot(xs, mins, color="#dc2626", marker="o", markersize=3, linewidth=1.6,
             label="worst peer matchup (win %)")

    # Retirement events: a peer dropping out of the roster between two
    # full-Gauntlet runs (80%-domination rule). Marked as vertical lines,
    # staggered slightly so overlapping labels stay legible.
    events = retirement_events(runs)
    for i, (ts, names) in enumerate(events):
        ax.axvline(ts, color="#7c3aed", linestyle=":", linewidth=1.3, alpha=0.8, zorder=1)
        label = "retired: " + ", ".join(names)
        ax.annotate(
            label, (ts, 1.0), xycoords=("data", "axes fraction"),
            textcoords="offset points", xytext=(8, -10 - 13 * (i % 4)),
            rotation=0, va="top", ha="left", fontsize=7.5, color="#7c3aed",
        )
    print(f"marked {len(events)} retirement event(s): "
          + "; ".join(f"{ts:%Y-%m-%d %H:%M} -> {names}" for ts, names in events))

    ax.axhline(50, color="#64748b", linestyle="--", linewidth=1, alpha=0.6, zorder=1)

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
    ax2.set_title(f"Average LOSING game length vs. benchmark bots over time (full ≥20-game tallies)")
    ax2.set_xlabel("Date (Pacific Time)")
    ax2.set_ylabel("Average rounds per losing game")
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
