#!/usr/bin/env python3
"""
Summarize bytecode-budget near-misses/overruns across one or more replays.

Iteration 127 added live in-game tracking (see RobotPlayer.java's
checkBytecodeBudget/SA_BYTECODE_NEARMISS/SA_BYTECODE_OVERRUN): every Archon's
own indicator string reports the team-wide cumulative counts each round
("r123 bcN0 bcO0 ..."). This tool scans replay(s) for the highest bcN/bcO
values reported (i.e. the final tally for that game) via
tools/bc22_replay.py --indicators, so a whole Gauntlet run (or any set of
replays) can be checked at once instead of manually grepping one file at a
time.

Usage:
    tools/.venv/bin/python3 tools/check_bytecode.py <file_or_dir> [more...]
    tools/.venv/bin/python3 tools/check_bytecode.py gauntlet/<run-id>/losses/

Only flags games where bcO > 0 (a confirmed overrun) or bcN is unusually
high, as actionable; a clean run prints a one-line summary.
"""
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PATTERN = re.compile(r"bcN(\d+) bcO(\d+)")


def scan(path):
    out = subprocess.run(
        [str(REPO_ROOT / "tools" / ".venv" / "bin" / "python3"),
         str(REPO_ROOT / "tools" / "bc22_replay.py"), str(path), "--indicators"],
        capture_output=True, text=True,
    ).stdout
    best_n, best_o = 0, 0
    for m in PATTERN.finditer(out):
        n, o = int(m.group(1)), int(m.group(2))
        best_n = max(best_n, n)
        best_o = max(best_o, o)
    return best_n, best_o


def collect_files(args):
    files = []
    for a in args:
        p = Path(a)
        if p.is_dir():
            files.extend(sorted(p.glob("*.bc22")))
        elif p.is_file():
            files.append(p)
    return files


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    files = collect_files(sys.argv[1:])
    if not files:
        print("no .bc22 files found")
        sys.exit(1)

    total_n, total_o = 0, 0
    flagged = []
    for f in files:
        n, o = scan(f)
        total_n += n
        total_o += o
        if o > 0 or n > 0:
            flagged.append((f, n, o))

    print(f"scanned {len(files)} replay(s)")
    if not flagged:
        print("clean: 0 near-misses, 0 overruns across all games")
        return
    print(f"{len(flagged)} game(s) with nonzero counts (sum across flagged games: "
          f"{total_n} near-miss rounds, {total_o} overrun rounds):")
    for f, n, o in flagged:
        marker = "OVERRUN" if o > 0 else "near-miss only"
        print(f"  {marker:16s} bcN={n:<6d} bcO={o:<4d} {f}")


if __name__ == "__main__":
    main()
