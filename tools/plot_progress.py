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

REPO_ROOT = Path(__file__).resolve().parent.parent

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
]


def commit_date(commit):
    out = subprocess.run(
        ["git", "log", "-1", "--format=%aI", commit],
        cwd=REPO_ROOT, capture_output=True, text=True, check=True,
    ).stdout.strip()
    return datetime.fromisoformat(out) if out else None


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
    return datetime.fromisoformat(out.splitlines()[-1])


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

    fig, ax = plt.subplots(figsize=(11, 6))
    ax.step(dates, cum, where="post", color="#2563eb", linewidth=2)
    ax.scatter(dates, cum, color="#2563eb", s=18, zorder=3)
    ax.set_title("Cumulative Accepted Iterations Over Time (Battlecode 2022 bot)", fontsize=13)
    ax.set_xlabel("Date")
    ax.set_ylabel(f"Cumulative accepted iterations (snapshots g_iter1..{rows[-1][0][len('g_iter'):]})")
    ax.grid(True, alpha=0.3)
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%m-%d %H:%M"))
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
            textcoords="offset points", xytext=(4, 8 + 14 * (i % 2)),
            rotation=90, va="bottom", ha="left", fontsize=7.5, color=color,
        )
    fig.tight_layout()

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=150)
    print(f"wrote {out_path} ({len(rows)} accepted iterations, {rows[0][0]}..{rows[-1][0]})")


if __name__ == "__main__":
    main()
