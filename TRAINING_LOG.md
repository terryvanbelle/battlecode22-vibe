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

## Iteration 2  —  coordinated hunt

**Step 6 — Solution.**

- Archons publish their start location to the shared array (slots 5–8).
- Every unit reports sensed enemy Archons to shared slots 20–23, and clears a
  slot when standing next to that location with nothing there.
- Soldier target priority: attack weakest enemy in range (Archons first) →
  else march to the nearest *known* enemy Archon → else advance on the nearest
  sensed enemy → else sweep the nearest **symmetric image** of one of our
  Archon starts (rotation / h-flip / v-flip — map symmetry is unknown, so all
  candidates are hunted; soldiers pick "nearest", which clusters them).
- Miners also chase and mine gold drops.
- Instrumentation: `enemyArchonsKnown=` added to the Archon report.

*Step 6.3 — re-run the losing game* (`intersection`): Iteration 2 **wins** as
both B (annihilation, round 1429 — was a round-2000 tiebreak loss) and A
(annihilation, round 808). → back to Step 2, full Gauntlet for Iteration 2
against `{examplefuncsplayer, g_iter1}`.

**Gauntlet (step 2/3).** Iteration 2 = **25/40 (62.5%)** — barely ≥ `WinPct`.
vs `examplefuncsplayer` **20/20**, but vs **`g_iter1` only 5/20**: the coordination
change is a net *regression* against a real opponent. Passes the gate → added as
`g_iter2`. Infra: per-game `./gradlew` overloaded the VM (SSH kept dropping);
switched to one bare `java` per game, 6-way parallel, 8-vCPU VM, 10-map loop set.

**Step 4:** loss `bot` (A, the first-mover side) vs `g_iter1` (B) on `maptestsmall`
— annihilated at round **485**.

### Step 5 — Hypothesis (iteration 1 of ≤5)

*Hypothesis:* Iteration 2's soldiers never concentrate. Each independently picks
"nearest known enemy Archon / nearest sensed enemy / nearest symmetric
candidate", so the army fragments into 2–3 groups heading different ways and
meets `g_iter1`'s single cohesive blob (all its soldiers march to the mirror of
their shared spawn point, so they clump) in pieces — losing every engagement.
No soldier defends home, so raiders wipe our miners (down to **1** by round 300
vs the enemy's 14) and then the Archon. We build 200+ soldiers but they arrive
one at a time.

| # | variable | threshold | measured @ r300 | ✓ |
|---|----------|-----------|-----------------|---|
| V1 | our miners vs enemy miners | ours ≤ ⅓ theirs | 1 vs 14 | ✓ |
| V2 | our team lead vs enemy lead | ours ≤ ¼ theirs | 10 vs 3386 | ✓ |
| V3 | our army split into ≥2 clusters; enemy is 1 | yes | yes (board) | ✓ |
| V4 | loss by annihilation before round 600 | yes | r485 | ✓ |
| V5 | our Archon HP monotonically bled from ~r220 | yes | 600→441→…→12→0 | ✓ |

**All five criteria met → hypothesis verified.** Proceed to Step 6.

---

## Iteration 3  —  one shared objective + home defense

**Step 6 — Solution.**

*Attempt 1 (rejected).* Single objective + "regroup when locally outnumbered" +
per-soldier candidate sweeps + home flag + fleeing miners. Re-running the losing
game: **lost** `maptestsmall` both sides (r553 / r1070) and `maze` both sides —
worse than Iteration 2. The "regroup when outnumbered" made soldiers oscillate
and never commit. **Undone (step 6.5), back to step 6.1.**

*Attempt 2 (accepted).* Keep Iteration 1's soldier COMBAT logic verbatim (it
wins fights); change ONLY what a soldier does with no enemy in sight — from
"mirror of my own position" (128 different points) to one army-wide objective:
threatened friendly Archon → known enemy Archon (fixed slot) → mirror of our
*first* Archon start (one shared point). Plus miners flee enemy combat units;
any unit flags an Archon with enemies near it, Archons clear it when safe.

*Step 6.3 — re-run the losing game* (`maptestsmall`, bot as A): Iteration 3
**wins at round 154** (was an annihilation *loss* at r485), and also wins side B
(r299). → back to Step 2, full Gauntlet vs `{examplefuncsplayer, g_iter1, g_iter2}`.

**Gauntlet (step 2/3).** Iteration 3 = **46/60 (76.7%)** ≥ `WinPct` — the
strongest iteration so far. vs `examplefuncsplayer` 20/20, vs `g_iter1` 16/20
(recovered from Iteration 2's 5/20), vs `g_iter2` 10/20. **Added as `g_iter3`.**
Losses cluster on the obstacle-heavy maps (`pillars` 0/4, `valley`, `maze/B`).
**Step 4:** loss `bot` (B) vs `g_iter2` (A) on `pillars` — annihilated at round 804.

### Step 5 — Hypothesis (iteration 1 of ≤5)

*Hypothesis:* On obstacle-dense maps, the greedy pather (`moveToward` = try the
goal direction ± up to 2 rotations) cannot get around the large rubble-wall
blocks, so the army splinters against them and feeds into the enemy's
concentrated force piecemeal. We end up with ~50 idle, scattered miners and
never more than ~10 live soldiers while the enemy fields 30–50, and our Archons
are annihilated by round 800.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | our soldiers ≥2 clusters >8 apart; enemy 1 cluster | yes | yes @ r400 (board) | ✓ |
| V2 | our live soldiers mid-game | ≤ 15 | 6 @ r500, 10 @ r700 | ✓ |
| V3 | our miners ÷ our soldiers mid-game | ≥ 3 | 39/6 @ r500 | ✓ |
| V4 | enemy soldiers ÷ our soldiers mid-game | ≥ 3 | 30/6 @ r500 | ✓ |
| V5 | our Archons lost by round 800 | ≥ 2 | 2 (3→1) | ✓ |
| V6 | map is obstacle-dense (high-rubble fraction) | ≥ 20% | ~30% (pillars) | ✓ |

**All six criteria met → hypothesis verified.** Proceed to Step 6.

---

## Iteration 4  —  navigation + miner cap

**Step 6 — Solution.**

*Attempt 1 (rejected).* 8-direction scored pather only (rubble avoidance +
heading momentum). Re-running `pillars`/B: improved (annihilation r804 → r1481)
but **still lost**. Undone (step 6.5), back to step 6.1.

*Attempt 2 (accepted).* Keep the scored pather **and** hard-cap registered
miners at 16 — the old "TARGET_MINERS then 1-in-5 forever" rule drifted to ~50
idle miners in long games and starved the army. Re-running the losing game
(`pillars`, bot as B vs `g_iter2`): Iteration 4 **wins at round 708** (was
annihilation at r804). → back to Step 2, full Gauntlet vs
`{examplefuncsplayer, g_iter1, g_iter2, g_iter3}`.

**Gauntlet (step 2/3).** Iteration 4 = **64/80 (80.0%)** ≥ `WinPct` — strongest
yet. vs `examplefuncsplayer` 20/20, `g_iter1` 16/20, `g_iter2` **15/20** (up from
Iteration 3's 10/20), `g_iter3` 13/20. Per-map: everything ≥ 6/8 except
**`pillars` 3/8** and **`valley` 4/8**. **Added as `g_iter4`.**
**Step 4:** loss `bot` (A, first-mover) vs `g_iter3` (B) on `pillars` —
annihilated at round 711 *despite* killing 2 of the enemy's 3 Archons by r370.

### Step 5 — Hypothesis (iteration 1 of ≤5)

*Hypothesis:* the fixed miner cap of 16 (attempt-2's fix) is too low for
obstacle/large maps — it starves mid-game lead income, so after an even-or-better
early fight the enemy's *uncapped* economy (33+ miners) rebuilds a bigger army
and annihilates us. On this game we reduce the enemy from 3 Archons to 1 by
round 370, then our soldiers collapse 11 → 1 (r210 → r530) while theirs grow
0 → 25.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | enemy Archons cut to ≤1 while we still have 3, by ~r370 | yes | B 3→1 by r370 | ✓ |
| V2 | our miners pinned at the cap while enemy's > 2× | yes | 16 vs 33 @ r450 | ✓ |
| V3 | our lead income ≈ passive (+2/round) mid-game | yes | +2 while enemy +6…+10 | ✓ |
| V4 | our soldiers collapse while enemy's grow | yes | 11→1 vs 0→25 (r210→r530) | ✓ |
| V5 | annihilated despite the early Archon-kill lead | yes | r711 | ✓ |

**All five criteria met → hypothesis verified.** Proceed to Step 6.

---

## Iteration 5  —  elastic economy  (pending)
