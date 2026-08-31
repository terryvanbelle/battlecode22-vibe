#!/usr/bin/env python3
"""
Plot cumulative accepted iterations over time.

Each accepted iteration gets a snapshot (src/g_iterN/, via tools/snapshot.sh),
so the count of snapshot directories over time is a reliable proxy for
"cumulative accepted iterations" -- no need to parse ACCEPTED/REJECTED text
out of TRAINING_LOG.md, whose formatting has drifted over the project's
history.

Usage:
    tools/.venv/bin/python3 tools/plot_progress.py [-o OUTPUT.png]

Requires matplotlib in tools/.venv (pip install matplotlib if missing).
Run from the repo root (uses relative git/src paths).
"""
import argparse
import re
import subprocess
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

REPO_ROOT = Path(__file__).resolve().parent.parent
PACIFIC = ZoneInfo("America/Los_Angeles")

# Process/policy changes worth marking on the timeline, as (label, commit).
# Resolved to dates via `git log` at runtime rather than hardcoding dates, so
# this stays correct even if history is rewritten. Add new entries here as
# other policy changes happen (e.g. future retirement-threshold tweaks).
MILESTONES = [
    ("retirement threshold 90%->80%", "c90a718"),
    ("MaxHypothesis/SolutionIterations 5->10", "73248a4"),
    ("mirror-check gate + near-miss extension", "9dd8da6"),
    ("round-count tracking (secondary metric)", "48ab983"),
    ("RESEARCH.md added", "d3d2e5c"),
    ("TRAINING_ALGORITHM.md rewrite: shape-based diffing", "6b492e1"),
    ("never-idle rule + high-risk structural track", "9bf8677"),
]

# Benchmark-bot win tallies (out of 20 games) over time, as recorded in
# TRAINING_LOG.md at the time each check was made. Dates are the exact
# commit timestamps for the TRAINING_LOG.md line reporting each tally
# (`git blame -L <line>,<line> -- TRAINING_LOG.md`), so this reflects when
# the check actually happened, not an estimate. Hand-curated from the log
# rather than derived automatically, since benchmark tallies are prose, not
# a structured file -- update this list when a new full (20-game) tally is
# run and reported in the log (informal spot-checks on 1-3 maps don't
# count; only add a point here for a real, reported /20 tally).
# (date_iso, camelcase_wins_of_20, afinals_wins_of_20)
BENCHMARK_HISTORY = [
    ("2026-08-27T17:50:53-07:00", 0, 2),
    ("2026-08-27T22:13:28-07:00", 0, 2),
    ("2026-08-28T03:32:28-07:00", 0, 2),
    ("2026-08-28T03:56:02-07:00", 0, 2),
    ("2026-08-28T05:56:30-07:00", 0, 2),
    ("2026-08-28T08:32:41-07:00", 0, 2),
    ("2026-08-28T17:05:11+00:00", 0, 2),
    ("2026-08-28T17:24:53+00:00", 0, 2),
    ("2026-08-28T17:50:33+00:00", 0, 2),
    ("2026-08-28T18:12:22+00:00", 0, 2),
    ("2026-08-28T18:31:50+00:00", 0, 2),
    ("2026-08-28T19:03:13+00:00", 0, 2),
    ("2026-08-28T19:57:16+00:00", 0, 2),
    ("2026-08-28T21:05:11+00:00", 0, 2),
    ("2026-08-28T21:24:25+00:00", 1, 2),   # first sample_camelcase win
    ("2026-08-28T21:44:19+00:00", 1, 3),
    ("2026-08-28T22:12:49+00:00", 1, 3),
    ("2026-08-28T22:35:57+00:00", 1, 3),
    ("2026-08-28T23:01:11+00:00", 1, 3),
    ("2026-08-29T00:06:10+00:00", 1, 3),
    ("2026-08-29T00:34:58+00:00", 0, 4),
    ("2026-08-29T01:05:31+00:00", 0, 4),
    ("2026-08-29T22:33:36+00:00", 0, 3),   # Iteration 61
    ("2026-08-30T14:52:55+00:00", 0, 4),   # this session's full 60-game tally
    ("2026-08-30T14:50:07-07:00", 0, 3),   # post-Iteration-97 check (Sage early-warning)
    ("2026-08-30T22:34:22-07:00", 0, 3),   # post-Iteration-103 check (build-priority/reinforce fixes)
    ("2026-08-31T15:34:09-07:00", 0, 3),   # post-Iteration-115 check (Sage-gate unlock; afinals unaffected -- separate A_gold=0 bottleneck)
]


def commit_date(commit):
    out = subprocess.run(
        ["git", "log", "-1", "--format=%aI", commit],
        cwd=REPO_ROOT, capture_output=True, text=True, check=True,
    ).stdout.strip()
    # git records each commit's author-local offset (varies across this
    # project's history -- some commits were made at -07:00/PDT, others at
    # +00:00/UTC depending on the machine's local clock at commit time).
    # Normalize everything to Pacific so the chart's x-axis is consistent
    # regardless of which offset a given commit happened to be made under.
    return datetime.fromisoformat(out).astimezone(PACIFIC) if out else None


def snapshot_dirs():
    src = REPO_ROOT / "src"
    names = [p.name for p in src.iterdir() if p.is_dir() and re.fullmatch(r"g_iter\d+", p.name)]
    names.sort(key=lambda n: int(n[len("g_iter"):]))
    return names


def first_commit_date(rel_path):
    out = subprocess.run(
        ["git", "log", "--diff-filter=A", "--format=%aI", "--", rel_path],
        cwd=REPO_ROOT, capture_output=True, text=True, check=True,
    ).stdout.strip()
    if not out:
        return None
    # earliest add (in case of history rewrites, take the last line = oldest)
    # -- normalized to Pacific, see the comment in commit_date().
    return datetime.fromisoformat(out.splitlines()[-1]).astimezone(PACIFIC)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--output", default=str(REPO_ROOT / "progress" / "cumulative_iterations.png"))
    args = ap.parse_args()

    rows = []
    for name in snapshot_dirs():
        d = first_commit_date(f"src/{name}")
        if d is not None:
            rows.append((name, d))
    rows.sort(key=lambda r: r[1])

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.dates as mdates

    dates = [r[1] for r in rows]
    cum = list(range(1, len(rows) + 1))

    fig, ax = plt.subplots(figsize=(13, 7))
    ax.step(dates, cum, where="post", color="#2563eb", linewidth=2)
    ax.scatter(dates, cum, color="#2563eb", s=18, zorder=3)
    ax.set_title("Cumulative Accepted Iterations Over Time (Battlecode 2022 bot)", fontsize=13)
    ax.set_xlabel("Date (Pacific Time)")
    ax.set_ylabel(f"Cumulative accepted iterations (snapshots g_iter1..{rows[-1][0][len('g_iter'):]})")
    ax.grid(True, alpha=0.3)
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%m-%d %H:%M", tz=PACIFIC))
    fig.autofmt_xdate(rotation=30)
    for name, d, c in [(rows[0][0], rows[0][1], 1), (rows[-1][0], rows[-1][1], len(rows))]:
        ax.annotate(name, (d, c), textcoords="offset points", xytext=(5, -12), fontsize=8, color="gray")

    # policy/process milestones, as vertical markers
    milestone_colors = ["#dc2626", "#16a34a", "#9333ea", "#ea580c", "#0891b2"]
    for i, (label, commit) in enumerate(MILESTONES):
        d = commit_date(commit)
        if d is None:
            continue
        color = milestone_colors[i % len(milestone_colors)]
        ax.axvline(d, color=color, linestyle="--", linewidth=1.2, alpha=0.8, zorder=1)
        ax.annotate(
            label, (d, 0), xycoords=("data", "axes fraction"),
            textcoords="offset points", xytext=(4, 8 + 30 * (i % 4)),
            rotation=90, va="bottom", ha="left", fontsize=7.5, color=color,
        )

    # benchmark-bot win rate, on a second y-axis sharing the same time axis
    bdates = [datetime.fromisoformat(d).astimezone(PACIFIC) for d, _, _ in BENCHMARK_HISTORY]
    camel_pct = [100 * w / 20 for _, w, _ in BENCHMARK_HISTORY]
    afinals_pct = [100 * w / 20 for _, _, w in BENCHMARK_HISTORY]
    ax2 = ax.twinx()
    ax2.plot(bdates, camel_pct, color="#b91c1c", linewidth=1.6, marker="o", markersize=4,
              label="vs sample_camelcase (win % of 20)")
    ax2.plot(bdates, afinals_pct, color="#059669", linewidth=1.6, marker="s", markersize=4,
              label="vs sample_afinals (win % of 20)")
    ax2.set_ylabel("Benchmark win rate (%, out of 20 games)")
    ax2.set_ylim(-5, 105)
    ax2.legend(loc="center left", fontsize=8, framealpha=0.9)

    fig.tight_layout()

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=150)
    print(f"wrote {out_path} ({len(rows)} accepted iterations, {rows[0][0]}..{rows[-1][0]})")


if __name__ == "__main__":
    main()
