# Training log

Running record of the `TRAINING_ALGORITHM.md` loop. Newest entries at the bottom.

Hyperparameters: `WinPct = 60%`, `MaxHypothesisIterations = 5`, `MaxSolutionsIterations = 5`.

The Gauntlet (loop set): 14 maps × both sides = 28 games per opponent.
`maptestsmall eckleburg intersection colosseum chessboard maze sandwich jellyfish
squer pillars highway fortress valley island_hopping`. A "full" gauntlet run
(all ~75 maps) is the gate before an iteration is declared final.

Infra: builds & games run on GCE VM `battlecode-dev` (Java 8). Current bot lives
in `src/bot/`; accepted iterations are snapshotted to `src/gauntlet/<name>/`.
`tools/gauntlet.sh` runs a Gauntlet; `tools/bc22_replay.py --indicators` reads
the per-round instrumentation strings back out of a replay.

---

## Iteration 0  —  baseline

**Implementation.** Archons collectively build exactly one Miner (coordinated via
shared-array slot 0), then idle. The Miner moves in a uniformly random direction
each turn. All other unit types idle. Instrumentation: each Archon writes a
per-round `r<n> lead=.. gold=.. myArchons=.. archonHP=.. minerBuilt=..` indicator
string; the Miner writes its location.

Smoke test: `bot` vs `examplefuncsplayer` on `maptestsmall` → lost by Archon
annihilation at round 546 (expected — we build no military).

**Gauntlet (step 2/3).** Iteration 0 vs `examplefuncsplayer`: lost every game it
played (0% win rate — far below `WinPct`). Not added to the Gauntlet. Infra note:
the first two full-gauntlet attempts died to gcloud-ssh flakiness under VM load;
switched to plain `ssh` + a bigger VM (e2-standard-4). Enough losing games were
recorded to proceed. **Step 4:** selected the loss `bot` (A) vs `examplefuncsplayer`
on `maptestsmall`.

### Step 5 — Hypothesis (iteration 1 of ≤5)

*Hypothesis:* Iteration 0 loses because it builds no economy and no military. Its
one Miner only wanders (never mines), so team lead income is the +2/round passive
only; the enemy builds ~100 miners + ~100 soldiers, out-economies us ~50:1, and
its soldiers walk to our Archon and destroy it. We deal zero damage all game.

*Verification variables / thresholds:*

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | attacks performed by our team | `== 0` | 0 | ✓ |
| V2 | our lead income | passive only (≤ +2/round) | exactly +2/round (152→1152 linearly) | ✓ |
| V3 | enemy soldiers when our Archon is first hit (r401) | `≥ 20` | 86 | ✓ |
| V4 | enemy attacks landed on our Archon | `≥ 100` | 200 | ✓ |
| V5 | our Archon count at game end | `== 0` | 0 (annihilated r546) | ✓ |

Instrumentation already recorded `lead=/myArchons=/archonHP=`; the rest came from
the replay event log via `bc22_replay.py`. No instrumentation change needed.
**All five criteria met → hypothesis verified.** Proceed to Step 6.

---

## Iteration 1  —  economy + military

**Step 6 — Solution.** Fix both failures the hypothesis identified:

- Archons build Miners until ~8 exist, then Soldiers (with 1-in-5 extra Miners),
  placing builds on the lowest-rubble adjacent tile.
- Miners mine gold then lead (leaving 1 for regen) on their tile and all 8
  neighbours, and drift toward the richest lead in vision.
- Soldiers attack the weakest enemy in action range (Archons prioritised),
  else advance on the nearest sensed enemy, else march to the mirror of their
  own position `(W-1-x, H-1-y)` — enemy territory on symmetric maps.
- Instrumentation: shared-array counters for miners / soldiers / cumulative
  team attacks / total builds, surfaced in the per-round Archon indicator.

*Step 6.3 — re-run the losing game:* Iteration 1 **wins** `maptestsmall` as both
A (annihilation, round 374) and B (annihilation, round 439). Iteration 0 lost
both. → back to Step 2, full Gauntlet for Iteration 1.

**Gauntlet (step 2/3).** Iteration 1 vs `examplefuncsplayer`: **27/28 (96%)** ≥
`WinPct`. **Added to the Gauntlet as `g_iter1`.** Most wins by annihilation or
"more Archons"; a handful of round-2000 tiebreak wins. **Step 4:** the one loss —
`bot` (B) vs `examplefuncsplayer` (A) on `intersection`, round-2000 tiebreak
("more gold net worth").

### Step 5 — Hypothesis (iteration 1 of ≤5)

*Hypothesis:* Iteration 1 fails to close out clearly-won positions. In this loss
we outnumber the enemy **281 units to 18** at round 2000 (153 miners + 128
soldiers vs 15 + 2 + 1 Archon) but never locate and kill their last Archon, so
the game hits the tiebreak — which we lose on gold **0 vs 16** (their miners
collected the ~16 Au reclaimed from a dead Archon; ours mined gold **0** times).
Root cause: a soldier with no enemy in sight marches to the mirror of *its own*
position, so 128 soldiers scatter to ~85 distinct points instead of massing on
the enemy's actual last Archon; enemy-Archon sightings are never shared.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | game ended by tiebreak, not annihilation | round == 2000 | 2000 | ✓ |
| V2 | our units ÷ enemy units at round 2000 | `≥ 5` | 281 / 18 ≈ 15.6 | ✓ |
| V3 | enemy Archons alive at round 2000 | `== 1` | 1 | ✓ |
| V4 | distinct soldier march-targets (no enemy in sight) | `> 20` | ~85 | ✓ |
| V5 | shared-array slots used to broadcast enemy-Archon locations | `== 0` | 0 (code) | ✓ |
| V6 | gold our miners mined all game | `== 0` | 0 | ✓ |

**All six criteria met → hypothesis verified.** Proceed to Step 6.

---

## Iteration 2  —  coordinated hunt (pending)
