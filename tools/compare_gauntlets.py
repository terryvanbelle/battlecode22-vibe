#!/usr/bin/env python3
"""Compare two Gauntlet runs game-by-game, including round-count movement.

Usage:
    tools/compare_gauntlets.py <baseline_dir> <candidate_dir>

Both directories are `gauntlet/<run-id>/` outputs (must contain
results.csv). For each (opponent, map, bot_side) key present in both:

  - If the win/loss outcome flipped, it's reported separately (this is
    already reflected in the two runs' WinPct, nothing new here).
  - If the outcome held the same, compute a round delta:
      win:  baseline_rounds - candidate_rounds   (positive = won faster)
      loss: candidate_rounds - baseline_rounds   (positive = lasted longer)
    A positive delta is progress toward winning even when the win/loss
    column didn't move -- the game got closer to flipping, not just
    "the same". A negative delta is regression in the same sense.

This is a *tracked* metric only (see TRAINING_ALGORITHM.md's "Logging"
section) -- it is not currently used to gate accept/reject decisions.
Round count alone doesn't capture everything that matters (e.g. a
2000-round stall isn't obviously "better" than a clean 400-round loss),
so treat this as a secondary signal for judging whether a near-miss
represents real margin progress, not a replacement for WinPct.
"""
import csv
import sys
from collections import defaultdict


def load(run_dir):
    games = {}
    with open(f"{run_dir}/results.csv") as f:
        for row in csv.DictReader(f):
            key = (row["opponent"], row["map"], row["bot_side"])
            games[key] = (row["bot_result"], int(row["rounds"]))
    return games


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    baseline, candidate = load(sys.argv[1]), load(sys.argv[2])
    common = sorted(set(baseline) & set(candidate))
    if not common:
        print("no matching (opponent, map, bot_side) games between the two runs")
        sys.exit(1)

    flips = []
    deltas = []
    for key in common:
        (b_result, b_rounds) = baseline[key]
        (c_result, c_rounds) = candidate[key]
        if b_result != c_result:
            flips.append((key, b_result, c_result, b_rounds, c_rounds))
            continue
        delta = (b_rounds - c_rounds) if c_result == "win" else (c_rounds - b_rounds)
        deltas.append((key, c_result, b_rounds, c_rounds, delta))

    print(f"{len(common)} matching games ({len(flips)} flipped outcome, "
          f"{len(deltas)} held outcome)\n")

    if flips:
        print("outcome flips:")
        for key, b, c, br, cr in flips:
            opp, map_, side = key
            print(f"  {opp:16s} {map_:14s} bot={side}  {b}(r{br}) -> {c}(r{cr})")
        print()

    if deltas:
        total = sum(d for *_, d in deltas)
        improved = sum(1 for *_, d in deltas if d > 0)
        worsened = sum(1 for *_, d in deltas if d < 0)
        print(f"round-delta on held-outcome games: total {total:+d}, "
              f"{improved} improved / {worsened} worsened / "
              f"{len(deltas) - improved - worsened} unchanged")
        print("(positive = win faster or lose slower; negative = the reverse)\n")
        for key, result, br, cr, delta in sorted(deltas, key=lambda x: x[-1]):
            if delta == 0:
                continue
            opp, map_, side = key
            print(f"  {opp:16s} {map_:14s} bot={side}  {result:4s} "
                  f"r{br}->r{cr}  ({delta:+d})")


if __name__ == "__main__":
    main()
