#!/usr/bin/env python3
"""
Append one row per opponent to progress/vs_old_bots_history.csv from a
completed Gauntlet run's results.csv. See tools/plot_vs_old_bots.py for
the full picture (what this data is for, how to regenerate the chart).

Usage:
    BOT=bot OPPONENTS="g_iter1 g_iter11 g_iter21 g_iter31 g_iter41 g_iter51" \\
        MAPSET=loop tools/gauntlet.sh
    tools/.venv/bin/python3 tools/track_vs_old_bots.py gauntlet/<run-id>/

current_snapshot is auto-detected as the highest-numbered src/g_iterN/
directory at the time this is run (i.e. the snapshot the working bot
was just checked in as, or will be next).
"""
import csv
import re
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

REPO_ROOT = Path(__file__).resolve().parent.parent
PACIFIC = ZoneInfo("America/Los_Angeles")
HISTORY_CSV = REPO_ROOT / "progress" / "vs_old_bots_history.csv"


def current_snapshot():
    names = [p.name for p in (REPO_ROOT / "src").iterdir()
             if p.is_dir() and re.fullmatch(r"g_iter\d+", p.name)]
    return max(names, key=lambda n: int(n[len("g_iter"):]))


def run_timestamp(rundir):
    m = re.search(r"(\d{8})-(\d{6})", rundir.name)
    dt = datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S").replace(tzinfo=timezone.utc)
    return dt.astimezone(PACIFIC)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    rundir = Path(sys.argv[1])
    results_csv = rundir / "results.csv"
    if not results_csv.exists():
        results_csv = rundir  # allow passing results.csv directly
        rundir = rundir.parent

    tally = defaultdict(lambda: [0, 0])
    with open(results_csv) as f:
        for row in csv.DictReader(f):
            opp = row["opponent"]
            tally[opp][1] += 1
            if row["bot_result"] == "win":
                tally[opp][0] += 1

    ts = run_timestamp(rundir).isoformat()
    snap = current_snapshot()

    is_new = not HISTORY_CSV.exists()
    with open(HISTORY_CSV, "a") as f:
        if is_new:
            f.write("date,current_snapshot,opponent,wins,total,win_pct\n")
        for opp in sorted(tally, key=lambda n: int(n[len("g_iter"):]) if n.startswith("g_iter") else 0):
            wins, total = tally[opp]
            pct = round(100 * wins / total, 1) if total else 0.0
            f.write(f"{ts},{snap},{opp},{wins},{total},{pct}\n")

    print(f"appended {len(tally)} rows to {HISTORY_CSV} (snapshot={snap}, date={ts})")


if __name__ == "__main__":
    main()
