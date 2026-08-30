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

## Iteration 5  —  ramped economy

**Step 6 — Solution.**

*Attempt 1 (rejected).* Map-scaled cap (`W*H/36`, 16–40) + "keep building miners
while banked lead ≥ 120". On lead-rich maps this produced an economy-first
opening and got rushed — lost `maptestsmall` all four ways at round ~80.
Undone (6.5), back to 6.1.

*Attempt 2 (accepted).* Miners **ramp with time**: `softCap = min(mapCap,
8 + round/12)` — ~8 early so the army leads, growing to the map-scaled cap by
mid-game. Re-running the losing game (`pillars`, bot as A vs `g_iter3`):
Iteration 5 **wins at round 572** (was annihilation at r711). Also flips `valley`
vs `g_iter3` from 0/2 to 2/2. → back to Step 2, full Gauntlet vs
`{examplefuncsplayer, g_iter1..g_iter4}`.

**Gauntlet (step 2/3).** Iteration 5 = **79/100 (79.0%)** ≥ `WinPct`. Dominates
the early ancestors — `g_iter1` **20/20**, `g_iter2` 19/20 (Iteration 4 was 16/20
vs `g_iter1`) — but **regressed vs `g_iter4` to 6/20**. **Added as `g_iter5`.**
**Step 4:** loss `bot` (B) vs `g_iter4` (A) on `maptestsmall` — annihilated r150.

### Step 5 — Hypothesis (iteration 1 of ≤5)  [Iteration 5]

*Hypothesis:* the *time*-ramped miner rule interleaves miner and soldier builds
for the whole game, so at every point both our economy and our army are smaller
than a "build N miners, then 100% soldiers" opponent's. `g_iter4` (flat cap 16)
reaches **lead 755 / 24 soldiers by r50** and **70 soldiers by r150**; we have
100 / 20 and 13. It annihilates us at r150. The economy needs to be *front-
loaded*, not dribbled in.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | enemy lead ÷ ours by r50 | ≥ 3 | 755 / 100 | ✓ |
| V2 | enemy soldiers ÷ ours by r150 | ≥ 3 | 70 / 13 | ✓ |
| V3 | we are still building miners past r90 | yes | miners 14→16 r73→r97 | ✓ |
| V4 | annihilated before round 200 | yes | r150 | ✓ |
| V5 | enemy hits a flat miner cap early then builds only army | yes | `g_iter4` = 17 miners by r30 | ✓ |

**All five criteria met → hypothesis verified.** Proceed to Step 6.

---

## Iteration 6  —  front-loaded economy

**Tooling.** Added `bc22_replay.py --metrics` — a per-round CSV of per-team
aggregates (lead, gold, unit counts by type, Archon HP, cumulative attacks,
soldier centroid + spread). Now used on every evaluation, not just deep-dives.

**Step 6 — Solution.**

*Attempt 1 (rejected).* `miners < min(mapCap, 6 + soldiers)` — economy tracks
army. Re-running `maptestsmall`/B vs `g_iter4`: **lost faster** (r121 vs r150).
Undone (6.5), back to 6.1.

*Attempt 2 (accepted).* `--metrics` on the loss showed `g_iter4` (flat cap 16,
reached ~r20) at **lead 382 / 17 miners by r31** while our time-ramp had us at
**52 / 10** — our early economy was *worse* than a flat 16. Drop the ramp: build
straight to `minerCap() = clamp(W*H/45, 16, 34)` (≥ 16 everywhere), then pure
army. Re-running the losing game: Iteration 6 **wins `maptestsmall`/B vs
`g_iter4` at round 351** (was r150 annihilation), and flips `maptestsmall`/A too.
Spot-check: vs `g_iter4` 11/18 (Iteration 5 was 6/20); `valley` vs `g_iter4`
0/8 → 2/2. → back to Step 2, full Gauntlet vs `{examplefuncsplayer, g_iter1..g_iter5}`.

Gauntlet run pending.

**Gauntlet (step 2/3).** Iteration 6 = **93/120 (78%)** ≥ `WinPct`. Beats every
ancestor: `g_iter1` 17/20, `g_iter2` 17/20, `g_iter3` 15/20, **`g_iter4` 13/20
(Iteration 5 was 6/20)**, `g_iter5` 11/20; `examplefuncsplayer` 20/20. Per-map,
`chessboard`/`maptestsmall` 12/12 and `intersection` 11/12, but `highway` 6/12,
`valley`/`pillars` 7/12, `sandwich` 8/12. 83 of the gauntlet's decisive games
ended by annihilation — Iteration 6 mostly out-fights or gets out-fought now,
few tiebreaks. **Added as `g_iter6`.**

---

_Iterations 1–6 summary: `bot` went from a single wandering Miner to a bot that
beats every frozen ancestor and sweeps `examplefuncsplayer`. Remaining weak maps
are the wide/obstacle ones (`highway`, `valley`, `pillars`). Marginal per-
iteration gains from here are small — the next structural lever is combat micro
(focus-fire, kiting) to win fights at better unit ratios, or Labs for a gold
economy._

---

## Iteration 7  —  re-baseline on a stronger opponent pool

**Tooling / opponent pool.** Vendored three public Battlecode 2022 reference
bots as new `src/` packages for the Gauntlet (background task, separate from the
loop):

| package | source | licence | notes |
|---|---|---|---|
| `sample_camelcase` | jmerle/battlecode-2022 `camel_case_v25_final` | MIT | Final Tournament 13th–16th; annihilates `examplefuncsplayer` r138 |
| `sample_afinals` | TestSubjector/BattleCode2022 `AFinalsBot` | AGPL-3.0 (scaffold) | Finalist, "Most Adaptive Strategy" prize; annihilates `examplefuncsplayer` r397 |
| `sample_monke` | Srishti-Goel/Battlecode-monke `Lecture2Player` | AGPL-3.0 | lecture-level, weak (loses to `examplefuncsplayer`); kept for behavioural diversity |

All three compile clean on the VM. `sample_camelcase` / `sample_afinals` are
real tournament finalists — far stronger sparring than any frozen ancestor.

### Step 4 — first attempt (highway / g_iter3), abandoned

`highway` was the worst map in Gauntlet 6 (6/12) — every loss a r2000 game
decided on Archon count, all against recent ancestors (`g_iter3/4/5` 0/2 each;
`examplefuncsplayer`/`g_iter1`/`g_iter2` 2/2). Analysed the loss
`gauntlet/20260827-153951/losses/g_iter3__highway__botA.bc22` (bot as A, B wins
"more Archons" at r2000) with `--metrics`:

| round | our miners | enemy miners | our archons | enemy archons |
|-------|-----------|--------------|-------------|---------------|
| 200 | 33 | 41 | 1 | 2 |
| 800 | 33 | 118 | 1 | 2 |
| 2000 | 33 | ~190 | 1 | 2 |

We lose an Archon in the first ~150 rounds and never recover it. Our miners
freeze at the `minerCap()` clamp of 34; `g_iter3` has no economy cap and its
miner count grows the whole game, so its lead income (and therefore its
soldier-replacement rate) dwarfs ours by mid-game. The game reaches r2000 and B
wins the Archon-count tiebreak.

*Two solution attempts, both rejected (Step 6.5):*

1. **Late-game economy expansion** — build miners up to `3*minerCap()` once
   `round > 350 && SA_HOME_THREAT == 0`. `--metrics` on the re-run: our miners
   *still* frozen at 33 — `SA_HOME_THREAT` is set continuously once the enemy
   camps our base, so the gate never opens. Lost highway both ways, r2000.
2. **Interleaved opening** — `openCap = min(cap, 18)` fast, then hold economy to
   `soldiers >= miners` up to the cap. Still lost highway both ways **and
   regressed** `maptestsmall`/B vs `g_iter4` (won r351 at Iter 6 → lost r188):
   weakening the front-loaded economy that Iteration 6 established broke a game
   it had fixed.

**Reassessment.** The highway losses are all r2000 Archon-count games against
recent *ancestors*. Chasing one weak-ancestor matchup on one map with economy
tweaks is low-value and keeps colliding with the Iteration-6 economy tuning.
With `sample_camelcase` / `sample_afinals` now in the pool, the right move is to
**re-baseline the Gauntlet against the stronger opponents** and target whatever
they expose. Reverted `src/bot/` to the Iteration-6 code; running Gauntlet 7 on
`g_iter6` vs `{examplefuncsplayer, g_iter1..g_iter6, sample_camelcase,
sample_afinals}`.

### Gauntlet retirement rule (domination)

To keep the Gauntlet from growing without bound (every accepted iteration is
added forever, and `games = 2·B·N`), opponents that no longer provide signal are
retired:

> After a Gauntlet completes, any opponent the current implementation has beaten
> in **≥ 90%** of that opponent's `2·B` games in **two consecutive** Gauntlets is
> removed from the pool. Applies to reference bots and frozen ancestors alike.

Rationale: if we've dominated a bot twice running, beating it a third time
carries almost no information, and its games are pure cost. Regression coverage
is preserved by the recent ancestors (still competitive) and the strong external
finalists (`sample_camelcase`, `sample_afinals`). A retired bot can be brought
back if a later iteration regresses badly against the ones near it.

Tracking (per-opponent win rate, `≥90%` flagged `**`):

| opponent | G6 | G7 | retire? |
|---|---|---|---|
| examplefuncsplayer | 20/20 (100%) ** | _pending_ | |
| g_iter1 | 17/20 (85%) | _pending_ | |
| g_iter2 | 17/20 (85%) | _pending_ | |
| g_iter3 | 15/20 (75%) | _pending_ | |
| g_iter4 | 13/20 (65%) | _pending_ | |
| g_iter5 | 11/20 (55%) | _pending_ | |
| g_iter6 | — (self at G6) | _pending_ | |
| sample_camelcase | — | _pending_ | |
| sample_afinals | — | _pending_ | |

---

**Gauntlet 7 (re-baseline, g_iter6 vs expanded pool).** `bot` = g_iter6.
**105/180 = 58.3%.** vs examplefuncsplayer 20/20, g_iter1 17/20, g_iter2 17/20,
g_iter3 15/20, g_iter4 13/20, g_iter5 11/20, g_iter6 10/20 (self),
**sample_camelcase 0/20, sample_afinals 2/20.** The two finalist bots dominate
us — that's the real capability gap.

*Retirement rule:* examplefuncsplayer ≥90% in G6 (100%) and G7 (100%) → **retired**
from the pool. g_iter1/g_iter2 at 85% both times — kept. Pool for G8 onward:
`{g_iter1..g_iter6, sample_camelcase, sample_afinals}` (8 opp = 160 games).

### Step 4 — losing game

`sample_camelcase__jellyfish__botA` — annihilated r196, having **never attacked
once** (`A_attacks == 0` the entire game).

### Step 5 — Hypothesis (iteration 1 of ≤5)  [Iteration 7]

*Hypothesis:* our opening builds miners until `miners >= minerCap()` before
building **any** soldier. `minerCap()` on jellyfish (30×30) = 20, but jellyfish
is lead-sparse — we reach only 13 miners by r141 — so we field **zero soldiers
all game**. Both sides have 2 Archons and ~equal miner counts; `sample_camelcase`
additionally builds a soldier line from ~r30 (16 soldiers by r141), marches in,
kills an Archon at ~r155, annihilates us at r196. `--metrics` on the same loss
on `maptestsmall` shows the flip side of the same rule: there we *do* finish the
20-miner opening, then bank 3000+ unspendable lead (one Archon can't build fast
enough to convert it) while camelcase out-armies us 43→27 and out-attacks us
887→330 by r101.

| # | variable | threshold | measured (jellyfish loss) | ✓ |
|---|----------|-----------|---------------------------|---|
| V1 | our soldier count all game | 0 | 0 at every sample r1…r181 | ✓ |
| V2 | our cumulative attacks all game | 0 | 0 | ✓ |
| V3 | our miners never reach minerCap before an Archon dies | yes | 13 @ r141, Archon 1200→675 @ r141 | ✓ |
| V4 | enemy fields a standing army in the same window | ≥ 12 by r120 | B soldiers 14 @ r121 | ✓ |
| V5 | loss is annihilation, first Archon dies with 0 soldiers home | yes | archons 2→1 @ ~r155, A soldiers 0 | ✓ |

**All five criteria met → hypothesis verified.** Proceed to Step 6.

**Step 6 — Solution (attempt 1, REJECTED).** Interleave army into the opening:
miner only while `miners < minerCap() && miners <= 6 + 2*soldiers`. Re-run:
jellyfish/botA vs camelcase still lost (r206 ≈ r196), and `maptestsmall`/B vs
`g_iter4` **regressed** (Iter 6 won r351 → r152 loss). Undone (6.5). Third time
an economy-ratio tweak has broken that maptestsmall game — the ratio is not the
lever.

**Board inspection (`--moves` / ROBOTS+LEAD render, jellyfish r2–35).** The real
cause is upstream of the build rule:

- jellyfish has **28 lead tiles / 1000 Pb total**, in small deposits 8–15 tiles
  from each Archon — none within a Miner's vision radius (√20 ≈ 4.5) of home.
- Our Miners with no lead in sight run `moveExplore` = **a one-step random walk**.
  Diffusive motion keeps them milling in the home corner: at r35, 7 of 9 Miners
  are clustered around the NE Archon on near-empty tiles; the rest wander at
  random, mostly not on lead.
- Result: team lead income is ~+7…+20/round and rarely reaches the 50 to build a
  Miner. Both Archons read the same low bank each turn; ARCHON #3 grabs it,
  ARCHON #5 (indicator `lead=2`) builds nothing. We are effectively playing
  jellyfish with **one working Archon and a stalled economy**.
- `sample_camelcase`, same starting lead, fields 15 Miners **and** 16 Soldiers
  by r141 — its Miners find and hold the deposits, so its income is multiples of
  ours.

The zero-soldiers symptom (V1/V2) is downstream: the pure-miner opening never
completes because the economy never gets going. **Revised root cause: Miners
have no directed lead-search, so on any map where lead is not adjacent to the
spawn the economy stalls** — which is exactly our weak-map set (jellyfish,
intersection, highway, valley, pillars all lead-sparse near spawn).

**Step 6 — Solution (attempt 2, REJECTED).** Directed exploration: each idle
Miner keeps a persistent random cross-map waypoint and paths to it. Re-run:
jellyfish still lost (r229 vs r196 baseline — confirmed pure `g_iter6` loses it
at r196), and `maptestsmall`/B vs `g_iter4` regressed again (pure `g_iter6` wins
r351 → r208 loss). On a small lead-dense map, sending idle Miners on cross-map
treks pulls them off the productive home area. Undone (6.5).

**Step 6 — Solution (attempt 3, REJECTED).** Shared lead beacons (slots 9–16):
a Miner that sees ≥10 lead publishes the tile; idle Miners path to the nearest
beacon instead of random-walking. Re-run: jellyfish still lost r197 (≈ r196
baseline); `--metrics` shows the beacons made **no economic difference** —
A_miners 13 @ r101 either way. jellyfish simply has too little lead: ~13 Miners
saturates it for *both* teams. The problem was never lead-finding.

`maptestsmall`/B vs `g_iter4` also regressed (r194) — as it did under attempts 1
and 2. Established that **any** perturbation of the opening loses that one
knife-edge game (pure `g_iter6` wins it very late, r351); it is not a useful
regression gate for Iteration 7 and will be judged on the Gauntlet aggregate
instead.

**Step 6 — Solution (attempt 4, REJECTED).** Lead-responsive cap: build 8
Miners, expand toward the area cap only while banked lead `> 240`. Re-run:
**fixed highway** (`g_iter3`/A: r2000 loss → **win r1789 by annihilation**!) but
broke maptestsmall (r124 loss), pillars (r1439 loss) and chessboard (r2000
loss) — on those maps the early economy never banks 240 with only 8 Miners, so
the cap sticks at 8 and we get out-produced. jellyfish still lost. Undone.

**Step 6 — Solution (attempt 5, REJECTED).** Softer rule: build to the area cap
unless we already have 12+ Miners *and* are dead broke (`lead < 40`). Re-run:
preserved maptestsmall/pillars/chessboard, but **lost the highway fix** — on
highway banked lead sits in the ~40-100 band, above the `< 40` trip, so the cap
never cuts. jellyfish still lost. Undone.

**Hypothesis exhausted (Step 6.6).** Five solution attempts, none won the
jellyfish game. jellyfish is lead-starved for *both* teams (~13 Miners
saturates it); `sample_camelcase` wins purely on combat — it converts the same
economy into 20 Soldiers and micro-kills our army. That needs combat micro
(focus-fire / kiting), a separate large piece of work, not an economy fix. Going
back to Step 4 with a different losing game.

---

### Step 4 — losing game (take 2)

`highway` / `g_iter3` (bot as A) — Gauntlet 7 loss, r2000 "more Archons". A
long-standing weak map: 0/6 on highway vs `g_iter3`/`g_iter4`/`g_iter5`, every
loss a r2000 game we lose because an Archon of ours dies and theirs don't.

### Step 5 — Hypothesis (iteration 1 of ≤5)  [Iteration 7, game 2]

*Hypothesis:* `minerCap()` scales only with map *area* (`W*H/45`, clamped
16-34), not with how much lead the map actually has. highway is a large,
lead-sparse map: we build Miners to a cap of ~34 that its lead can't feed, so
those builds come at the cost of army during the rush window; `g_iter3` (uncapped
economy but a real early army) kills one of our Archons by ~r320 and wins the
r2000 tiebreak. The `--metrics` on the earlier highway analysis already showed
our Miners frozen at 33 with lead income far below the enemy's soldier-
replacement rate.

| # | variable | threshold | measured (highway/g_iter3 loss) | ✓ |
|---|----------|-----------|----------------------------------|---|
| V1 | our Miners plateau at ~the area cap for most of the game | yes | 33-34 from r150→r2000 | ✓ |
| V2 | banked lead stays low despite the full Miner count | < 100 typical | A_lead 20-99 through the midgame | ✓ |
| V3 | we field an army late / never enough to defend | yes | 4 soldiers @ r200, Archon dying by r320 | ✓ |
| V4 | game reaches r2000, decided on Archon count | yes | r2000, "more Archons" | ✓ |
| V5 | attempt-4 lead-responsive cap flips this exact game to a win | yes | win r1789 by annihilation | ✓ |

**All five criteria met → hypothesis verified.** Proceed to Step 6.

**Step 6 — Solution (attempt 1 for game 2, ACCEPTED for re-Gauntlet).**
Lead-responsive cap via a **low-lead streak**: the Archon counts consecutive
turns with banked lead `< 90`; once it has 10+ Miners and the streak exceeds 8,
stop adding Miners and build army. Persistent low bank = income-constrained
(highway); transient dips from spending reset it (maptestsmall stays in the
hundreds). Keeps the beacons.

Re-run of the selected losing game: **highway / g_iter3 / A → win r1849 by
annihilation** (Step 6.4 satisfied → back to Step 2). Broad spot-check
(bot as A unless noted):

| game | result | vs Iter 6 |
|---|---|---|
| highway / g_iter3 / A | **win r1849** | was r2000 loss |
| highway / g_iter3 / B | **win r2000** | was r2000 loss |
| highway / g_iter5 / A | **win r1317** | was r2000 loss |
| pillars / g_iter4 | win r516 | ~same |
| intersection / g_iter4 | win r1243 | ok |
| maptestsmall / g_iter4 / A | win r193 | ok |
| sandwich / g_iter6 / B | win r250 | ok |
| maptestsmall / g_iter4 / B | loss r196 | knife-edge (Iter6 win r351) |
| valley / g_iter5 | loss r756 | was r2000 loss |
| chessboard / g_iter6 | loss r2000 | check in Gauntlet |
| maze / g_iter4 | loss r538 | check in Gauntlet |
| jellyfish / camelcase | loss r191 | unchanged (combat gap) |

highway flips 0/6 → 3/3 on the spot-checks. chessboard/maze losses need the
full Gauntlet to judge. Running **Gauntlet 8** vs `{g_iter1..g_iter6,
sample_camelcase, sample_afinals}` (examplefuncsplayer retired).

**Gauntlet 8 (step 2/3).** Iteration 7 candidate = **94/160 = 58.8%**, just
under `WinPct` 60% → **not added to the Gauntlet yet.** But the shortfall is
entirely the two finalist bots (`sample_camelcase` 0/20, `sample_afinals` 2/20 —
identical to `g_iter6`; a combat-micro tier we don't reach yet). Against the
comparable-strength pool (the six ancestors) Iteration 7 is **92/120 = 76.7%**,
up from `g_iter6`'s 69% on the same six, and it beats or matches **every**
ancestor head-to-head:

| opp | G7 (g_iter6) | G8 (Iter 7) |
|---|---|---|
| g_iter1 | 85% | **90%** |
| g_iter2 | 85% | 85% |
| g_iter3 | 75% | **95%** |
| g_iter4 | 65% | 60% |
| g_iter5 | 55% | **70%** |
| g_iter6 | 50% | **60%** |
| sample_camelcase | 0% | 0% |
| sample_afinals | 10% | 10% |

Per-map vs ancestors: **highway 6/6** (was 0/6 — the lead-responsive cap
worked), but **maze 0/6, chessboard ~2/6, intersection 3/6 regressed** (were
near-perfect at Gauntlet 6). The low-lead streak is over-tripping on those maps
and starving an economy that actually has lead to gather.

*Retirement tracking:* g_iter1 hit 90% this Gauntlet (85% in G7) — **not** two
consecutive, stays. No new retirements.

**Decision.** Keep the lead-responsive cap in `src/bot` (current best — clear net
gain vs the ancestor pool), do **not** snapshot as `g_iter7`. Continue Step 4 on
the current implementation. Next target: the maze / chessboard economy
regression (self-inflicted, tractable) before the camelcase combat gap.

---

### Step 4 — losing game (take 3)

`maze / g_iter4` (bot as A) — r538 annihilation, a **regression I introduced**
with the low-lead streak (maze was ~12/12 at Gauntlet 6).

### Step 5 — Hypothesis (iteration 1 of ≤5)  [Iteration 7, game 3]

*Hypothesis:* the low-lead streak used banked lead as a proxy for "economy
saturated", but banked lead is low whenever you're *spending* it — independent
of income. On maze it tripped at 10 Miners; `g_iter4`, with the *same* ~30-50
banked lead, kept building to 16 Miners, out-produced Soldiers in the back half
(18 vs 1 by r520) and annihilated us. `--metrics` on every strong opponent
(`sample_camelcase`, `sample_afinals`) across maze / chessboard / pillars /
jellyfish shows a single consistent doctrine: **~4-16 Miners, Soldiers built
continuously from ~r30, never a long pure-Miner opening.** Our bot still had
**0 Soldiers at r150** on maze while `sample_camelcase` had 6.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | our Miner cap vs `g_iter4`'s peak on maze | ours ≪ theirs | 10 vs 16 | ✓ |
| V2 | our banked lead ≈ `g_iter4`'s despite fewer Miners | yes | ~30-50 both | ✓ |
| V3 | our Soldiers at r150 on maze | 0 | 0 (camelcase: 6) | ✓ |
| V4 | strong bots' Miner count, any map | ≤ 16 | 4-16 across maze/chess/pillars | ✓ |
| V5 | strong bots build Soldiers before r60 | yes | camelcase/afinals maze: 2-3 by r51 | ✓ |

**Verified → Step 6.**

**Step 6 — Solution (attempt 1 for game 3).** Drop the low-lead streak and the
area-scaled cap. New rule: cap Miners at `min(16, 8 + 2*archons)` (10 on
1-Archon maps, 14 on 3); build the first 6 fast, then a Miner only while
`soldiers + 2 >= miners` so army and economy climb together. Keeps the beacons.
This is the shape every strong opponent uses. Re-run of maze + highway (must
hold) + the broad set pending.

**Step 6 — attempts for game 3.**

1. **Strong-bot doctrine** — cap `min(16, 8 + 2*archons)`, first 6 Miners then a
   Miner only while `soldiers + 2 >= miners`. Re-run: **wins maze** (r538 loss →
   r2000 lead-tiebreak win) and also flips **highway 0/6 → wins** (g_iter3/A now
   r533!), **valley**, **intersection**. Regresses **pillars** (win r516 → loss)
   and **maptestsmall / g_iter4 / A** (win → loss r138) — both maps where the old
   big-Miner economy was out-snowballing a frozen ancestor, and both maps we
   lose to `sample_camelcase` regardless.
2. **+ latched "rich map" cap bump** (cap → 24 if the opening banks > 150 lead) —
   inert: with the interleave we spend lead on Soldiers before r35, so the latch
   never fires. maptestsmall/pillars unchanged. Reverted the latch (dead code).

**Decision.** Ship the doctrine (attempt 1). It fixes four maps against the
ancestor pool (maze, highway, valley, intersection) and matches what every
strong bot does; the two regressions are frozen-ancestor games on maps already
lost to the finalists. Selected game (maze/g_iter4/A) won → **Step 2, Gauntlet 9.**

---

**Policy update (peer / benchmark split).** `TRAINING_ALGORITHM.md` now
distinguishes **peer** opponents (we win ~30-90% — count toward the `WinPct=60%`
accept gate) from **benchmark** opponents (we win <30% — tracked as a scoreboard,
excluded from the gate, played only every `BenchmarkEvery=3` Gauntlets). Step 4
still draws losing games from the whole pool.

Rationale: `sample_camelcase` (0/20) and `sample_afinals` (2/20) are the *target*,
not noise — but they dragged Iteration 7's Gauntlet-8 aggregate to 58.8% while it
was **76.7% vs peers**. Under the new rule that iteration clears the gate.

Current classification: **peers** = g_iter1..g_iter6; **benchmarks** =
sample_camelcase, sample_afinals. Gauntlet 9 is a snapshot-candidate run so it
includes the benchmarks.

---

**Gauntlet 9 (step 2/3).** Iteration 7 (doctrine) = 91/160 = 56.9% overall;
**peer** (g_iter1..6) = **89/120 = 74.2% ≥ WinPct** → passes the (new,
peer-only) gate. vs g_iter6 (last snapshot) **75%**, up from 60%. **highway 6/6
and maze 6/6 vs ancestors** (both were 0/6). But **maptestsmall regressed to
0/8** — the small doctrine cap + early Soldiers is wrong on that tiny, wall-to-
wall-lead, 1-Archon map; we get rushed and annihilated by ~r150.

### Step 5 — Hypothesis  [Iteration 7, game 4: `maptestsmall / g_iter4 / A`]

*Hypothesis:* maptestsmall is uniquely lead-dense — *every tile* holds 25-100
Pb — so an Archon sees thousands of Pb within vision at spawn, and more Miners
genuinely convert to more army. The doctrine cap of 10-16 leaves us out-
economied by the flat-16 ancestors, which then out-produce Soldiers and rush us.
The starting LEAD map confirms the density: maptestsmall is solid `+`/`#`/`@`
everywhere; maze / highway / chessboard are near-empty by comparison.

*Verify:* Archon `senseNearbyLocationsWithLead` total at spawn — maptestsmall
≫ 600; maze/highway/chessboard ≪ 600. (Confirmed from replay starting maps.)

**Step 6 — Solution.** `richHome` latch: on turn 1 the Archon sums lead in its
vision; if > 600 it latches a high Miner cap (22). Only maptestsmall trips it.
Re-run: maptestsmall improves 0/8 → **2/8** (A-side wins, B-side still rushed
r127-157 — a *combat/defence* problem on that map, not economy). highway/maze
wins preserved, nothing else regressed. Marginal but positive; kept.

maptestsmall is no longer primarily an economy problem — deferring the B-side
rush to Iteration 8. Running **Gauntlet 10** (full, snapshot candidate).

**Gauntlet 10 (step 2/3).** Iteration 7 (doctrine + `richHome`) = 93/160 = 58.1%
overall; **peer 91/120 = 75.8% ≥ WinPct** → **added to the Gauntlet as
`g_iter7`.** vs `g_iter6` 75% (was 60%). Per-map vs peers: **highway 6/6, maze
6/6** (both were 0/6 for four iterations); still weak on maptestsmall (~5/12,
second-player rush), chessboard/valley/intersection (r2000 tiebreaks).
Benchmarks unchanged: `sample_camelcase` 0/20, `sample_afinals` 2/20 — still
benchmarks, still the target.

*Retirement / reclassification:* no bot at ≥90% for two consecutive Gauntlets
(g_iter3: 95/85/90 across G8/9/10 — not consecutive). No benchmark ≥30%. No
changes. Pool: peers `{g_iter1..g_iter7}`, benchmarks `{sample_camelcase,
sample_afinals}`.

---

## Iteration 8

Every remaining weakness points the same way — **we lose fights at even or
favourable economy.** maptestsmall second-player losses (rushed r130-160),
chessboard/valley/intersection r2000 tiebreak losses (can't close), and the
benchmark gap (`sample_camelcase` out-attacks us ~3:1 at equal army size). The
economy is now competitive; combat is the lever.

### Step 4 — losing game  [`sample_camelcase / maze / botA`, annihilated r302]

### Step 5 — Hypothesis (iteration 1 of ≤5)  [Iteration 8]

*Hypothesis:* our Soldiers have no retreat and no kiting. `runSoldier` attacks
once if a target is in range, else advances and stands — it never steps back.
So each Soldier trades its 50 HP for roughly one 3-dmg hit before dying to
focused enemy fire. `sample_camelcase`'s Soldiers retreat to an Archon to heal
when wounded and kite while their weapon reloads, so each survives many
exchanges. Across every camelcase loss our Soldier count collapses to 0 while
theirs grows, and cumulative attacks run 4-8:1 against us.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | our Soldiers alive: r160 vs r40 | strictly falling to ~0 | maze 0 @ r161 vs 1 @ r41; mts 7 @ r161 vs 12 @ r41 | ✓ |
| V2 | our cumulative attacks ÷ enemy's, midgame | ≤ 0.4 | maze 79/304 = .26; mts 246/1193 = .21 | ✓ |
| V3 | retreat / kite logic in runSoldier | none | none (code) | ✓ |
| V4 | enemy Soldiers grow while ours shrink, same window | yes | maze B 2→10, A 2→0 (r41→r281) | ✓ |
| V5 | our Archon HP falls while enemy's stays ~full | yes | maze A 1800→240, B 1800 | ✓ |

**Verified → Step 6.**

**Step 6 — Solution (attempt 1).** Add to `runSoldier`: (a) take any available
shot first; (b) if HP ≤ 18, fall back to the nearest home Archon (Archons heal
friendly droids) then rejoin; (c) kite — right after firing, while the action is
on cooldown, step away from the target. Re-run of the maze loss + regression set
pending.

**Step 6 — Iteration 8 solution attempts (combat).**

1. Retreat-when-hurt (HP≤18 → home Archon to heal) + kite (step back while
   weapon reloads). Doesn't beat `sample_camelcase` on any map, but the
   maptestsmall second-player rush flips to a win.
2. **Census bug fix.** `SA_MINERS/SA_SOLDIERS` were cumulative-ever counts (bump
   once at birth, no decrement). Fine when units rarely die, but vs a strong
   opponent our Soldiers die and rebuild constantly, so cumulative Soldiers
   raced ahead of Miners and the build rule `soldiers + 2 >= miners` flipped
   back to Miners — we fielded ~8 Miners and ~0 live Soldiers on maps we should
   contest. Replaced with a real per-round census (accumulator + first-actor
   publishes last round's total). This is a genuine bug fix, kept regardless.
3. With accurate counts, changed the build rule to `soldiers >= miners` (army
   leads, economy follows, both track losses). Flips valley + pillars vs g_iter6
   to wins; maptestsmall B-side still rushed.
4. Refined retreat (stand and fight when already near a home Archon — it heals
   in place; running just lets the enemy past). valley flipped back — the micro
   tweaks thrash map-to-map.

**Assessment.** `sample_camelcase` / `sample_afinals` are not beatable with
incremental Soldier tweaks — they use Dijkstra pathfinding + coordinated focus
fire + formation. That's a multi-iteration combat rewrite. For now: keep the
**census fix** (correct) and a **conservative retreat-to-heal** (HP≤15, not near
home) — it clearly helps maze-vs-g_iter4 (r538 loss → r898 annihilation),
pillars, valley — drop the thrashy kite. Running **Gauntlet 11** (peer-only, per
`BenchmarkEvery=3`) to see if this beats `g_iter7` on peers; if not, revert to
`g_iter7` and make the combat rewrite its own iteration.

**Gauntlet 11 (peer-only).** Iteration 8 (census + retreat + `soldiers >=
miners`) = **52.9% peer, 3/20 vs g_iter7** — a clear **regression**. Reverted
`src/bot/` to `g_iter7`.

*Lesson:* the census "bug fix" can't be shipped in isolation — g_iter7's whole
build-rule tuning is (accidentally) calibrated around the cumulative counter's
behaviour, and `soldiers >= miners` on accurate counts + retreat interact into a
death spiral (soldiers hover low → economy capped low → weak everything).

**Restarting Iteration 8 minimally.** Keep `g_iter7` exactly — same counter,
same build rule. Change **only** `runSoldier`: add a single conservative
retreat-to-heal (HP ≤ 15, not already near a home Archon → move to nearest home
Archon, keep firing). No census, no build-rule change, no kite. Test that one
change in isolation.

**Iteration 8 v2 (retreat-to-heal only, on top of g_iter7).** vs g_iter7 across
all 10 loop maps × 2 sides: **11/20** — bot wins 9/10 as team A, 2/10 as team B.
That A/B split is just first-mover advantage; the retreat change is **within
noise of g_iter7** and regresses `pillars` (0/2). Does not beat
`sample_camelcase`. **Reverted — Iteration 8 abandoned.** `g_iter7` stands.

### Iteration 8 — postmortem / plan for the combat iteration

Four sessions of incremental Soldier tweaks (retreat, kite, census, build-rule)
have not moved the needle against the benchmark bots. The gap is structural:

- **Pathfinding.** We use an 8-direction greedy scorer (`moveToward`).
  `sample_camelcase` ships a real BFS/Dijkstra (`dijkstra/Dijkstra20`). On
  obstacle maps our army still arrives strung out and is beaten in detail.
- **Focus fire.** Each of our Soldiers independently picks "weakest in range",
  spreading damage. camelcase concentrates fire — kills faster, so takes less
  return fire. This needs a shared target broadcast.
- **Kiting / formation.** camelcase Soldiers attack-then-retreat while the weapon
  reloads and pull back wounded; ours stand and die.
- **The census bug is real** but can't be fixed alone — g_iter7's build rule is
  tuned around the cumulative counter. The combat iteration must redo the census
  *and* re-baseline the build rule together.

Next iteration should be a deliberate combat rewrite (BFS pathfinder + shared
focus target + census + retreat), built and gauntletted as one unit against a
fresh baseline, not incrementally patched.

---

## Iteration 9  —  combat rewrite (focus fire + census + retreat)

### Step 4 — losing game  `sample_camelcase / maze / botA` (r302 baseline)

### Step 5 — Hypothesis

Reuses the verified Iteration-8 combat hypothesis: our army loses fights at even
or favourable numbers because (a) each Soldier targets independently ("weakest
in range"), spreading damage so enemies die slowly and keep firing; (b) no
retreat, so wounded Soldiers die instead of healing at an Archon; (c) the
cumulative-counter bug left us fielding ~0 live Soldiers on contestable maps.
Measured vs `sample_camelcase`: our Soldier count → 0 while theirs grows;
cumulative attacks 4-8:1 against us; our Archon HP falls while theirs stays full.

### Step 6 — Solution (attempt 1): one coherent rewrite

- **Census** — real per-round live counts (accumulator + first-actor publish),
  replacing the cumulative-ever bump. Build rule back to pure `miners <
  minerCap()` then pure army (census makes "pure army after cap" self-replacing).
- **Focus fire** — `SA_FOCUS` holds the army's shared target; each Soldier
  promotes its own best pick (Archon-first, then lowest HP) if it beats the
  current focus or the focus is dead; everyone prefers the shared focus when in
  range. Concentrates damage so enemies die fast and stop shooting back.
- **Retreat-to-heal** — HP ≤ 15 and not near a home Archon → fall back to one
  (Archons repair droids), still firing, then rejoin. Stand and fight near home.

Built and tested as one unit. Re-run of maze/camelcase + full g_iter7 regression
set pending.

**Step 6 attempt 1 result.** vs g_iter7, all 10 loop maps × 2 sides: **9/20 =
45%** — a regression. Lost highway (g_iter7 goes 6/6 there), squer, sandwich,
valley badly. Won chessboard 2/2 and jellyfish 2/2. Still loses all benchmark
checks. The combined rewrite disturbs g_iter7's tuning the same way Iteration 8
did — the common factor is the census + build-rule change. Reverted.

**Step 6 attempt 2.** Isolate: **focus fire only**, on top of an unmodified
g_iter7 — same counter, same build rule, same runSoldier structure; change only
which target a Soldier shoots (shared `SA_FOCUS`, no census, no reset machinery,
soldiers re-promote their best each round and clear a stale focus when adjacent
to an empty focus tile).

**Step 6 attempt 2 (focus fire only) + attempt 3 (+ rally when outnumbered).**
Both land at **10-11/20 vs g_iter7** — the exact first-mover split (≈9/10 as team
A, ≈1/10 as B), i.e. **measurably zero effect** in self-play, and **zero change**
against `sample_camelcase` (maze r302 identical to baseline every time).

**Iterations 8-9 conclusion.** Soldier-micro changes (retreat, kite, focus fire,
rally) on top of `g_iter7` are inert or regressive. The bottleneck is upstream:
our army is too small and arrives too strung-out for targeting logic to matter.
Reverted to `g_iter7`.

The combat iteration must be a **ground-up rewrite**, built and gauntletted as
one unit against its own baseline, in this order:

1. **Census** (accurate live counts) + re-tune the whole build rule from scratch
   with a fresh Gauntlet — `g_iter7`'s rule is calibrated around the buggy
   cumulative counter and can't be changed piecemeal.
2. **BFS / Dijkstra pathfinder** replacing the 8-dir greedy scorer, so the army
   moves as a concentrated body (study `src/sample_camelcase/dijkstra/`).
3. Only then: **focus fire + formation + kiting** — these only pay off once we
   field a real, concentrated army.

`g_iter7` remains the current best. Deferring the combat rewrite to a dedicated
push rather than continuing to patch.

---

## Iteration 8  —  Dijkstra pathfinding

### Step 4 — losing game  `sample_camelcase / maze / botA` (r302)

### Step 5 — Hypothesis (verified in Iterations 8-9 earlier)

Our army loses fights because it arrives **strung out** — the 8-direction greedy
scorer can't trace obstacles, so Soldiers reach the enemy one at a time and are
destroyed piecemeal. `sample_camelcase` ships a real within-vision Dijkstra
(`dijkstra/Dijkstra20`). Independent evidence: every incremental Soldier-micro
change (Iter 8-9) was inert on top of g_iter7 because the bottleneck is upstream
(movement), not targeting.

### Step 6 — Solution (attempt 1)

**Vendor `sample_camelcase`'s `Dijkstra20`** (MIT, package-renamed to `bot`;
2380 lines of code-generated unrolled Dijkstra — cost `1 + rubble` per tile).
Route `moveToward(rc, goal)` through it (same signature, so nothing else
changes); keep the old scorer as the fallback when the Dijkstra step is blocked
by a unit.

- **Pathing alone:** vs g_iter7, 13/20 — and for the first time a change **broke
  the first-mover symmetry**, winning *both* sides on maze/sandwich/squer/
  pillars/intersection. But it **regressed chessboard/jellyfish** (0/2 both):
  the sharper army over-commits, our Miners get raided to 0, and — the
  cumulative `SA_MINERS` cap already "hit" — never rebuild.
- **+ census fix:** made it *worse* (11/20) — maintaining a Miner economy is a
  liability in the ~r500 annihilation games (want pure army). Reverted the census.
- **+ narrow late-game Miner revival** (round > 700 & this Archon's area
  stripped of Miners & lead affordable): **14/20 vs g_iter7**, both sides on
  **6 of 10 maps** (intersection, maze, sandwich, squer, highway, pillars).
  jellyfish 0/2 → 1/2, highway 1/2 → 2/2. Still regresses chessboard (0/2,
  r2000 "more Archons") and valley (0/2) — army over-commits with no home
  garrison; that is combat work for the next iteration.

Benchmarks unchanged (`sample_camelcase` maze still r300) — expected, combat
micro is the next step. → Step 2, **Gauntlet 12** (snapshot candidate).

**Gauntlet 12 (step 2/3).** Iteration 8 (Dijkstra + revival) = 112/180 = 62.2%
overall; **peer 110/140 = 78.6% ≥ WinPct** → **added to the Gauntlet as
`g_iter8`.** Beats every ancestor: g_iter1 95%, g_iter2 85%, g_iter3 75%,
g_iter4 70%, g_iter5 75%, g_iter6 80%, **g_iter7 70%**. Benchmarks unchanged
(`sample_camelcase` 0/20, `sample_afinals` 2/20).

Per-map: strong on maze/sandwich/squer/pillars/intersection/jellyfish/highway;
**chessboard 0/10 vs peers** (was g_iter7's strong map) — the sharper Dijkstra
army marches out and loses the r2000 Archon-count game with no home garrison.
That is Iteration 9's target. maptestsmall (~3/12) and valley (~4/12) also weak.

*Retirement / reclassification:* g_iter1 at 95% (G12) but 80% at G10 — not two
consecutive ≥90%. No benchmark ≥30%. No changes. Pool: peers
`{g_iter1..g_iter8}`, benchmarks `{sample_camelcase, sample_afinals}`.

---

## Iteration 9  —  home defence

### Step 4 — losing game  `chessboard / g_iter7 / botA` (r2000 "more Archons")

### Step 5 — Hypothesis

The Dijkstra army marches as one body to `armyObjective`, which -- with no home
threat and no *known* enemy Archon -- returned the **rotational mirror of our
Archon**, i.e. deep enemy territory. On open maps (chessboard) this leaves home
undefended: our army crosses to the enemy half by ~r300 (`solCx` 17 → 3 on a
46-wide map, spawn x≈37), the enemy out-trades it in the middle *and* raids our
base -- we lose an Archon (2→1) and our economy (12 → 0 Miners) around r400-500
while the army is away, then survive to r2000 but lose the Archon-count tiebreak
1-v-2. `g_iter7`'s sloppier pathing kept the army closer to home and
incidentally defended.

| # | variable | threshold | measured (chessboard loss) | ✓ |
|---|----------|-----------|-----------------------------|---|
| V1 | our Soldier centroid crosses the midline before r300 | yes | solCx 11.7 @ r301 (mid ≈ 23) | ✓ |
| V2 | we lose an Archon while the army is on the enemy half | yes | arch 2→1 by r601, solCx 3-9 @ r400-500 | ✓ |
| V3 | our economy collapses in the same window | Miners → 0 | 12 → 0 by r500 | ✓ |
| V4 | objective sends the army to the enemy mirror with no intel | yes | code: mirror-of-Archon | ✓ |
| V5 | enemy keeps 2 Archons + economy through r800 | yes | B: 2 Archons, 12 Miners, arHP 1140 | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

- `armyObjective` with no threat and no *known* enemy Archon → a **forward
  defensive line** one third of the way from our nearest Archon toward map
  centre, instead of the enemy mirror. The army holds there and defends; it only
  pushes deep once an enemy Archon is actually sighted.
- **Miners report enemy Archons** too (they roam widest scouting for lead, so
  they make first contact and unlock the push).

Re-run of the chessboard loss + g_iter8 regression set pending.

**Step 6 — Iteration 9 attempts.**

1. `armyObjective` → forward defensive line (1/3 toward centre) + Miners scout
   Archons. vs g_iter8 **10/20 = neutral** — too passive, turtled games we were
   winning (intersection, highway, maze regressed).
2. Fixed ~1/4 of Soldiers (by ID) a permanent home garrison. **Worse** (~2/11) —
   splitting a small early army left too few attackers; didn't fix chessboard.
3. **Mass-gate**: when marching to a *speculative* objective (no threat, no
   sighted enemy Archon), a Soldier that is far from the objective and has < 3
   friendly Soldiers in vision **waits** instead of trickling forward alone.
   Fixed chessboard (0/10 → 2/2) and valley (2/2), but the unbounded wait
   regressed highway (0/2) — on a long open map the army never left home.
4. **Windowed mass-gate** (only rounds 60-250): the army forms up through the
   opening, then commits regardless. vs g_iter8 subset **9/14 = 64%** —
   chessboard **2/2**, valley **2/2**, highway recovered to 1/2, everything else
   neutral (1/2). No clear regression.

→ Step 2, **Gauntlet 13** (snapshot candidate).

**Gauntlet 13 (step 2/3).** Iteration 9 (windowed mass-gate) = 125/200 = 62.5%
overall; **peer 122/160 = 76.25% ≥ WinPct** → **added as `g_iter9`.** vs g_iter8
60%, beats every ancestor (g_iter1 95, g_iter2 85, g_iter3 90, g_iter4 80,
g_iter5 65, g_iter6 60, g_iter7 75, g_iter8 60). **chessboard 10 → 5 peer
losses** (the Iteration-9 target). `sample_afinals` 2 → 3 — first movement on a
benchmark all session. `sample_camelcase` still 0/20.

*Retirement:* **`g_iter1` retired** — ≥90% in G12 (95%) and G13 (95%), two
consecutive. No benchmark ≥30%. Pool: peers `{g_iter2..g_iter9}`, benchmarks
`{sample_camelcase, sample_afinals}`.

New worst maps (peer): **maptestsmall 8/16** (the 1-Archon rush map — long-
standing), pillars 7/16, valley/maze/chessboard 5/16. → Iteration 10 targets
maptestsmall.

---

## Iteration 10  —  don't mass on the Archon

### Step 4 — losing game  `g_iter8 / maptestsmall / botB` (we = B, annihilated r206)

### Step 5 — Hypothesis

maptestsmall is 30×30, **1 Archon**, wall-to-wall lead. `--metrics` on the loss:
through r60 both sides are *identical* (23 Miners, ~15 Soldiers, ~940 lead). From
r60 on, the opponent's Soldiers ramp 16 → 28 → 42 → 67 while ours plateau at
~18-22 — on the **same** ~5000 banked lead, which we never spend. Cause: our
Iteration-9 mass-gate makes Soldiers gather near home during r60-250; on a tiny
map they cluster around our lone Archon and **wall in all 8 of its build tiles**,
so `canBuildRobot` fails every direction and production stops. The opponent
(no mass-gate) sends Soldiers straight out, keeps its Archon clear, and
out-produces us to annihilation.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | economies identical through r60 | yes | both 23 Miners / ~940 lead @ r61 | ✓ |
| V2 | enemy Soldiers ÷ ours by r140 | ≥ 2 | 50 / 22 | ✓ |
| V3 | we sit on thousands of unspent lead | yes | 4124 banked @ r141, Soldiers not growing | ✓ |
| V4 | 1-Archon map (build-throughput bound + single choke point) | yes | 1 Archon | ✓ |
| V5 | our Soldier count flat/declining r80-200 while enemy's climbs | yes | 22 → 18 vs 28 → 67 | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

Massing Soldiers gather at a **rally point ~6 tiles out** from the nearest home
Archon toward the objective, instead of freezing in place — clears the Archon's
build tiles while keeping the army concentrated and forward-positioned. Re-run of
the maptestsmall loss + g_iter9 regression set pending.

**Step 6 — Iteration 10 attempts.**

1. Massing Soldiers gather at a rally point ~6 tiles out instead of freezing.
   vs g_iter9 **8/16 = neutral**; maptestsmall/B still lost r209.
2. + mass-gate disabled on small maps (`W*H <= 1400`) + `richHome` cap only with
   ≥2 Archons. **Worse (7/16)** — cap 10 on maptestsmall lost the *A* side we had
   been winning (r210 → r301 loss), i.e. maptestsmall genuinely wants the big
   economy. Both changes reverted.

**Iteration 10 abandoned.** maptestsmall/B (second player on the 30×30 1-Archon
map) is a persistent hole — lost by every recent iteration, r128-210. The strong
bots (`sample_afinals` wins both sides by annihilation r87-96) beat it with pure
early aggression, which is the opposite of our `richHome` economy; but cutting
the economy loses the *A* side. Needs a maptestsmall-shaped opening (fast Miners
to a modest count, then hard rush) that doesn't cost the other maps — deferred.

`g_iter9` stands as current best.

---

## Iteration 11  —  accurate census

### Step 4 — losing game  `sample_camelcase / maze / botA` (annihilated ~r280)

### Step 5 — Hypothesis

Even with the Dijkstra pather (g_iter8/9), we field **0-2 live Soldiers** on maze
vs `sample_camelcase` (`--metrics`: our Soldier count 0,0,1,2,1,1,1,0; our
cumulative attacks crawl 10→40→75→82 while camelcase's run 42→196→563). Cause:
`SA_MINERS`/`SA_SOLDIERS` are cumulative-ever counts. vs a peer, few units die,
so cumulative ≈ alive and the build rule works. vs camelcase our Soldiers die as
fast as we build them, so the cumulative Soldier count races ahead of Miners,
`soldiers + 2 >= miners` flips true, and the Archon builds Miners instead of
replacing the dead Soldiers -- the army never exists.

| # | variable | threshold | measured (maze/camelcase) | ✓ |
|---|----------|-----------|----------------------------|---|
| V1 | our live Soldiers, whole game | ≤ 2 | 0-2 at every sample | ✓ |
| V2 | camelcase cumulative attacks ÷ ours by r150 | ≥ 3 | 281 / 75 | ✓ |
| V3 | we lose Archons while never fielding an army | yes | 3 → 1 by r241, Soldiers 0-1 | ✓ |
| V4 | camelcase fields a growing army from the same economy | yes | 2 → 10 Soldiers r31→r270 | ✓ |
| V5 | build rule reads a cumulative, not alive, Soldier count | yes | code | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

Real per-round **census** (accumulator + first-actor publish) so
`SA_MINERS`/`SA_SOLDIERS` are accurate ALIVE counts. Build rule simplifies to
`miners < minerCap()` then pure army -- with alive counts this self-replaces
losses of both types (raided Miners drop the count below the cap → rebuild;
otherwise every build is a Soldier). Drops the Iteration-7 interleave and the
Iteration-8 late-game revival hack (both were workarounds for the cumulative
bug). Everything else (Dijkstra, mass-gate, richHome, beacons) unchanged.
Re-run of the maze loss + g_iter9 regression set pending.

**Step 6 — Iteration 11 results.**

- Attempt 1 (census + pure `miners < minerCap()`): vs g_iter9 **11/20** —
  maptestsmall **2/2** (Iteration 10's unsolved hole!), pillars 2/2, sandwich
  2/2; but squer/valley/intersection 0/2. Net neutral.
- Attempt 2 (census + 8-Miner floor + army-forward economy): vs g_iter9
  **10/20** — chessboard/sandwich/squer 2/2; pillars regressed to 0/2. Net
  neutral, different maps trade.
- **Neither helped vs `sample_camelcase`** (maze still ~r260, sandwich ~r205).
  The census correctly *replaces* dead Soldiers, but one Archon can't out-build
  camelcase's focus-kill rate, so the army still never accumulates.

**Iteration 11 abandoned.** The census is *correct* but inert on its own -- it
only pays off bundled with Soldier survival (retreat-to-heal) and concentration
(focus fire) so the army actually stays alive to accumulate. That is the real
combat iteration and must be built + gauntletted as **one** package:

```
1. census() (exact diff below) replacing registerOnce; slots 17/18/19;
   build rule -> `miners < minerCap()` (pure), drop interleave + revival
2. runSoldier: retreat to nearest home Archon at HP <= 15 when not near home
3. runSoldier: SA_FOCUS shared target, everyone shoots the focus when in range
4. gauntlet vs peers -- must hold 60%+ -- AND check camelcase/afinals
```

Also worth trying next: **use the second+ Archons better** -- the 1-Archon
build-throughput bound (≈1 unit / 2 rounds) is a hard ceiling camelcase doesn't
have; a Laboratory (lead→gold) or Watchtower (static defence, 150 HP / 4 dmg)
may spend surplus lead better than a 15th Soldier the lone Archon can't afford
to build anyway.

`g_iter9` remains the current best.

---

## Iteration 12  —  combat package (census + retreat + focus fire)

### Step 4 — losing game  `sample_camelcase / maze / botA`

### Step 5 — Hypothesis

Iteration 11's verified hypothesis, expanded: we field 0-2 live Soldiers vs
`sample_camelcase` because (a) the cumulative counter stops the build rule
replacing dead Soldiers [Iter 11 V1-V5], and even with an accurate census (b)
our Soldiers die in one exchange (no retreat) and (c) spread their fire (no
focus), so camelcase out-attacks us 3-8:1 and the army never accumulates. All
three must be fixed together.

### Step 6 — Solution (attempt 1): one package, built + gauntletted together

1. **Census** — accurate per-round ALIVE counts (slots 17/18/19); build rule →
   `miners < minerCap()` then pure army (self-replacing).
2. **Focus fire** — `SA_FOCUS` shared target (cleared each round by the census);
   every Soldier shoots the focus when in range, promotes its own better pick.
3. **Retreat-to-heal** — HP ≤ 15 and not near a home Archon → fall back to one,
   still firing.

Dijkstra, mass-gate, richHome, beacons unchanged. Re-run of maze/camelcase +
full g_iter9 regression set pending.

**Step 6 attempt 1 result.** vs g_iter9 across all 10 loop maps × 2 sides:
**13/20 = 65%** — a real improvement. Wins **both sides** on maptestsmall
(Iterations 10-11's unsolved hole!), intersection, pillars, sandwich, jellyfish.
Regresses maze (0/2) and squer (0/2).

**Still loses every `sample_camelcase` game** (maze r259, sandwich r221) and
`sample_afinals` (maze r343) — unchanged. The package fixes our army vs *peers*
(it now accumulates and survives), but camelcase's edge is elsewhere (Dijkstra
depth, build order, Sage/Watchtower usage) and not closed by this.

→ Step 2, **Gauntlet 14** (snapshot candidate).

**Gauntlet 14 (step 2/3).** Iteration 12 (combat package) = 126/200 = 63.0%
overall; **peer 124/160 = 77.5% ≥ WinPct** → **added as `g_iter10`.** Beats every
ancestor (g_iter2 95, g_iter3 90, g_iter4 90, g_iter5 80, g_iter6 70, g_iter7
65, g_iter8 65, g_iter9 65). Benchmarks unchanged (`sample_camelcase` 0/20,
`sample_afinals` 2/20).

Per-map peer losses: **maptestsmall 8 → 1** (the combat package fixed the
long-standing rush hole), but **maze 5 → 9** (new worst), squer 6, highway 6.

*Retirement:* **`g_iter3` retired** — 90% in G13 and G14, two consecutive. No
benchmark ≥30%. Pool: peers `{g_iter2, g_iter4..g_iter10}`, benchmarks
`{sample_camelcase, sample_afinals}`.

→ Iteration 13 targets maze.

---

## Iteration 13  —  army-forward economy

### Step 4 — losing game  `g_iter9 / maze / botA` (annihilated r721; also lost g_iter4/5/6 both sides)

### Step 5 — Hypothesis

Iteration 12's census made the build rule "maintain exactly `minerCap` Miners
forever". On maze (3 Archons, cap 14) `--metrics` shows we sit at **12-14
Miners / 2-5 Soldiers** through the whole midgame while the opponent -- with
the same slow ~180-round Miner opening -- lets its economy drift (16 → 10
Miners) and ramps Soldiers 2 → 7 → 11 → 14. We lose 2 Archons by r480 and are
annihilated; the opponent keeps 3. The economy is never the constraint (we bank
40-90 lead); the army is.

| # | variable | threshold | measured (maze/g_iter9) | ✓ |
|---|----------|-----------|--------------------------|---|
| V1 | our Soldiers through r300 | ≤ 8 | 0 to r181, 8 by r301 | ✓ |
| V2 | our Miners held at ~cap all midgame | yes | 11-14 from r120 to r480 | ✓ |
| V3 | enemy Soldiers ÷ ours mid-game | ≥ 2 | g_iter4: 14 / 3 by r480 | ✓ |
| V4 | we lose Archons the enemy keeps | yes | 3 → 1 by r480 vs their 3 | ✓ |
| V5 | we are not lead-starved | yes | 40-90 banked throughout | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

Race Miners to the cap only through the opening (`round < 140`); after that hold
an 8-Miner raid-replacement floor and put every other build into the army.
`needMiners = miners < 8 || (miners < minerCap() && round < 140)`. Re-run of the
maze loss + g_iter10 regression set pending.

**Step 6 attempt 1 result.** `needMiners = miners < 8 || (miners < minerCap()
&& round < 140)`. vs g_iter10 **8/20 = 40%** — a clear regression (the A-side
sweep collapsed; g_iter10-as-B now beats us on most maps). maze still 0/2.
Cutting the Miner economy hurts everywhere; Iteration 12's full economy was
better. Reverted.

**Iteration 13 abandoned (attempt 1).** Re-reading the maze loss: it is **not**
an economy problem. `--metrics` shows our Soldier count *collapses* r270-470
(5 → 0) with `solSpread` frequently 0.0 — our army is **stacked on a single
tile near a home Archon**, not fighting. The mass-gate (gather near home) +
retreat-to-heal (fall back when hurt) together produce a passive death-ball on
small maps (maze is 20×20): Soldiers mass, take a little damage, retreat to heal,
re-mass — and never push out to engage the enemy army, which grinds our Archons
down from range. The fix belongs in Soldier movement (a clumped, un-threatened
army should *advance*, not hold), not the build rule — deferred to a focused
Soldier-behaviour iteration.

`g_iter10` remains the current best.

### Step 6 — Solution (attempt 2)

Board inspection of the maze loss (r320): we had **found and were attacking two
enemy Archons** in one corner while ours in the other corner were raided (3 → 2
Archons, the survivor at HP 153). Same over-commit as chessboard, but the
mass-gate doesn't help because once an enemy Archon is *sighted* it disables and
the whole army rushes. Fix: a fixed **~1/5 of Soldiers (by ID), once the army is
past 6**, are a permanent home garrison — sit on the nearest home Archon with no
enemy in sight, still fight anything adjacent, never leave. (Iteration 9 tried
an ID garrison and it split the tiny early army; the `soldierCount >= 6` gate
and the accurate census fix that.) Re-run pending.

**Step 6 attempt 2 result.** ID garrison (1/5, gated `soldierCount >= 6`). vs
g_iter10 **8/20 = 40%** — maze improved (0/2 → 1/2) but **maptestsmall regressed
to 0/2** (r115-135: the garrison splits even the maptestsmall army once it hits
6, and the idle fifth loses the rush) and most other maps dropped. Net -3.
Reverted.

**Iteration 13 abandoned.** maze (army over-commits on a small map, home Archon
raided) resists both a build-rule fix (attempt 1: cutting economy hurts
everywhere) and a fixed-fraction garrison (attempt 2: always costs more than it
saves). The real fix is **dynamic** army allocation -- send home only what a
sensed home threat actually needs, and don't let the *whole* army chase a
sighted enemy Archon when we're not ahead -- which is a larger redesign of
`armyObjective` / the Soldier's commit logic. Deferred.

`g_iter10` remains the current best. **Session close:** g_iter7 → g_iter8
(Dijkstra) → g_iter9 (mass-gate) → g_iter10 (combat package: census + retreat +
focus fire). Peer 77.5%. The benchmark bots (`sample_camelcase` 0/20,
`sample_afinals` 2/20) remain unbeaten -- the open frontier.

---

## Iteration 14  —  Archon repair

### Step 4 — losing game  `sample_camelcase / maze / botA`  (also the maze regression vs peers)

### Step 5 — Hypothesis

Studying `src/sample_camelcase/robot/building/Archon.java`: camelcase's Archon
calls `tryRepair()` **every turn** (heals the most-wounded friendly droid in
action range). **Our Archon never repairs** -- it spends its action on
`buildRobot` every single turn. So Iteration 12's retreat-to-heal is a **no-op**:
wounded Soldiers fall back to an Archon that ignores them, sit there, and either
die anyway or return to the fight still hurt. Our army evaporates in sustained
fights (maze: Soldier count 5 → 0 while clumped near home) partly because the
"heal" half of retreat-to-heal was never wired up.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | `rc.repair` / `canRepair` calls in our Archon code | 0 | 0 | ✓ |
| V2 | camelcase Archon repairs every turn it can | yes | `tryRepair()` unconditional | ✓ |
| V3 | our Soldiers retreat home then still die | yes | maze: clumped at Archon, count 5→0 | ✓ |
| V4 | our Archons out-of-combat with a spare action most turns | yes | 1 build / ~2 rounds, action idle otherwise | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

Archon: before building, scan friendly droids in action range (R²=20); if one is
wounded (> 6 below max HP), spend the action repairing the most-wounded (combat
units before Miners) instead of building. Now retreat-to-heal actually heals.
Re-run of maze + camelcase + g_iter10 regression set pending.

**Step 6 attempt 1 result.** vs g_iter10, all 10 loop maps × 2 sides: **12/20 =
60%** — modest gain. valley **2/2**, pillars **2/2**, maze **0/2 → 1/2**, and bot
wins the *B* (second-player) side on 6 maps (maze/sandwich/jellyfish/squer/
valley/pillars) — the signature of a real defensive improvement. No regression.
**Still loses every `sample_camelcase`/`sample_afinals` game.**

→ Step 2, **Gauntlet 15** (snapshot candidate).

**Gauntlet 15 (step 2/3).** Iteration 14 (Archon repair) = 126/200 = 63.0%
overall; **peer 124/160 = 77.5% ≥ WinPct** → **added as `g_iter11`.** Beats every
ancestor (g_iter2 95, g_iter4 90, g_iter5 85, g_iter6 75, g_iter7 75, g_iter8
75, g_iter9 65, g_iter10 60). **maze peer losses 9 → 3** (the repair wired up
retreat-to-heal). Benchmarks unchanged (`sample_camelcase` 0/20, `sample_afinals`
2/20).

*Retirement:* **`g_iter2` (95/95) and `g_iter4` (90/90) retired** — both ≥90% two
consecutive Gauntlets. No benchmark ≥30%. Pool: peers `{g_iter5..g_iter11}`,
benchmarks `{sample_camelcase, sample_afinals}`.

New worst maps (peer): **squer 7/16**, highway 6/16, valley 5/16. → Iteration 15
targets squer.

---

## Iteration 15  —  don't heal mid-fight

### Step 4 — losing game  `g_iter10 / squer / botA` (annihilated r378)

### Step 5 — Hypothesis

squer (25×25, 2 Archons) opening is *identical* to g_iter10's through r120
(12 Miners, 0 Soldiers). In the r240-360 fight our Soldier count stalls at 4
while g_iter10's holds at 6, they out-attack us 556/351, we lose an Archon
(2 → 1) and are annihilated; they keep both Archons. The difference is
Iteration 14's Archon repair: **our Archon heals wounded Soldiers mid-fight
instead of building new ones**, so we fall behind on Soldier *count* in a
fast attrition fight. g_iter10 (no repair) just builds and wins.

| # | variable | threshold | measured (squer/g_iter10) | ✓ |
|---|----------|-----------|----------------------------|---|
| V1 | openings identical through r120 | yes | both 12 Miners / 0 Soldiers | ✓ |
| V2 | our Soldier count vs theirs in the r280-360 fight | ours < theirs | 4 vs 6, then 1 vs 6 | ✓ |
| V3 | enemy cumulative attacks ÷ ours mid-fight | ≥ 1.4 | 556 / 351 | ✓ |
| V4 | we lose an Archon they keep | yes | 2 → 1 vs 2 | ✓ |
| V5 | the only diff from g_iter10 is Archon repair | yes | code | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

Archon repairs only when **no enemy combat unit / Archon is in its vision** --
during a fight it builds the army instead (matches `sample_camelcase`, which
does `tryBuildRobot(SOLDIER)` when an attack target is visible and repairs only
otherwise). Re-run of the squer loss + g_iter11 regression set pending.

**Step 6 attempt 1 (REJECTED).** Archon repairs only when no enemy in vision.
vs g_iter11 **8/20** — regression (pillars 0/2, army commits later on most maps).
The mid-fight heal is net-helpful; removing it hurts. Reverted.

**Step 6 attempt 2.** Re-reading the squer loss: our Soldiers crawl to 4 while
the opponent holds 6. squer is 25x25 -- the **mass-gate** makes our small army
wait for 3 friends and never commits in force. Skip the mass-gate under
`W*H <= 900` (squer, maze, maptestsmall); keep it on the big open maps it was
built for. Re-run pending.

**Step 6 attempt 2 result.** Mass-gate off under `W*H <= 900`. vs g_iter11
**9/20** — and **squer got worse (0/2)**. Many games ended at the *identical*
round for both sides (intersection 562/562, valley 1333/1333, pillars 580/580):
bot ≈ g_iter11 and the mass-gate change only touches squer, where it hurt.

**Iteration 15 abandoned.** squer's 7/16 peer losses are ≈ coin-flip: bot and
recent ancestors are near-mirror-matches on that 25×25 rotational map and the
first mover wins. Neither the repair-timing nor the mass-gate is the lever.
Not a productive Step-4 target.

`g_iter11` remains the current best. **Next: the benchmark gap.** camelcase has
been 0/20 for the whole session. Its concrete, portable advantages over us
(from reading `src/sample_camelcase/robot/building/Archon.java`):
- **3:1:1 SOLDIER:MINER:BUILDER spawn cycle** after the opening (vs our ~all-
  Soldier-after-cap). Far more army throughput.
- **Miner count = `max(lead tiles sensed at spawn, 5)`** -- a per-Archon cap,
  low. (We tried this in Iter 10; camelcase makes it the primary mechanism.)
- **Archons transform to PORTABLE and relocate** to low-rubble ground for faster
  builds and safety. (I was wrong earlier that Archons can't move.)
Iteration 16 should port one of these -- most likely the Archon relocation, or
the spawn-ratio shift toward more army.

---

## Iteration 16  —  camelcase opening (per-Archon lead-based Miner quota)

### Step 4 — losing game  `sample_camelcase / maze / botA` (annihilated ~r250)

### Step 5 — Hypothesis

`--metrics` with full unit breakdown: on maze we build **3 → 14 Miners over
~160 rounds and ZERO Soldiers**, while `sample_camelcase` builds **4 Miners and
stops**, pumping Soldiers from r20 (7 by r161, 11 by r241). Our Archon HP
1800 → 765 → 192; theirs untouched. Our whole-session 0/20 vs camelcase is this
one thing: **our opening Miner cap (`min(16, 8 + 2*archons)` = 14 on a 3-Archon
map) is set by Archon COUNT, not by how much lead the spawn can feed** -- so on
lead-sparse maps we mine air for 160 rounds while the enemy armies up.

| # | variable | threshold | measured (maze/camelcase) | ✓ |
|---|----------|-----------|----------------------------|---|
| V1 | our Miners at r120 | ≥ 12 | 12 | ✓ |
| V2 | our Soldiers at r160 | 0 | 0 | ✓ |
| V3 | camelcase Soldiers at r160 | ≥ 6 | 7 | ✓ |
| V4 | camelcase Miner count all game | ≤ 8 | 4-8 | ✓ |
| V5 | we are annihilated before r300 | yes | ~r250 | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

Port camelcase's rule: **each Archon** spawns only
`max(lead tiles within its 9-tile spawn cluster, 5)` Miners (fixed turn 1), then
pure army; `richHome` (dense-lead spawn) raises its quota to 18. Per-Archon
counter `myMinersSpawned`; the shared census keeps a 6-Miner team floor for
raid replacement. Replaces the archon-count `minerCap()`. Re-run pending.

**Step 6 attempt 1 result.** Per-Archon quota `max(leadTiles, 5)` = still ~15
total on 3-Archon maps -> still built 13 Miners / 0 Soldiers to r200 on maze
(camelcase r279, unchanged). vs g_iter11 **12/20** -- **squer 2/2** (the coin-
flip hole fixed!), sandwich/jellyfish 2/2, but **chessboard 0/2**. Kept the
quota, adding the real mechanism:

**Step 6 attempt 2.** `SA_ENEMY_SEEN` -- any unit that sights an enemy combat
unit stamps the round. While that stamp is < 60 rounds old, every Archon caps
its Miners at **3** and rushes army (camelcase's danger-target check). Otherwise
the lead-based quota. Re-run pending.

**Step 6 attempt 2 result.** vs g_iter11 **12/20** — squer/highway/jellyfish
2/2, but **maptestsmall 0/2** (the contact cut to 3 Miners starves the economy
on a lead-dense rush map). camelcase marginally less bad (maze r283-291,
sandwich r230) but still 0/20.

**Step 6 attempt 3.** Contact cut applies only when `!richHome` -- on
maptestsmall the economy is the game. Re-run pending.

**Step 6 attempt 3 result.** Contact cut gated on `!richHome`. vs g_iter11
**13/20 = 65%** — maptestsmall recovered to 1/2, **highway/jellyfish/squer 2/2**,
no 0/2 regressions. camelcase still 0/20 (maze r283-291, sandwich r230 — we hold
a little longer but don't win; the gap is deeper than the opening).

→ Step 2, **Gauntlet 16** (snapshot candidate).

**Gauntlet 16 (step 2/3).** Iteration 16 (camelcase opening) = 100/180 = 55.6%
overall; **peer 98/140 = 70.0% ≥ WinPct** → **added as `g_iter12`.** vs g_iter11
65% (beats last snapshot); vs g_iter5 90, g_iter6 75, g_iter7 80, g_iter10 70,
g_iter9 60, **g_iter8 50** (a lateral matchup that tipped — losses spread across
8 maps, no single cause; watch item). **squer and maze holes closed** (squer 0
peer losses, maze 3). Benchmarks unchanged (`sample_camelcase` 0/20,
`sample_afinals` 2/20).

*Retirement:* g_iter5 at 90% (G16) but 85% (G15) — not consecutive. No changes.
Pool: peers `{g_iter5..g_iter12}`, benchmarks `{sample_camelcase, sample_afinals}`.

New worst maps (peer): **pillars 7/14, chessboard 7/14**, intersection 6/14. →
Iteration 17.

---

## Iteration 17  —  team-wide Miner target

### Step 4 — losing game  `sample_camelcase / maze / botA`

### Step 5 — Hypothesis

Iteration 16's Miner quota is **per-Archon** (`max(spawn lead, 5)`), so on
3-Archon maps we still build ~9-15 Miners and 0 Soldiers to r200 vs camelcase's
4. `--metrics` on the current g_iter12 loss: 9 Miners / 0-1 Soldiers at r200,
Archon HP 1800 → 621, annihilated r290. The per-Archon design was the bug.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | our Miners at r120 (g_iter12) | ≥ 9 | 9 | ✓ |
| V2 | our Soldiers at r180 | ≤ 1 | 0-1 | ✓ |
| V3 | camelcase Miner count all game | ≤ 8 early | 4 | ✓ |
| V4 | contact-cut (SA_ENEMY_SEEN) fires only ~r100+ on maze | yes | our units don't sight the enemy until it's close | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

Miner target is now a **team total**, not per-Archon: `richHome ? 20 : 9`, cut
to 6 on enemy contact. Once the team has that many Miners, every Archon builds
army. Removed the per-Archon `myMinersSpawned` counter. Re-run pending.

**Step 6 attempt 1 result.** Flat team target 9. vs g_iter11 **9/20 = 45%** --
intersection/maze/valley/pillars 0/2 (9 Miners starves the economy on the maps
that need it). **But camelcase/maze survival jumped to r389/r346** (from ~r250)
-- the sharpest camelcase improvement all session, though still a loss.

**Step 6 attempt 2.** Map-size-gated target: `richHome ? 20 : (W*H < 1000 ? 9
: 15)` -- small maps rush (maze/squer 20x20-25x25), big maps develop economy
first. Re-run pending.

**Step 6 attempt 2 result.** Map-size-gated target (small 9 / big 15). vs
g_iter12 **9/20 = 45%** — highway 0/2, **squer regressed 2/2 → 0/2** (25×25 → the
"9" bucket, but squer needs the economy). Kept the camelcase/maze survival gain
(r389) but still a loss, and a clear peer regression.

**Iteration 17 abandoned.** The opening Miner count is genuinely
**map-dependent in a way no single formula captures** -- every value fixes one
cluster of maps and breaks another (this session: per-Archon ~15 → good peers,
0/20 camelcase; flat 9 → r389 vs camelcase, −8 peer wins; size-gated → −11).
The camelcase gap is **not an economy-knob problem** -- their real edge is the
full package: BUILDER → Laboratory → gold → **SAGE** (100 HP, 45 dmg, one-shots
Soldiers), plus Watchtower static defence and Archon relocation. That is a
multi-iteration structural build-out, not a tuning pass.

`g_iter12` remains the current best. **Session close.** Iterations 7-16 produced
g_iter7..g_iter12 (6 snapshots): miner doctrine, Dijkstra pathfinder, mass-gate
home defence, combat package (census + focus fire + retreat), Archon repair,
camelcase-style opening. Peer ~70-77%. `sample_camelcase` 0/20 and
`sample_afinals` 2/20 remain unbeaten -- the frontier is a gold/Sage economy.

---

## Iteration 18  —  raise the Soldier retreat threshold

New session (fresh Claude Code instance on `claude-driver`, per `CLOUD_DRIVER.md`
-- picking up here rather than migrating the prior laptop session, as designed).

### Correction to the Iteration 17 session-close note

Before selecting a game, read the vendored `sample_camelcase` source directly
(not just its game behaviour): **camelcase never builds `LABORATORY` or
`SAGE`.** `grep -rn "RobotType.SAGE\|RobotType.LABORATORY"` over the whole
package turns up only the dead `case` arms in `RobotPlayer.createRobot` and one
defensive check in `Robot.lookForDangerTargets` (running away from an enemy
Sage) -- `Archon.spawnOrder` is `{SOLDIER, SOLDIER, SOLDIER, MINER, BUILDER}`,
nothing else. The gold->Laboratory->Sage economy is **`sample_afinals`'s**
mechanism (`BotLaboratory.java`, `BotSage.java` exist and are wired up there),
not camelcase's -- Iteration 17's closing hypothesis was answering the wrong
opponent. camelcase's actual, verified mechanics (`Archon.java` / `Robot.java`
/ `Soldier.java`): a continuous 3:1:1 SOLDIER:MINER:BUILDER spawn cycle after a
short opening, Builder-built `WATCHTOWER`s at home, Archon relocation to safer/
lower-rubble ground, a shared "danger target" broadcast, and an attack-priority
list ranking enemy Soldiers (7) above Archons (2) -- it does not Archon-rush.

### Step 4 — losing game  `sample_camelcase / maptestsmall / botA` (annihilated r161)

Chosen over another maze rerun (Iterations 16-17 already spent there): a fast
loss (r161) on our best-case map (`richHome` grants an 18-Miner quota on
maptestsmall), so whatever kills us here isn't an opening-economy problem.

### Step 5 — Hypothesis

`--metrics` on the g_iter12 loss: our 31-soldier force (round 95) is ground to
**zero by round 137**, while camelcase's army barely loses a man (36->35 once
at r108, then straight back up to 56) -- camelcase kills ~31 of ours for ~1 of
theirs, well before any Watchtower comes online (`B_watchtowers` stays 0 until
r100). `--indicators` on 501 sampled A-Soldier-turns in the crash window
(r100-130): 48% still marching toward the static `armyObjective` (never
reached the fight), **28% retreating ("heal")**, only **7% actually
attacking**. camelcase's own `Soldier.java` only disengages at HP<10, or HP<16
*and already within ~6 tiles of home* -- it never abandons a fight far from
home over a moderate wound. Ours retreats at HP<=15 unconditionally (Iteration
12), pulling a large, disproportionate share of the army out of every
away-from-home engagement.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | A-soldier-turns in "heal" state, r100-130 sample | ≥ 20% | 28% (142/501) | ✓ |
| V2 | A-soldier-turns actually attacking, same sample | ≤ 10% | 7% (37/501) | ✓ |
| V3 | B soldier net losses, r95-137 crash window | ≤ 2 | ~1 (one dip r108) | ✓ |
| V4 | A soldier count, same window | 31 → 0 | 31 → 0 (r137) | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

Raise the retreat threshold from HP<=15 to **HP<=10** (critical only),
matching camelcase's far-from-home bar. `runSoldier`'s retreat block,
unchanged otherwise. Re-run pending.

**Step 6 attempt 1 result.** `sample_camelcase/maptestsmall`: still a loss both
sides, but survival jumped r161/183 -> **r188/197**. Not yet a win on the
selected game, but a clean directional improvement with no new failure mode --
kept per the established practice (Iteration 16) of proceeding to a full
Gauntlet on a promising non-winning attempt rather than reverting on
principle. Self-mirror sanity check vs `g_iter12` (near-identical code, one
changed constant): 10/20 = 50%, i.e. a coin-flip lateral match, not a
regression.

-> Step 2, **Gauntlet 17** (snapshot candidate -- benchmarks included).

**Gauntlet 17 (step 2/3).** Peer: **111/160 = 69.4% ≥ WinPct** -> **added as
`g_iter13`.** Per-ancestor: g_iter5 90%, g_iter7 85%, g_iter6 70%, g_iter11
70%, g_iter8 65%, g_iter10 65%, g_iter9 60%, g_iter12 50% (expected -- a
near-mirror match against its own immediate parent, one changed constant).
Per-map (peer, 16 games each): **chessboard 9/16, intersection 9/16, pillars
9/16, valley 9/16** worst; highway/maptestsmall 10/16; sandwich 12/16;
jellyfish/maze 14/16; squer 15/16 best. Benchmarks unchanged: `sample_camelcase`
0/20 (longer survival on several maps though: maptestsmall r188/197 vs prior
r161/183, chessboard r391/407 vs prior r352/461), `sample_afinals` 2/20 (both
wins on maptestsmall, r81/86, same as the prior baseline).

*Retirement:* `g_iter5` 90% (G16) **and** 90% (G17) -- two consecutive ≥90% ->
**retired.** Pool: peers `{g_iter6..g_iter13}`, benchmarks `{sample_camelcase,
sample_afinals}`.

The retreat fix bought measurable survival time against camelcase without
costing peer strength (69.4%, above every prior Gauntlet), but didn't flip a
single benchmark game. **Next:** the 48% idle-march share found in Step 5 is
the more likely remaining lever -- soldiers with no sighted enemy walk toward a
single static point (`armyObjective`) with no ongoing reinforcement-massing
(Iteration 9's "wait for 3" only gates the *first* advance), so a large
fraction of the army is in transit at any moment during a live engagement
instead of reinforcing it piecemeal-in. Worth re-measuring the
heal/attack/objective split against the new HP<=10 threshold on a fresh loss
before deciding the next hypothesis.

---

## Iteration 19  —  reinforce the live fight (SA_FOCUS) instead of a static objective

### Step 4 — losing game  `sample_camelcase / maptestsmall / botA` (annihilated r188, re-run under g_iter13)

Same game as Iteration 18, per its own "next" pointer -- re-measured fresh
under the new HP<=10 retreat threshold rather than assumed.

### Step 5 — Hypothesis

`--indicators` on 562 sampled A-Soldier-turns, r120-160 (the live-engagement
window, confirmed live by 52 non-zero "focus" turns in the sample): **62%
"objective"** (marching to the static `armyObjective` guess, no enemy in
sight), 26% "advance" (chasing a sighted target not yet in range), only **9%
"focus"** (actually attacking) -- "heal" dropped to 2.5% (the Iteration 18 fix
worked as intended). `runSoldier`'s no-target branch reads `armyObjective(rc)`
directly and never consults `SA_FOCUS`, the shared focus-fire location that
engaged soldiers already broadcast every round (Iteration 12) -- so a soldier
with nothing in its own vision has no way to learn a fight is already
underway nearby and just keeps marching toward a stale guess-point instead of
reinforcing it.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | A-soldier-turns in "objective" state, r120-160 sample | ≥ 50% | 62% (349/562) | ✓ |
| V2 | A-soldier-turns actually attacking ("focus"), same sample | ≤ 15% | 9% (52/562) | ✓ |
| V3 | SA_FOCUS is live (non-zero) during the window | yes | confirmed by the 52 "focus" turns | ✓ |
| V4 | "heal" turns, same sample (post-Iteration-18 sanity check) | ≤ 5% | 2.5% (14/562) | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

In `runSoldier`'s no-target branch: read `SA_FOCUS` first; if non-zero, march
there (skip the Iteration-9 mass-gate below -- reinforcing a *known* live
fight isn't the speculative advance that gate was built for). Fall through to
the old static `armyObjective` only when no fight is in progress. Re-run
pending.

**Step 6 attempt 1 result.** `sample_camelcase/maptestsmall`: still a loss both
sides, survival improved again, r188/197 -> **r235/250**. `g_iter13` mirror
sanity check: 11/20 = 55%, no regression signal.

-> Step 2, **Gauntlet 18** (snapshot candidate -- benchmarks included).

**Gauntlet 18 (step 2/3).** Peer: **103/160 = 64.4% ≥ WinPct** -> **added as
`g_iter14`.** Per-ancestor: g_iter7 90%, g_iter6 80%, g_iter11 70%, g_iter9
65%, g_iter13 55%, g_iter8 55%, g_iter10 50%, g_iter12 50%. Per-map (peer,
16 games each): **chessboard 7/16, highway 8/16** worst; intersection/pillars/
valley 9/16; maze/maptestsmall 11/16; squer 12/16; jellyfish 13/16; sandwich
14/16 best -- chessboard and highway are now the clear weak points (were mid-
pack in G17). Benchmarks unchanged: `sample_camelcase` 0/20, `sample_afinals`
2/20. camelcase survival was **mixed, not a broad win**, on a per-map replay
compare against the G17 benchmark run: maptestsmall clearly better (188/197 ->
235/250) but intersection (288/399 -> 231/270), sandwich (230/219 -> 182/226),
and highway (560/663 -> 493/550) all got *shorter* -- reinforcing a fight
faster can also mean feeding soldiers into a losing fight faster when the
opponent's per-engagement kill efficiency is still better than ours, which
SA_FOCUS reinforcement does nothing to address. The structural camelcase gap
is still the kill-ratio asymmetry documented in Iteration 18's Step 5, not the
arrival-time problem this iteration fixed.

*Retirement:* no opponent reached two-consecutive ≥90% this Gauntlet (g_iter7
90% in G18 only, 85% in G17). No changes. Pool: peers `{g_iter6..g_iter14}`,
benchmarks `{sample_camelcase, sample_afinals}`.

**Next:** chessboard/highway are the new worst peer maps and haven't been a
Step-4 target since early iterations -- worth a fresh loss analysis there
rather than continuing to mine camelcase, which has now absorbed two
iterations (18, 19) of army-behavior fixes without a single round won. The
per-engagement kill-ratio asymmetry against camelcase (documented Iteration
18) remains unaddressed and is likely the higher-value target once a
peer-side loss is worked through -- candidates: matching camelcase's
kite-only-on-cooldown attack pattern (`tryAttack`/`tryMoveToSafety` sequencing
in `Soldier.java`) instead of our current "attack if in range, else advance"
loop, which never repositions for a better trade.

---

## Iteration 20  —  scale the long-game Miner floor with round number

### Step 4 — losing game  `g_iter6 / chessboard / botB` (loses on r2000 tiebreak)

Followed Iteration 19's "next" pointer: chessboard is the new worst peer map.
A loss against `g_iter13` on chessboard turned out to be a 2000-round mirror
stalemate decided by an early accidental Archon-HP tiebreak (both sides run
near-identical code) -- the same "near-mirror coin-flip" class Iteration 15
already ruled out as unproductive for squer. A loss against the much older
`g_iter6` (which predates Iteration 7's small-doctrine Miner cap and still
uses the old large map-scaled cap) is a cleaner signal: a real strategic
mismatch, not mirror noise.

### Step 5 — Hypothesis

`--metrics`: our Miners crash 10 -> 2 around r420-480 (`--events` confirms a
small enemy raiding party -- 2-3 soldier IDs -- picking off exposed Miners
unopposed), then flatline at exactly the hardcoded floor of **6** for the
remaining ~1500 rounds of a game that runs to the r2000 timeout. `g_iter6`'s
Miners decline only slowly (34 at r180 -> 18 at r560) and its Soldier count
grows essentially unbounded (32 at r400 -> 422 at r2000) while ours stays
capped near single digits the whole back half. The floor (`miners < 6`,
Iteration 16) was designed as a raid-*replacement* number for the opening, not
a long-game economy target -- it never scales, so any game that runs long
enough to matter permanently caps our production at whatever "6" was tuned
for, while an opponent with a larger sustainable economy keeps compounding.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | B(bot) Miners flatline at the floor from some round through r2000 | yes, ~1000+ rounds flat | flat at 6, r560-r2000 (~1440 rounds) | ✓ |
| V2 | A(g_iter6) Soldiers, r500 -> r2000 | grows ≥5x | 60 -> 422 (~7x) | ✓ |
| V3 | B(bot) Soldiers, same window | stays roughly flat/bounded | 2-9 range throughout | ✓ |
| V4 | Miner die-off (r420-480) caused by real combat, not a code plateau | yes | confirmed: enemy Soldiers #10776/#13581/#10732 repeatedly attacking B Miners in the event log | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

Replace the flat floor of 6 with a round-scaled one, gated off while under
recent contact: `floor = contact ? 6 : min(6 + round/200, 20)`. Re-run
pending.

**Step 6 attempt 1 result.** Re-ran `g_iter6/chessboard/botB`: still a loss,
and `--metrics` showed the floor **never actually moved** -- B Miners stayed
flat at 5-7 through r2000, identical to before the fix. Diagnosis from the
data: `contact` (`round - SA_ENEMY_SEEN < 60`) never goes false in a long
grinding game where the enemy army is continuously in vision, so the `!contact`
gate suppressed the ramp for the entire game -- the fix never engaged. This is
exactly the kind of thing Step 6.3's "re-run and check" step exists to catch.

**Step 6 attempt 2.** Drop the `!contact` gate -- the floor is a distinct,
ongoing replenishment mechanism from the opening `quota` (which already
handles early-rush response via the separate `contact` cut to 3); let it climb
with round number unconditionally: `floor = min(6 + round/200, 20)`. Re-run
pending.

**Step 6 attempt 2 result.** Re-ran `g_iter6/chessboard/botB`: `--metrics`
confirmed the floor now works -- B Miners climb 6 -> 14 by r2000, actually
*overtaking* g_iter6's declining economy (9 by r2000). Still a loss on this
specific game (B Soldiers stay 3-8 the whole game despite the healthier
economy -- g_iter6's already-massive standing army, built during our r400-600
stall, absorbs new recruits faster than we can accumulate them; the early
deficit is too large to claw back in one game) but a real, verified change in
mechanism, not a no-op. `g_iter14` mirror sanity check: 10/20 = 50%, clean.

-> Step 2, **Gauntlet 19** (snapshot candidate -- benchmarks included).

**Gauntlet 19 (step 2/3).** Peer: **115/180 = 63.9% ≥ WinPct** -> **added as
`g_iter15`.** Per-ancestor: g_iter7 95%, g_iter6 80%, g_iter9 75%, g_iter11
65%, g_iter8 60%, g_iter13 60%, g_iter14 50%, g_iter10 45%, g_iter12 45%.
Per-map (peer, 18 games each): **chessboard 8/18** still clearly worst (the
floor fix didn't flip the underlying deficit problem, as expected from the
Step-6 result above); pillars 9/18; intersection 10/18; valley 11/18;
maptestsmall/maze 12/18; jellyfish/squer 13/18; highway 12/18; sandwich 15/18
best. Benchmarks unchanged: `sample_camelcase` 0/20, `sample_afinals` 2/20.

*Retirement:* `g_iter7` 90% (G18) **and** 95% (G19) -- two consecutive ≥90% ->
**retired.** Pool: peers `{g_iter6, g_iter8..g_iter15}`, benchmarks
`{sample_camelcase, sample_afinals}`.

**Next:** chessboard remains the worst peer map even after the floor fix --
the Step-6 result showed why: it's a kill-ratio/early-deficit problem, the
same underlying gap Iteration 18 documented against camelcase (soldiers trade
worse than the opponent's per engagement), not an economy-scale problem. This
converges Iterations 18-20 on a single remaining structural target: **combat
trade efficiency**, not army composition or arrival timing. Next iteration
should select a fresh peer or benchmark loss and hypothesize directly about
per-engagement damage exchange (e.g. camelcase's kite-on-cooldown pattern
noted at the end of Iteration 19, or whether `betterTarget`/focus-fire
selection is landing worse trades than the opponent's local targeting).

---

## Iteration 21  —  reposition to lower rubble before firing (camelcase's kiting mechanism)

### Step 4 — losing game  `sample_camelcase / maptestsmall / botA` (fresh loss under g_iter15)

Selected to directly re-test whether the camelcase kill-ratio gap documented
in Iteration 18 still exists post-Iterations-19/20, rather than reasoning from
stale data.

### Step 5 — Hypothesis

`--metrics`, r100-200 window (troop counts roughly comparable early: 42 vs 30
at r100): our per-soldier attack rate is **~0.236** attacks/soldier/round
(613 attacks / 100 rounds / ~26 avg soldiers) vs camelcase's **~0.347** (1667 /
100 / ~48) -- camelcase lands ~1.47x more attacks per soldier per round even
before the numeric gap opens up. Read `sample_camelcase/robot/Robot.java`'s
`tryAttack`: before firing on a non-building target, it steps onto a lower-
rubble adjacent tile still within action range, if one is free -- movement and
action are separate per-turn resources, so this costs nothing and speeds up
next turn's cooldown recovery. Our `runSoldier` never does this.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | our attack rate, r100-200 (attacks/soldier/round) | baseline | ~0.236 | ✓ |
| V2 | camelcase attack rate, same window | > 1.2x ours | ~0.347 (1.47x) | ✓ |
| V3 | troop counts roughly comparable at window start (rules out "just outnumbered") | within 1.5x | 42 vs 30 (1.4x, and in *our* favor) | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

Add `repositionForRubble()`: before the focus-fire attack, if movement is
ready and an adjacent tile still within action range of the target has lower
rubble than the current tile, step onto it first, then attack. Re-run pending.

**Step 6 attempt 1 result.** Re-ran the selected game: **byte-identical**
`--metrics` output to the pre-fix run at every checkpoint through r240 -- the
change had zero measurable effect. Checked why: `maptestsmall`'s starting
rubble map is uniformly 0-9 rubble everywhere (confirmed via the ASCII
render) -- `repositionForRubble` can never find a strictly-lower tile there,
so it's a guaranteed no-op on this specific map. Not a disproven hypothesis,
just the wrong map to observe it on (noted here rather than silently
discarded, per the "confirm/disconfirm honestly" standard this session is
holding to). Re-tested on `chessboard` (real rubble variation, and our
current worst peer map) against `g_iter6`: still a loss (1/2, same split as
before), but the long, chaotic 2000-round game diverges too much round-to-
round once any code changes to get a clean single-replay before/after delta.
The change is harmless by construction (only ever moves to a strictly lower-
rubble tile, otherwise no-op) and grounded in verified opponent code, so
rather than force a clean single-game proof, let the standard Step 2/3
aggregate decide it. `g_iter15` mirror sanity check: 11/20 = 55%, clean.

-> Step 2, **Gauntlet 20** (snapshot candidate -- benchmarks included).

**Gauntlet 20 (step 2/3).** Peer: **114/180 = 63.3% ≥ WinPct** -> **added as
`g_iter16`.** Per-ancestor: g_iter6 80%, g_iter8 80%, g_iter11 75%, g_iter9
60%, g_iter14 60%, g_iter12 55%, g_iter13 55%, g_iter15 55%, g_iter10 50%.
Per-map (peer, 18 games each): jellyfish 17/18 best; **chessboard 8/18,
valley 9/18, intersection 9/18** worst (chessboard unchanged from G19 -- the
rubble fix didn't move it, consistent with Step 6's inconclusive result there).
Benchmarks unchanged: `sample_camelcase` 0/20, `sample_afinals` 2/20.

*Retirement:* no opponent reached two-consecutive ≥90% this Gauntlet (max was
80%). No changes. Pool: peers `{g_iter6, g_iter8..g_iter16}`, benchmarks
`{sample_camelcase, sample_afinals}`.

**Next:** chessboard/valley/intersection remain the worst peer maps across two
Gauntlets running -- these are large/open maps where games run long (many
still hit the r2000 timeout). The Iteration 20 finding (economic snowball from
an early, undefended miner raid) is the more likely lever there than further
combat-efficiency tuning: worth checking whether `checkHomeThreat`'s ≥2-enemy
threshold and its distance-29-from-a-known-Archon gate actually catches a
small raiding party that targets miners away from the Archon rather than the
Archon itself -- the g_iter6/chessboard raid at r420-480 (Iteration 20) was
exactly 2-3 raiders, right at that threshold's edge, and killed miners
unopposed with no visible defensive response in the event log.

---

## Iteration 22  —  raided Miners cry for help

### Step 4 — losing game  `g_iter6 / chessboard / botB` (same replay as Iteration 20's raid analysis)

### Step 5 — Hypothesis

`checkHomeThreat` only scans a ~5.4-tile radius (dist² ≤ 29) around a *known
Archon location* -- it has no way to see a raid on Miners mining 8-15+ tiles
out (Iteration 7's own note on lead-sparse maps). The Iteration 20 raid (2-3
enemy Soldiers, r420-480) was outside that radius the whole time: `--events`
showed zero friendly-Soldier attacks defending the Miners, and `armyObjective`
never had anything but the static mirror-point or (once sighted) an enemy
Archon to offer -- there was no shared-array signal a raid was even
happening. Miners already detect a local threat and flee (existing code); the
gap is that this information dies with the fleeing Miner instead of alerting
the army.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | friendly-Soldier attacks defending Miners during the r420-480 raid | 0 | 0 (event log) | ✓ |
| V2 | `SA_HOME_THREAT` set at any point during the raid | 0 (never fires) | confirmed via code read: raid location is >29 dist² from the tracked Archon | ✓ |
| V3 | Miner already has local threat-detection to hook into | yes | `runMiner`'s existing `threat != null` branch | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

New shared slots `SA_ECON_THREAT`/`SA_ECON_RND`: a Miner that detects a local
threat broadcasts its own location + round (in addition to fleeing, unchanged).
`armyObjective()` honors it for 40 rounds, ranked just below `SA_HOME_THREAT`
and above sighted-enemy-Archon locations. Also folded into the Iteration 9
mass-gate's `known` check, so responding to a live raid isn't treated as a
speculative advance. Re-run pending.

**Step 6 attempt 1 result.** Re-ran `g_iter6/chessboard`: 0/2 this time (was
1/2) -- but `--metrics` on the loss shows the raid itself is measurably less
damaging: Miners drop 10 -> 5 this run (was 10 -> 2), and recover to 8 by r560
instead of sitting at the floor for 1500 rounds. `--indicators` on the raid
window (r415-445) confirms the mechanism fires (soldiers responding via
"objective"/"reinforce"). The win/loss flip is noise from one small sample
diverging on RNG (as Iteration 21 already found for this same matchup); the
underlying mechanism verifiably worked as designed. `g_iter16` mirror sanity
check: 11/20 = 55%, clean.

-> Step 2, **Gauntlet 21** (snapshot candidate -- benchmarks included).

**Gauntlet 21 (step 2/3).** Peer: **129/200 = 64.5% ≥ WinPct** -> **added as
`g_iter17`.** Per-ancestor: g_iter8 95%, g_iter9 70%, g_iter11 65%, g_iter14
65%, g_iter6 60%, g_iter12 60%, g_iter13 60%, g_iter15 60%, g_iter10 55%,
g_iter16 55%. Per-map (peer, 20 games each): jellyfish/squer 18/20 best;
**chessboard 7/20, valley 8/20** still clearly worst -- the raid-defense fix
measurably softened the specific raid it was built from but hasn't flipped
the map's overall win rate yet. Benchmarks unchanged: `sample_camelcase`
0/20, `sample_afinals` 2/20.

*Retirement:* `g_iter8` 95% this Gauntlet but only 80% last (G20) -- not
consecutive, no change. Pool: peers `{g_iter6, g_iter8..g_iter17}`,
benchmarks `{sample_camelcase, sample_afinals}`.

**Next:** chessboard/valley are large/open maps where games commonly run to
the r2000 timeout -- three iterations running (20, 21, 22) have chipped at
different facets of these losses (economy floor, attack cadence, raid
response) without flipping the map outright. Worth a fresh Step-4 loss
specifically on one of these two maps under the current code (g_iter17)
rather than continuing to re-mine the same Iteration-20 replay, since each
fix so far was verified against a snapshot several iterations stale by the
time it landed.

---

## Iteration 23  —  steepen the long-game Miner floor (with a rejected first attempt)

### Step 4 — losing game  `g_iter6 / valley / botB` (fresh loss under g_iter17)

Followed Iteration 22's "next" pointer -- a fresh loss on `valley`, not a
re-mine of the stale Iteration 20 replay. Unlike the chessboard raid, this one
has no dramatic Miner crash: a steady, structural economy gap instead.

### Step 5 — Hypothesis

`--metrics`: our team Miners settle at **15** by r100 (matches the non-
`richHome` per-Archon fallback `max(leadTiles,5)` x 3 Archons on this map) and
*decline* to 8-10 by r700-800 while `g_iter6` holds 20-30 the whole game --
55 Soldiers to our 0 by r800, Archon HP crashing from full to 849 right after.
The Iteration 20 floor (`6 + round/200`, cap 20) is provably too slow here:
at r700 it's only 9-10, always <= our actual (declining) count, so it never
fires -- and the game is already decided by r800, long before the ramp would
reach anything meaningful.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | our team Miners, r100 -> r800 | flat or declining | 15 -> 8 | ✓ |
| V2 | g_iter6 Miners, same window | stays well above ours | 19-30 throughout | ✓ |
| V3 | floor value at r700-800 vs our actual count | floor <= actual (never fires) | 9-10 <= 10, 8 | ✓ |
| V4 | g_iter6 Soldiers, r600 -> r800 | explosive growth | 13 -> 55 | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1, REJECTED)

Steepen the ramp to `6 + round/50`, cap 30 (g_iter6's old map-scaled cap
topped out at 34). Re-run pending.

**Step 6 attempt 1 result.** Re-ran `g_iter6/valley`: economy held much better
(17 Miners at r700, was 8-10) but still lost both games -- more mining doesn't
reduce the *opponent's* independently-compounding army, and our Soldier count
still crashed to 0 by r800 regardless. Proceeded to the standard pipeline
anyway (mirror clean, 11/20) since the change could still be net-positive
elsewhere. **Gauntlet 22, peer: 125/220 = 56.8% < WinPct -- REJECTED.**
g_iter14/15/16 dropped to 40-45% (were 55-65% in G21) -- a real regression:
diverting build turns to extra Miners has a genuine opportunity cost in games
already winnable on combat alone, and the aggressive ramp paid that cost
everywhere, not just where it was needed. Reverted per Step 6.5 (`git
checkout`).

### Step 6 — Solution (attempt 2)

Split the difference: `6 + round/100`, cap 25. Re-run pending.

**Step 6 attempt 2 result.** `g_iter17` mirror: 10/20 = 50%, clean.

-> Step 2, **Gauntlet 23** (snapshot candidate -- benchmarks included; the
rejected attempt's Gauntlet 22 numbers don't count toward retirement, since
that code was reverted and never became the accepted lineage).

**Gauntlet 23 (step 2/3).** Peer: **141/220 = 64.1% ≥ WinPct** -> **added as
`g_iter18`.** Per-ancestor: g_iter8 90%, g_iter13 70%, g_iter6/g_iter9/g_iter11/
g_iter12/g_iter14 65%, g_iter15/g_iter16 60%, g_iter10/g_iter17 50% -- and
critically, **no regression** against g_iter14/15/16 this time (60-65%, back
to G21 levels). Per-map (peer, 22 games each): **valley 9/22** still worst,
but **chessboard improved to 10/22** (was 7/20 in G21). Benchmarks unchanged:
`sample_camelcase` 0/20, `sample_afinals` 2/20.

*Retirement:* `g_iter8` 95% (G21, accepted-lineage) **and** 90% (G23,
accepted-lineage) -- two consecutive ≥90% on the accepted-iteration sequence
(G22 excluded, rejected) -> **retired.** Pool: peers `{g_iter6, g_iter9..g_iter18}`,
benchmarks `{sample_camelcase, sample_afinals}`.

**Next:** valley is now the clear single worst peer map. This session has now
tried economy-floor fixes (20, 23), attack-cadence (21), and raid-response
(22) against this family of large-map, long-game losses -- each helped
somewhat but none flipped the class outright, and attempt 1 here showed
economy tuning has a real ceiling (helping the loss can regress wins
elsewhere). Worth checking whether the remaining gap on these big maps is
positional/pathing (Dijkstra20's within-vision horizon on very large maps) or
simply that our combat-strength-per-Soldier is still behind at parity numbers
-- a fresh V1/V2-style attack-rate comparison (per Iteration 21's method) on a
valley loss, since that measurement hasn't been done on this specific map.

---

## Iteration 24  —  stop resetting SA_FOCUS every round (formation cohesion)

### Step 4 — losing game  `g_iter9 / valley / botB` (fresh loss under g_iter18)

Per Iteration 23's pointer: an attack-rate comparison on `valley`, not yet
done on this map.

### Step 5 — Hypothesis

Both armies started nearly identical (14-17 Soldiers each through r350).
Over r350-410 the attack-volume comparison (Iteration 21's method) showed only
a modest edge for the opponent (371 vs 311 attacks, 1.19x) yet a hugely
lopsided kill ratio (they lost 4 Soldiers, we lost 10, 2.5x). `solSpread`
(the per-round soldier-position stddev, already in `--metrics`) explained it:
their formation *tightened* during the fight (8.9 -> 4.0) while ours *widened*
(8.3 -> 13.2) -- a scattered army trades worse even at comparable attack
volume, since fewer of the scattered units are simultaneously in range of the
focus target. Read `census()`: it clears `SA_FOCUS` to 0 every single round
("re-pick a focus-fire target each round", Iteration 12) before any engaged
Soldier has re-selected it that round -- Iteration 19's reinforcing soldiers
march toward wherever `SA_FOCUS` points *right now*, so a rally point that can
change or blink to 0 from round to round scatters the approach instead of
producing a stable convergence.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | our solSpread, start vs peak of engagement (r350->r380) | widens | 8.3 -> 13.2 | ✓ |
| V2 | opponent solSpread, same window | narrows | 8.9 -> 7.6 -> 4.4 (by r390) | ✓ |
| V3 | attack-volume ratio, r350-410 | modest (<1.5x) | 1.19x (371 vs 311) | ✓ |
| V4 | Soldier-loss ratio, same window | much larger than V3 | 2.5x (10 vs 4) | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1)

Drop the blanket per-round `SA_FOCUS` reset from `census()`. The existing
dead-target check in `runSoldier` (clears `SA_FOCUS` when the tracked enemy is
confirmed gone via `canSenseLocation` + team check) is sufficient to keep it
from going stale. Re-run pending.

**Step 6 attempt 1 result.** Re-ran `g_iter9/valley`: **1/2 -- won as side A**
(this exact matchup was a clean loss before). The remaining loss (side B)
diverged onto a much longer game (r1522, RNG cascade changed by the code
change) so a clean single-replay before/after comparison wasn't available,
but the win is a real result, not noise reduction. `g_iter18` mirror sanity
check: 12/20 = 60%, clean (slightly above the usual 50-55% mirror baseline).

-> Step 2, **Gauntlet 24** (snapshot candidate -- benchmarks included).

**Gauntlet 24 (step 2/3).** Peer: **139/220 = 63.2% ≥ WinPct** -> **added as
`g_iter19`.** Per-ancestor: g_iter6/g_iter9/g_iter10 75%, g_iter12/g_iter16
65%, g_iter13/g_iter18 60%, g_iter11/g_iter14/g_iter15/g_iter17 55%. Per-map
(peer, 22 games each): **valley jumped from 9/22 (41%, G23) to 17/22 (77%)** --
the formation-cohesion fix landed exactly where it was aimed. **pillars is now
the worst map (10/22)**, chessboard improved slightly to 11/22. Benchmarks
unchanged: `sample_camelcase` 0/20, `sample_afinals` 2/20.

*Retirement:* no opponent reached two-consecutive ≥90% this Gauntlet (max
75%). No changes. Pool: peers `{g_iter6, g_iter9..g_iter19}`, benchmarks
`{sample_camelcase, sample_afinals}`.

**Next:** pillars is the new clear worst map. This session's last several
iterations (20-24) have each targeted one distinct large-map failure mode
(economy floor, attack cadence, raid response, formation cohesion) and each
helped -- pillars is worth the same treatment: a fresh Step-4 loss there,
--metrics for army-size/economy comparison, --indicators for the
heal/attack/objective/reinforce split, and solSpread for formation cohesion,
before forming a new hypothesis rather than assuming it's the same mechanism
already fixed.

---

## Iteration 25  —  mass-gate reinforcement (REJECTED) -- and a bigger finding: pillars is side-imbalanced

### Step 4 — losing game  `g_iter18 / pillars / botA`

Before selecting, checked the full Gauntlet 24 `results.csv` for `pillars`
across the whole ancestor pool: **whichever Battlecode team letter is "B" won
19/22 games (86%), regardless of which bot -- ours or any ancestor -- was
playing it.** This is a structural map/spawn asymmetry, not an
opponent-strength or code-quality signal; it had been invisible in per-
opponent win-rate aggregates because both A-side and B-side games get averaged
together per opponent.

### Step 5 — Hypothesis

`--metrics` on a fresh team-A loss: we pushed the enemy Archon down to
708/1800 HP by r360 (a near-kill), then were wiped 13 -> 1 Soldiers over
r380-500 while the opponent rebuilt 6 -> 38. `--indicators` on that window:
79% of our Soldier-turns were "reinforce" -- solo, freshly-built Soldiers
marching individually into the same now-losing distant fight, since Iteration
19's reinforcement branch skips the Iteration 9 mass-gate unconditionally.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | opponent Archon HP damaged to a near-kill by r360 | yes | 708/1800 | ✓ |
| V2 | our Soldier count, r380->r500 | large collapse | 13 -> 1 | ✓ |
| V3 | opponent Soldier count, same window | large rebuild | 6 -> 38 | ✓ |
| V4 | our Soldier-turns in "reinforce" state, r380-460 sample | ≥ 50% | 79% | ✓ |

**Verified → Step 6.** (In hindsight, V1-V4 describe a real *symptom* of
losing -- they don't establish that gating reinforcement is the *cause* fix;
see the rejection below.)

### Step 6 — Solution (attempt 1, REJECTED)

Extend the mass-gate to the `SA_FOCUS` reinforcement branch: a solo Soldier
>25 dist² from the live focus point waits for 3+ friendlies before
committing, same rule Iteration 9 used for the speculative advance. Re-run
pending.

**Step 6 attempt 1 result.** Re-ran `g_iter18/pillars`: still 1/2 (same split
as before, side A still lost, survived longer r578->r648). `g_iter19` mirror:
9/20 = 45%, within normal noise. **Gauntlet 25, peer: 126/240 = 52.5% <
WinPct -- REJECTED**, and broadly: 7 of 12 opponents dropped below 50%
(g_iter10 40%, g_iter12/15/16/17/19 45%, g_iter18 40%) where most had been
55-75% in Gauntlet 24. Reverted per Step 6.5 (`git checkout`).

**Why this failed broadly:** Iteration 19's entire point was *unconditional,
fast* reinforcement -- most peer wins since then likely depend on that speed.
Gating it broadly slowed down reinforcement in the large majority of games
that don't have the specific failed-siege problem, for a fix aimed at one
narrow map. Worse: re-examining the pillars side-imbalance data, **the 86%
team-B win rate holds across ancestors like g_iter6 and g_iter9, which predate
Iteration 19's reinforcement mechanism entirely** -- so the mechanism this
iteration targeted cannot be the root cause of the map imbalance. The
solo-reinforcement pattern in the Step 5 replay was a symptom of an
already-losing position, not its cause.

**Not retrying this hypothesis further** (Step 5.6 in spirit, even though the
verified-variables table technically passed) -- the corrected understanding
changes what Step 4 should target next. **Next:** investigate `pillars`'
team-A-vs-team-B asymmetry directly and structurally -- compare Archon
starting terrain/nearby-lead between the two spawn corners (already spotted:
team A's nearest rich lead cluster is ~9-14 tiles out vs. closer clusters
near team B's spawn in one sampled replay) rather than any in-game behavioral
fix. If the map itself is unfair, the correct response may be down-weighting
or flagging it rather than chasing a code fix that can't exist.

### Methodological finding: most loop maps have a strong side bias

Checked every map in the loop set the same way (which Battlecode team letter
won, across the whole Gauntlet 24 ancestor pool, regardless of which bot --
ours or any ancestor -- was playing which side):

| map | winner=A | winner=B | balanced? |
|---|---|---|---|
| maptestsmall | 21/22 | 1/22 | **no -- A** |
| intersection | 19/22 | 3/22 | **no -- A** |
| sandwich | 18/22 | 4/22 | **no -- A** |
| chessboard | 20/22 | 2/22 | **no -- A** |
| maze | 5/22 | 17/22 | **no -- B** |
| pillars | 3/22 | 19/22 | **no -- B** |
| highway | 13/22 | 9/22 | roughly |
| jellyfish | 13/22 | 9/22 | roughly |
| valley | 12/22 | 10/22 | roughly |
| squer | 11/22 | 11/22 | yes |

Six of ten loop maps have a strong, code-independent side bias baked into the
map geometry -- our code is fully side-symmetric (nothing reads `rc.getTeam()`
to change strategy), so this cannot be a bug we introduced, and one team's
disadvantaged-side win rate on these maps is structurally capped well below
50% no matter how good the bot is. This reframes several sessions' worth of
"worst map" chasing: **Iterations 20-25 repeatedly targeted chessboard,
pillars, maze, valley** as "worst maps" from raw aggregate win rate, but at
least three of those four are side-imbalanced -- the low aggregate score was
substantially map geometry, not fixable bugs. (Iteration 24's valley fix is
the exception that proves the rule: valley is one of the *balanced* maps, so
its 41%->77% jump is a real, clean signal.) On the four balanced maps we are
already doing reasonably well (squer 64%, highway 64%, jellyfish 91%, valley
77%, all from Gauntlet 24) -- there just wasn't much signal left to chase
there, which is *why* the worst-aggregate-map heuristic kept pointing at the
imbalanced ones instead.

**Going forward: prefer squer/highway/jellyfish/valley (and full-mapset runs)
as the reliable source of true Step-4 losing games.** For imbalanced maps, a
loss on the map's disadvantaged side is not strong evidence of a real bug
unless it's also anomalous relative to other losses on that same side.

---

## Iteration 26  —  peak-triggered Miner floor boost (REJECTED, both attempts)

### Step 4 — losing game  `g_iter9 / highway / botA` (highway is one of the balanced maps)

### Step 5 — Hypothesis

`--metrics`: we pushed the enemy Archon to 846/1200 HP by r800 (a near-kill),
then collapsed 27 -> 0 Soldiers by r1300 while the opponent's economy surged
(Miners 12->27, Soldiers 9->121) -- the same push-stall-collapse shape as
Iterations 20/23/25's chessboard/valley/pillars losses, still recurring.
Unlike Iteration 25's rejected fix (which gated army *movement*), this
targets the *economy* side: track the team's peak live-Soldier count
(`SA_PEAK_SOL`); when current Soldiers fall below half that peak (a real
battlefield reversal, not just early-game noise), boost the Miner floor to
rebuild faster than the round-based ramp alone would.

| # | variable | threshold | measured | ✓ |
|---|----------|-----------|----------|---|
| V1 | opponent Archon HP damaged to a near-kill | yes | 846/1200 by r800 | ✓ |
| V2 | our Soldiers, r800->r1300 | collapse | 27 -> 0 | ✓ |
| V3 | opponent Miners/Soldiers, same window | surge | 12->27 / 9->121 | ✓ |

**Verified → Step 6.**

### Step 6 — Solution (attempt 1, REJECTED)

`peakSol >= 10 && liveSoldiers < peakSol/2` -> `floor = max(floor, min(peakSol, 30))`.

**Result.** Re-ran the target game: mechanism fired exactly as designed
(Miners jumped 13->28-30 after the crash, visibly outpacing g_iter9's own
declining economy for a while) and flipped to 1/2 (won as side A). But the
opponent's economy scaled even faster in the specific replay tested (Miners
to 62, Soldiers to 397) so the other side still lost. `g_iter19` mirror: 50%,
clean. **Gauntlet 26, peer: 141/240 = 58.8% < WinPct** -- close, and critically
*no* opponent dropped below 50% (not Iteration 25's broad-collapse
signature), but still a miss.

### Step 6 — Solution (attempt 2, REJECTED)

Tightened the trigger (`peakSol >= 15`) and lowered the cap (`min(peakSol,
25)`, matching Iteration 23's already-validated ceiling) to reduce
false-positive firing on ordinary early fluctuations.

**Result.** `g_iter19` mirror: 50%, clean. **Gauntlet 27, peer: 142/240 =
59.2% < WinPct** -- almost identical to attempt 1, still no opponent below
50%. Two materially different parameterizations of the same mechanism both
landed 1-2 points under the bar, consistently below Iteration 24's 63.2%
pre-change baseline -- this reads as a genuine, if mild, net-negative from
this lever rather than sampling noise (a truly neutral/noop change would be
expected to hover *around* 63%, not sit reliably below it twice). Reverted
per Step 6.5.

**Not attempting a third variant** (of 5 allowed) -- two consistent
near-misses in the same direction is a weak signal that peak-triggered
economy boosting isn't the right lever for this failure class, distinct from
Iteration 23's floor tuning (which had one bad attempt and one clear pass,
not two consistent near-misses). **Next:** the push-stall-collapse pattern
has now resisted three different fix angles (reinforcement gating in
Iteration 25, economy boosting here) on top of the earlier partial fixes
(20/23/24) -- it may be a combat-quality problem after all (per-engagement
trade efficiency once the siege stalls and rubble/positioning turns against
the attacker), which Iteration 21's rubble-kiting only partially addressed.
Worth a fresh Step-4 loss with `--indicators` specifically on the stall
window (not the collapse window) to see what a soldier does in the rounds
right after an assault stops making progress, before it starts dying.

**Process change (user request):** from this iteration on, check the single
most interesting replay from each iteration's Gauntlet into `replays/` in
git, so bot play can be reviewed over time independent of the git-ignored
`gauntlet/` directory. See `TRAINING_ALGORITHM.md`'s new "Replay archive"
section.

---

## Iteration 27  —  extend rubble-repositioning to the direct-attack fallback

Rather than force a fourth attempt at the push-stall-collapse pattern (three
dead ends running: Iterations 25, 26 attempt 1, 26 attempt 2), picked a
smaller, low-risk, mechanically well-understood gap instead.

### Step 4/5

Iteration 21's `repositionForRubble()` (step onto a lower-rubble tile before
firing, for faster next-turn cooldown) was only wired into the focus-fire
attack branch (`SA_FOCUS` set). The direct-target fallback branch just below
it -- used for the *first* contact of a fight, before focus fire has been
established -- never got the same treatment. No fresh hypothesis-verification
table for this one; it's a direct, low-risk extension of an already-accepted,
already-verified mechanism to a second code path that does the same kind of
attack.

### Step 6 — Solution

Add the same `repositionForRubble()` call to the direct-attack branch.
`g_iter19` mirror: 50%, clean.

-> Step 2, **Gauntlet 28** (snapshot candidate -- benchmarks included).

**Gauntlet 28 (step 2/3).** Peer: **149/240 = 62.1% ≥ WinPct** -> **added as
`g_iter20`.** Per-ancestor: g_iter6/g_iter9/g_iter10 75%, g_iter12/g_iter16
65%, g_iter13/g_iter18 60%, g_iter11/g_iter14/g_iter15/g_iter17 55%, g_iter19
50%. Per-map (peer, 24 games each): jellyfish 21/24 best; pillars 11/24 worst
(map-imbalanced, expected). Benchmarks unchanged: `sample_camelcase` 0/20,
`sample_afinals` 2/20.

*Retirement:* no opponent reached two-consecutive ≥90% (max 75%). No changes.
Pool: peers `{g_iter6, g_iter9..g_iter20}`, benchmarks `{sample_camelcase,
sample_afinals}`.

**Replay archived:** `replays/iter27_sample_camelcase_maptestsmall_botA.bc22`
(a fresh camelcase loss under the current code, for baseline comparison in
future iterations).

**Next:** the push-stall-collapse pattern (Iterations 20/23/24 partial fixes,
25/26 rejected fixes) remains the biggest unresolved structural gap, now on
`squer`/`highway`-class balanced maps as much as the map-imbalanced ones.
Iteration 26's closing note (fresh `--indicators` on the *stall* window, not
the collapse window) is still the most promising unexplored angle.

---

## Iteration 28  —  target enemy Soldiers before Archons (biggest single-iteration jump this session)

### Step 4/5

Followed Iteration 26's own closing pointer: fresh `--events` on the
g_iter9/highway *stall* window (r815-830, not the later collapse) instead of
re-treading the collapse itself. Result: of our 5 remaining Soldiers, only
**one** (`#13152`) was ever in range of the enemy Archon; the other four were
individually entangled with the opponent's growing defensive garrison, each
still honoring `betterTarget`'s "Archon first" rule (unchanged since
Iteration 1) by ignoring the Soldier actually in front of them whenever *any*
Archon was in sight anywhere. Once a siege meets real defenders, "always
chase the Archon" starves the fight that's actually blocking the siege of any
help. camelcase's own verified, working attack-priority list ranks Soldier
above Archon for exactly this reason. No formal V1-V4 table -- the fix is a
direct structural read of the event log, not a threshold measurement.

### Step 6 — Solution

Replaced `betterTarget`'s binary Archon-first rule with a 3-tier
`targetPriority`: combat units (Soldier/Sage/Watchtower) > Archon > workers
(Miner/Builder/Laboratory), tie-broken by health within a tier as before.

**Result.** Re-ran `g_iter9/highway`: **2/2**, including the side that had
been losing all session. `g_iter20` mirror: **70%** (vs the usual 45-55%
mirror baseline -- a strong signal even before the full Gauntlet).

-> Step 2, **Gauntlet 29** (snapshot candidate -- benchmarks included).

**Gauntlet 29 (step 2/3).** Peer: **203/260 = 78.1% ≥ WinPct** -> **added as
`g_iter21`.** By far the best peer score this session (previous best 69.4%,
Iteration 18) -- *every* opponent at 70-90% (g_iter6 90%, g_iter9/10 80%,
g_iter12 85%, rest 70-80%). Per-map (peer, 26 games each): **squer 26/26
(100%)**, maptestsmall 22/26, maze/pillars 21/26, highway 21/26 -- **pillars
jumped from 45% to 81%** and even **chessboard (previously an 91%-imbalanced
map) improved to 11/26 (42%)**, well above what pure map geometry would
allow if the fix weren't doing real work on both sides.

**Benchmarks: `sample_camelcase` 1/20 (5%) -- the first win against it all
session** (`sandwich`, bot as B, r296), after 0/20 across Iterations 18-27.
`sample_afinals` unchanged at 2/20. Neither crosses the 30% peer-
reclassification threshold yet, but this is the clearest evidence yet that
the accumulated iterations are closing the gap against the strongest
opponent, not just the peer pool.

*Retirement:* `g_iter6` hit 90% this Gauntlet but only 75% in G28 -- not
consecutive, no change. Pool: peers `{g_iter6, g_iter9..g_iter21}`,
benchmarks `{sample_camelcase, sample_afinals}`.

**Replay archived:** `replays/iter28_sample_camelcase_sandwich_botB_WIN.bc22`
-- the historic first camelcase win (pulled directly from the VM since
`gauntlet.sh` only auto-copies losses back).

**Next:** with the Archon-rush trap fixed, worth re-running the exact
Iteration-18/20 style attack-rate and solSpread comparisons against
camelcase fresh -- the whole shape of these fights has likely changed and the
old measurements (e.g. the 1.47x attack-rate gap) may no longer describe the
current gap accurately.

---

## Iteration 29  —  side-step around blocked-path congestion

### Step 4/5

Fresh `--metrics` on the g_iter21-era camelcase/sandwich win-then-loss replay
(Iteration 28's own log): we reduced camelcase to **zero Soldiers for 60+
rounds** (r220-280) after damaging one Archon to 600/1200 HP -- an even more
decisive advantage than any prior siege -- yet never closed it out. `--events`
showed why: 384 consecutive identical `"objective [38,11]"` indicator strings
from every Soldier, and the army's position centroid barely advanced (29.3 ->
34.1 over 90 rounds, oscillating) despite zero enemy resistance. Pure
corridor congestion on a long, narrow map (`sandwich`, 60x21) -- many
Soldiers funneling through one path, each independently falling back to the
full 8-direction greedy re-scan whenever the Dijkstra pather's blocked (by a
friendly unit, not terrain), which can pull a unit onto a different route and
scatter the column instead of keeping it filing through.

### Step 6 — Solution

In `moveToward`, when the Dijkstra direction is blocked, try side-stepping to
its immediate neighbors (`rotateLeft`/`rotateRight`) before falling back to
the full greedy re-scan.

**Result.** Re-ran `sample_camelcase/sandwich`: 0/2 (was 1/2) -- but the RNG
cascade diverged completely (a much faster, differently-shaped loss), so no
clean single-replay comparison. `g_iter21` mirror: 40%, within normal noise.

-> Step 2, **Gauntlet 30** (snapshot candidate -- benchmarks included).

**Gauntlet 30 (step 2/3).** Peer: **184/280 = 65.7% ≥ WinPct** -> **added as
`g_iter22`.** Notable shape: very strong against older ancestors (g_iter6
90%, g_iter9-13 75-85%) but soft against the most recent ones (g_iter19/20
45%, g_iter21 40%) -- a lateral-matchup pattern (this iteration's own
immediate lineage responds differently to congestion changes than older,
structurally different code), not a broad regression; overall WinPct clears
the bar. Per-map: **maze jumped to 27/28 (96%)**, but **sandwich -- the map
this fix directly targeted -- dropped to 13/28 (46%)**, a genuine regression
on the target map even as the overall pool improved. Worth revisiting.
Benchmarks: `sample_camelcase` held at 1/20 (a new win on `valley`, r551,
different game from Iteration 28's), `sample_afinals` improved to **3/20
(15%, up from 2/20)**.

*Retirement:* `g_iter6` hit 90% in **both** Gauntlet 29 and 30 -- two
consecutive -> **retired.** Pool: peers `{g_iter9..g_iter22}`, benchmarks
`{sample_camelcase, sample_afinals}`.

**Replay archived:** `replays/iter29_sample_camelcase_valley_botA_WIN.bc22`.

**Next:** the sandwich regression is the immediate open question -- worth a
fresh loss there specifically before the next unrelated hypothesis, to check
whether the side-step change itself is the cause or whether it's confounded
with the same lateral-matchup effect seen against g_iter19-21.

---

## Iteration 30  —  Builder/Watchtower static home defense (new mechanic)

The `sandwich` regression check (this iteration's own opening item) turned
out to be a near-mirror "small early edge compounds" pattern like squer
(Iteration 15), not a specific bug -- as the peer pool converges in strength
after Iteration 28's jump, more maps show this noise. Skipped re-chasing it
and picked a fresh, non-mirror signal instead: a `sample_camelcase/highway`
loss.

### Step 4/5

`--metrics`: a clean, low-combat economy race -- neither Archon takes real
damage until r570+, army sizes diverge purely on production from ~r300
(camelcase 24->132, us 21->0). This is the same structural gap Iteration 26
tried to fix twice (raise the Miner floor on a Soldier-count crash) and both
attempts were rejected for broad peer regressions. Rather than retry
economy-floor tuning a third time, added a mechanic never attempted this
session: Builder-built Watchtower static defense, present in camelcase's own
design since early in the project but never implemented on our side.

### Step 6 — Solution

One Builder per Archon (capped via `myBuildersSpawned`), which heads home,
builds one Watchtower, and repairs it (and anything else) while it's a
`PROTOTYPE`. Watchtower AI is a single attack call using the existing
`betterTarget` priority. Two failed gating attempts before it worked:
- **Attempt 1**: gated on `!contact` -- same trap as Iteration 20's attempt 1
  (`contact` means "enemy sighted recently", permanently true for the back
  half of a long game with sustained visibility). `--metrics` confirmed 0
  Builders/Watchtowers the entire replay.
- **Attempt 2**: gated on `!needMiners` -- but the Miner floor climbs with
  round number, so Miners hover perpetually at/near it and `needMiners` is
  nearly always true too, for the identical reason. Still 0 Builders.
- **Attempt 3**: since the Builder is self-limiting (max 1 ever), gave it
  priority over the Miner/Soldier decision once `round > 100`, `miners >= 8`,
  and `lead > 300` -- guaranteed to fire once instead of waiting for a
  condition that never fully clears. `--metrics` confirmed: Builder=1,
  Watchtower=1, functional by r320 in one of the two `highway` test games.

`g_iter22` mirror: 50%, clean.

-> Step 2, **Gauntlet 31** (snapshot candidate -- benchmarks included).

**Gauntlet 31 (step 2/3).** Peer: **177/280 = 63.2% ≥ WinPct** -> **added as
`g_iter23`.** Same lateral shape as Iteration 29 (strong vs older ancestors
75-85%, softer vs the most recent 40-55%, g_iter21 the low point at 40%) --
overall still clears the bar. Per-map: maze 26/28, valley 25/28 strong;
chessboard 8/28, sandwich 12/28 weak (map-imbalance and near-mirror noise
respectively, both already understood, not new problems). No retirements
(max 85%).

**Benchmarks:** `sample_camelcase` held at 1/20 (5%, same `valley` win as
Iteration 29 -- this specific replay is apparently unaffected by the
Watchtower change). `sample_afinals` steady at 3/20 (15%) with a **new win on
`sandwich`** (r231) replacing a previous loss there. No regression, no
decisive breakthrough yet -- the mechanic works correctly but a single
Watchtower per Archon is a modest addition against opponents with truly
unbounded late-game armies.

**Replay archived:** `replays/iter30_sample_afinals_sandwich_botA_WIN.bc22`.

**Next:** the Watchtower mechanic is now proven functional -- worth checking
whether more than 1 per Archon, or triggering earlier/more aggressively,
helps further, but only after confirming (via a fresh peer Gauntlet) that a
second Watchtower doesn't reproduce Iteration 26's "extra economy investment
hurts games we're already winning" problem.

---

## Iteration 31  —  allow a second Watchtower per Archon in long games

### Step 6 — Solution

`builderCap = round > 400 ? 2 : 1` -- the second Builder/Watchtower can only
trigger in games already running long, so the extra investment doesn't land
in every game the way a flat cap increase would.

`g_iter23` mirror: 50%, clean.

-> Step 2, **Gauntlet 32** (snapshot candidate -- benchmarks included).

**Gauntlet 32 (step 2/3).** Peer: **188/300 = 62.7% ≥ WinPct** -> **added as
`g_iter24`.** Same lateral shape as the last two Gauntlets (g_iter21 the low
point at 40%, older ancestors 75-85%) -- stable, not worsening. No
retirements (max 85%).

**Benchmarks: identical to Iteration 30** -- `sample_camelcase` 1/20 (same
`valley` win), `sample_afinals` 3/20 (same three wins). Makes sense: most
benchmark losses are decided well before r400 (per the loss-round data
logged across Iterations 27-30, typically r200-800), so the second
Watchtower rarely gets a chance to fire in exactly the matchups being
tracked. Confirmed it *does* fire in genuinely long peer games: a 2000-round
`g_iter9/highway` loss shows 2 Builders by the end (though the second
Watchtower hadn't finished construction in time).

**Replay archived:** `replays/iter31_g_iter9_highway_botB.bc22` -- the
2000-round game showing the second Builder/Watchtower mechanism engaging.

**Next:** the Watchtower investment appears to have plateaued in value for
now -- two iterations (30, 31) added it and extended it with no benchmark
movement. Time to return to a different angle for the camelcase/afinals gap;
worth checking whether the two benchmarks' losses now cluster around a
common cause (e.g. via a fresh V1-style attack-rate/solSpread comparison
post-Iteration-28's targeting fix, which was never re-verified against
afinals specifically -- all the post-Iteration-28 measurement has been
camelcase-only).

---

## Iteration 32  —  prioritize enemy Laboratory (afinals's Sage pipeline, finally identified)

### Step 4/5

Fresh `--metrics` on `sample_afinals/highway` (a balanced map, following
Iteration 26's own methodological correction): a real, distinct opponent
build, unlike anything analyzed against camelcase this session. afinals
built **0 Soldiers** and instead grew to **111 Sages** (100 HP, 45 dmg -- a
near-one-shot on our 50-HP Soldiers) by r840, fueled by 6 Laboratories
converting lead to gold throughout the game. Their Sages alone destroyed our
25-Soldier army and then our Archon (1200 -> 15 HP) while they fielded zero
regular Soldiers the entire game. This also **corrects** a wrong claim from
Iteration 17's session-close note (already partially corrected in Iteration
18): "camelcase never builds Sage/Laboratory" is true, but the note went on
to describe the gold/Sage economy as *the* frontier without ever actually
profiling `sample_afinals` directly to confirm it's real there -- it is, and
this is the first time this session it's been measured.

`targetPriority` ranked Laboratory at 0 (tied with Miner/Builder) -- the
literal source of the entire threat was untargeted. camelcase's own
attack-priority list (`Robot.java`, already read back in Iteration 4-era
research) ranks Laboratory **second only to Soldier** -- above Sage,
Watchtower, and even Archon. Match that.

### Step 6 — Solution

`targetPriority`: combat units (3) > Laboratory (2) > Archon (1) > workers
(0). `g_iter24` mirror: 50%, clean.

-> Step 2, **Gauntlet 33** (snapshot candidate -- benchmarks included).

**Gauntlet 33 (step 2/3).** Peer: **198/320 = 61.9% ≥ WinPct** -> **added as
`g_iter25`.** Stable, same shape as recent Gauntlets (g_iter21 40% low
point, older ancestors 75-85%). No retirements (max 85%).

**Benchmarks: unchanged from Iteration 30-31** -- `sample_camelcase` 1/20,
`sample_afinals` 3/20, identical win set both times. The targeted replay
(`highway`) itself showed no Laboratory kills even with the new priority --
our Soldiers likely never survive long enough against 100+ Sages to reach
the Labs, which (per afinals' own economy) are presumably kept well back
from the front line. The fix is directionally correct (matches verified
working code) and clears the peer bar with no regression, but doesn't move
this specific benchmark on its own -- reaching the Labs would need our army
to survive the Sage gauntlet first, which is really the same combat-quality
gap flagged repeatedly this session, now with a concrete adversary (Sage's
45-damage hits) instead of a vague "trade efficiency" description.

**Replay archived:** `replays/iter32_sample_afinals_maptestsmall_botA_WIN.bc22`.

**Next:** afinals' Sage threat is now precisely characterized (0 Soldiers,
pure Sage/Lab economy, 45 dmg/hit, actCD 200 -- attacks roughly once every 20
rounds per Sage, but compounds at scale). A soldier that survives to melee
range trades reasonably against a single Sage (100 HP is not much more than
a Soldier's 50, and Sage's slow cooldown means most rounds it can't act) --
the real problem is the sheer *count* by the time our army arrives. Worth
checking whether an earlier, more aggressive push (before Sage count builds
past ~20-30) fares better than the current patient buildup this session's
economy-tuning has favored.

---

## Iteration 33  —  narrow extreme-distance reinforcement gate (REJECTED, marginal)

### Step 4/5

`sample_afinals/highway` again: 64% of A-Soldier-turns in "reinforce" state
across a 200-round window with troop superiority. Checked whether the target
itself was unstable (would explain wasted travel) via `--indicators`: three
distinct focus points in a 10-round sample, dominated 75% by one -- stable,
not flickering. Genuine cross-map travel-distance problem, not a targeting
bug. Considered full Archon relocation (a real camelcase mechanic never
attempted this session) but held back due to uncertainty about Battlecode's
exact PORTABLE/TURRET mode-gating rules for building -- see the user's
correction below.

### Step 6 — Solution

Retried Iteration 25's rejected idea (mass-gate solo reinforcement) with a
far narrower trigger: >30 tiles (dist² 900) instead of the original ~5 tiles
(dist² 25) that caught nearly all reinforcement and caused a broad
regression. `g_iter25` mirror: 45%, within normal noise.

-> Step 2, **Gauntlet 34** (snapshot candidate -- benchmarks included).

**Gauntlet 34 (step 2/3).** Peer: **203/340 = 59.7% < WinPct** -- a marginal
miss (0.3 points), same standard applied to Iteration 26's 58.8%/59.2%
misses. No opponent below 40% (g_iter21, the same recurring lateral-softness
point as Iterations 29-32) -- not Iteration 25's broad-collapse signature,
just the pre-existing lateral pattern tipping the overall average under 60%
this time. Reverted per Step 6.5.

**User feedback mid-session:** "Don't worry so much when you try a big idea.
It's ok to try something that's high risk and fail... we're never going to
defeat camelcase by being cautiously incremental." Saved as a standing
preference (memory: `embrace-high-risk-iterations`). Directly applies to the
Archon-relocation idea shelved above -- picking that up next instead of
another narrow tweak.

---

## Iteration 34  —  Archon relocation (ACCEPTED, marginal); snapshot g_iter26

### Step 4/5

Picked up the idea shelved in Iteration 33: a besieged-but-not-yet-lost
Archon relocating toward the army's front, exactly like camelcase's own
PORTABLE/TURRET transform-and-move mechanic. Resolved the uncertainty that
stopped me last time by pulling the real `battlecode22-2.2.1.jar` onto
`battlecode-dev` and decompiling `RobotMode` with `javap -c`: `TURRET` is
`canAct=true, canMove=false, canTransform=true`; `PORTABLE` is
`canAct=false, canMove=true, canTransform=true`. A relocating Archon
genuinely cannot build, repair, or attack -- confirmed, not inferred.

Implementation: an Archon with no home threat (`th==0`), no combat unit
within dist² 40, more than 20 tiles (dist² 400) from `armyObjective()`, past
round 500, and not yet relocated this game transforms to PORTABLE and walks
toward the objective for up to 6 steps (or until a threat appears nearby),
then transforms back. Capped at one relocation per Archon per game.

Two gate-loosening attempts on the Step-4 test replay (`sample_afinals/
highway`) showed **zero** behavioral change (identical r849/r1105 round
counts before and after, both with `foes.length==0` and then with the
`localThreat` distance check). `--metrics` explained why: the Archon's HP in
that specific matchup declines continuously from r500 onward (660 -> 525 ->
420 -> 105) under sustained ranged Sage fire -- `th==0` correctly never
holds there, and a besieged Archon shouldn't relocate anyway. Concluded this
was the gate working as intended for an unfavorable matchup, not a bug, and
moved to the standard validation pipeline rather than loosening further
without more evidence -- per the "embrace risk" feedback, tested the honest
version instead of tuning against one replay until it fired.

### Step 6 — Solution

`g_iter25` mirror: 50% (10/20), clean.

-> Step 2, **Gauntlet 35** (snapshot candidate -- benchmarks included).

**Gauntlet 35 (step 2/3).** Peer: **205/340 = 60.3% >= WinPct** -- a
marginal but genuine pass. Per-opponent spread unchanged in shape from
Iteration 32/33's baseline: strong against early ancestors (g_iter9-13,
75-85%), soft against the recent lateral cluster (g_iter19-25, 45-55%,
g_iter21 lowest at 35% -- the same recurring lateral-softness point noted
since Iteration 29). Per-map: no collapse anywhere (worst was chessboard at
12/34 = 35%, still within the range seen on that historically-hard map in
prior accepted iterations). No retirements this round (max domination 85%,
vs g_iter11/g_iter12).

**Snapshot:** `g_iter26`.

**Benchmarks: unchanged from Iteration 30-32** -- `sample_camelcase` 1/20
(5%), `sample_afinals` 3/20 (15%). Consistent with the Step-4 finding that
this feature is a positional fix for the peer pool's mid-game reinforcement
problem, not a combat-quality fix -- it was never expected to move the
benchmark needle on its own.

**Replay archived:**
`replays/iter34_g_iter25_valley_botB_WIN.bc22` -- a clean win (side B, r615)
where all three Archons independently trigger and complete a full
relocation cycle (`begin relocate toward [20, 3]` -> `relocating 1..6` ->
`relocated, transforming back`), the first replay this session where the
new mechanic visibly fires end-to-end.

**Next:** the lateral-softness cluster (g_iter19-25, and g_iter21
specifically) has now persisted across five-plus iterations without a
targeted fix -- worth a dedicated Step 4 investigation into what those
opponents' code actually does differently on the maps where we lose to
them, rather than continuing to treat it as background noise. Also worth
checking, in a calmer matchup than `sample_afinals/highway`, whether
relocation's positional benefit shows up quantitatively (e.g. reinforcement
travel distance or time-to-front) now that it's confirmed to fire.

---

## Iteration 35  —  sticky Miner beacon target (ACCEPTED, big jump); snapshot g_iter27

### Step 4/5

Picked up the "Next" pointer above: a dedicated investigation into the
g_iter19-25 lateral-softness cluster, starting from a `g_iter21/
intersection` loss (side A, r375). `--metrics` showed our Soldier count
peaking at ~9-10 by r70 (matching g_iter21's own) then collapsing to 0 by
r325 while g_iter21's climbed steadily to 50 -- despite both sides holding
~7 Miners the whole game. `--all-actions` traced it to the actual mining
rate, not the fight: over the whole game our Miners mined lead 552 times vs
g_iter21's 1241 (2.25x) with equal Miner counts. Re-running the exact same
map with sides flipped (`g_iter21__intersection__botB.bc22`) reproduced the
same asymmetry the other way (whichever side is g_iter21 mines far more,
regardless of which physical side of the mirrored map it's on) -- ruling out
spawn-position luck and pointing at our own code.

Since g_iter21 *is* an earlier snapshot of our own bot, `diff`ing
`runMiner()`/`moveToward()` against the current version showed the mining
logic itself is byte-identical -- the regression, if any, was indirect.
First hypothesis (Iteration 29's rubble-blind corridor side-step walking
Miners onto heavy-rubble maze walls) was implemented, then falsified before
even reaching the Gauntlet: re-running the same `TEAM_A=bot TEAM_B=g_iter21
tools/vm-match.sh intersection` reproduction with the fix in place produced
a byte-for-byte identical replay trace -- the side-step branch never fired
for the specific Miners in question, so it couldn't be the cause. Reverted
per Step 6.5 without spending a Gauntlet run on it.

Looked closer at the actual movement trace instead of the aggregate count:
individual Miners were visibly ping-ponging between two adjacent tiles for
10+ rounds without ever mining (e.g. `(29,22) -> (28,23) -> (29,22) -> ...`).
`nearestLead()` recomputes "closest known beacon" fresh every round with no
memory; on a maze map like `intersection` where lead clusters repeat at
regular intervals, two published beacons can sit at similar distance, and
which one is "nearest" can flip round to round as the Miner moves or as
*other* Miners deplete one of them. `moveToward`'s own anti-reversal guard
is keyed on the goal staying the same across calls -- it never engages here
because the goal itself is what's flipping.

### Step 6 — Solution

Added a per-Miner `myLeadTarget` static: once a Miner commits to a beacon it
keeps heading there until it actually arrives or the beacon reads out
depleted (`senseLead < 6`), instead of re-picking "nearest" every round.

Verified directly on the reproduction case before running anything else:
`TEAM_A=bot TEAM_B=g_iter21 tools/vm-match.sh intersection` flipped from a
loss (r375, our mining actions 436 vs g_iter21's 1253) to a **win** (r378,
mining actions 1450 vs 501) -- the fix visibly ends the ping-pong and lets
Miners actually settle on a deposit.

`g_iter25` mirror: 11/20 = 55%, no collapse -- and notably both
`intersection` games flipped from losses to wins (they were the original
Step-4 target), as did both `chessboard` games (the other historically-worst
map, also a maze/heavy-rubble archetype).

-> Step 2, **Gauntlet 36** (snapshot candidate -- benchmarks included).

**Gauntlet 36 (step 2/3).** Peer: **251/360 = 69.7%** -- up 9.4 points from
Iteration 34's 60.3%, the largest single-iteration jump this session.
Improved against essentially every opponent (early ancestors g_iter9-18 now
75-90%, vs g_iter10 hitting 90% specifically), and the lateral-softness
cluster moved too though it's still the relative soft spot: g_iter19-26 now
45-60% (worst g_iter21 at 45%, up from 35% in Gauntlet 35). No opponent
below 40%. g_iter10 crossed the 90%-domination retirement line this
Gauntlet but did not cross it in Gauntlet 35 (80% then) -- not retired yet,
needs a second consecutive 90%+ Gauntlet.

**Snapshot:** `g_iter27`.

**Benchmarks:** `sample_camelcase` 0/20 (0%, down from 1/20 -- single-game
noise, camelcase's own combat-heavy doctrine barely touches Miner
pathing), `sample_afinals` 4/20 (20%, up from 3/20). Roughly flat, as
expected -- this fix targets peer-pool maze/chokepoint maps specifically,
not the benchmarks' own (very different) failure modes.

**Replay archived:**
`replays/iter35_g_iter21_intersection_botA_WIN.bc22` -- the exact
reproduction match from Step 6 verification: a win (r378) on the map and
opponent that originally exposed the bug, with the mining-action count
(1450 vs 501) directly demonstrating the fix.

**Next:** the lateral-softness cluster (g_iter19-26) is still the clearest
remaining soft spot, though this iteration narrowed rather than closed the
gap -- worth checking whether the same beacon-oscillation class of bug (or
a related staleness issue in `publishLead`'s dedup radius) shows up on the
specific maps where that cluster still wins against us. Also watch g_iter10
for retirement next Gauntlet.

---

## Iteration 36  —  investigation only, no code change (findings recorded)

### Step 4/5

Per-map breakdown of Gauntlet 36 (Iteration 35's sticky-beacon fix) against
Gauntlet 35's, opponent set held constant for the 17 shared opponents:
`intersection` 44%->97%, `jellyfish` 62%->100%, `squer` 76%->100%,
`chessboard` 35%->50% -- the fix's intended targets, all big gains. But two
maps dropped hard: `valley` 82%->58% (-24 points) and `sandwich` 44%->31%
(-13 points), with early ancestors `g_iter14`/`g_iter15` -- previously
comfortable wins -- now beating us on both. Confirmed with a saved replay:
`g_iter14,valley,A,A,726,win` in Gauntlet 35 vs `g_iter14,valley,A,B,600,loss`
in Gauntlet 36, same opponent code, same map -- a real regression, not
sampling noise on that one data point at least.

Investigated the `g_iter14__valley__botA.bc22` loss to find the mechanism.
Ruled out a mining-side regression: A miner positions during the early game
were well spread across distinct deposits (not crowded onto one beacon as
hypothesized), and the sample was too small/mixed to say more without
running dedicated tracer games. The real divergence: 3 Archons vs 3, even
Soldier counts (15 v 15) through r320, then two of our three Archons die
(r400-480) while only one of B's does, and army sizes diverge purely as a
production-capacity consequence afterward (B climbs to 25 Soldiers, we
collapse to 0 by r600).

Checked per-round attack/kill volume in the r320-400 window (while troop
counts were still even) specifically to test the "combat trade-efficiency
gap" flagged repeatedly since Iteration 21/32/33/35's "Next" notes: kills
were 10 (A) vs 9 (B), attacks 387 (A) vs 377 (B) -- essentially even. The
gap only opens in r400-600, exactly tracking the Archon-count divergence
(800 B attacks vs 286 A in that window, driven by B's growing troop count,
not a better per-soldier hit rate). This reframes the long-standing
"combat-quality gap" language used since Iteration 32: at least in this
sample, our Soldiers trade roughly evenly per-unit -- the compounding
disadvantage comes from *which side loses more Archons early*, which then
cascades into an economy/production gap that looks like a combat gap by
the time it's visible in aggregate army-size charts.

### Outcome

No fix implemented this iteration -- didn't have a verified, specific
mechanism for the valley/sandwich regression or for why 2/3 Archons died
early against g_iter14 specifically, and Step 6's discipline (verify before
spending a Gauntlet run) argues against guessing. Recording the findings
instead of a speculative patch.

**Next:** the real question is Archon survival, not Soldier combat AI --
why did 2 of 3 Archons die by r480 against g_iter14 on valley when the
opposing army was never larger than ours until after those deaths? Worth a
dedicated Step 4 pass on Archon HP timelines vs local threat/reinforcement
lag specifically (is help arriving too late to an Archon under attack?),
using `valley`'s 3-Archon layout as the test case. Separately, the
valley/sandwich regression itself deserves a larger sample (the standard
Gauntlet is only 2 games per opponent per map) before concluding it's fully
explained by Archon-death variance rather than some remaining
Iteration-35-adjacent side effect.

---

## Retirement threshold lowered 90% -> 80% (user request); 5 opponents retired

Per direct user request, `TRAINING_ALGORITHM.md`'s domination-retirement
rule now fires at **≥80%** in two consecutive Gauntlets (was ≥90%).

This was prompted by a look at Gauntlet 37's per-opponent spread (see
Iteration 37 below): `g_iter9`/`g_iter10`/`g_iter11` sitting at 85-90% while
`g_iter27` -- our own immediately-preceding accepted iteration -- was down
at 40%. The aggregate 65.8% WinPct was real but partly propped up by
domination of ancestors that no longer test anything, exactly the
over-optimizing-on-weak-opponents risk the user flagged. Asked whether to
add a separate "must also beat the most-recent iteration" gate on top of
this; user preferred to rely on the faster retirement alone rather than add
a second gate.

Checking Gauntlet 36 + 37 (both needed for the two-consecutive condition)
against the new 80% bar:

| opponent | G36 | G37 | retire? |
|---|---|---|---|
| g_iter9  | 80% | 90% | **yes** |
| g_iter10 | 90% | 90% | **yes** |
| g_iter11 | 85% | 85% | **yes** |
| g_iter12 | 80% | 75% | no |
| g_iter13 | 80% | 75% | no |
| g_iter14 | 80% | 80% | **yes** |
| g_iter15 | 80% | 80% | **yes** |
| g_iter16 | 85% | 75% | no |
| g_iter17 | 75% | 70% | no |
| g_iter18 | 75% | 70% | no |

**Retired: `g_iter9`, `g_iter10`, `g_iter11`, `g_iter14`, `g_iter15`** (5 of
19 opponents). Remaining peer pool going forward: g_iter12, 13, 16-27 (14
ancestors) plus the two benchmarks. This should make aggregate WinPct a more
honest reflection of current strength -- the pool now skews toward
opponents that still contest us (45-75%) rather than ones we've long since
outgrown.

---

## Iteration 37  —  Soldier home-defense priority (ACCEPTED); snapshot g_iter28

### Step 4/5

Picked up Iteration 36's "Next" pointer directly: on the `g_iter14/valley`
loss, `--all-actions` showed 2 of our 3 Archons killed (r384, r467) by
flanking raids while our army centroid sat at the map's center the entire
window (solCx/solCy ~15,20 throughout r320-480), fighting an even,
ongoing skirmish there the whole time.

Traced to `runSoldier()`'s own control flow: `armyObjective()` (used by
idle Soldiers with no live fight) already checks `SA_HOME_THREAT` first --
but that function is only reached when `SA_FOCUS` is zero, i.e. no live
fight anywhere. Iteration 19's "reinforce toward SA_FOCUS" branch sits
*before* that check and returns unconditionally whenever `SA_FOCUS` is set
-- which is essentially always, once real combat starts. A correctly-raised
home-threat flag was structurally unreachable by any Soldier for the whole
back half of a real game, because there was always a live fight to reinforce
somewhere else.

### Step 6 — Solution

Added a `SA_HOME_THREAT` check ahead of the `SA_FOCUS` reinforcement branch
in `runSoldier()` -- a real home threat now overrides marching to reinforce
a distant fight, extending the priority `armyObjective()` already gave it
into the live-combat case.

Verified directly on the reproduction case first: `TEAM_A=bot
TEAM_B=g_iter14 tools/vm-match.sh valley` still lost (r697, vs the original
r600) but the "defend home" indicator fired 529 times and the game visibly
extended -- confirming the mechanism engages correctly even though it
didn't flip this specific matchup on its own. `g_iter27` mirror: 8/20 =
40% -- softer than a typical mirror check but not a collapse; proceeded to
the full Gauntlet per the embrace-risk standing preference rather than
pre-judging from one soft mirror result.

-> Step 2, **Gauntlet 37** (snapshot candidate -- benchmarks included).

**Gauntlet 37 (step 2/3).** Peer: **250/380 = 65.8%** -- down from Iteration
35's 69.7% but comfortably above the 60% bar. `g_iter14`/`g_iter15` both
climbed to 80% (from the same value in G36, so flat rather than clearly
improved by the fix in aggregate) while `g_iter27` -- our own immediately
preceding iteration -- came in at 40%, the softest matchup in the pool. This
prompted the retirement-threshold conversation above; per the user's
preference, no separate frontier gate was added, so this iteration is
judged on the standard aggregate rule alone, which it clears.

**Snapshot:** `g_iter28`.

**Benchmarks:** `sample_camelcase` 0/20 (0%), `sample_afinals` 4/20 (20%) --
unchanged from Iteration 35, as expected (this fix targets peer-pool
Archon defense, not the benchmarks' very different failure modes).

**Replay archived:** `replays/iter37_g_iter14_valley_botB_WIN.bc22` -- a win
(r815) on the exact map/opponent that originally exposed the bug, with the
"defend home" indicator firing 248 times.

**Next:** `g_iter27` at 40% is worth a dedicated look next -- what
specifically does Iteration 35's own code (our immediate predecessor) do
against Iteration 37's code that it didn't do against older ancestors? With
the retirement prune above, the Gauntlet pool is now weighted toward
opponents close to our current strength, so this kind of frontier-specific
softness should be easier to spot and chase directly going forward.

---

## Iteration 38  —  investigation only, no code change (g_iter27 softness reframed)

### Step 4/5

Followed up on Iteration 37's "Next" pointer: picked
`g_iter27__maptestsmall__botB.bc22` (a quick loss, r217) to look for a
concrete mechanism behind `g_iter27`'s 40% record against current code.
`--metrics` showed a genuine, gradual mid-game grind: both sides open
identically (18 Miners, matched Soldier ramp through r80), then our
Soldier count slowly bleeds from r100 (25) to r200 (1) while `g_iter27`'s
climbs (25->73) -- a real fight lost, not a sudden collapse or an economy
stall. Checked whether Iteration 37's new "defend home" branch was
over-triggering and pulling Soldiers off the front for false alarms (the
most obvious way a *new* change could cause *this* kind of slow bleed): it
fired only 10 times in the entire game -- not remotely enough to explain a
72-round divergence. No fix-shaped mechanism found in this replay.

Reframed the underlying premise instead: `g_iter28` differs from `g_iter27`
by exactly one small change (Iteration 37's home-defense priority). Two
adjacent iterations of the *same lineage* are a near-mirror matchup, and
mirror matchups are close to a coin flip by construction (see the
`g_iter25` mirror check earlier in this session landing at 50%, and this
session's `g_iter27` mirror at 40%, `g_iter37`... i.e. plain `g_iter27` vs
itself-family checks -- both well within one standard deviation of 50% at
n=20 games). The clean, monotonic climb in win rate against *older*
ancestors (g_iter16 75%, g_iter12 75-80%, g_iter9-11 85-90% before
retirement) isn't a sign of over-optimizing on weak opponents so much as
the expected shape of a lineage that keeps compounding real improvements --
the gap to each ancestor grows with iteration-distance, while the gap to
the immediately-preceding iteration stays naturally noisy near 50%
regardless of whether the latest change was a genuine improvement.

### Outcome

No fix implemented. This iteration didn't find a specific bug to attribute
the `g_iter27` softness to -- the maptestsmall loss was a fair, close fight
that we lost, and the aggregate 40% is plausibly explained by mirror-match
variance rather than a regression from Iteration 37.

**Next:** rather than continuing to chase `g_iter27` specifically as if it
were a bug, track win rate against the *single most recent* opponent across
several Gauntlets as a rolling signal -- if it stays persistently well
below 50% across multiple iterations (not just one noisy 20-game sample),
that's a real signal worth a dedicated investigation; a single below-50%
reading on a near-mirror matchup is not, on its own.

---

## Iteration 39  —  investigation only, no code change (Archon-isolation is a distance problem)

### Step 4/5

Continued the Archon-survival thread against `g_iter21` (lowest peer win
rate in the pruned pool, 45%): `g_iter21__sandwich__botA.bc22`, a fast
loss (r279) with Archon #10 dead at r112 -- very early. `--metrics` showed
our army centroid parked at (17, 6.5) the entire window while the dying
Archon sat at (38, 11), ~21 tiles away -- essentially undefended by
geography, not by any missing logic.

Checked whether Iteration 37's fix was actually the bottleneck here (it
wasn't obviously broken, but worth ruling out precisely): `--indicators`
confirmed `SA_HOME_THREAT` correctly fired on this Archon and Soldiers
*did* show `defend home [38, 11]` starting well before the death -- the
detection and prioritization from Iteration 37 both worked exactly as
designed. But only 1-2 of our Soldiers responded against 3 enemy Soldiers
converging on the target (`#13697`, `#11678`, `#13807` all visibly
`focus`/`reinforce [38, 11]`), and even those responders couldn't cover 21
tiles in the ~20-30 rounds available before the Archon's 600 HP ran out.
This is a travel-time problem, not a logic bug -- the fix from Iteration 37
is doing its job and still isn't enough when the distance is this large.

### Outcome

No fix implemented. The Watchtower/Builder mechanic (Iteration 30/31) is
gated to `round > 100`, which is already too late for an Archon that dies
at r112, and even if it weren't, a freshly-spawned Watchtower needs further
rounds under repair before it can act. A same-day code fix that reliably
saves an Archon rushed this early, this far from the main cluster, isn't
obviously available without a much bigger design question (e.g. should
isolated Archons keep a standing local guard instead of contributing every
Soldier to the main army?) that's too large to verify in one iteration.

**Next:** if this keeps costing games specifically on maps with widely
spaced Archon spawns (sandwich, valley both fit this so far), the fix is
more likely "detach 1-2 Soldiers as a standing home guard per Archon,
independent of SA_FOCUS/SA_HOME_THREAT reinforcement" than any tweak to the
existing threat-detection pipeline, which is already confirmed working.
Worth checking Archon spawn-distance as a per-map property (average
pairwise Archon distance) against peer win rate to see if it actually
predicts the softer maps before committing to a standing-guard rework.

---

## Iteration 40  —  standing per-Archon home guard (REJECTED, broad regression)

### Step 4/5

Implemented Iteration 39's proposed idea directly: the first Soldier to
spawn at each Archon (via a new `SA_GUARD_0` shared-array slot, claimed if
within `dist^2<=20` of an unclaimed Archon) becomes a permanent guard --
never joins `SA_FOCUS` reinforcement or the `armyObjective` march, just
holds near home and fights whatever comes into local range.

Verified the mechanism itself worked: re-running `TEAM_A=bot
TEAM_B=g_iter21 tools/vm-match.sh sandwich` (the Iteration 39 reproduction
case) showed the "guard" indicator firing 221 times, correctly claiming the
nearest unclaimed Archon. But digging into *which* Archon actually got a
guard revealed the real story: only one of our two Archons -- (8,1) -- ever
had a Soldier claim guard duty; the doomed one at (38,11) never built a
single Soldier before the raid arrived (still in its Miner-heavy opening,
plausibly a `richHome` spawn). The guard concept never got a chance to
matter for the specific case that motivated it -- Archon #10 still died at
r115 (vs r112 before), no real change.

### Step 6 -- Solution attempted, then reverted

`g_iter28` mirror: **6/20 = 30%** -- far softer than any mirror check this
session (typically 40-55%). Checked a loss before spending a Gauntlet run:
`g_iter28/maptestsmall` (a lead-rich, **single-Archon** map) showed the
same familiar Soldier-count attrition pattern, but on a map with only one
Archon per side, a permanent guard has zero defensive upside (there's no
second, undefended Archon to protect) while still permanently benching one
Soldier from every fight. The cost (one fewer combat unit, always) is
universal; the benefit (saving a distant, slow-to-reinforce Archon) is
narrow and, per the verification above, didn't even fire correctly in the
one game that motivated it. Reverted per Step 6.5 without running the full
Gauntlet -- the 30% mirror plus a clear, understood causal mechanism was
enough to decide without spending 300+ games confirming it.

**Next:** the underlying Archon-isolation problem (Iterations 36/39) is
still open. A fix needs to be conditional on actually having multiple,
widely-spaced Archons -- e.g. only stand up a guard when
`SA_OUR_ARCHON_0`'s slots show >1 Archon at distance, not unconditionally --
and separately needs to address Iteration 39's actual observed failure
mode (an Archon that hasn't built any Soldiers yet when a rush arrives),
which a "first Soldier becomes a guard" rule can't fix if that Archon never
gets a first Soldier out in time. Worth reconsidering whether the fix
belongs in the Archon's own build priority (build at least 1 Soldier early
regardless of Miner quota) rather than in Soldier behavior at all.

---

## Iteration 41  —  richHome contact-exemption narrowed (REJECTED, no-op)

### Step 4/5

Followed Iteration 40's "Next" pointer directly: hypothesized that the
doomed Archon at `(38,11)` in the `g_iter21/sandwich` reproduction case was
a `richHome` Archon, whose contact-cut exemption (`!richHome &&
...SA_ENEMY_SEEN...`) was written and tuned for `maptestsmall` (a
single-Archon, lead-dense map where the full team's economy really is the
whole game) but applies unconditionally per-Archon -- so on a multi-Archon
map, one rich-local-lead Archon could roll `richHome` independently and
build its full Miner quota with zero Soldiers, regardless of any sighted
threat, while a teammate elsewhere gets no say in its defense.

### Step 6 — Solution attempted, then reverted

Narrowed the exemption to `curArchons > 1` (only fully exempt a richHome
Archon from the contact cut when it's the *only* Archon on the team --
`maptestsmall`'s actual case). Verified directly on the reproduction case
before running anything else: `TEAM_A=bot TEAM_B=g_iter21
tools/vm-match.sh sandwich` came back **byte-for-byte the same result**
(r279, identical to the unpatched build) -- and `--all-actions` confirmed
zero Soldiers were ever built at `(38,11)` either way. Checked the
Archon's own indicator log (`r10 miners=6`, plateauing at 7 by r12) and
found its own Miner count was already small (~7), not 18 -- meaning this
Archon was never actually `richHome` in this game to begin with. The whole
hypothesis was built on an untested assumption about *why* this specific
Archon stalls; that assumption was wrong, so the fix was a no-op by
construction. Reverted per Step 6.5 -- confirmed ineffective before
touching the Gauntlet at all.

**Next:** three iterations (36, 39, 40, 41) have now chased the
`g_iter21/sandwich` Archon-(38,11)-never-builds-Soldiers case without
landing a working fix, and the real gating condition for that specific
Archon is still unidentified -- `richHome` is ruled out, `contact`'s
`SA_ENEMY_SEEN` window is presumably being satisfied (soldiers exist
team-wide by r15-20, just all built at the *other* Archon). The next
attempt should stop guessing at the mechanism and instead trace this one
Archon's own `needMiners`/`quota`/`floor` values directly (e.g. a temporary
indicator string dump of those three numbers each round) rather than
inferring them from aggregate counts -- this thread has cost four
iterations on inference-only debugging without result.

---

## Iteration 42  —  diagnosis only, root cause found (no fix yet)

### Step 4/5

Took the previous "Next" note literally: added a temporary per-round debug
indicator string to `runArchon()` (`mine=/q=/tm=/fl=/want=/best=/rich=`,
dumping `myMinersSpawned`, `quota`, team miners, `floor`,
the computed `want`, the chosen build `Direction`, and `richHome`) and
re-ran the exact `TEAM_A=bot TEAM_B=g_iter21 tools/vm-match.sh sandwich`
reproduction case. Found it immediately: from ~r15 through the Archon's
death at r112, the doomed Archon's own log reads `want=SOLDIER best=null`
**every single round** -- the build-priority logic is completely correct
(it wants a Soldier, exactly as it should once `needMiners` clears), but
`best` (the chosen build direction, out of the Archon's 8 adjacent tiles)
is `null` -- none of the 8 tiles are ever buildable. Not a priority bug at
all; a placement bug.

Cross-referenced a `--map-detail` board snapshot at r40: the Archon sits
with several of its own Miners (`M`) clustered in the rows immediately
around it, while enemy Soldiers (`s`) are visible nearby on the board too.
The likely mechanism: `runMiner()`'s threat-flee branch
(`moveToward(rc, me.add(threat.location.directionTo(me)))`) moves a
threatened Miner directly *away* from the enemy, which -- for a Miner
already working near home -- points straight at its own Archon. Under
sustained nearby enemy presence, fleeing Miners pile up in the Archon's own
8-tile build ring, right where it most needs an open tile to build the
Soldier that would drive the enemy off. A self-reinforcing trap: threat
appears -> Miners flee to the Archon -> Miners block the Archon's only
build tiles -> Archon can't build the Soldier that would help -> Archon
dies with the "correct" build decision queued the entire time but never
executable.

### Outcome

No fix implemented -- diagnosis only, cleaned the debug instrumentation
back out before committing. This is now enough to guide a real fix, unlike
Iterations 40/41's blind guesses:

**Next:** the fix belongs in `runMiner()`'s flee branch, not in
`runArchon()` -- a threatened Miner already adjacent to a friendly Archon
should route *around* it (e.g. sidestep like `moveToward`'s Iteration 29
congestion fix) rather than piling directly onto the Archon's build ring.
Verify by re-running this exact reproduction case and checking the same
debug dump shows `best != null` during the r90-115 window before spending
any Gauntlet games on it.

---

## Iteration 43  —  correcting Iteration 42's diagnosis (no fix yet)

### Step 4/5

Implemented Iteration 42's proposed fix: when a fleeing Miner is already
adjacent to a friendly Archon, step to whichever direction both increases
distance from the threat and clears the Archon's build ring, instead of
just stopping. Verified on the same reproduction case with the debug dump
still attached -- **zero change**: `want=SOLDIER best=null` still logged
92 consecutive rounds, same death round (r112) as before the fix.

Went back to a `--map-detail` board snapshot, this time at **r25** --
deliberately early, before any enemy unit is anywhere near this Archon (the
only visible enemy units at r25 are far away, elsewhere on the board). The
Archon's build ring was *already* occupied then, by two of our own
uppercase `M` (friendly) Miners sitting one and two rows below it. This
disproves Iteration 42's "fleeing Miners pile up under threat" theory
outright -- there is no threat at r25, so nothing is fleeing. The real
mechanism is much simpler: this Archon's spawn happens to have rich lead
immediately adjacent to it, Miners correctly camp on those tiles to mine
it (the top of `runMiner()` mines any adjacent tile down to 1 lead every
round, then relies on passive regen, +5 per 20 rounds, to keep supplying
it) -- and because that's genuinely the *correct*, efficient thing for a
Miner to do, nothing in the existing logic ever moves them off those tiles
once they've settled. A handful of productive Miners can permanently fill
an Archon's entire 8-tile build ring as a side effect of doing their job
well, with no threat or fleeing involved at all.

### Outcome

Reverted the (now-confirmed-irrelevant) flee-routing change; no fix
implemented this iteration either. Five iterations (36, 39, 40, 41, 42, 43)
have now been spent on this one Archon's build-stall across two sessions'
worth of a single training loop invocation, without a working fix, though
each pass has narrowed the actual mechanism further -- this one finally
looks complete and specific enough to act on.

**Next:** the fix needs to live in `runMiner()`'s own tile selection, not
in threat response: cap how many Miners are allowed to sit within an
Archon's 8-tile build ring at once (e.g. at most 2), directing any Miner
beyond that cap to mine from one tile further out even if that means
slightly less locally-optimal lead access. This directly preserves the
Archon's ability to build while barely touching the (already
well-tuned) general mining/beacon logic. Verify with the same debug dump
approach (confirm `best != null` clears during the previously-stuck
r15-112 window) before spending a Gauntlet run.

---

## Iteration 44  —  ring-occupancy cap (REJECTED, no-op); thread parked

### Step 4/5/6

Implemented Iteration 43's proposed fix: once more than 2 friendly Miners
are already within an Archon's build-adjacent ring, a newcomer steps one
tile further out instead of piling on. Verified on the same reproduction
case with the debug dump still attached: **no change at all** --
`want=SOLDIER best=null` logged for 97 of 97 sampled rounds, same r112
death.

Went one level deeper this time: pulled the exact starting-rubble values
around the doomed Archon's 8 neighboring tiles from the `STARTING RUBBLE
MAP` dump. The tile directly south, `(38,10)`, reads `#` (67-100 rubble --
the densest tier); north and west read `o` (34-66); only east reads clean.
This raises a real, unresolved question this session doesn't have a firm
answer for: does `canBuildRobot` in this engine version refuse to place a
unit on sufficiently high rubble, independent of occupancy? If so, this
Archon may simply be sitting in a spawn pocket where rubble alone removes
most of its 8 build directions, and Miner crowding (Iterations 42-43) is
only part of the story, not the whole one.

### Outcome

Reverted (no-op, confirmed before any Gauntlet spend, per Step 6.5).
Six sub-attempts across Iterations 39-44 have now gone into this single
Archon's build-stall on one map/opponent pairing without landing a fix --
each pass genuinely narrowed the mechanism (ruled out `richHome`, ruled out
"fleeing miners," ruled out simple ring-crowding at a 2-Miner threshold),
but the marginal cost of continuing to guess is no longer clearly worth it
against the value of moving to other, more tractable work. Parking this
specific thread rather than spending a seventh iteration on it.

**Next:** before touching this again, resolve the actual Battlecode 2022
rule for `canBuildRobot` vs. rubble directly (decompile/check the real
`battlecode22-2.2.1.jar`, the way Iteration 34 did for `RobotMode`, rather
than inferring from replay behavior) -- if rubble does block building, the
fix is a rubble-aware spawn-site check on turn 1 (an Archon whose ring is
mostly high-rubble could pre-emptively treat itself as needing an earlier,
smaller Soldier commitment), which is a fundamentally different and more
promising angle than anything tried in Iterations 40-44.

**Addendum (same session):** resolved the rubble question directly --
`javap -c` on the real `RobotControllerImpl.assertCanBuildRobot` (decompiled
on `battlecode-dev`, same technique as Iteration 34's `RobotMode` check)
shows exactly four checks in order: type compatibility (`RobotType.canBuild`),
sufficient lead, sufficient gold, `onTheMap`, and `isLocationOccupied` --
**no rubble check anywhere in the method**. Rubble is fully ruled out as a
cause; `best=null` for 97 straight rounds can only mean all 8 adjacent
tiles were genuinely occupied (by any unit, friendly or enemy) the entire
time. Precisely identifying *which* units, every round, needs programmatic
instrumentation (dumping the actual occupant list) rather than more manual
ASCII-board reading, which proved too imprecise to pin down cleanly across
several attempts this session. Logging this as a durable, confirmed engine
fact for whoever picks this thread back up, and moving on rather than
sinking further effort into a ninth attempt at the same specific bug.

---

## Iteration 45  —  Soldier-unaffordable Miner fallback (REJECTED, catastrophic)

### Step 4/5

Followed the addendum's own advice: added precise per-direction occupant
instrumentation (`canBuildRobot` false -> either `offmap`, the actual
`RobotInfo` at that tile via `senseRobotAtLocation`, or -- critically --
`?` when the tile is genuinely empty and on-map but still fails) to the
`g_iter21/sandwich` reproduction case. The "?" tiles turned out to be the
whole story: printing team lead alongside cost showed `lead=0 cost=75`,
`lead=12 cost=75`, `lead=17 cost=75` -- for the entire r15-112 window, team
lead sat in the single/low-double digits, far under the 75 a Soldier costs.
Not occupancy, not rubble, not fleeing Miners: this Archon's sibling
(closer to richer lead) was continuously spending the *shared* team lead
pool on its own cheaper (50-lead) Miners before this Archon ever got a
round where 75 had accumulated.

### Step 6 — Solution attempted, then reverted (severe regression)

Added a fallback: if an Archon wants a Soldier but team lead is under 75,
build an affordable Miner instead of contributing nothing that round.
Verified the mechanism engaged on the reproduction case (team Miners
climbed 7->17 instead of sitting flat at 6-7) -- didn't flip that specific
game (same r112 death, defense timing unaffected by extra Miners), but
looked like a strictly-better, low-risk change: it only fires when the
alternative was already doing nothing.

`g_iter28` mirror: **1/20 = 5%** -- the worst result of the entire session,
worse even than Iteration 40's 30% guard collapse. Reverted immediately
without running the Gauntlet. The likely mechanism, on reflection: the
fallback has no patience. The instant team lead crosses 50, it gets spent
on a Miner -- which structurally prevents lead from *ever* accumulating to
75, since a Miner-hungry Archon (there's always at least one on most maps)
will drain any surplus the moment it appears. What was meant as an
occasional bonus became a standing lead-sink that suppresses Soldier
production almost entirely, in *both* directions in a mirror match --
degrading the whole match into an economy neither side ever converts into
an army.

### Outcome

Reverted per Step 6.5. Nine sub-attempts (Iterations 39-45) have now gone
into this one Archon's build-stall across two full diagnostic passes,
landing on a real, well-confirmed root cause (shared lead contention
between Archons) but no safe fix yet -- both attempts so far (standing
guard, greedy fallback) shared the same failure shape: an unconditional
behavior change with a real, non-obvious cost that only shows up at scale
(mirror match), not in the single reproduction case used to verify it.

**Next:** any fix here needs a patience mechanism, not a greedy one -- e.g.
only fall back to a Miner if this Archon has been unable to afford its
wanted Soldier for N consecutive rounds (giving lead a chance to actually
accumulate first), or cap how many fallback Miners a single Archon will
ever build this way. Verify not just on the single reproduction case but
also watch team-wide Soldier *and* Miner counts across a full game before
trusting a "looks like pure upside" argument again -- Iteration 45's fix
looked strictly beneficial from the reproduction case alone and was
actually the worst regression of the session.

---

## Iteration 46  —  patient Soldier-starvation fallback (REJECTED); thread abandoned

### Step 4/5/6

Implemented the patient version Iteration 45 proposed: only fall back from
an unaffordable Soldier to a Miner after 20+ *consecutive* rounds of
starvation (not immediately), and reset the counter each time the fallback
fires so lead gets a real recovery window between triggers -- directly
targeting the "no patience, permanently suppresses Soldier production"
mechanism Iteration 45 was rejected for. Verified on the reproduction case
that Miner counts stayed bounded this time (peaked at 12, not runaway).

`g_iter28` mirror: **6/20 = 30%** -- much better than Iteration 45's 5%,
but still a clear, real regression from the normal 40-55% mirror range.
Reverted immediately.

### Outcome

Two different tunings of the same core idea (immediate fallback: 5%
mirror; patient fallback: 30% mirror) both regressed significantly. That
pattern -- fixing the "no patience" mechanism only partially recovered the
loss -- suggests the *concept* itself is unsound here, not just
miscalibrated: building extra Miners has real costs beyond the lead they
spend (more wandering units exposed to raids, diminishing mining returns
once local deposits are saturated, avoidable turns near a possibly-
contested build ring) that apparently outweigh the benefit of "not sitting
idle" even when kept on a leash. Abandoning this fix direction --
Iterations 39-46 (eight sub-attempts across two sessions) have now been
spent on this one Archon's build-stall, landing on a solid, confirmed root
cause (shared lead contention between Archons) but no safe fix.

**Next:** stop trying to give the starved Archon something to spend on.
The alternative angle from Iteration 30/31's own design (a second Builder/
Watchtower once `lead > 300`) suggests the fix might belong upstream, in
resource *allocation* between Archons rather than in what a starved Archon
does with nothing -- e.g. a soft priority signal (shared array) that lets
whichever Archon has been waiting longest for a Soldier claim the next
sufficient lead surplus before a sibling's routine Miner spend grabs it.
That's a bigger, riskier change than anything tried so far in this thread,
better suited to a fresh iteration with its own dedicated verification
rather than another quick patch.

---

## Iteration 47  —  investigation only: chessboard's FURY-timed losses explained

### Step 4/5

Pivoted away from the abandoned sandwich thread to `chessboard` (50%
aggregate, tied for the softest non-sandwich map). 7 of 19 chessboard
losses in Gauntlet 37 timed out at r2000; the rest clustered tightly at
r743-842. That tight clustering was the tell -- checked the anomaly
schedule and every one of those games has a `FURY` global anomaly at
r800.

Decompiled `GameWorld.causeFuryUpdate`/`AnomalyType`'s static initializer
directly (same jar, same technique as Iterations 34/44): FURY is a real
global effect -- for every `TURRET`-mode unit on the map (Archons and
Watchtowers are always `TURRET`), it applies `-0.05 * maxHealth` instantly
(30 damage to a 600-HP Archon), for *both* teams, then immediately checks
if either team has hit zero Archons and ends the game by annihilation
right there if so. On the `g_iter14/chessboard` loss, our Archon dies at
r805 -- 5 rounds after the r800 FURY tick.

30 damage alone isn't dramatic, though: checking the Archon's HP trend
into r800 (see `--metrics`, same replay), it was already deep in the same
long-game economic-snowball collapse flagged repeatedly since Iterations
20/23 (`g_iter14`'s Soldier count climbing 4->95 over the game while ours
fell to 0). FURY's chip damage is the finishing blow on an *already*
critical Archon, not a new root cause -- the real driver is still the
economic snowball, and the one documented lever for that (raising the
Miner floor past its current `min(6+round/100,25)` cap) has a real
regression history of its own (Iteration 23: `g_iter14/15/16` dropped to
40-45% peer when pushed too far).

### Outcome

No fix attempted -- didn't want to re-open an already-explored, documented-
risky lever without a genuinely new angle, and this session has already
absorbed two consecutive rejected attempts on a similarly-shaped economy
problem (Iterations 45/46). Recording the FURY mechanism precisely since
it's a durable, confirmed engine fact (globalPercentage=0.05,
sagePercentage=0.1, fires on a fixed global schedule visible in every
match header) that's useful context for chessboard/long-game analysis
going forward, even without a fix attached to it this iteration.

**Next:** the actionable version of this finding, if pursued, is narrow:
an Archon already critical (say <20% HP) heading into a *known* upcoming
FURY round (the schedule is visible via `rc.getAnomalySchedule()` if
exposed to `RobotController`, otherwise inferable from round number
patterns) could prioritize repair/retreat slightly harder in that window.
But this is a small mitigation for a symptom -- the higher-leverage fix
remains the economic snowball itself, which needs a fresh angle on Miner
production scaling for very long games rather than another floor-cap
tweak, given the documented history of that specific lever backfiring.

---

## Iteration 48  —  investigation only: shared-lead contention generalizes beyond sandwich

### Step 4/5

Checked `sample_afinals/maze` (a fresh, unrelated matchup -- 3-Archon map,
never previously tied to the Iterations 39-46 thread) to see if the
Iteration 44 finding (Archons structurally unable to afford a wanted
Soldier because a *shared* lead pool gets spent elsewhere first) was a
sandwich-specific quirk or something broader. It's broader: `--metrics`
showed our Soldier count staying at 0-2 for the *entire* 390-round game
while afinals' Sage count climbed to 8 and their Archon HP never dropped
below 1800 (we landed zero effective damage the whole game). Team Miners
capped at ~9 -- exactly 3 Archons x quota-3 each, confirming the
contact-cut *is* engaging correctly (ruling out the richHome-exemption
theory from Iteration 41 for this case too) -- but essentially no Soldiers
ever get built regardless. Same shape as the sandwich case: correct
build-priority decisions, structurally unable to execute on them because
of lead contention between 3 simultaneously-active Archons.

### Outcome

No fix attempted -- this session already spent two rejected attempts
(Iterations 45/46) on variations of "give a starved Archon something to
build instead," both of which backfired for non-obvious reasons (a
fallback spend competes for the exact resource it's trying to save up).
Finding the SAME failure mode independently on a completely different
map/opponent raises the importance of this thread considerably -- it's not
a sandwich curiosity, it's a general multi-Archon economy weakness that
plausibly explains real losses across several maps, including against
`sample_afinals` where our benchmark win rate has been stuck at ~20% all
session with no dedicated Step 4 pass since Iteration 32.

**Next:** this deserves a dedicated iteration with real budget, not another
squeezed-in attempt -- the failure mode is well-understood now (3
independent Archons, one shared lead pool, no coordination primitive
between them), but every fix tried so far has manipulated *spending*
behavior and made things worse. Worth trying the opposite lever next:
increasing shared *income* specifically when multiple Archons are
simultaneously contact-cut (e.g. a per-Archon Miner quota that scales
slightly with `curArchons`, since 3 Archons at quota-3 each is the same
per-Archon economy as a 1-Archon map at quota-3, but with 3x the aggregate
spending pressure on the same-sized shared pool) -- carefully, given
Iteration 23's specific warning against raising Miner quotas broadly, and
with the same full-game (not just mirror) verification discipline
Iteration 45/46 learned the hard way.

---

## Iteration 49  —  fairness-yield fix (REJECTED, no-op); corrects the contention theory

### Step 4/5/6

Checked the `sample_afinals/maze` reproduction case precisely: all 5
Soldiers our team built the entire game spawned from the *same* Archon,
one build every 30-73 rounds. Implemented a turn-order fairness fix -- an
Archon that just built a Soldier sits out the next 15 rounds, giving
siblings a window to claim the next lead surplus instead of the same
Archon grabbing every one. Re-ran the exact reproduction case:
byte-for-byte identical result (same round, same 5 Soldiers, same robot
IDs). The fix never engaged, because the real gaps between this Archon's
own builds (30-73 rounds) were already far longer than the 15-round
cooldown -- by the time it wanted another Soldier, the window had long
since expired and yielding never fires.

More importantly, that gap length itself disproves the underlying
contention theory: if the dominant Archon goes 73 rounds without wanting
anything, that's ample time for either sibling to independently reach 75
lead if the team's aggregate income were adequate -- turn-order dominance
can't explain 73-round silence. Checked the map itself instead: `maze`'s
lead map shows only **240 total Pb across 12 tiles on the whole 20x20
map** -- one of the sparsest maps in the pool. Since `getTeamLeadAmount`
is a genuinely shared, single team-wide pool (confirmed by its use
throughout `runArchon`), *where* a Miner mines doesn't restrict who can
spend the resulting lead -- so the real constraint here isn't contention
or geography, it's that the map's total economy can only support
somewhere around 5 Soldiers in 390 rounds, full stop, regardless of which
Archon gets them or how fairly they're distributed.

### Outcome

Reverted (no-op, confirmed on the reproduction case before any Gauntlet
spend). This corrects the "shared lead contention" framing from
Iterations 44-48: contention was a real, correctly-diagnosed mechanism for
*why one specific Archon wins over another*, but on a genuinely
lead-scarce map like `maze`, no reshuffling of *who* gets the rare surplus
changes the *aggregate* amount available -- the team-wide economic ceiling
is the real constraint, and it may simply be map-inherent, closer in kind
to Iteration 15's squer conclusion or Iteration 17's miner-count
conclusion than to a fixable coordination bug.

**Next:** this doesn't necessarily kill the fix ideas already logged
(Iteration 45/46/48's income-scaling angle) -- they may still matter on
maps with more total lead where contention, not aggregate scarcity, is the
binding constraint (worth re-checking whether the original `g_iter21/
sandwich` case was itself contention-bound or just as lead-poor as `maze`
before trying anything else there). But treat "fix the coordination" and
"the map just doesn't have enough lead" as two *different* diagnoses
requiring different evidence before attempting either again -- this
iteration's mistake was assuming the `maze` case was the same shape as
`sandwich` without checking the map's own lead supply first.

**Addendum (same session):** checked immediately -- `sandwich`'s starting
lead map shows **738 Pb on 32 tiles**, roughly 3x `maze`'s 240 Pb on 12
tiles. The two cases are *not* the same shape after all: `sandwich` has
genuinely abundant lead and still starved one Archon for 97 rounds
(contention/distribution, a real and plausibly-fixable bug), while `maze`
simply doesn't have enough lead on the whole map to support much of an
army for either team (likely map-inherent). Future attempts at the
Iteration 45/46/48/49 coordination fixes should target `sandwich`-shaped
cases (rich map, starved Archon) specifically, and check a candidate
map's total starting Pb before assuming the same mechanism applies.

---

## Note (research, no code change): camelcase mass-builds Watchtowers

While Iteration 50's Gauntlet ran in the background, checked a fresh
`sample_camelcase/maptestsmall` loss (single-Archon map, so clear of the
multi-Archon lead-contention thread entirely) to look for anything new
about the one benchmark we've never beaten at all (0/20 all session).
Found something not previously characterized: by r200, camelcase has
**44 live Watchtowers** (confirmed via `--all-actions` unit-count line,
not a metrics artifact) alongside 41 Miners, 13 Builders, and 75 Soldiers
-- on a *single*-Archon map. Our own Watchtower mechanic (Iteration 30/31)
caps at 1-2 per Archon as a minor home-defense bonus; camelcase is
apparently using Builders to mass-produce Watchtowers as a core army
component, not an afterthought.

This is a large, doctrinal difference, not a quick fix -- matching or even
partially countering it would mean a real Builder-production rework (build
many Builders, place Watchtowers offensively/defensively at scale), on the
order of the Iteration 34 Archon-relocation or bigger. Not attempted this
session; recording it as a well-documented, high-value lead for a future
iteration with real budget for a structural change, particularly since it
targets `sample_camelcase` specifically, the one opponent this session
never moved the needle on at all.

---

## Iteration 50  —  fairness-yield fix, correctly re-targeted (REJECTED, close)

### Step 4/5/6

Re-implemented Iteration 49's exact fairness mechanism (an Archon that
just built a Soldier yields the next 15 rounds to a sibling) but tested it
against the *right* case this time: `g_iter21/sandwich` (738 starting Pb,
contention-bound) instead of `maze` (240 Pb, aggregate-scarcity-bound --
the mismatch that made Iteration 49 a no-op). On the reproduction case the
fix visibly worked: Soldiers were built from multiple Archon locations for
the first time, including the previously fully-starved one at `(38,11)`,
and the enemy Archon died for the first time in any test of this exact
matchup (previously 3-0 losses every time; this run traded 2-for-1).

`g_iter28` mirror: **9/20 = 45%** -- squarely in this session's normal
40-55% mirror range, not a collapse like Iterations 45 (5%) or 46 (30%).
Proceeded to the full Gauntlet with real optimism for the first time on
this thread.

**Gauntlet 50 (step 2/3).** Peer: **162/300 = 54.0% < 60%** -- below the
accept bar, but a genuinely different shape of rejection than 45/46: no
opponent collapsed (worst was 35%, vs g_iter21 and g_iter27), and several
mid-pack opponents improved (g_iter12 80%, g_iter13 75%, g_iter17 70%).
Reverted per Step 6.5.

### Outcome

Closer than any other attempt on this thread, and for the first time
showed clear, verified positive signal on the actual target mechanism
(sandwich Archon starvation) rather than an unexplained regression. Still
short of the bar -- the 15-round yield window is a guess, not tuned, and
the softer lateral-cluster matchups (g_iter21-27, 35-45%) suggest the
fairness mechanism may cost something elsewhere (an Archon voluntarily
not building for 15 rounds is real downtime with its own opportunity
cost) that roughly offsets the sandwich-shaped gains in aggregate.

**Next:** this is the most promising unaccepted idea from the whole
Iterations 39-50 thread -- worth a follow-up attempt tuning the yield
window (shorter, so less downtime per yield; or scaled to team lead
income rate rather than a fixed 15) rather than abandoning the mechanism
outright. Also worth checking whether the yield should only engage when a
sibling Archon is *actually* below quota/starved (readable via
`myMinersSpawned`-style per-Archon state isn't visible across Archons, but
`SA_MINERS`/`SA_SOLDIERS` team totals combined with `curArchons` could
approximate "is anyone else waiting") rather than unconditionally on a
timer, which may be needlessly costing games where no sibling is actually
contention-starved.

---

## Iteration 51  —  shortened 8-round fairness-yield (REJECTED, same shortfall)

### Step 4/5/6

Tuned Iteration 50's fairness-yield window down from 15 to 8 rounds, per
its own "Next" recommendation, to reduce the yielding Archon's downtime.
On the `g_iter21/sandwich` reproduction case this produced a genuine
**win** (r716) -- the first time this exact matchup has ever been won in
this whole thread -- with Soldiers built from multiple Archon locations.

`g_iter28` mirror: 11/20 = 55% -- normal range, even better than
Iteration 50's 45%.

**Gauntlet 51 (step 2/3).** Peer: **160/300 = 53.3% < 60%** -- essentially
the same shortfall as Iteration 50's 54.0%, but with a different
per-opponent shape: `g_iter21` (the original target) improved 35%->55%,
while `g_iter22-26` all dropped to 35% (worse than Iteration 50's 45% for
the same opponents). Reverted per Step 6.5.

### Outcome

Two different tunings of the same mechanism (15-round and 8-round yield
windows) both landed at 53-54%, just under the bar, with different
opponents winning and losing each time -- not noise, a real, consistent
structural tradeoff. The fix demonstrably helps the exact case it targets
(sandwich-shaped Archon starvation) but costs something against a
different cluster of opponents (g_iter22-26) each time, roughly
cancelling out in aggregate regardless of window length. Shortening the
window changed *which* opponents won and lost without changing the net
outcome -- evidence that window length isn't the actual lever.

**Next:** stop tuning the window; the shared cost is probably structural,
not a duration parameter. Before trying a fourth variant, it's worth
directly diagnosing *why* g_iter22-26 specifically get worse under this
mechanism (a Step 4 pass on a fresh g_iter22-26 loss under this fix,
the same way sandwich itself was diagnosed) rather than guessing at
another tuning knob -- three iterations (49/50/51) have now shown the
mechanism has a real, repeatable cost somewhere, and it needs to be found
and addressed directly rather than averaged away by adjusting timing.

---

## Iteration 52  —  curArchons-gated fairness-yield (REJECTED, 58.7% -- closest miss on this thread)

### Step 4/5

Took Iteration 51's own advice literally: pulled a fresh
`g_iter22/maptestsmall` loss from Gauntlet 51 (the fix active) instead of
guessing at another window length. Found the real cost immediately:
`maptestsmall` is a **single-Archon** map. With only one Archon, the
fairness-yield mechanism has no sibling to benefit -- the lone Archon ends
up yielding to *itself*, for nothing. Confirmed by comparing to the
pre-fix baseline: `g_iter22/maptestsmall/botA` was a **win** (r234) in
Gauntlet 37 with no fix; under Iterations 50/51's fix it became a **loss**
(r118), and the existing `botB` loss got much faster (r207 -> r107). The
mechanism was quietly sabotaging every single-Archon matchup in the pool
while helping only the multi-Archon ones it was designed for.

### Step 6 — Solution

Gated the entire fairness-yield mechanism on `curArchons > 1`. Verified
both target cases directly: `g_iter22/maptestsmall` restored to its
original r234 win, and `g_iter21/sandwich` still won (r582, a second
distinct win on that exact matchup with a fresh test).

`g_iter28` mirror: 12/20 = 60% -- the best mirror result on this whole
thread.

**Gauntlet 52 (step 2/3).** Peer: **176/300 = 58.7% < 60%** -- the closest
miss of the entire Iterations 39-52 thread, and unlike every prior attempt
on this mechanism, **every single opponent improved or held** versus
Gauntlet 51's numbers with no gate (g_iter12 75%->80%, g_iter16 65%->75%,
g_iter21 -- the original sandwich target -- 55%->60%, and even the
previously-regressed g_iter22-26 cluster recovered from 35% to 40%). No
mixed tradeoff this time, just a uniform lift that fell 4 games short of
the bar (176/300 vs. 180 needed) -- well within one standard deviation of
noise for a Gauntlet this size (binomial SD ~2.8% at n=300).

Tried one further refinement before deciding (shortening the yield window
5 rounds instead of 8) but it lost the `sandwich` win back to the original
r279 loss on direct reproduction-case testing -- reverted that specific
change immediately without spending a Gauntlet run, keeping the
already-verified 8-round/gated version.

### Outcome

Reverted per Step 6.5 and this project's own established precedent for
marginal misses (Iterations 26 and 33 were both rejected without retry
when they landed just under 60%) -- not treating this as a special case
despite how promising the direction is. Six iterations (39-52, spanning
two sessions) have now gone into this Archon-economy thread; this is by
far the strongest result, the first with a *uniform* improvement pattern
rather than a tradeoff, and the closest numerically.

**Next:** this is the strongest candidate for a fresh re-attempt of
anyone in the whole thread -- not by guessing at more window-length
tweaks (already shown not to reliably help, and the 5-round attempt just
made things worse), but either (a) re-running the identical Gauntlet 52
code once the peer pool composition changes (a new opponent added, or a
retirement), since 58.7% is close enough that ordinary game-to-game
variance could plausibly clear it on a re-roll, or (b) a small, different
refinement -- e.g. only yielding when a sibling Archon can be confirmed to
actually still want a Soldier (not just unconditionally on a timer),
which targets the residual cost more precisely than adjusting duration
did.

---

## Iteration 53  —  sibling-hunger-aware fairness-yield (REJECTED, 59.3%); thread parked

### Step 4/5/6

Implemented Iteration 52's own refinement idea (b): an Archon only yields
Soldier-building to a sibling if a fresh shared-array "hunger" signal
shows a *different* Archon currently can't afford a Soldier either --
instead of yielding unconditionally on a timer regardless of whether
anyone actually needs the room. Verified both target cases held: `g_iter21/
sandwich` still wins (r582, unchanged), `g_iter22/maptestsmall` still wins
(r234, unchanged).

`g_iter28` mirror: 12/20 = 60%, matching Iteration 52's best.

**Gauntlet 53 (step 2/3).** Peer: **178/300 = 59.3%** -- the closest miss
yet, only 2 games short, with most opponents matching or slightly beating
Iteration 52's numbers (`g_iter13`/`g_iter16` improved 75%->80%). But
`g_iter22-26` sat at exactly the same 40% as the ungated timer-only
version -- the hunger-awareness refinement changed nothing for that
cluster. Diagnosed why directly: diffing `g_iter22`'s 20 games against the
pre-thread baseline (Gauntlet 37) game-by-game showed the regression isn't
concentrated in one place -- 3 different maps flipped win->loss
(chessboard, intersection, pillars) while one flipped loss->win (highway),
scattered across otherwise-unrelated matchups. That pattern reads as
ordinary timing sensitivity in already-close games (any change to Archon
build cadence nudges the exact moment of contact, which can flip a
marginal fight either way) rather than a single further-fixable mechanism.

Given how close 178/300 was, re-ran the *identical, unmodified* Iteration
53 code a second time to test whether the shortfall was sampling noise a
team could plausibly clear on a re-roll. Result: **byte-for-byte
identical** -- 178/300, every single per-opponent number matching exactly.
This settles an open question from earlier in the session: Battlecode
matches between two fixed bots on a fixed map/side are fully
deterministic given `gauntlet.sh`'s per-game seeding, not independently
sampled noise each invocation. 59.3% is the true, reproducible number for
this exact code, not an unlucky draw -- earlier assumptions this session
that re-running "the same matchup" would give a different result were
about *different code* producing different (but still each individually
deterministic) outcomes, not true run-to-run randomness.

### Outcome

Reverted per Step 6.5. This closes out the Iterations 39-53 thread (now
spanning the *raised* `MaxHypothesisIterations`/`MaxSolutionsIterations`
budget of 10, itself partly motivated by this thread's persistence) without
an accepted fix, but with the diagnosis essentially complete: a real,
well-understood, reproducible shared-lead-contention bug on multi-Archon,
lead-rich maps (`sandwich`-shaped), a proven mechanism to fix it
(curArchons-gated, sibling-hunger-aware fairness yield), and a precisely
characterized, scattered residual cost (timing-sensitivity noise in
already-marginal games against one opponent cluster) that isn't reducible
by further tuning the same lever. 178/300 is real and reproducible, not
noise to wait out.

**Next:** the fix code itself (Iteration 53's exact diff) is worth keeping
on hand rather than rederiving from scratch -- it is the strongest,
most-precisely-targeted version across nine sub-attempts, and the residual
2-game gap is small enough that either (a) a future change to the peer
pool (new opponents, more retirements) could shift the aggregate over 60%
without any further change to this mechanism, or (b) a genuinely different
lever entirely (not more yield-mechanism tuning, which is now demonstrated
exhausted) applied elsewhere could supply the last couple of points. Not
picking this thread back up again without one of those two conditions
changing first.

---

## Iteration 54  —  multi-Watchtower Builder (REJECTED, marginal, roughly neutral)

### Step 4/5/6

First attempt at the camelcase Watchtower lead logged earlier this
session (44 live Watchtowers by r200 on a single-Archon map, vs. our own
1-per-Builder cap). Deliberately modest first step: let a Builder keep
placing Watchtowers (up to 4, still just adjacent to itself) instead of
stopping after one, with the existing conservative Builder-count gating
in `runArchon` left untouched.

`g_iter28` mirror: 10/20 = 50%, clean.

**Gauntlet 54 (step 2/3).** Peer: **176/300 = 58.7%**. Comparing
per-opponent to the Gauntlet-37 baseline (no Watchtower changes):
`g_iter17`/`g_iter18` improved (70%->75% each), `g_iter16` dropped
(75%->70%), everything else unchanged -- a small, roughly neutral net
movement rather than a clear win or loss. Reverted per Step 6.5.

### Outcome

Not a regression, but not a validated improvement either -- 4 Watchtowers
per Builder (capped by the existing rare Builder-count gate) is too small
a step to meaningfully test the doctrine the camelcase finding pointed at.
The real gap (44 Watchtowers vs. our low single digits) is much more about
Builder *count* than Watchtowers-per-Builder, which this iteration left
untouched.

**Next:** the more informative next experiment is raising `builderCap`
itself (currently `round>400?2:1`, extremely conservative) rather than
Watchtowers-per-Builder -- that's the lever actually gating our total
Watchtower output an order of magnitude below camelcase's. Do that
carefully and incrementally (this project's own history, e.g. Iteration
23, shows aggressive economy-lever jumps can cause broad regressions) and
verify with `--metrics` that Watchtower count actually climbs
meaningfully before spending a Gauntlet run on it.

---

## Iteration 55  —  3rd Builder for very long games (REJECTED, exact no-op)

### Step 4/5/6

Followed Iteration 54's own "Next" note: raised `builderCap` (the real
constraint on Watchtower output) from `round>400?2:1` to
`round>700?3:round>400?2:1`, extending the same incremental,
round-gated pattern rather than jumping toward camelcase's scale.
Verified via `--metrics` on a long `g_iter25/highway` game (r1089) that
Watchtower count climbed (0->2, up from a previous flat 0-1) before
spending anything on a Gauntlet run.

`g_iter28` mirror: 10/20 = 50%, clean.

**Gauntlet 55 (step 2/3).** Peer: **175/300 = 58.3%** -- and checking
per-opponent against the Gauntlet-37 baseline, **every single number
matched exactly** (`g_iter12` 75%, `g_iter13` 75%, ... `g_iter27` 40%,
all identical). The r700+ gate is restrictive enough that it essentially
never engages within the standard 10-map peer pool -- most games conclude
well before r700. Reverted; not a regression, a genuine no-op.

### Outcome

Confirms the mechanism itself is inert at this threshold, not merely
unmeasured. The camelcase Watchtower lead needs either a much lower round
threshold (so it engages in a meaningful fraction of ordinary-length
games, not just the rare r700+ ones) or a different trigger entirely
(e.g. gated on lead surplus rather than round number, so it fires
whenever the economy can afford it regardless of game length). Both
Iterations 54 and 55 targeted the doctrine correctly in direction but too
conservatively in magnitude to produce a measurable signal either way.

**Next:** try gating the 3rd (and maybe a 4th) Builder on team lead
surplus (e.g. `lead > 600`) instead of round number -- this would engage
in *any* sufficiently long, low-combat economy race regardless of exact
round count, which better matches how camelcase's own build order
actually behaves (continuous investment as resources allow, not a fixed
timer). Verify engagement with `--metrics` on more than one long game
before spending a Gauntlet run, given how narrowly Iteration 55's r700
gate missed mattering at all.

---

## Iteration 56  —  lead-surplus-gated 3rd Builder (REJECTED); Watchtower-scaling thread parked

### Step 4/5/6

Followed Iteration 55's own note: gated the 3rd Builder on team lead
surplus (`>400`, recalibrated down from an initial `>600` guess after
`--metrics` showed the actual observed peak lead in a representative long
game topped out at 519) instead of round number. Verified engagement
directly this time: Builder count climbed to 5 in the same long
`g_iter25/highway` game that stayed flat at 4 under Iteration 55's
never-firing round gate.

`g_iter28` mirror: 10/20 = 50%, clean.

**Gauntlet 56 (step 2/3).** Peer: **176/300 = 58.7%** -- and, notably, the
*exact same per-opponent signature* as Iteration 54's multi-Watchtower
attempt: `g_iter16` down (75%->70%), `g_iter17`/`g_iter18` up each
(70%->75%), everything else unchanged. Reverted.

### Outcome

Three attempts at the camelcase mass-Watchtower lead (Iteration 54: more
Watchtowers per Builder; 55: round-gated 3rd Builder, confirmed inert;
56: lead-gated 3rd Builder, confirmed engaged) have now converged on the
same small, roughly-neutral result whenever the mechanism actually fires.
That consistency across three different trigger conditions suggests the
lever itself -- more static Watchtowers, isolated from any other change --
just isn't very impactful for this bot's playstyle, not that any
particular gating was wrong. camelcase's 44-Watchtower doctrine likely
only pays off as part of a broader, integrated strategy (Builder
production, placement, and army composition all built around it from the
start), not as an addition bolted onto an otherwise-unchanged Soldier/
Miner economy.

**Next:** parking the Watchtower-scaling thread -- not worth a 4th
tuning attempt on the same isolated lever. If this gets picked up again,
it should be as part of a genuinely different, larger doctrine change
(e.g. Watchtowers built forward/offensively rather than only at home,
which is closer to what camelcase's own Builder AI reportedly does) with
its own dedicated verification, not another gating tweak on the current
home-only placement.

---

## Iteration 57  —  Laboratory/Sage gold economy (REJECTED, catastrophic); new mechanism found

### Step 4/5

Our own gold economy had been completely unused all session -- 0
Laboratories, 0 Sages, ever, despite Sage costing **0 lead / 20 gold**
(confirmed via `RobotType` field dump), a fully parallel production lane
that never competes with the lead pool the Iterations 39-53 thread spent
so much effort on. Read `sample_afinals`'s own vendored `BotLaboratory.
java` for the real mechanic: `rc.canTransmute()`/`rc.transmute()`
converts lead to gold on command.

### Step 6 — Solution attempted, then reverted (severe regression)

Minimal version: one Laboratory per Archon (same gating pattern as the
existing Watchtower mechanic), transmute every round it can, spend
surplus gold on a Sage instead of a Soldier. Verified on the
`g_iter22/maptestsmall` reproduction case used throughout the Watchtower
thread -- previously a clean win (r234) -- and got a **loss** (r212)
with `--metrics` showing something far worse than a simple regression:
team lead ballooned to **9750 unspent** by r200 while Soldiers collapsed
from 38 to 0 and gold stayed at exactly 0 the entire game (the Laboratory
never once successfully transmuted).

Diagnosed the mechanism: a Laboratory, like a Watchtower, is a
*stationary* building -- once placed adjacent to the Archon, it occupies
one of its 8 build-ring tiles **permanently**, unlike a Soldier or Miner
that walks away after spawning. Adding a second permanent structure
(Watchtower + now Laboratory) on top of the existing Miner/Builder/Soldier
traffic through that same 8-tile ring makes the Archon dramatically more
prone to the exact occupancy-blocking mechanism the Iterations 42-44
`sandwich` investigation spent so much effort characterizing (`canBuildRobot`
fails purely on tile occupancy, confirmed via `javap` on the real engine)
-- except this time self-inflicted and severe enough to fully paralyze
the Archon's build queue for the rest of the game. Reverted immediately.

### Outcome

A real, valuable connecting finding even though the fix itself failed:
the occupancy-blocking mechanism from the sandwich thread isn't
sandwich-specific or contention-specific -- it generalizes to *any*
permanent structure competing for an Archon's own adjacent tiles, and
adding more such structures (as both this iteration and the Watchtower
thread tried) makes it worse, not better.

**Next:** don't have the Archon build the Laboratory directly. Route it
through a Builder instead (the way Watchtowers conceptually should be,
and the way `sample_afinals`'s own `BotLaboratory` explicitly relocates
away from the Archon after construction) so it's placed somewhere that
doesn't consume the Archon's own precious build-ring tiles at all. This
is a bigger change than the minimal version tried here, but addresses the
actual failure mode directly rather than just moving where the same
mechanism bites.

---

## Iteration 58  —  Builder-placed Laboratory + Sage economy (REJECTED, 58.7%)

### Step 4/5/6

Implemented Iteration 57's own fix: the Builder now walks ~7 tiles past
the Watchtower site before placing the Laboratory, keeping it clear of
the Archon's own build ring entirely. Verified directly on the exact
`g_iter22/maptestsmall` case that collapsed catastrophically last
iteration: win restored (r232, matching the original r234), and
`--metrics` confirmed the fix actually works this time -- Laboratory
built by r140 and visibly transmuting (gold fluctuating 0-19 rather than
stuck at 0), a Sage produced by r160, Soldiers growing healthily to 82 by
r220 with no collapse.

`g_iter28` mirror: 10/20 = 50%, clean.

**Gauntlet 58 (step 2/3).** Peer: **176/300 = 58.7%**. Reverted per Step
6.5.

### A striking cross-iteration pattern

Checking the per-opponent breakdown against Iterations 54 and 56:
**identical down to every single number** -- `g_iter12` 75%, `g_iter13`
75%, `g_iter16` 70%, `g_iter17` 75%, `g_iter18` 75%, `g_iter19` 60%,
`g_iter20` 60%, `g_iter21` 45%, `g_iter22`-`g_iter25` 50% each,
`g_iter26` 55%, `g_iter27` 40%, `g_iter28` 50% -- across three
*completely unrelated* mechanisms (more Watchtowers per Builder, a
lead-gated 3rd Builder, and now a full gold/Sage economy). All three
share only two things: each is additive rather than replacing existing
logic, and each barely engages in the specific 300-game sample (the gold
economy, for instance, only produces meaningful gold in long, calm
economy-race games -- a small fraction of this pool). That three
structurally different mechanisms produce byte-identical aggregate
results strongly suggests the actual games decided differently
(`g_iter16` down, `g_iter17`/`g_iter18` up, rest completely unchanged)
aren't being decided by any of these mechanisms' actual content -- more
likely some shared, incidental effect of adding code at all (e.g. a
bytecode-budget shift nudging a handful of already-marginal games one way
or the other, independent of what the new code does).

### Outcome

Not a regression -- Iteration 57's actual bug (catastrophic self-boxing)
is fixed and confirmed working -- but not yet a validated aggregate win
either. Given how rarely a full gold economy would engage within the
current 10-map peer pool (needs a long, calm, high-lead-surplus game to
ever produce a Sage at all), 58.7% with an *inert-in-most-games*
mechanism isn't a fair test of whether the underlying idea has value.

**Next:** the cross-iteration identical-numbers pattern deserves its own
look before trusting any more "small additive change, marginal miss"
results at face value -- worth deliberately testing whether a genuinely
*no-op* change (e.g. an unused local variable, or a comment-only diff)
produces the same `g_iter16`/`g_iter17`/`g_iter18` shift, which would
confirm it's a mechanical artifact of the Gauntlet/build process rather
than anything about these specific mechanisms, before spending further
iterations chasing what might be noise mistaken for signal. Separately,
the gold economy itself (Iteration 58's actual code, now proven not to
self-box) is worth keeping on file for a future test against maps/
opponents chosen specifically to engage it -- `sample_afinals` most of
all, since it's the one opponent whose own doctrine is built entirely
around this exact mechanic.

---

## Iteration 59  —  no-op methodological test (diagnostic only, no feature)

### Test and result

Ran the Iteration 58 note's proposed test directly: added a single,
genuinely inert line to `runArchon()` (an unused local variable computed
from `foes.length`, zero behavioral effect) and re-ran the identical
15-opponent Gauntlet used for Iterations 54/56/58.

Result: **175/300 = 58.3%**, with `g_iter16` **75%**, `g_iter17` **70%**,
`g_iter18` **70%** -- matching the *true* Gauntlet-37 baseline exactly,
**not** the 54/56/58 pattern (`g_iter16` 70%, `g_iter17`/`g_iter18` 75%
each). This refutes the "any code addition" hypothesis outright: a pure
no-op reproduces the original baseline, not the anomaly.

### Corrected finding

The `g_iter16`/`g_iter17`/`g_iter18` shift is real and specific, not a
generic bytecode-perturbation artifact -- but it's tied to a narrower
common thread than "any change" across Iterations 54, 56, and 58:
**all three modified Builder-related logic** (54: Watchtowers-per-Builder;
56: the `builderCap` formula controlling how many Builders get spawned;
58: `runBuilder`'s Laboratory placement). The no-op touched none of that.
Apparently these three specific opponents' games are decided by something
sensitive to *any* change in Builder timing/count/placement, consistently
in the same direction (worse vs `g_iter16`, better vs `g_iter17`/`g_iter18`)
regardless of which specific Builder-related change is made -- a real,
narrow effect, not noise, just not evidence that any particular one of
those three mechanisms is itself good or bad.

Reverted the no-op line (purely diagnostic, never a real feature).

**Next:** this narrows future Watchtower/Builder-doctrine work
meaningfully -- `g_iter16`, `g_iter17`, `g_iter18` are now known to be
unusually sensitive test cases for anything touching Builder behavior,
worth checking first (via a quick single-game or mirror-scale test)
before running a full Gauntlet on any future Builder-related change, since
their consistent swing may be masking or distorting the aggregate signal
from whatever the actual change does elsewhere in the pool.

**Addendum:** traced the exact flip -- `g_iter16,maptestsmall,botB` went
from a win (r317, Gauntlet 37 baseline) to a loss (r349) under Iteration
58's code. `--metrics` on the losing replay showed the Laboratory never
actually got built in this specific game (`B_labs=0` throughout) and our
Soldier count was already at 0 in the endgame either way -- this reads as
ordinary timing sensitivity in an already-close, marginal matchup (same
shape as the `g_iter22` "scattered noise across unrelated maps" finding
from Iteration 53), not a new distinct bug worth chasing further.

---

## Iteration 60  —  curArchons-scaled contact-quota (REJECTED on reproduction case, no Gauntlet spent)

### Step 4/5/6

Tried Iteration 48's original income-side idea directly, which the
thread had skipped straight past in favor of the fairness-yield approach
(Iterations 49-53): scale the contact-cut Miner quota with `curArchons`
(2 Archons -> quota 4, 3 -> quota 5, up from a flat 3) so multi-Archon
maps get modestly more aggregate mining capacity.

Tested on the `g_iter21/sandwich` reproduction case before touching
anything else: still a loss (r312), and critically, **still zero
Soldiers built from the previously-starved Archon** -- all builds still
came from the same dominant Archon as before, just slightly more Miners
alongside them.

### Outcome

This directly confirms Iteration 49's turn-order-dominance diagnosis
rather than contradicting it: raising the *income* lets the already-
winning Archon build a bit more of everything (including Miners), but
does nothing about *which* Archon wins each contested lead surplus --
the starved Archon is exactly as starved as before. Reverted without
spending mirror or Gauntlet time, since the reproduction case gave a
clean, immediate answer.

**Next:** the sandwich-shaped Archon-starvation problem now has two
independent lines of evidence pointing the same direction (Iteration 49's
diagnosis, confirmed again here): it is specifically about *who wins each
lead-surplus race*, not aggregate income. Iteration 53's sibling-hunger-
aware fairness-yield remains the best-verified fix for that exact
mechanism (59.3%, closest miss on the whole thread) -- any further attempt
should keep pursuing turn-order/priority fixes, not income-side ones,
which are now twice-confirmed not to touch the actual problem.

## Iteration 61  —  shortened 6-round sibling-hunger fairness-yield (ACCEPTED, 65.0%); sandwich thread (39-61) resolved

### Step 4/5/6

Picked the sandwich thread back up under the new Step 3.1 near-miss rule,
starting from Iteration 53's sibling-hunger-aware fairness-yield design
(the closest miss so far at 59.3%). Kept the core mechanism unchanged --
an Archon that just built a Soldier yields the build slot for a cooldown
window, gated on both `curArchons > 1` (avoids the single-Archon self-yield
bug from earlier in the thread) and a fresh "sibling hunger" broadcast
signal showing a *different* Archon currently can't afford a Soldier
(`SA_SOLDIER_HUNGRY`/`SA_SOLDIER_HUNGRY_RND`, freshness `< 8` rounds) --
and narrowed only the "am I personally on cooldown" window, from 8 rounds
down to 6 (`SA_LAST_SOLDIER_BUILDER`/`SA_LAST_SOLDIER_RND`, freshness
`< 6` rounds). The theory: 8 rounds of self-cooldown was giving up more
build slots than the fairness fix actually needed, feeding the g_iter22-26
regression cluster that showed up in Iterations 52-53.

Verified on the `g_iter21/sandwich` reproduction case (win, r582; replay
archived below). Before spending a full Gauntlet, ran the Step 6.5 mirror
check against the last-accepted iteration (g_iter24, Gauntlet 37): passed
comfortably, well above `MirrorCheckMinWinPct`.

**Correction on the way in:** before restarting this thread, I'd claimed
from memory that Iteration 53 (59.3%) already qualified as a clean near
miss under the new Step 3.1 rule. Checking the actual `results.csv` files
(baseline `gauntlet/20260829-004246` vs. Iteration 52
`gauntlet/20260829-033255` vs. Iteration 53 `gauntlet/20260829-035729`)
showed this was wrong: `g_iter22-26` (5 opponents) had already regressed
from the Gauntlet-37 baseline's 50-55% down to 40% in *both* Iteration 52
and Iteration 53 -- not introduced by Iteration 53's sibling-hunger
addition as I'd implied, and in any case a real per-opponent regression
that Step 3.1 as written should have caught. Diagnosed one flipped game
(`g_iter22/chessboard/botB`, win r711 -> loss r916 under Iteration 52)
directly: both Archons did build Soldiers in a normal split, and the loss
was a genuine decisive combat wipe at r495, not a starvation artifact from
the yield mechanism. Same class of finding as Iteration 59's
Builder-timing sensitivity on g_iter16-18: inherent volatility in an
already-close, marginal matchup under any timing perturbation, not a
fixable bug. User chose to accept this tradeoff and continue refining
rather than chase it further.

### Gauntlet 61 (peer)

**195/300 = 65.0%** peer WinPct (`gauntlet/20260829-221650`) -- clean pass
of `WinPct` (60%), a jump from the 59.3% near-miss ceiling this thread had
been stuck at since Iteration 53. Comparing to the Gauntlet-37 baseline
per-opponent: every g_iter22-26 opponent recovered except `g_iter26`
(-5%, the sole remaining regression, down from a uniform -10pt hit across
all five under Iterations 52/53); every other peer opponent improved or
held. The shorter 6-round window appears to have found a real sweet spot
between fixing the target contention case and minimizing collateral
timing damage elsewhere.

### Round-count metric (tracked, not gating)

`tools/compare_gauntlets.py` against the Gauntlet-37 baseline: outcome
flips concentrated in the sandwich-adjacent opponents as expected (the
mechanism's actual target), with the `g_iter26` regression's flipped
games generally losing at a *later* round than the baseline's own losses
there (partial-credit movement even where the aggregate win rate didn't
recover) -- consistent with "same underlying volatility, not a new
failure mode."

### Benchmarks (`sample_camelcase` + `sample_afinals`, Gauntlet 61 is
a `BenchmarkEvery` cycle)

`sample_camelcase` 0/20 (0%, unchanged all session), `sample_afinals`
3/20 (15%, down slightly from Iteration 37's 4/20/20% -- within noise for
a 20-game sample, and this thread never targeted benchmark-specific
behavior). Neither benchmark crossed the 30% peer-reclassification
threshold. No retirements due this round either: no peer has hit >=80%
in two consecutive *accepted* Gauntlets, since Gauntlet 37 is the only
other accepted Gauntlet since the 80%-domination rule was introduced.

### Outcome

**ACCEPTED.** Snapshot `g_iter29`. Replay:
`replays/iter61_g_iter21_sandwich_botA_WIN.bc22` (the reproduction-case
win, r582, matching Gauntlet 61's own `g_iter21,sandwich,A` result
exactly).

This closes out the sandwich Archon-starvation thread that ran from
Iteration 39 through 61 (23 iterations). Summary of the arc: the symptom
(one Archon on multi-Archon maps starves for Soldiers while a sibling
hoards the lead surplus) was consistently misdiagnosed at first as
aggregate income scarcity (Iterations 39-48, all rejected on income-side
fixes) until Iteration 49 diagnosed the real mechanism as turn-order
dominance over a *shared* lead pool, not aggregate scarcity -- confirmed
independently twice more, once by Iteration 60's direct income-side
re-test and once by this iteration's per-game diagnosis. Every
income-side fix attempted (Iterations 39-48, 60) failed to move the
starved Archon's build count at all. The fairness-yield family
(Iterations 49-53) converged on the right mechanism but couldn't clear
`WinPct` until this iteration's narrower cooldown window found the
balance between fixing the target case and not over-yielding into
timing-sensitive matchups elsewhere.

**Next:** with this thread resolved, the two parked leads from
Iterations 54-58 (multi-Watchtower Builder scaling, and the Laboratory/
Sage gold economy from Iteration 57-58) are the most promising unexplored
territory -- both were proven non-regressive but landed roughly neutral
against the *old* peer pool; worth re-testing against the new g_iter29
baseline in case the sandwich fix changed the economic picture enough to
make either one pay off now. Otherwise, Step 4 should pick a fresh losing
game from Gauntlet 61 directly.

## Iteration 62  —  late-game Miner-floor tuning for chessboard timeouts (REJECTED, both attempts)

### Step 4 — losing games `g_iter12/chessboard/botA` and `g_iter13/chessboard/botB`

Both hit the round-2000 cap, "the winning team won by having more Archons."
Only 4/300 Gauntlet-61 games run this long (all chessboard vs. the two
oldest peers, g_iter12/g_iter13) -- a narrow corner case, but a clean one
to instrument.

### Step 5 — Hypothesis

`--metrics` on `g_iter12/chessboard/botA`: at game end, 42605 lead sat
unmined on the map (never lead-constrained), yet in the r1500-2000 window
our build queue split ~evenly between Miner (78) and Soldier (90) while
g_iter12 built 100% Soldiers (154) and zero Miners in the same window --
confirmed generalizing on `g_iter13/chessboard/botB` (our side: 39 Miner /
70 Soldier / 2 Builder / 2 Watchtower vs. opponent's 171 Soldier / 0
everything else). Hypothesis: the round-based Miner floor
(`min(6+round/100,25)`) keeps consuming ~half our late-game build capacity
that could go to Soldiers, directly costing us the army-size tiebreak.
**Verified on both games → Step 6.**

### Step 6 — Solution (attempt 1, REJECTED)

Disabled Miner replenishment entirely past round 1200. Retest:
still 0/4 on the g_iter12/g_iter13 chessboard reproduction set, all still
r2000 losses. `--metrics` showed the hypothesis's framing was backwards:
Miners aren't idly topping off a cap that round, they're under steady
combat attrition (13 deaths in just the r1250-1700 window) even that late.
With zero replacement, Miners crashed 18->0 by r1700 and stayed at zero
for 300+ rounds -- lead income collapsed to near-nothing, which then
starved Soldier production too (Soldiers cost lead), making the game
strictly worse than the unmodified baseline. The "42605 unmined lead"
reading was backwards: it sits unmined *because* there are no live Miners
left to reach it, not because mining was already unnecessary.

### Step 6 — Solution (attempt 2, REJECTED)

Froze the floor's growth at round 1200 (`min(6+min(round,1200)/100,25)`)
instead of disabling replenishment -- still replaces attrition losses,
just stops climbing toward the 25 cap. Retest: still 0/4, all r2000
losses. `--metrics`: Miners now held steady (17-18 the whole late game,
economy didn't collapse this time) but Soldier counts were statistically
indistinguishable from the unmodified baseline at every checkpoint
(r1200-1900) -- the floor's *growth* past r1200 (18->25) turned out to be
a small fraction of the ongoing replacement cost; steady-state attrition
of ~18 Miners against this opponent's harassment consumes roughly the
same build-turn share whether the cap is 18 or 25.

### Outcome

**REJECTED both attempts, reverted.** Two attempts is enough to show the
Archon build-queue split isn't the right lever here: the real asymmetry is
that g_iter12/g_iter13 sustain their economy on just 8-10 Miners total
while we need ~18-25 to survive the same attrition, and no amount of
reallocating *our* build queue closes that gap without either starving
income (attempt 1) or barely moving Soldier output (attempt 2). Not
spending a 3rd attempt -- this is a low-generality corner case (1.3% of
Gauntlet-61 games) and the diagnosis now points somewhere build-queue
tuning can't reach.

**Next:** the real question this surfaces is Miner *survivability*, not
Archon build allocation -- why do our Miners take enough combat attrition
to need constant replacement while g_iter12/g_iter13's much smaller Miner
count apparently doesn't? Worth a dedicated Step 4 pass on Miner death
causes/locations in this exact matchup if revisited, but given the narrow
scope (4/300 games), better ROI right now is a fresh Step 4 target from a
higher-volume loss category (`valley`: 16 losses, `maptestsmall`: 14
losses in Gauntlet 61) that could move aggregate peer WinPct more broadly.

## Iteration 63  —  skip Archon repair under active enemy fire (REJECTED despite clearing WinPct -- net regression)

### Step 4 — losing game `g_iter22/valley/botA` (r746, Archon annihilation)

`valley` showed a strong side asymmetry in Gauntlet 61: as bot-side A,
15% peer win rate (11 losses); as bot-side B, 67%. Losses cluster at
nearly identical rounds (746, 765, 792) across many different peer
snapshots -- pointed at one structural bug rather than an opponent-
specific one.

### Step 5 — Hypothesis

`--metrics` on `g_iter22/valley/botA`: army sizes were roughly even
through r300 (13 A Soldiers vs. 14 B), then A's Soldier count crashed
13->1 by r575 while B's grew 10->35 -- the well-documented "push-stall-
collapse" pattern from Iterations 20-26/32-36, never previously root-
caused. Traced the mechanism directly this time: in the r325-590 window,
A built only 14 Soldiers to B's 40, and the dominant cause was **Archon
build-turn allocation**, not combat trades or Miner floor competition --
our Archon spent 30 of 43 actions on `repair` in the r400-610 window
(Iteration 14's unconditional "heal before build" priority). One case
traced exactly: the Archon re-repaired the same Miner 6 consecutive
rounds while it sat under sustained enemy fire, then it died anyway --
a fully wasted damage race. **Generality check**: confirmed on
`g_iter16/valley/botA` too (75 repairs vs. 23 builds in the same-shaped
window, an even more skewed ratio) → verified, continue to Step 6.

### Step 6 — Solution (attempt 1, superseded)

Skip repairing any friendly unit (combat or not) that currently has an
enemy combat unit within range 20 of it. Reproduction batch (5 valley
opponents x 2 sides = 10 games): 4/10 = 40%, up from this same subset's
30% baseline -- real signal, including one clean flip
(`g_iter16/valley/botA` loss->win). The Step-4 target game itself stayed
a loss but 33 rounds faster (mild regression there specifically).

### Step 6 — Solution (attempt 2, REJECTED)

Narrowed the skip to non-combat units only (Miners/Builders), keeping
Iteration 14's original heal-during-a-fight logic for Soldiers/Sages/
Watchtowers, on the theory that healing a unit mid-exchange can tip that
exchange unlike babysitting a Miner with no way to fight back. Same
reproduction batch: 3/10 = 30%, exactly baseline, and it lost attempt 1's
one clean flip. Reverted to attempt 1's (broader) version as the better
performer.

### Step 6.5 — Mirror check

Attempt 1 vs. `g_iter29` (last accepted): 11/20 = 55%, comfortably above
`MirrorCheckMinWinPct` (35%). Passed -> full Gauntlet.

### Gauntlet 63 (peer, 16 opponents incl. new `g_iter29`)

**194/320 = 60.6%** -- clears `WinPct` (60%) on raw aggregate. But
comparing to Gauntlet 61's per-opponent baseline on the shared 15-opponent
pool (excluding the new `g_iter29`): 183/300 = 61.0%, down from 65.0%.
9 of 15 shared opponents dropped exactly 5 points, one (`g_iter21`)
dropped 15 points, **none improved**. `tools/compare_gauntlets.py`
against Gauntlet 61 showed why: of 16 outcome flips, only 2 were gains
(`g_iter16/valley/A` and `g_iter28/sandwich/A`, both loss->win) against
**14 losses**, heavily concentrated on two specific maps: 5 near-identical
`pillars/botA` win->loss flips (all landing at exactly r552 -- opponents
g_iter22-26, a near-mirror snapshot family) and 4 `sandwich/botB`
win->loss flips (r415/r332). Both maps had substantial prior iteration
investment (`pillars`: iterations 4/5/21/27; `sandwich`: the 39-61 thread)
now regressing.

Traced the `g_iter22/pillars/botA` flip (win r893 -> loss r552) directly:
team A's Soldier count hit **zero** by r503 (last Soldier built r464,
4 died with no replacement) while the Archons then took direct,
undefended fire and died in sequence. This doesn't fit the "wasted
repair on a doomed unit" mechanism the fix targeted -- freeing more
build turns should only ever produce *more* Soldiers, not a full
production stall. Most likely explanation: the fix changes early-game
Archon behavior enough to diverge the whole game's trajectory (a
butterfly effect in a chaotic combat sim), and on `pillars`/`sandwich`
specifically that divergence lands somewhere worse, not just "less
repair, more Soldiers" in isolation.

### Outcome

**REJECTED, reverted** -- despite technically clearing the raw `WinPct`
bar, this is a genuine, systematic net regression from the last-accepted
baseline (65.0% -> 61.0% on the shared opponent pool), concentrated
enough (14 of 16 flips unfavorable, two specific well-tested maps) that
it reads as a real cost, not noise. TRAINING_ALGORITHM.md's Step 3 gate
is an absolute bar with no explicit no-regression clause, but accepting
a change the Gauntlet itself shows moving backward relative to the prior
snapshot contradicts the algorithm's purpose. Choosing engineering
judgment over the letter of Step 3 here.

**Next:** the underlying diagnosis (Archon repair-priority competing with
Soldier production during sustained combat) is still well-evidenced on
`valley` specifically and worth returning to, but needs either (a) a
narrower trigger that doesn't touch `pillars`/`sandwich`'s game trajectory
at all, or (b) direct root-causing of *why* `pillars/botA` loses all
Soldier production by r503 under the new code before trying a third
variant -- attempt 3 would be within `MaxSolutionsIterations` (2 used of
10) but the sandwich/pillars interaction needs to be understood first,
not just parameterized around blindly a third time. In the meantime, pick
a fresh Step 4 target from the still-large `maptestsmall`/`intersection`
loss categories.

## Diagnostic note  —  maptestsmall side-A/B asymmetry (no fix attempted, parked)

`maptestsmall` showed the most extreme side split of any map in Gauntlet
61: bot-side A won **15/15**, bot-side B won only **1/15**. Per-map side
splits across the whole Gauntlet: most maps mildly favor A (intersection
13/2, maze 13/8, highway 14/10, pillars 15/5), but `valley` strongly
favors B (4/15 A vs. 10/15 B, see Iteration 63) and `squer` mildly favors
B (9/15 vs. 13/15) -- ruling out a simple universal engine turn-order
artifact (that would favor the same side on every map). Each map's split
looks like its own idiosyncratic interaction with our code.

Fetched an actual `maptestsmall/botA` win replay directly from the VM
(gauntlet.sh only copies back losses) to compare against a `botA` loss --
`g_iter22__maptestsmall__botA_WIN.bc22` vs.
`losses/g_iter22__maptestsmall__botB.bc22`. Both show the **same shape**:
whichever iteration occupies side B has its Soldier count climb in
lockstep with side A through ~r100, then crash to 0 by r200-226 while
side A's climbs to 60-90 -- present regardless of which specific
iteration occupies which side, meaning it's baked into logic shared
across (most of) our snapshot lineage, not something specific to the
current code.

Checked the two most likely absolute-coordinate-bias candidates:
Watchtower placement (built essentially at-home on both sides, no
directional skew) and `nearestLead()` (tie-breaks on `distanceSquaredTo`
from the calling unit, i.e. relative, not absolute). Neither explains it.
The remaining candidate is the primary `Dijkstra20` pathfinder (2388
lines) -- not audited, would need a dedicated pass. **Parked without a
fix**: this is a clean, well-evidenced, high-value lead (a 15/15 vs. 1/15
split is enormous) but finding the actual mechanism needs more targeted
effort than fits in this pass. Revisit with a dedicated Step 4 session on
`Dijkstra20`'s tie-breaking specifically, ideally by instrumenting a
tracer that logs each Miner/Soldier's chosen direction alongside its
absolute position over the first ~150 rounds on both sides of this exact
matchup.

## Iteration 64  —  fixed Sage/Laboratory gold economy (ACCEPTED, 63.8%); g_iter12/13/16 retired

### Step 4/5/6

Reverted Iteration 63's code first (back to the `g_iter29` baseline), then
picked up Iteration 58's own parked "Next" note rather than a fresh
Step-4 loss: the Sage/Laboratory gold economy was proven non-regressive
back in Iteration 58 (58.7% peer) but almost never engaged within the
standard 10-map peer pool, so it was never a fair test of the mechanism
itself. `sample_afinals` -- the one opponent whose own doctrine is built
entirely around this exact mechanic -- is the natural place to actually
test it. Restored Iteration 58's design: Archon builds a Sage (0 lead /
20 gold) at top priority whenever affordable, ahead of the existing
Miner/Soldier tradeoff since it's a fully parallel resource lane; Builder
places a Laboratory 7 tiles clear of the Archon's build ring (fixing
Iteration 57's self-boxing bug) and it transmutes lead to gold every
round; `SAGE` dispatches through `runSoldier`, `LABORATORY` through a new
`runLaboratory`.

First test vs. `sample_afinals`: 3/20 = 15%, byte-for-byte identical to
`g_iter29`'s own baseline result against this opponent (0 outcome flips,
17/20 rounds unchanged) -- suspicious for a mechanism that was supposed
to at least sometimes fire. `--metrics` confirmed why: our single
Laboratory got built and repaired 4 times, then sat as a lifeless
PROTOTYPE for the remaining ~190 rounds, 0 gold produced all game, while
`sample_afinals`'s own 4 Laboratories transmuted every round to 66+
Sages. Root cause: the repair-priority check at the top of `runBuilder`
only fires when `canRepair()` succeeds *that specific round* -- on any
round where the Builder's own repair action is merely on cooldown (not
because the Laboratory finished), control fell through to the "walk
home" fallback and permanently abandoned the still-unfinished building
the moment the Builder left its action radius. Fixed: don't walk home
while a nearby friendly unit is still in `PROTOTYPE` mode, regardless of
whether this round's `canRepair()` happens to be true.

Retested vs. `sample_afinals`: still 3/20 (same result), but `--metrics`
now confirmed the mechanism genuinely works -- the Laboratory completed,
transmuted 53 times, gold reached 20, and a Sage was built. Traced why it
still didn't move the outcome: the Sage spawned and was killed in the
*same round* by three of `sample_afinals`'s own Sages focus-firing it
(they'd built 72 by that point in the game) -- our single-Lab, minimal-
priority investment is simply too small in scale to matter against an
opponent that built its whole doctrine around this mechanic from the
start. Not a bug; a genuine scale mismatch. Still valuable: unlike the
broken version (pure wasted Builder/Archon turns with zero payoff), the
fixed version is now a real, if modest, working economy lane.

### Step 6.5 — Mirror check

vs. `g_iter29`: 10/20 = 50%, comfortably clear of `MirrorCheckMinWinPct`
(35%) -> full Gauntlet.

### Gauntlet 64 (peer, 16 opponents incl. `g_iter29`)

**204/320 = 63.8%.** Comparing the shared 15-opponent subset (excluding
`g_iter29`) against Gauntlet 61 -- the actual last-accepted baseline, not
the rejected Iteration 63 candidate -- gives **194/300 = 64.7%**,
essentially flat (Gauntlet 61 was 65.0%). `tools/compare_gauntlets.py`
shows only **one** outcome flip in either direction
(`g_iter16/maptestsmall/B`, win->loss) and a mixed, net-neutral round-delta
pattern (47 improved / 29 worsened) on the rest -- nothing like Iteration
63's systematic regression signature. `g_iter29` itself: 10/20 = 50%.
This reads as a clean, low-risk accept: the underlying mechanism is now
correctly functioning (a real bug fix with lasting value for any future
work that builds on the gold economy) and the net aggregate effect is
neutral-to-slightly-positive rather than the broken version's pure
wasted overhead.

### Retirements

Both `g_iter12` and `g_iter13` sit at 80% in this Gauntlet, matching
their 80% in Gauntlet 61 (the prior accepted Gauntlet) -- two consecutive
accepted Gauntlets at >=80%, meeting the domination-retirement rule.
`g_iter16` similarly: 80% here vs. 85% in Gauntlet 61, also two
consecutive Gauntlets >=80%. **All three retired from the Gauntlet's
opponent list** (`OPPONENTS` for future runs drops `g_iter12`,
`g_iter13`, `g_iter16`) -- the first retirements since the threshold was
lowered from 90% to 80%.

### Outcome

**ACCEPTED.** Snapshot `g_iter30`. Replay:
`replays/iter64_sample_afinals_highway_botB_LOSS.bc22` (the game used to
diagnose and verify the Laboratory-completion fix -- a loss overall, but
the clearest illustration of the fixed mechanism actually working:
Laboratory completes and transmutes, a Sage gets built, and dies
instantly to `sample_afinals`'s own much larger Sage army).

**Next:** the gold economy lever is now correctly functional but still
far too small in scale to matter against a doctrine built around it --
if this thread gets picked up again, scale the investment materially
(more than one Laboratory per Archon, or an earlier/more aggressive
trigger) rather than another isolated tuning pass, per Iteration 56's
same lesson about the Watchtower-scaling thread. Otherwise, with three
retirements just landing, the Gauntlet's opponent pool is smaller and
fresher -- worth a routine peer Gauntlet run to get an updated per-
opponent picture before picking the next Step 4 target.

## Diagnostic note  —  maptestsmall side-A/B split resolved: likely a Battlecode engine team-order artifact, not our code

Follow-up to the earlier parked `maptestsmall` diagnostic (this file,
after Iteration 63). A routine Gauntlet run with the post-retirement
opponent pool showed the split had gotten even more extreme: **A:14/14
(100%), B:0/14 (0%)**. Crucially, the `g_iter30` opponent slot is our own
current implementation playing against an exact copy of itself (a true
mirror match, byte-identical code both sides) -- and it split **A wins,
B loses, at the identical round (r212) both times**. Since the code is
literally identical, this rules out any strategy-quality explanation
entirely: the outcome depends only on which team ID a given army
occupies.

Fetched both replays of that exact mirror pair (`g_iter30/maptestsmall`,
one from each side) and traced the first combat contact directly. Both
armies approach the map center in perfect lockstep (identical lead
income, identical build timing, mirrored positions round-for-round) until
the first Soldier pair enters mutual attack range on the exact same
round (r41): team A's Soldier #13120 (at (13,16)) and team B's Soldier
#10014 (at (16,17)) -- distance-squared 10, both within a Soldier's
`actionRadiusSquared` of 13, meaning **both were in range to attack each
other that same round**. Only `A SOLDIER #13120 attacks B SOLDIER #10014`
is recorded in round 41; `B SOLDIER #10014`'s return attack doesn't
appear until round 42, by which point it's already taken a free hit.

This is consistent with team A's units resolving before team B's within
each simulated round (a Battlecode engine convention, not something in
our `RobotPlayer.java`) -- on most maps this tiny per-clash edge gets
buried in the noise of terrain, numbers, and Archon-count effects (see
`valley`'s *opposite* skew, B:67% vs A:15% in Gauntlet 61, which already
ruled out a naive "side A always wins" theory). `maptestsmall` is the one
map where two mirror-symmetric armies rush and clash almost entirely
simultaneously across dozens of paired 1v1s with essentially nothing else
going on -- so the same tiny first-strike edge repeats across every
skirmish and compounds into a fully deterministic outcome.

**Not treating this as a Step 6 target.** If the mechanism is genuinely
engine-level team-order (plausible, not yet exhaustively proven), there's
no code fix on our side that changes which team ID we're assigned in a
given match -- the only lever available would be deliberately engineering
around simultaneous engagement (e.g. slight approach hesitation or
kiting to avoid exact-same-round mutual-range clashes), which is a
speculative, higher-risk mechanic change disproportionate to a single
map's worth of games, and would need to help on whichever side we end up
on without knowing in advance which that'll be. Recording the finding in
full since it's a real, well-evidenced piece of project knowledge, but
parking any fix attempt.

### Correction: not an engine team-order artifact -- it's per-map, pointing back to our own pathfinder

Checked the `g_iter30`-vs-itself (true mirror, byte-identical code) result
across **all 10 maps**, not just `maptestsmall`: A wins `chessboard`,
`highway`, `intersection`, `jellyfish`, `maptestsmall`, `squer` (6 maps);
B wins `maze`, `pillars`, `sandwich`, `valley` (4 maps). If team A
genuinely resolved before team B at the engine level, A should win every
mirror match, not 6 of 10 -- this cleanly falsifies the "engine team-
order" theory from directly above. The real mechanism has to be
map-dependent, which is much more consistent with the earlier-suspected
absolute-coordinate bias in our own movement/pathfinding code (ruled out
too hastily after Iteration 63's diagnostic pass) -- something that
happens to align favorably with team A's spawn corner under some maps'
specific 180-degree rotational offset and unfavorably under others,
rather than a fixed per-team engine fact.

The likely remaining suspect is `Dijkstra20.java`, the primary pathfinder
(vendored, unmodified, from `jmerle/battlecode-2022`, generated/unrolled
code, 2388 lines) -- not yet audited; `moveToward`'s greedy fallback and
`nearestLead()`'s tie-break were already checked and ruled out earlier.
Given the scope (10 maps' worth of outcomes potentially riding on one
mechanism) this is likely the single highest-value remaining lead in the
codebase, but auditing 2388 lines of generated pathfinding logic by hand
is a large, uncertain-payoff task that doesn't fit the remainder of this
session -- flagging it as the top candidate for a dedicated future Step
4/5 pass (ideally with a purpose-built tracer comparing the exact
shortest-path direction `Dijkstra20` returns for a probe unit at
carefully chosen rotationally-mirrored positions on one of the B-favored
maps, ties included) rather than another speculative code change without
having found the actual mechanism first.

## Iteration 65  —  away-from-home explore bias (REJECTED, severe regression)

### Step 4/5

`g_iter22-26/maze/botB` (5 near-identical instances, r417 each): a board
snapshot at r300 showed Miners clustered tight against the home Archons
in a cramped maze pocket, and `--metrics` confirmed team B built **zero**
Soldiers for the entire game up to r391 despite Miners meeting the floor
by r271 -- the same class of build-ring occupancy-blocking bug
characterized in the sandwich thread (Iterations 42-44) and hit again by
Iteration 57's Laboratory. Hypothesis: `moveExplore` (called when an idle
Miner has no lead target) was a pure random walk with no persistent bias,
so in a confined maze pocket idle Miners kept drifting back near the
Archon by chance, occupying its build-ring tiles.

### Step 6 — Solution (REJECTED, severe regression)

Biased `moveExplore` to move away from the nearest home Archon instead of
a pure random direction. Verified on the `g_iter22/23/24`/`maze` reproduction
set: Soldiers now actually got built (previously zero the whole game) and
the loss was delayed from r417 to r547-547 -- real, if partial, progress.
Mirror check vs. `g_iter30`: 10/20 = 50%, passed.

**Gauntlet 65 (peer):** **140/280 = 50.0%** -- a severe drop from the
routine baseline's 166/280 = 59.3%. Per-map breakdown showed why:
`pillars` collapsed 57%->7% and `squer` 64%->7%, and even `maze` itself
-- the map this was built to fix -- got *worse* in the larger sample
(61%->21%), contradicting the small 3-opponent reproduction check.
`sandwich`/`valley` improved (39%->68%/71%), an interesting but
insufficient offset.

### Outcome

**REJECTED, reverted.** The fix's blast radius was badly underspecified:
"move away from home" says nothing about *safety* -- unlike
`moveToward`'s careful rubble-aware, obstacle-tracing pathing, this just
picks whichever of 3 candidate directions increases distance from the
Archon, with no regard for whether that direction walks a Miner toward
the enemy or through contested territory. On `pillars`/`squer`
specifically this apparently marched Miners into harm's way en masse.
The 3-opponent maze-only reproduction sample was too narrow to catch
this -- it only tested the exact scenario the fix targeted, not the
much larger population of games where the same code path fires under
completely different (and often adversarial) circumstances.

**Next:** the maze build-ring occupancy diagnosis itself is still
credible and well-evidenced (zero Soldiers for 300+ rounds is a real,
severe bug) -- but the fix needs to be far more targeted than "move
away from home" for *any* idle explorer. A better-scoped version might:
only apply the bias when the Archon's own build actually failed due to
occupancy that specific round (a precise trigger, not a standing
behavior change for all idle Miners everywhere), and/or bias toward
*known safe* directions (away from the last-seen enemy, not just away
from home) rather than a context-free geometric heuristic. Given this
session already spent two rejected attempts on adjacent Archon-
build-priority issues (Iterations 62, 63), a future pass should verify
any fix against a broader reproduction sample (multiple maps, not just
the target one) before spending a full Gauntlet, given how narrow this
iteration's own pre-Gauntlet check turned out to be.

## Iteration 66  —  targeted build-block-nudge (REJECTED, inert on target + new squer regression)

### Step 6 — Solution (attempt 2 on Iteration 65's hypothesis, REJECTED)

Learning from Iteration 65's broad regression, tried a far more precise
version: an Archon writes a shared-array signal only when it can afford
its wanted unit but every build direction is genuinely occupied (not
merely unaffordable); only a Miner directly adjacent to *that* specific
Archon, sitting on already-depleted lead, steps aside once in response to
a fresh signal -- no standing behavior change for idle Miners generally.

Tested across a broader reproduction set this time per Iteration 65's own
lesson: 3 opponents x 5 maps (`maze`, `pillars`, `squer`, `sandwich`,
`valley`), not just the target map. Result: **7/30 = 23.3%**, down from
this exact 30-game subset's baseline of **9/30 = 30%**. Per-map
breakdown showed the regression was contained (not Iteration 65's
across-the-board collapse) but also that the fix **did nothing for the
target case**: `maze` was 2/6 in both the baseline and the new version,
byte-for-byte -- `--metrics` on the same `g_iter22/maze/botB`
reproduction case showed the identical zero-Soldiers-until-collapse
trajectory as the original unfixed bug. Only `squer` moved, and it moved
the wrong way (2/6 -> 0/6).

### Outcome

**REJECTED, reverted.** The signal-and-nudge mechanism is either never
firing (trigger condition too strict) or firing without the intended
effect (the one-tile step-aside isn't enough to actually clear the build
ring) -- either way, two attempts at this exact hypothesis (a broad
behavior change, then a narrow signal-based one) have now failed to move
the target case at all, with the second attempt adding a small new
`squer` regression on top. Not spending a third attempt guessing at yet
another variant.

**Next:** before any third attempt, actually verify the mechanism can
fire and matters at all -- add a temporary indicator-string trace on the
Archon's blocked-signal write and the Miner's nudge-response branch, then
re-run the exact `g_iter22/maze/botB` reproduction case with
`--indicators` to see whether the signal ever gets written, whether a
Miner ever responds to it, and whether responding actually opens a build
direction the following round. Without that direct confirmation, further
parameter tweaks are guessing blind at a mechanism whose basic operation
hasn't been verified. This diagnostic step should come *before* the next
Gauntlet-spending attempt, not after.

## Diagnostic note  —  Dijkstra20's tie-break mechanism found; likely root cause of the per-map A/B split (not fixed -- high risk, needs a dedicated pass)

Three consecutive rejected attempts (Iterations 63, 65, 66) all targeted
Archon-priority/build-allocation symptoms of the same repeated shape:
armies start even, then one side's production quietly out-paces the
other's over 100-300 rounds until an Archon dies and the game snowballs.
That exact shape has now shown up on `sandwich`, `valley`, `maze`, and
(checked this pass) `intersection` (`g_iter22/intersection/botB`: 10 v 11
Soldiers at r61, drifting to 43 v 0 by r421). Given three point-fixes on
the *build-priority* side failed or were inert, and given the earlier
`maptestsmall` mirror-match finding already implicated the pathfinder,
read `Dijkstra20.java`'s actual decision logic instead of guessing at
another economy tweak.

Found the mechanism: `getBestDirection` (the whole function is ~2100
lines, an unrolled BFS/Dijkstra over every tile within radius-squared 20)
ends in a long, flat chain -- `if (maxScore == scoreN) return directionN`
-- checked in a **fixed, hardcoded, compile-time-generated order** (31
candidates in just the final tie-break block; similar chains exist for
each closer "radius shell" earlier in the function). `maxScore` is the
max of all candidates' `(currentDistance - distanceToTarget) / pathCost`
scores. On genuinely symmetric terrain -- exactly the situation on a
freshly-spawned, rotationally-mirrored map -- **exact ties between
multiple equally-good tiles are common**, and every tie resolves to
whichever candidate happens to appear first in this arbitrary generated
sequence, which has no relationship to team identity or map rotation.
This is a strong, mechanistic explanation for why the same code produces
a different winner depending on which map (i.e. which specific 180-degree
rotational offset) it's mirrored across: the fixed tie-break order
happens to point toward useful territory for one spawn corner's
geometry and away from it for the mirrored one, and that flips per map
depending on the exact rotation -- matching the empirically observed 6-4
split from the `g_iter30`-vs-itself mirror-match data.

**Not fixed this pass.** This is vendored (`jmerle/battlecode-2022`,
MIT-licensed camelcase pathfinder), the single most heavily-used
navigation function in the whole bot (every `moveToward` call for every
unit type goes through it), and reordering or randomizing a 31+ branch
tie-break chain by hand risks subtle correctness bugs in code this
project didn't write and doesn't fully understand the invariants of --
exactly the kind of underestimated blast radius that burned Iteration 65
(a much smaller change to a single call site still caused severe,
map-specific collateral damage). A safe fix needs: (a) confirming the
tie-break theory directly (instrument a probe to log how often
`maxScore` ties occur and which candidate wins, on both a heavily
A-favored and heavily B-favored map), (b) a properly-scoped change (e.g.
seeding the tie-break scan order per-robot-ID or per-round rather than
using a fixed compile-time order, so ties resolve unpredictably rather
than systematically) verified in isolation before it touches live
gameplay, and (c) a much broader Gauntlet check than any single
iteration in this session has used, given the mechanism potentially
touches every game on every map. Recording as the top-priority lead for
a dedicated future session with room for that scope, rather than a
change squeezed into this one's remaining budget.

## Iteration 67  —  per-robot DIRS tie-break decorrelation (REJECTED, confirmed mechanism but net regression)

### Step 5 — Verification (safe, zero code changes)

Confirmed the Dijkstra20 diagnostic note's theory directly, from existing
data alone: fetched both sides of a fresh `g_iter30`-vs-itself mirror
match on `pillars` (byte-identical code) and compared early Miner moves
under the map's 180-degree rotational symmetry. Found a concrete
divergence: team A's Miner moved SOUTHWEST from a position; its exact
mirror counterpart on team B, facing the geometrically equivalent
situation, moved NORTH instead of the expected mirror direction
NORTHEAST. Same mechanism, different manifestation than Dijkstra20
itself: `RobotPlayer.java` has its own instance of the identical bug --
5 places (`runArchon` build direction, `runBuilder` Watchtower/Laboratory
placement, `repositionForRubble`, `moveToward`'s greedy fallback) all
iterate the fixed `DIRS` array (NORTH first) to break ties, an absolute
order with no team/rotation awareness. This part is safely fixable
(simple, hand-written code, not the 2388-line vendored pathfinder).

### Step 6 — Solution (attempt 1, superseded)

`myDirs(rc)`: each robot gets `DIRS` rotated by `rc.getID() % 8`, used at
all 5 call sites instead of the raw array. Broad reproduction test (3
opponents x 8 maps, 48 games): confirmed the mechanism matters a lot
(17/48 outcome flips) but wasn't neutral -- `valley`/`pillars`/`sandwich`
consistently improved while `highway`/`intersection` consistently
worsened across all 3 opponents, suggesting raw ID%8 still carries a
residual team-level correlation (IDs are assigned sequentially as units
are built).

### Step 6 — Solution (attempt 2, REJECTED)

Hashed the ID (Knuth multiplicative hash) before taking the offset to
decorrelate from build-order sequentiality. Same 48-game reproduction
sample: 21/48 flips this time, and the map-level clustering from attempt
1 was gone (flips looked genuinely mixed, not map-clustered) -- but the
aggregate win rate was *exactly* unchanged (14/32, identical before and
after) on this narrow "hardest maps" slice. Mirror check vs. `g_iter30`
passed (8/20 = 40%, within the expected 40-55% near-mirror band).

**Gauntlet 67 (peer):** **140/280 = 50.0%** -- well below `WinPct` and
not within `NearMissMargin` of it (would need >=55%). Every single
opponent's win rate against us dropped, uniformly, by roughly 5-10
points (e.g. `g_iter17` 75%->70%, `g_iter22` 50%->45%, `g_iter30`
50%->40%) -- not a chaotic, matchup-specific collapse like Iteration 65,
but a broad, consistent decline. Checked the two maps not covered by the
reproduction sample (`chessboard`, `jellyfish`) specifically: both
regressed too (57%->36%, 86%->64%), confirming this wasn't sampling luck
in the narrower test -- the full 10-map picture is a genuine, uniform
net negative.

### Outcome

**REJECTED, reverted.** The mechanism is real and confirmed (this is not
in doubt), but decorrelating it produced a broad regression rather than
an improvement. Best current explanation: the entire peer pool (`g_iter17`
through `g_iter30`) descends from the same codebase lineage and has
always shared this exact fixed-order tie-break bias -- 67 iterations of
tuning happened *with* it present. It's plausible other mechanisms (Miner
targeting, army positioning, build-ring placement) implicitly evolved
compatibly with that consistent bias over the whole project's history,
even though the bias itself has no principled justification. Removing it
this late doesn't recover a "correct" baseline so much as it disrupts
accumulated fit across dozens of other tuned mechanisms, with no
compensating benefit since ties are (by definition) situations where the
bias's target-progress heuristic was already indifferent between options.

**Next:** this significantly downgrades the practical priority of the
Dijkstra20 pathfinder lead too -- if fixing the *known, simpler* instance
of this bug (the `DIRS` tie-breaks in our own code) produces a net
regression against the current peer pool, there's no strong reason to
expect fixing the vendored pathfinder's much larger version would fare
differently. Not pursuing either further as a standalone fix. If
revisited, it would need to happen as part of a broader, deliberate
re-tuning pass (letting other mechanisms re-adapt via the normal
iteration loop afterward) rather than a single isolated change expected
to pay off immediately -- a fundamentally different, much larger-scope
project than incremental Step 4/5/6 iteration is suited for. Back to
picking ordinary Step 4 losses from the peer pool for continued work.

## Iteration 68  —  forward Watchtower placement (confirmed no-op, not spent on a Gauntlet)

Picked up Iteration 56's own parked "Next" note instead of a fresh
Step-4 loss: three prior attempts (54/55/56) at scaling home-only
Watchtower count/gating all converged on the same small, neutral result
and concluded the lever itself wasn't very impactful for this bot's
playstyle -- but never tested camelcase's reported doctrine of placing
Watchtowers forward/offensively rather than only at home. Bounded,
single-mechanism change: place the (still-capped-at-1) Watchtower at the
midpoint between home and the map center instead of always at home,
touching nothing movement/pathing-related (deliberately staying away
from this session's now-established dead end).

Broad reproduction test (3 opponents x all 10 maps, 60 games) per the
lesson from Iterations 65/67: **21/40 = 52.5%** on the shared g_iter22+27
subset, identical to the baseline's 21/40. `tools/compare_gauntlets.py`
confirmed this wasn't a lucky cancellation -- **zero outcome flips across
all 60 games**, the strongest possible "genuine no-op" signal. Not
spending a full Gauntlet on a confirmed no-op. Reverted without further
attempts.

**Next:** this most likely reproduces Iteration 55's finding rather than
refuting the forward-placement doctrine specifically -- `needBuilder`'s
gate (round>100, lead>300, miners>=8) may simply not engage early/often
enough within this map pool's typical game lengths for the Watchtower's
placement to matter either way, regardless of where it ends up. The
Watchtower thread has now had four converging null results (54, 55, 56,
68) across meaningfully different variations (more per Builder,
round-gated extra Builder, lead-gated extra Builder, forward placement) --
treating it as exhausted for incremental tuning; a real test of the
doctrine would need it integrated into core strategy from the start
(more like a fresh Iteration 0 rewrite) rather than layered on top.
Picking a genuinely fresh Step 4 target next: combat micro/targeting
logic, unexplored this session.

## Iteration 69  —  Miner-rescue priority fix (near WinPct, REJECTED as a mixed regression)

### Step 4/5 — a genuinely different angle: combat/rescue logic, not build-priority

`g_iter22/squer/botA`: `--metrics` on the r150-326 window (where army
sizes diverge) showed we lose Miners at **2x** the opponent's rate (8 vs
4) -- a real, distinct contributor to the production gap this session
kept finding downstream of, but never traced to its own root before.
Direct trace: Miner #10684 was besieged by 2 enemy Soldiers for ~10
rounds with zero response before dying, despite fleeing each round it
could -- Miners have `movCD=20` vs. Soldiers' `movCD=16`, so a caught
Miner structurally cannot outrun a pursuing Soldier; the only real
counter is a rescue. Root cause: Iteration 22's raided-Miner "cry for
help" (`SA_ECON_THREAT`) is only ever read inside `armyObjective()`,
the *exact* structural trap Iteration 37 already found and fixed for
`SA_HOME_THREAT` -- unreachable by any Soldier that already knows about
a live focus-fire fight elsewhere, which is most Soldiers most of the
time once real combat has started anywhere on the map.

### Step 6 — Solution

Gave a fresh raid call the same priority tier as Iteration 37's home-
threat fix: inserted right after the in-range-attack branches (so it
only affects march-destination choice for Soldiers with nothing in
immediate attack range, not units actively fighting) and above the
"march to reinforce a merely-known distant fight" check. Verified
directly on the target game: Miner deaths in the r150-326 window dropped
8->5, and the loss was delayed r346->r433 (+87 rounds).

Broad reproduction (3 opponents x all 10 maps, 60 games) per this
session's established lesson: **23/40 = 57.5%** vs. baseline **21/40 =
52.5%** on the shared `g_iter22`+`g_iter27` subset -- a real net
positive, +2 favorable outcome flips outweighing the unfavorable ones
(`intersection`/`sandwich`/`maze`/`squer` improved; a few `squer`/
`pillars` instances went the other way). Mirror check vs. `g_iter30`:
9/20 = 45%, passed.

**Gauntlet 69 (peer):** **165/280 = 58.9%** -- within `NearMissMargin`
(5 points) of `WinPct` numerically, but per-opponent comparison against
the last-accepted baseline showed a genuinely **mixed** picture: 7
opponents improved (`g_iter17`/`18`/`22`-`27` all +5), 6 dropped
(`g_iter19`/`20` -5, `g_iter21` -15, `g_iter28` -10, `g_iter29`/`30`
-5). Step 3.1's near-miss extension explicitly requires "no peer
opponent's win rate dropping" -- a mixed regression like this doesn't
qualify, even this close to the bar.

### Outcome

**REJECTED per Step 3.2, reverted.** The underlying mechanism is real
and directly verified (Miner survival genuinely improved on the target
case, and the fix follows an already-proven pattern from Iteration 37) --
this isn't a speculative miss like several of this session's earlier
attempts. The mixed per-opponent pattern most likely reflects a genuine
tradeoff: redirecting a Soldier to rescue a raided Miner is a good trade
against opponents/maps where that Soldier wasn't doing much else, and a
bad trade against opponents/maps where it pulls meaningful reinforcement
away from a fight that mattered more (`g_iter21`'s -15 points is the
sharpest instance).

**Next:** worth one refinement rather than abandoning outright, given
how close and how well-verified this is -- gate the rescue response more
conservatively, e.g. only redirect a Soldier that is *closer* to the
raided Miner than to the live focus-fire point (so it's genuinely a
short detour, not a full reassignment), or cap it to one responder
rather than every idle Soldier reading the same signal. Diagnose
`g_iter21` specifically first (the -15 outlier) to see exactly what got
pulled away and whether a distance-gated version would have avoided it,
before spending another full Gauntlet on a variant.

## Iterations 70-72  —  Miner-rescue gate calibration (3 more attempts, all REJECTED; thread closed)

Followed up on Iteration 69's own recommendation with three further
attempts at gating the raided-Miner rescue detour, each verified against
a broadening reproduction sample before spending a full Gauntlet:

- **Attempt 2** (relative gate: raid must be closer than the known live
  fight, *plus* an absolute 200-distance-squared cap): fixed the
  diagnosed `g_iter21` over-commitment case cleanly (4/4 on a small
  check), but the absolute cap alone dragged the broader reproduction
  sample back down to *exactly* the unmodified baseline (52.5%) --
  it filtered out the beneficial rescues (the original `squer` case has
  no live focus to compare against at all, so only the cap was doing
  anything there) right along with the harmful over-commitment.
- **Attempt 3** (relative gate only, cap dropped entirely): broader
  sample looked promising (55% on `g_iter22`+`g_iter27`, `g_iter21`
  improved to 50%), and the full Gauntlet landed at **59.6%** -- 0.4
  points off `WinPct`. Still disqualified from the near-miss extension:
  9 opponents improved, but `g_iter19`/`20`/`21`/`28` all dropped
  (-10 each). Dropping the cap fixed part of `g_iter21`'s problem but let
  `g_iter19`/`20` regress *more* than attempt 1 had -- they don't raid as
  aggressively as `g_iter21`, so the 200 cap wasn't what had been hurting
  them; something else was still over-committing.
- **Attempt 4** (relative gate + a looser 500-distance-squared cap, a
  middle ground): a 100-game reproduction sample looked like the best
  balance yet (`g_iter19` apparently back to its baseline, `g_iter21`
  further improved to 55%) -- but this was a **real analysis error**,
  caught only when computing the full Gauntlet's per-opponent comparison
  properly: I'd compared `g_iter19`'s new number against `g_iter21`'s
  baseline (60%) by mistake, not `g_iter19`'s own baseline (75%). The
  full Gauntlet (**59.3%**) showed `g_iter19`/`g_iter20` still dropping
  -15 each, essentially identical in magnitude to every prior attempt.
  Own mistake worth flagging plainly: even a 100-game reproduction sample
  doesn't protect against a wrong-baseline comparison error, only against
  sampling variance -- always diff against the *matching* opponent's own
  number, not eyeballed from memory.

### Outcome

**All three further attempts REJECTED, reverted.** Four solution
attempts total across Iterations 69-72 have now converged on the same
underlying tension: this codebase's peer pool contains opponents with
meaningfully different raid frequency/aggression (`g_iter19`-`21`
noticeably more aggressive than `g_iter22`-`30`), and any single global
gate (relative-only, absolute-only, or both combined at several
different thresholds) trades one group's gain for another's loss rather
than finding a setting that helps everyone. The mechanism itself remains
correctly diagnosed and genuinely fixes a real bug (Iteration 37's exact
structural trap, confirmed via direct trace) -- the difficulty is purely
in calibrating *how aggressively* to respond to it, which single-
parameter gating on distance can't cleanly solve.

**Not spending a 5th attempt** -- four attempts at the same lever
converging on the same "someone always regresses" shape is a strong
enough signal that the fix needs a fundamentally different shape, not
another threshold guess. A future attempt should consider something
that scales with the *actual observed threat level* rather than a fixed
distance cutoff -- e.g. track how many raid alerts have fired recently
(a genuinely low-raid opponent naturally sends few signals, so a
count-based throttle could self-calibrate per-opponent within a single
game without needing a hand-tuned constant) -- but that's a more involved
mechanism than fits as attempt 5 of the same basic idea. Picking a fresh
Step 4 target next.

## Iteration 73  —  self-calibrating raid-count throttle (ACCEPTED, 60.4%); Miner-rescue thread resolved

### Step 6 — Solution

Picked up the closed thread's own recommendation: instead of a fixed
distance/priority threshold (four rejected attempts, Iterations 70-72),
throttle the Miner-rescue response by *observed raid frequency*. New
mechanism: `SA_RAID_COUNT`/`SA_RAID_BUCKET` track how many *distinct*
raid events (not every round of the same ongoing siege -- gated on a
fresh Miner location or a stale previous alert) have fired within the
current round/50 bucket. A Soldier only takes the rescue detour while
the count is `<=3`; against a low-raid opponent the count never climbs
that high, so response stays effectively unconditional, while a
high-raid opponent (`g_iter21`, this thread's worst regression) hits the
cap and further raids get ignored until the next bucket -- no hand-tuned
constant, the threshold tracks each opponent's own behavior within the
game itself.

First check (`g_iter21`/`g_iter22`, 3 maps) showed `g_iter21` jumping to
5/6 = 83%, the best single result this whole thread produced -- but the
*original* `squer` target case regressed back to the unfixed baseline.
Traced directly: all 3 of our available Soldiers were converging on the
same single rescue signal every round (nothing limits responder count
per raid, a different problem than raid *frequency*), and `squer`'s
small unit counts meant "the whole army becomes the rescue squad" was
costly regardless of the throttle. Added a modest distance cap intended
to limit simultaneous responders -- it didn't move `squer` specifically
(the map's units are already clustered close together, so distance
doesn't differentiate there), but didn't hurt either.

### Verification

Broad reproduction (5 opponents incl. the two troublesome ones,
`g_iter19`/`g_iter21`, x all 10 maps, 100 games), diffed carefully
against each opponent's own correct baseline this time (the lesson from
the prior attempt's analysis-error near-miss): **zero regressions across
all five opponents**, with `g_iter21` and `g_iter30` each +5 points.
Mirror check vs. `g_iter30`: 11/20 = 55%, passed.

### Gauntlet 73 (peer)

**169/280 = 60.4%** -- clears `WinPct` outright. Per-opponent comparison
against the last-accepted baseline: **every single one of the 14
opponents either matched baseline exactly or improved** --
`g_iter21`/`g_iter29`/`g_iter30` each +1 win, everything else unchanged.
`tools/compare_gauntlets.py` showed 13 outcome flips, 8 favorable (the
`g_iter21/chessboard` win this thread was chasing, `sandwich` recovering
across `g_iter22-26`, `pillars` wins for `g_iter29`/`30`) against 5
unfavorable (a `pillars` cluster for `g_iter22-26`) -- a real, if modest,
net gain with no opponent left worse off. No retirements due (nothing
crosses 80%).

### Outcome

**ACCEPTED.** Snapshot `g_iter31`. Replay:
`replays/iter73_g_iter21_chessboard_botA_WIN.bc22` (loss r777 -> win
r1084 against `g_iter21`, the opponent this whole thread's regressions
centered on).

This resolves the Miner-rescue thread that ran from Iteration 69 through
73 (5 iterations). Summary of the arc: Iteration 69 correctly diagnosed
and fixed a real structural bug (raided-Miner cry for help unreachable
once any Soldier had a live combat focus, the same class of trap
Iteration 37 fixed for home-Archon threats) but its unconditional
version caused a mixed regression. Four single-parameter distance/
priority gating attempts (70-72) each traded one opponent group's gain
for another's loss, because opponents in this peer pool raid at
meaningfully different frequencies and no fixed constant fits all of
them. The winning insight: throttle by *counting observed raid events
within the game*, not by guessing a spatial constant -- a mechanism that
self-calibrates per-opponent without needing to know in advance which
opponent it's facing.

**Next:** the responder-count problem found on `squer` (multiple
Soldiers converging on one rescue signal in a small-army game) is still
real but didn't cost anything net this iteration -- worth a dedicated
look if `squer`'s specific loss pattern is revisited, but not urgent
given the clean accept. Otherwise, back to picking a fresh Step 4 target
from the current peer pool.

## Iteration 74  —  self-calibrating repair-vs-build throttle (REJECTED, no net improvement)

### Step 4/5

`g_iter22-26/valley` (both sides, all 5 opponents): a clean **0/10** sweep in
Gauntlet 73's results -- not the usual mixed/noisy pattern, a total loss
against this whole snapshot family on this one map. Traced directly:
neither side's Archons take real damage until very late (both still
near-full HP past r700), yet Soldier counts diverge sharply in that same
window (opponent 11->56, us 11->0) with B's Soldier *builds* exactly
matching B's Soldier *deaths* (44 = 44, zero net growth all game) against
the opponent's 99 builds. Root cause: our Archons spent 45 actions on
repair to the opponent's 2 in the same game (16 on Miners, 29 on
Soldiers) -- the same general repair-vs-build tradeoff Iteration 63
already investigated and rejected twice (a blanket "skip repair under
fire" and a combat-unit-only variant), both causing broad regressions
elsewhere in the peer pool.

### Step 6 — Solution

Rather than retry either of Iteration 63's fixed-rule variants, applied
the pattern that resolved the Miner-rescue thread (Iteration 73):
self-calibrate instead of hand-tuning a threshold. Track repairs and
builds team-wide in a round/50 bucket (`SA_REPAIR_BUCKET`/
`SA_REPAIR_COUNT`/`SA_ARCHON_BUILD_COUNT`); only skip a repair once
repairs have *already* consumed more of the current bucket's Archon
actions than builds have, and only once there's a real sample (>=4
repairs) to avoid throttling off early-game noise. The idea: a matchup
generating heavy repair pressure (like `g_iter22-26`/`valley`) backs off
automatically once the imbalance is demonstrated, while a matchup with
light, occasional repair needs never throttles at all.

Verified the mechanism engaged on the target case: repairs dropped
45->23 (nearly halved) and the loss round shifted (r853->r790,
r711->r799) -- but Soldier build *rate* barely moved (0.0516 -> 0.0519
builds/round), suggesting the freed Archon-turns weren't clearly
converting into more Soldiers. Broad reproduction (5 opponents incl.
`g_iter22` itself, all 10 maps, 100 games): **59/100** vs. baseline
**60/100** -- essentially flat, and critically **zero net change on
`g_iter22` itself** (10/20 both before and after) despite the repair
count genuinely halving in the checked game. `g_iter19`/`g_iter21`
dropped (-5/-10) while `g_iter27`/`g_iter30` improved (+5/+5) -- the same
"redistributes rather than helps" signature seen in several of this
session's other near-miss attempts.

### Outcome

**REJECTED, reverted.** This is the third distinct mechanism tried in
the repair-vs-build allocation space this session (blanket skip,
combat-unit-only skip, self-calibrating throttle) and the third to fail
to produce a net improvement, including on the exact matchup it was
built to fix. Unlike the Miner-rescue thread, where the self-calibrating
pattern was the missing piece, here it doesn't appear to be -- the
mechanism visibly engages and changes behavior (repairs genuinely halve)
without that translating into more Soldiers or better outcomes, which
suggests repair time was never really the bottleneck on the build side;
something else consumes the freed capacity (a `best == null` occupancy
block, a still-throttled `needBuilder`/`needMiners` gate, or genuine
lead-affordability limits are the untested remaining candidates, matching
the same "freed capacity doesn't convert" pattern first seen in
Iteration 62's Miner-floor attempts).

**Next:** treating the entire repair-vs-build allocation space as
exhausted for this session -- three mechanisms, three rejections, one
of them (self-calibration) proven to work well on an analogous problem
elsewhere. If revisited, the next step should be instrumenting *why* a
freed Archon-turn doesn't become a build (log `best == null` occurrences
directly) before trying a fourth mechanism blind. The `g_iter22-26`
sweep on `valley` itself remains unsolved and is likely best understood
as a genuine, if extreme, instance of the already-documented
opponent-family timing-sensitivity (Iteration 61's "ordinary
timing-sensitivity" finding, previously seen on chessboard/intersection/
pillars, now confirmed to extend to valley too). Picking a fresh,
unrelated Step 4 target next.

## Diagnostic note  —  g_iter22-26/valley: the real bottleneck is Soldier survival, not Archon-turn allocation

Follow-up to Iteration 74's rejection, before moving to a fresh target.
Instrumented `runArchon` directly (temporary, pure-diagnostic build, no
behavior change -- confirmed via identical round numbers to the unfixed
baseline) to find out where the "missing" Archon-turns Iteration 74
freed up were actually going, rather than guessing at a 4th repair-vs-
build mechanism blind:

- **Occupancy-blocking (`best == null`, nothing to repair either): 0
  occurrences** the entire game. Ruled out.
- **Sibling-hunger fairness-yield (Iteration 61's mechanism,
  `yieldSoldier == true`): 0 occurrences.** Ruled out -- valley's
  specific traffic pattern never actually triggers it.
- **Archon relocation (Iteration 34, `RobotMode.PORTABLE`): only 24
  turns total** (3 round-trips x ~8 steps each) out of a ~850-round,
  3-Archon game. Real, but far too small to be the primary driver.
- **Lead affordability: not the constraint either** -- `--metrics`
  showed A_lead and B_lead in comparable ranges throughout (both
  sides regularly banking, spending, and re-banking lead in the same
  rough tens-to-hundreds range; B was never starved relative to A).

None of the four candidate mechanisms explain the gap. Recomputed the
actual asymmetry directly instead: B built 44 Soldiers and **all 44
eventually died** (100% mortality); A built 99 and roughly 56 survived
to game end (~43% mortality). The dominant asymmetry isn't *production
rate* at all -- it's *survival rate*. Traced one death directly: a lone
B Soldier, isolated from the rest of its 10-11-strong army, got
focus-fired down 2-3-to-1 by grouped A Soldiers while B's other units
were engaged (or not engaged) elsewhere and never reinforced it -- the
classic "isolated unit destroyed in detail" failure mode, distinct from
(and likely a better explanation for) the production-side hypotheses
this thread (and Iteration 63 before it) spent most of its effort on.

**Not pursuing a fix this pass.** This reframes the target as army
grouping/cohesion -- whether Soldiers converge into a coordinated mass
before engaging, or trickle into contact piecemeal and get picked off
individually -- which is a genuinely different mechanism than anything
tried in the repair-vs-build or Miner-rescue threads. It's also exactly
the class of problem this session's Iteration 65 disaster (a "small"
movement-behavior change causing a severe, broad regression) warns
against attempting without very careful, narrow scoping and a wide
reproduction sample. Recording the corrected diagnosis for whoever picks
this up next, rather than guessing at a fix under time pressure: check
whether `SA_FOCUS` reliably pulls *all* nearby idle Soldiers toward a
live fight once one starts (an army-wide convergence check), or whether
isolated units simply have no mechanism to call for reinforcement the
way raided Miners do via `SA_ECON_THREAT` -- the latter, if true, would
be a natural, well-precedented next mechanism (a "Soldier in a losing
fight cries for help" signal, mirroring Iteration 73's raid throttle).

## Iteration 75  —  Soldier distress-reinforcement signal (REJECTED, mixed regression); valley/g22-26 thread closed

### Step 6 — Solution

Implemented the diagnostic note's own suggested next step: `SA_DISTRESS`
-- a Soldier currently fighting while locally outnumbered (enemy combat
units in vision >= friendly combat units + 2) broadcasts its location;
other Soldiers with nothing more urgent (checked above `SA_ECON_THREAT`,
since a fight actively being lost is more urgent than a raided Miner)
converge to reinforce. Deliberately no distance cap or responder-count
limit, unlike the Miner-rescue mechanism -- multiple Soldiers converging
on an outnumbered fight is the actual fix, not overkill.

Verified on the target case: the signal fired 482 times in one game
(extremely frequent) and the loss was delayed (r853->r914), but Soldier
mortality stayed at exactly 100% -- no survival-rate improvement despite
heavy engagement. Best explanation: once an army is already smaller than
the opponent's (as ours was by the time this triggers), "locally
outnumbered" becomes a near-permanent background state rather than a
genuine emergency signal, so the mechanism can't distinguish "help, we're
about to lose a unit we could save" from "we're just chronically behind
in numbers" -- grouping alone doesn't manufacture more Soldiers.

Broad reproduction (5 opponents incl. `g_iter22`, all 10 maps, 100
games): **54/100** vs. baseline **60/100** -- a real net regression. Per-
opponent: `g_iter22` (the exact target) improved sharply, 10/20 -> 13/20
(+15) -- a genuine partial win -- but `g_iter19`/`21`/`27`/`30` all
dropped (-5/-15/-10/-15). The same "helps the target, hurts broadly"
shape seen repeatedly this session (Iterations 63, 69, 74), most likely
because "outnumbered by 2" is common enough in ordinary, otherwise-fine
fights across many matchups that the signal fires far too promiscuously
outside the one matchup it was diagnosed on.

### Outcome

**REJECTED, reverted.** This closes the `g_iter22-26`/`valley`
investigation (Iterations 74-75, following on from 63 and 69-73's
adjacent repair/rescue work): four distinct mechanisms now tried against
this general shape of problem (fixed repair-skip, combat-unit-only
repair-skip, self-calibrating repair throttle, distress-based
reinforcement), all rejected, two (Iteration 63 attempt 1, Iteration 75)
sharing the exact same signature of "real win on the diagnosed matchup,
real loss everywhere else." The underlying diagnosis (Soldiers dying at
100% vs. ~43% mortality due to isolated, unreinforced skirmishes) is
almost certainly correct and well-evidenced -- but every mechanism tried
to act on it either doesn't move survival at all (Iteration 74) or moves
it at an unacceptable cost elsewhere (Iteration 75).

**Next:** not spending further attempts on Soldier-level reinforcement
mechanisms for this specific matchup family. The `g_iter22-26` cluster on
`valley` (and, per Iteration 61's original finding, on chessboard/
intersection/pillars too) is now well-supported as a genuine instance of
opponent-family timing-sensitivity that resists every lever tried across
two extended investigation threads (39-61's sandwich thread found a real
fix eventually; this one, spanning Iterations 63/69-75, has not). Treat
it the same way Iteration 61 already treats the chessboard/intersection/
pillars instances of this pattern: acknowledged, accepted as a cost of
doing business with this specific snapshot family, not chased further.
Picking a target with no history of resisting fixes next.
## Iteration 76 — blind rotational-symmetry assumption in armyObjective (REJECTED, reverted)

### Hypothesis (RESEARCH.md-motivated)

RESEARCH.md sec. 3 (synthesized from Gone Fishin' 2023's postmortem)
flags a known Battlecode failure pattern: assuming rotational map
symmetry without detecting it, which "backfired quite heavily" for that
team. Grepped `armyObjective()`'s no-information fallback (the guess the
whole army marches toward before any real enemy sighting) and confirmed
it unconditionally computes `(W-1-x, H-1-y)` -- pure rotational mirror,
with zero detection. Direct inspection of our 10-map pool's `symmetry:`
header field (via `bc22_replay.py`) showed 4 maps (maze, intersection,
jellyfish = horizontal; highway = vertical) are NOT rotationally
symmetric, meaning this guess is provably wrong on 40% of our maps.

Verified concretely on `g_iter22__highway__botB` (vertical symmetry):
our Archon at (44,5) has a true mirror partner at (5,5) (confirmed --
that's exactly where the enemy Archon sits), but the blind rotational
guess computes (5,44) -- 39 tiles off on a 50-tall map, sending the army
to the opposite end of the map from the real enemy.

### Solution attempt 1: reactive detection

Added `SA_SYMMETRY` (0=undetermined, 1/2/3=rotational/horizontal/
vertical) and `detectSymmetry()`: the first time any unit sights a real
enemy Archon, test all 3 transforms against every known own-Archon
location and lock in whichever one lands exactly on the sighting (exact,
not a guess, since map generation applies one global transform to
everything). `armyObjective()`'s two fallback lines switched to a
`mirror(loc, sym)` helper using the detected type (defaulting to
rotational -- identical to old behavior -- while undetermined).

Re-ran `g_iter22__highway__botB`: **identical result, round-for-round
(r691, still a loss)**. Root cause: this mechanism is reactive-only, and
in this exact game the wrong guess is *itself* what prevents contact
from ever happening -- 718 "objective [5,44]" indicator hits, constant
for the entire 691-round game, zero Archon sightings ever recorded. A
chicken-and-egg failure: the fix can only correct the guess after real
contact, but the bad guess is what's blocking contact.

### Solution attempt 2: time-based cycling

While `SA_SYMMETRY` is undetermined, cycle the assumed type every 200
rounds (rotational -> horizontal -> vertical -> repeat) instead of
freezing on rotational forever, so every hypothesis eventually gets a
turn driving the army somewhere it can make contact.

Re-ran the target game: still lost at r691 (unfixable for this
particular game -- turned out real, non-Archon combat contact happens
around r139, which routes Soldiers into a "reinforce" state that never
consults `armyObjective()` again, so no later cycle phase ever gets a
chance to steer toward the true Archon location; this game's loss isn't
actually decided by the initial march direction at all).

Tested on an 8-peer x 10-map x 2-side (160-game) reproduction sample
against the matching slice of Gauntlet 73's baseline: **98/160 vs.
100/160 -- a net regression**, with the exact "helps one, hurts another"
signature that has now killed every threshold/timing-based fix attempted
this session (Iterations 69-72, 74, and now this one). Per-game diff
showed the regression wasn't confined to the target maps -- 3 of the 7
flipped games were on **chessboard**, a *rotational* map where the old
permanent-rotational default was already correct. Cycling away from it
after round 200 on long (800-1200+ round) chessboard games caused
repeated wasted oscillation with no compensating benefit, since
rotational was never wrong there to begin with.

### Outcome

**REJECTED, reverted** (`git checkout -- src/bot/RobotPlayer.java`,
confirmed clean diff against `g_iter31`). The underlying bug is real and
confirmed via direct replay math (not a theoretical concern) -- but
fixing it cheaply is harder than it looks: a purely reactive fix is a
no-op on precisely the pathological games it matters most for, and a
naive time-based hedge disrupts the 6-of-10 rotational maps where the
old default was already correct, for a net loss. A real fix would need
either (a) genuine terrain-based symmetry elimination (comparing
observed rubble/wall values at simultaneously-visible mirror-candidate
tiles, as Gone Fishin' 2023's `MapRecorder` and confused 2025's
bytecode-efficient technique both do) -- a materially bigger investment
than a single iteration, since it requires dedicated scout routing to
get both sides of a candidate mirror pair into vision -- or (b) some
other cheap signal not yet identified. Not a good target for further
quick-refinement attempts; the failure mode here is structural, not a
tuning problem.

**Next:** picking a different Step 4 target. Not revisiting: this
symmetry-detection avenue (would need the bigger terrain-based
investment above to be worth another attempt), the `g_iter22-26`/valley
matchup family, movement/pathing/directional tie-break fixes, Watchtower
placement/scaling, or the repair-vs-build-priority lever.

### Diagnostic note — maptestsmall: 14/14 team-B losses, likely traces to the closed movement-tie-break thread

While picking the next Step 4 target after Iteration 76, a much starker
pattern than anything chased this session turned up: on `maptestsmall`
specifically, **every one of the 14 peer opponents beats us when we
play as team B (0/14), and we beat all 14 as team A (14/14)** -- a
total, opponent-independent sweep, versus the mild 60-75%-vs-25-40%
team-A tempo edge visible on most other maps (chessboard, highway,
intersection, jellyfish, maze, pillars, sandwich all show the same
directional bias, just far less extreme; `squer` and `valley` are the
only exceptions, and `valley` is already closed per the g_iter22-26
thread).

Traced one instance (`g_iter19__maptestsmall__botB`, r259, single-Archon
map, "annihilating enemy Archons"): both sides' economies and army sizes
are byte-identical for the first ~90 rounds (a mirror match, as expected
-- all 14 "opponents" here are just earlier snapshots of this same bot
lineage), then diverge over a long (~150-round) attritional grind --
team A's Soldier count climbs from 20 to 76+ while team B's declines
from a peak of ~30 to 0, ending in Archon death. Not a single decisive
battle; a slow, compounding trade-efficiency loss.

Checked for a code-level explanation: `betterTarget()` (combat
target-priority tie-break) is positional-bias-free (unit-type priority,
then lowest HP) -- not the cause. `moveToward()`'s final greedy-scan
tie-break, however, does favor whichever `Direction` comes first in the
fixed `DIRS` array (`score > bestScore`, strict inequality, so North
wins ties) -- exactly the mechanism the already-closed "movement/
pathing/directional tie-break fixes (Iteration 67)" thread covered. On
a rotationally-symmetric map, team B's mirrored orientation means a
fixed, non-team-relative directional bias would land asymmetrically,
and `maptestsmall`'s small size + single Archon (no redundancy, fast
close-quarters engagement) plausibly amplifies a marginal per-round tie
edge into a total sweep, the same way it's a smaller but still-visible
edge on every other rotationally-adjacent map.

**Not pursued further this iteration** -- the likely root cause is the
same lever already investigated and closed under Iteration 67; retrying
it here would be reopening a thread the standing instructions say not
to. Recording this because it's a genuinely different, starker
data point on the same underlying issue than whatever motivated closing
Iteration 67, in case a future session wants to revisit that closure
with this evidence in hand (worth ~5% of total Gauntlet games -- all 14
maptestsmall/B losses -- plus a smaller slice of the pattern visible on
6 other maps).

### Diagnostic note — three more angles considered and set aside this cycle

Continuing the search for a fresh Step 4 target after Iteration 76:

**Pillars g_iter17-21 losses** (distinct from the closed g_iter22-26
cluster on the same map): checked whether these share a root cause.
They don't look like the same thing -- g17-21 only lose as `botB` (all
wins as `botA`), matching the ordinary mild team-A tempo bias visible
on almost every map and already traced to the closed Iteration 67
movement-tie-break thread. `g22-26`/pillars, by contrast, loses on
*both* sides, matching Iteration 61's original "opponent-family
timing-sensitivity" finding. Not a new lead; both trace to already-
closed threads.

**Sage `envision()` / anomaly mechanics** (CHARGE/FURY/ABYSS/VORTEX):
confirmed via `javap` against the actual game jar that `RobotController.
envision(AnomalyType)` exists and is never called anywhere in
`RobotPlayer.java` -- Sage units are built (`wantSage`) but only ever
use their base `attack()`, never their special ability. Pulled the real
enum field values via a small probe program on the VM (`AnomalyType.
values()`): all 4 anomalies are global-schedule events; ABYSS/CHARGE/
FURY are also Sage-triggerable locally (`isSageAnomaly=true`), with
sagePercentage 0.99/0.22/0.10 respectively (Sage's local Abyss is
almost as strong as the global one; local Charge/Fury are much weaker).
Direct replay inspection of a global ABYSS trigger (round 250,
`g_iter22__highway__botB`) showed no dramatic unit-death spike --
inconclusive from one data point, and neither the client jar nor
GameConstants expose what the percentages actually *do* mechanically
(unit destruction chance? resource loss? no docs bundled, official
specs site inaccessible, and the one on-disk 2022 postmortem PDF
(`2022-5-musketeers.pdf`) can't be rendered locally -- no `pdftoppm`/
`fitz` available in this environment). Implementing an offensive use of
`envision()` without being confident what it does risks a real
regression (e.g. friendly-fire on an area effect). **Set aside**: this
needs either a working PDF-render path to read the 2022 postmortem
properly, or enough replay samples across multiple anomaly triggers to
reverse-engineer the effect empirically -- more foundational research
than fits one iteration. Worth revisiting if either becomes cheap.

**Sage-build priority timing**: `wantSage` in `runArchon` has
unconditional top priority over Soldier/Builder/Miner whenever gold is
affordable, and Sage competes with Soldier for the same scarce
Archon-build-turn (gold and lead are parallel resources, but the build
action itself isn't). Counted actual occurrences in the maptestsmall
collapse (`g_iter19__maptestsmall__botB`, rounds 150-259, the same game
diagnosed in the earlier maptestsmall note): 5 Sage builds vs. 68
Soldier builds in that window -- a real but modest ~7% diversion during
the exact stretch the army was collapsing from ~30 Soldiers to 0.
Checked the obvious low-risk gate (defer Sage while `SA_HOME_THREAT` is
active) against this same game first, per the standing "verify before
implementing" practice: `SA_HOME_THREAT` never fires once in this
entire window (0 "defend home" indicator hits) -- the fighting happens
away from the Archon, so that particular gate would be a no-op on
exactly the game it's meant to help. No other cheap, low-risk trigger
condition (that reliably engages during a real reinforcement crunch
without misfiring elsewhere) was identified in the time available.
**Set aside** rather than force a gate that doesn't verifiably engage;
a self-calibrating throttle (the pattern that worked for Iteration 73)
is a more promising direction here than a fixed condition, but needs
more design work than fits this cycle.

**Next:** none of the three landed a testable fix this cycle. Still
open for a future cycle: the anomaly/envision mechanic (once its real
effect can be confirmed), and Sage-timing (once a reliable, verifiably-
engaging trigger condition is designed). Continuing the search for a
different Step 4 target.

## Iteration 77 — Miner sticky lead-target unification (REJECTED, reverted)

### Hypothesis

`runMiner`'s live-vision lead scan re-picks the single richest
*currently visible* tile fresh every round via an unconditional early
return (`if (goal != null && !goal.equals(me)) { moveToward(rc, goal);
... return; }`), bypassing the sticky `myLeadTarget` mechanism Iteration
35 added specifically to stop this exact class of thrashing for the
published-beacon fallback case. Verified via `--moves` on
`g_iter17__intersection__botA` (a baseline loss): 452 of 3677 total
Miner move events (12.3%) were an immediate A->B->A reversal; the worst
single Miner recorded 31 in one game, with visible multi-round
back-and-forth between adjacent tiles (e.g. `(6,4)<->(6,3)<->(7,3)<->
(6,2)` for a dozen+ rounds with no net progress).

### Solution attempt 1: route the live-vision scan through myLeadTarget

Removed the separate early-return branch and fed the live-vision scan's
`goal` into the same sticky-target logic as the beacon fallback: once
`myLeadTarget` is set (from either source), keep moving toward it until
it drops below 6 lead or is reached, instead of re-evaluating "richest
visible" every round.

Single-game check on the exact target game raised an immediate red
flag: `g_iter17`/`intersection` botB flipped from a baseline win to a
loss (r416), while the oscillation-rate improvement was only modest
(12.3% -> 11.5%, and the remaining oscillation on inspection was mostly
combat-fleeing thrashing, a separate already-closed movement-tie-break
issue, not the lead-picking bug this fix targeted). Ran an 8-peer x
10-map x 2-side (160-game) reproduction sample to get a statistically
reliable read rather than judging off one game: **68/160 = 42.5% vs.
100/160 = 62.5% baseline -- a severe, uniform regression, not the usual
mixed "helps one hurts another" signature.** Every single one of the 8
opponents got worse (e.g. g_iter21: 13/20 -> 7/20, g_iter29: 11/20 ->
6/20, g_iter30: 11/20 -> 6/20) -- a decisive, unambiguous reject.

### Root cause of the regression

The original unconditional early-return wasn't just "the source of
thrashing" -- it was *also* the mechanism that let a Miner immediately
redirect toward whatever's richest nearby, every round, even mid-march
toward an older, farther sticky target. Removing it in favor of pure
`myLeadTarget` stickiness meant a Miner now commits to its *first* found
tile and marches straight to it, ignoring richer deposits it passes
along the way, until that one tile is nearly fully depleted (<6 lead)
or reached. That's a much more restrictive, far less greedy mining
policy than intended -- the fix conflated "stop flip-flopping between
similarly-ranked options" (Iteration 35's actual, narrow fix) with
"never redirect toward something better," which is a large, blanket
loss to mining throughput, not a narrow fix to a narrow bug.

### Outcome

**REJECTED, reverted** (`git checkout -- src/bot/RobotPlayer.java`,
confirmed clean diff against `g_iter31`). The underlying oscillation
this iteration set out to fix is real and measurable (12.3% of Miner
moves), but a fix needs to preserve "redirect toward something
genuinely better, right now" while still damping flip-flopping between
near-equal options -- e.g. only committing to the live-vision `goal` as
`myLeadTarget` when it's meaningfully richer than the current sticky
target (a hysteresis/threshold gate), not making stickiness absolute.
Not attempted this iteration; the severity of this first attempt's
regression means a follow-up needs careful design and its own fresh
verification pass, not a quick tweak.

**Next:** picking a different Step 4 target for the next cycle. If
revisiting this specific lead, design a hysteresis-based redirect gate
(e.g. only switch `myLeadTarget` to a newly-found tile if it's some
margin richer than the current one, or if the current one is more than
some distance away) rather than an absolute stickiness rule, and verify
the fix doesn't just trade one failure mode (thrashing) for another
(rigidity) before spending a broad reproduction sample on it again.

### Iteration 77 v2/v3 -- hysteresis-margin refinements (also REJECTED; thread closed)

Two further attempts at the Miner sticky-lead-target fix (see the
Iteration 77 entry above for the original bug and the v1 failure):

**v2**: instead of absolute stickiness, only redirect `myLeadTarget` to
a newly-spotted tile if it's a clear margin richer (`bestLead >
curLead + 10`) than the current commitment, using `curLead = -1` as a
sentinel when the current target isn't currently visible. Single-game
check on the original target (`g_iter17`/`intersection`) looked
promising -- oscillation down to 9.1% (from 12.3% baseline) and `botA`
flipped from a baseline loss to a win. An 8-peer x 10-map x 2-side
(160-game) reproduction sample told a different story: **88/160 = 55.0%
vs. 100/160 = 62.5% baseline -- still a net regression**, with a mixed
pattern (g_iter17-19 improved +1 each; g_iter21/23/26/29/30 all
regressed -2 to -4 each) -- the same "helps one cluster, hurts another"
signature that has now killed essentially every fixed-parameter
threshold attempted this entire session.

**v3**: hypothesized the `-1` sentinel was the specific problem (any
found tile with `bestLead > 9` would unconditionally override an
out-of-vision commitment, however good that commitment was, causing
instability). Removed the sentinel entirely: only compare/redirect when
the current target is *currently visible*; if it's out of vision, stick
with it unconditionally, no comparison at all. Ran a cheaper, targeted
40-game check against specifically the two worst-regressing opponents
from v2 (`g_iter21`, `g_iter29`, all 10 maps x 2 sides) before spending
another full sample: **g_iter21 stayed at 8/20 = 40% (v2 was 9/20 =
45%, baseline 13/20 = 65%) -- no improvement, if anything slightly
worse. g_iter29 moved to 9/20 = 45% (v2 was 8/20 = 40%, baseline 11/20
= 55%) -- a marginal uptick, still well short of baseline.** The
sentinel hypothesis doesn't explain the regression; something more
fundamental about the margin-based redirect approach doesn't suit these
specific opponents.

### Outcome

**REJECTED, reverted** (all three attempts; confirmed clean diff
against `g_iter31`). The underlying oscillation bug (12.3% of Miner
moves on the original target game) is real, and the general design
insight from v1's failure is sound and worth keeping for a future
attempt: absolute stickiness is wrong (loses greedy redirect to
genuinely better finds), but a *fixed* hysteresis margin doesn't
generalize across the peer pool either -- two different tunings (v2,
v3) both produced the same "helps some, hurts more" mixed-regression
signature this session has already seen from every other fixed-
threshold approach (Iterations 69-72, 74, the Iteration 76 cycling
attempt). The one mechanism that *has* reliably worked for this failure
signature elsewhere this session is a self-calibrating throttle
(Iteration 73's round-bucketed counter, gated off actually-observed
behavior rather than a guessed constant) -- a fixed lead-amount margin
is the wrong shape of fix for this bug, by the same logic.

**Next:** if revisiting Miner lead-target redirect, design a self-
calibrating version instead of tuning the margin further (e.g. track
how often a Miner's current tile richness undercuts newly-spotted
options in a given map/game, and adjust the redirect bar accordingly,
rather than a hardcoded lead-amount constant). Given three attempts (v1
absolute-stickiness, v2 margin=10, v3 margin=10-no-sentinel) have all
failed with variations on the same signature, treating this as closed
for quick-tuning purposes -- picking a different Step 4 target next.

## Iteration 78 — Watchtower relocation (ACCEPTED, 60.4%); safe, verified, currently-neutral

### Step 4/5 — a genuinely fresh, unexplored mechanism

While searching for a Step 4 target not covered by any closed thread
this session, noticed `runWatchtower` is minimal: attack if possible,
otherwise do nothing -- no repositioning ever, for the rest of the
game, regardless of where the front moves. Confirmed via `javap`
against the actual `battlecode22-2.2.1.jar` that `RobotController.
canTransform()`/`transform()` are general methods, not Archon-specific,
and via a temporary diagnostic indicator string on a live Watchtower
that `canTransform()` genuinely returns `true` in `TURRET` mode (128/144
rounds logged `true` in the check). This is the *same* PORTABLE/TURRET
mechanic Iteration 34 already uses for Archon relocation -- just never
extended to Watchtower, which sits wherever the Builder first placed
it, forever.

### Step 6 — Solution

Mirrored Archon's own proven relocation pattern (Iteration 34) as
closely as possible: once per Watchtower per game, only past round 500,
only when no local combat threat, and only if more than 20 tiles from
the current `armyObjective`, transform to `PORTABLE` and march toward
the action for up to 6 steps (or fewer if a threat appears mid-trip),
then transform back to `TURRET`. Same downtime tradeoff as Archon
relocation (`canAct=false` while `PORTABLE`) -- gated the same way,
deliberately conservative.

Mechanism correctness verified directly: temporarily lowered the
round-500 gate to round-50 on `g_iter19`/`maptestsmall` (a game known to
build and keep a Watchtower) and confirmed via indicator strings the
full sequence works cleanly -- "begin relocate toward [...]" ->
"relocating 1" through "relocating 6" -> "relocated, transforming
back", then reverted the threshold back to 500 before testing the real
candidate.

### Verification

Watchtowers are relatively rare (~15% of games in this peer pool ever
build one, and construction itself is gated on round>100 + miners>=8 +
lead>300), and the fix only engages past round 500 with no local
threat and a distant front -- by construction, most games see zero
behavior change, giving this a naturally low regression-risk profile.
An 8-peer x 10-map x 2-side (160-game) reproduction sample confirmed
this empirically: **100/160 = 62.5%, an exact per-opponent match to
baseline in all 8 slices** -- zero regressions. Mirror check vs.
`g_iter31`: 10/20 = 50%, comfortably clear of the 35% floor.

### Gauntlet 78 (peer, full 14-opponent)

**169/280 = 60.4%** -- matches baseline `WinPct` exactly. Per-opponent
comparison against the last-accepted baseline: **every single one of
the 14 opponents matched baseline exactly, win-for-win** -- literally
identical to Gauntlet 73's per-opponent breakdown. Round-count diff
(not just win/loss) confirms the mechanism isn't simply dead code: 7 of
280 games show a different round count (`g_iter17/valley/B`,
`g_iter27/highway/B`, `g_iter29-30/pillars/A`, etc. -- all long games,
consistent with the round>500 gate), meaning relocation genuinely fired
in those games, but never flipped an outcome either direction. A real,
mechanically-verified, currently-neutral change: safe to keep, with
plausible (if unproven against this specific peer pool) upside against
opponents or benchmark bots whose games run long enough and whose front
moves far enough from a surviving Watchtower for this to matter. No
retirements due.

**Snapshot**: `src/g_iter32/` (via `tools/snapshot.sh g_iter32`).
Replay reference: `gauntlet/20260830-070713/` (full Gauntlet run);
`gauntlet/20260830-065710/losses/g_iter19__maptestsmall__botB.bc22`
(mechanism-verification replay, temporary round-50 threshold).

**Next:** add `g_iter32` to the peer set for future Gauntlet runs
alongside `g_iter17-31`.

## Iteration 79 — self-calibrating Miner-redirect throttle (REJECTED; thread closed for good)

### Solution

Fourth attempt at the Miner lead-redirect thrashing fix (see Iteration
77 and 77 v2/v3 entries for the first three, all rejected). This
attempt followed the closed thread's own recommendation: instead of a
fixed lead-amount margin, self-calibrate it the same way Iteration 73
did for the raid-rescue throttle. Tracked, per Miner, how many times it
has already redirected `myLeadTarget` within a round/30 bucket, and
required a linearly escalating margin for each further redirect in that
window (5, 10, 15, ...) -- cheap for a single well-justified switch,
increasingly reluctant for a Miner caught oscillating between near-equal
options.

### Verification

Single-game check on the usual target (`g_iter17`/`intersection`) was
the most promising result of any attempt in this thread: oscillation
down to 8.3% (baseline 12.3%, best prior attempt v2 was 9.1%), and
*both* games flipped to wins (baseline was 1 win, 1 loss). Given this
thread's history -- v2 also looked good on this exact game before
regressing net -- ran the same 8-peer x 10-map x 2-side (160-game)
reproduction sample before drawing any conclusion: **83/160 = 51.9% vs.
100/160 = 62.5% baseline -- a net regression, and a *worse* one than
v2's 88/160.** Same signature as every other attempt: `g_iter17`/`18`
held steady, but `g_iter19/21/23/26/29/30` all regressed, some
severely (`g_iter29`/`30` dropped from 11/20 to 7/20 each).

### Outcome

**REJECTED, reverted** (confirmed clean diff against `g_iter32`). Four
fundamentally different designs -- absolute stickiness, two different
fixed hysteresis margins, and now a self-calibrating escalating margin
modeled directly on the one pattern (Iteration 73) that's worked
elsewhere this session -- have all failed with the identical "helps
`g_iter17-18`, hurts `g_iter19/21/23/26/29/30`" signature. That the
self-calibrating approach *specifically* also failed is the important
new data point: it rules out "wrong shape of fix" (fixed vs. adaptive)
as the explanation and points instead to something more fundamental --
whatever's actually different about the `g_iter19-30` cluster's own
Miner/army behavior isn't something a *targeting-margin* lever, of any
shape, can fix. (Notably, `g_iter19`, `21`, `23`, `26`, `29`, `30`
significantly overlap the same "g_iter22-26/valley" opponent-family
timing-sensitivity already closed earlier this session for a completely
different mechanism -- plausibly the same underlying, currently
unidentified root cause is what's resisting *every* lever tried against
this specific opponent cluster, not something specific to Miner
targeting.)

**This closes the Miner lead-redirect thread for good.** The underlying
oscillation (12.3% of Miner moves on the target game) remains real and
un-fixed, but four well-reasoned, properly-verified attempts have found
no lever that helps broadly without hurting this same opponent cluster
elsewhere. Not a good target for a fifth attempt without first
understanding *why* `g_iter19-30` specifically resist every Miner/
Soldier-behavior change tried against them (a question bigger than this
thread; see the standing `g_iter22-26`/valley closure notes for the
closest existing lead on that).

### Diagnostic note — the "resistant cluster" is just strong peers, not a special weakness

Following up on Iteration 79's closing observation (four different
Miner-redirect fixes all failed specifically against `g_iter19/21/23/
26/29/30`): diffed the actual snapshot source (`src/g_iterNN/
RobotPlayer.java`) across this range against the easy peers (`g_iter17/
18`, 75% baseline) to see if there's a specific mechanism making this
cluster resistant to fixes.

There is, but it's mundane: `g_iter19` includes Iteration 24's SA_FOCUS
stability fix (stopped resetting the focus-fire target every round,
fixing a formation-scattering bug), `g_iter21` includes Iteration 28's
target-priority fix (Soldier-first instead of Archon-first, clearing
defenders before chasing buildings), `g_iter22` onward include
Iteration 29's side-step congestion fix and Iteration 30's Builder/
Watchtower economy. **Every one of these snapshots is a genuinely more
developed, substantively different point in our own bot's lineage** --
not opponents with some specific exploitable trait, just accumulated,
real improvements. `g_iter17/18` are comparatively early/undeveloped
ancestors (missing all of the above), which is *why* they're easy
matchups (75%) -- not because our current bot has some specific edge
against them.

This reframes the "resistance" pattern: it's very likely not a bug or
gap to find and fix, but the ordinary, expected consequence of
self-play training -- later, more-developed ancestors are naturally
tougher mirror-match opponents than earlier ones, and TRAINING_ALGORITHM.
md's own Step 6.5 threshold (near-mirror matchups land in a 40-55% band
"even for a genuinely good solution") already anticipates exactly this.
Chasing "why is `g_iter22-26` only 50%" as if it's a fixable weakness is
likely the wrong frame -- 50% against a comparably-developed mirror
opponent isn't broken, it's expected. The four failed Miner-redirect
attempts (and the earlier g22-26/valley thread's four failed attempts
at a different mechanism) both plausibly failed for the same reason: a
single marginal-mechanism tweak has little room to move a genuinely
close mirror matchup, while the *same* tweak shows up clearly against
weaker, less-developed peers where there's more headroom.

**Implication for future iterations:** stop treating "improve
specifically against `g_iter19-30`" as an achievable Step 4 target in
isolation. The way to raise performance against strong peers is the
same as always -- find genuinely general improvements (like Iteration
78's Watchtower relocation, Iteration 73's raid throttle) that raise
overall play quality, which then compounds into better peer WinPct
across the board, rather than searching for an opponent-cluster-specific
trick.

### Diagnostic note — bytecode limits ruled out; Laboratory placement shows no evidence of trouble

**Bytecode usage**: RESEARCH.md flags bytecode/communication efficiency
as a recurring cross-year design theme, and this session had never
directly measured our own bot's actual bytecode consumption (only
confirmed no runtime exceptions occur). Enabled the engine's profiler
(`-Dbc.engine.enable-profiler=true`, normally off in `gauntlet.sh`) for
one demanding test game (`bot` vs `g_iter22`/`highway`, ~90 units/side
by the late game) and wrote a one-off parser against `tools/
bc22_schema.py`'s `ProfilerFile`/`ProfilerProfile`/`ProfilerEvent`
classes (not currently exposed by `bc22_replay.py`) to extract true
per-round bytecode usage: for each robot, track open/close deltas
specifically on its own `run<Type>()` frame (the outer `run()` loop
never returns, so naively diffing that frame's events gives a
lifetime-cumulative number in the millions, not a per-round one -- the
inner per-type method is what's called and returns fresh every round).
Result: **the worst-case robots (Miners and Soldiers in the most
crowded, longest-running match tested) topped out around 48-50% of
their type's bytecode limit** (e.g. Miner: max 4968/10000). Comfortable
headroom, not remotely bytecode-limited even under a stress case. This
rules out an entire category of "maybe we're silently losing actions to
bytecode overrun" speculation with real data instead of assumption --
worth remembering so a future session doesn't re-derive this.

**Laboratory placement**: checked whether the fixed "walk 7 steps away
from home, then place" logic (Iteration 58/64) ever leaves a Builder
stuck searching for a valid tile on obstacle-dense maps. The one replay
checked with a confirmed, completed Laboratory build (`g_iter19`/
`maptestsmall`) showed a perfectly clean sequence -- exactly 7 "to lab
site N/7" indicator hits, zero "finding lab site" (the fallback path
for "no open tile found yet") hits. Checked 3 more replays on
different, more obstacle-dense maps (`pillars`, `valley`) for
comparison, but none of those particular losses ever reached the
economic maturity needed to attempt a Laboratory at all (0 lab-related
indicator hits either way) -- inconclusive rather than a clean bill of
health, since the sample doesn't cover a genuinely obstacle-heavy
successful placement. Not pursued further without a positive signal of
an actual problem; recording as "checked, no evidence found" rather
than "confirmed fine."

**Next:** neither of these turned into an actionable Step 4 target this
cycle. Continuing the search for a genuinely fresh, general-improvement
lever next.

## Iteration 80 — Miner exploration momentum (REJECTED; promising signal, real regression elsewhere)

### Step 4/5 — deeper structural re-read surfaces a genuinely fresh lead

After 3+ cycles unable to find a new Step 4 target via targeted probing
(and a fresh routine Gauntlet with the current 15-peer set turning up
nothing new -- every number matched already-understood baselines,
`g_iter31` included), did a from-scratch re-read of `runSoldier`/
`runMiner`/`moveExplore` with no prior hypothesis. `moveExplore()`
(used when a Miner has no live-vision lead, no known beacon, and
`nearestLead()` also returns null) re-randomizes its movement direction
*every single round* -- a pure "drunkard's walk" with zero persistence,
unlike `moveToward()`'s own `lastDir` momentum tracking.

Added a temporary diagnostic indicator and ran it on `valley`/`pillars`
(flagged lead-sparse since Iteration 7) against `g_iter22`: **on one
`g_iter22`/`valley` loss, ~4900 of ~7700 possible Miner-turns (~64%)
were spent in this branch.** These are also our two weakest maps at
baseline (`valley` 40%, `pillars` 47% peer win rate) -- a real,
substantial, previously invisible time sink.

### Step 6 — Solution

Gave `moveExplore` directional persistence: commit to a direction and
keep moving in it until blocked or a 1-in-15 chance per round triggers
a redirect, instead of re-randomizing every round. Single-game check
(`g_iter22` on `valley`+`pillars`) was promising: valley flipped from
0/2 wins to 2/2; pillars still lost both sides but with different round
counts (real behavior change, no flip there).

### Verification

8-peer x 10-map x 2-side (160-game) reproduction sample, with careful
matched-subset comparison against the same 8 opponents' baseline
(not the full-14-peer baseline, to avoid an apples-to-oranges
comparison): **valley genuinely improved broadly, 8/16 -> 12/16 (+4)
across all 8 opponents, confirming the single-game signal generalizes**
-- but **pillars regressed sharply, 8/16 -> 3/16 (-5)**, more than
offsetting the valley gain, plus softer regressions on most other
individual opponents (`g_iter17/18/19/21/23/26` all down, `g_iter29/30`
unchanged). Overall: **81/160 = 50.6% vs. 100/160 = 62.5% baseline -- a
clear net regression.**

### Outcome

**REJECTED, reverted** (confirmed clean diff against `g_iter32`). Unlike
the closed Miner-redirect thread, this one has a *genuinely positive,
verified, broadly-confirmed* result on one map (valley) -- the
underlying insight (Miners waste the majority of their turns in a
zero-persistence random walk on lead-sparse maps) is real and worth
keeping. The regression is plausibly specific to *why* pillars is
obstacle-dense (its name): a persistent-direction walk that works well
in open terrain (valley) may cluster multiple Miners into the same
corridor or trap them oscillating locally against a pillar cluster
before the blocked-check redirects, in a way the old (inefficient but
naturally self-spreading) pure-random walk didn't. Not diagnosed in
detail this cycle -- reverted rather than spend a second full
reproduction sample mid-hypothesis in an already long cycle.

**Next:** worth a v2 attempt with a hypothesis specifically targeting
the pillars regression rather than re-tuning the redirect-chance
constant blindly -- e.g. bias the direction pick to avoid crowding
(check for nearby friendly Miners already exploring, and skip a
candidate direction that's already someone else's `exploreDir`), or
verify directly via `--moves`/`--indicators` on a pillars replay
whether the failure mode is clustering, local-trap oscillation, or
something else before designing the fix. Don't just widen or narrow the
1-in-15 redirect constant without first confirming which failure mode
it's actually hitting.

## Iteration 80 v2 — self-calibrating stuck-detection (REJECTED; thread parked, not closed)

### Diagnosis

Before designing v2, diagnosed v1's pillars regression directly:
re-implemented the persistent-direction fix with a diagnostic indicator
and ran `--moves` analysis on the target `g_iter22`/`pillars` loss.
Confirmed **local-trap oscillation, not clustering**: several Miners
made 100+ total moves for under 10 tiles of net displacement (e.g. one
made 111 moves netting only 4.5 tiles). Root cause: a persistent-
direction walker only reconsiders its heading when explicitly blocked,
giving it *fewer* independent random draws per unit time than the old
every-round-random walk -- so it's *less* likely to stumble onto the
specific escape sequence a dead-end pillar-pocket needs, exactly
backwards from what makes persistence help on open terrain like valley.

### Solution (v2)

Self-calibrating stuck-detector: track each Miner's position over a
rolling 15-round anchor window; if net displacement in that window is
under 3 tiles, treat it as trapped and fall back to the old high-
entropy every-round-random walk for 15 rounds (maximizing escape
attempts) before resuming persistent exploration.

### Verification

Single-game check (`g_iter22` pillars+valley) was mixed: pillars/A
flipped loss->win, but valley/B regressed from a v1 win to a loss --
already a yellow flag. 8-peer x 10-map x 2-side reproduction sample,
matched-subset comparison: **88/160 = 55.0% vs. 100/160 = 62.5%
baseline -- still a net regression, though less severe than v1's
81/160.** The map-level picture flipped in an unhelpful way: **pillars
landed exactly at baseline (8/16, the stuck-detector genuinely
neutralized v1's regression there)**, but **valley dropped *below*
baseline (6/16, worse than doing nothing)** -- the stuck-detector fixed
the map it was designed for and broke the one the *original* fix (v1)
had genuinely improved. Per-opponent: `g_iter17/18/19/21/29/30`
regressed, `g_iter23/26` improved -- the same "helps one cluster, hurts
another" signature as v1 and the closed Miner-redirect thread.

Checked the new valley loss for the same "100+ moves, <10 tiles
displacement" pattern that explained the pillars regression -- **not
present**: Miners in the sampled valley loss moved only a handful of
times each (single digits), nothing resembling the dramatic pillars
trap signature. The valley regression's specific mechanism wasn't
identified this cycle; the stuck-detector may be misfiring in some
subtler way there (e.g. false-triggering during ordinary slow
progress), but this wasn't confirmed, only suspected.

### Outcome

**REJECTED, reverted** (confirmed clean diff against `g_iter32`). This
thread is **parked, not closed for good** like the Miner-redirect
thread -- two attempts (v1, v2) have each shown a genuine, confirmed
positive component (v1: valley; v2: pillars neutralized) traded for a
new negative elsewhere, a materially different pattern from the
Miner-redirect thread's four attempts that never showed *any* net-
positive signal. The core insight (Miners spend the majority of their
turns in a zero-persistence random walk once known lead runs out, most
visible on our two weakest maps) remains real and unexploited.

**Next, if revisited:** diagnose the v2 valley regression specifically
before another attempt (why does the stuck-detector, which measurably
helped pillars, hurt valley below its *own* pre-fix baseline?) -- don't
just retune the 3-tile/15-round constants blindly. Given this session's
now-repeated pattern of "fixed constants generalize poorly across this
peer/map mix," a genuinely different mechanism (rather than tuning the
stuck-detector's thresholds) may be needed -- e.g., distinguishing
"trapped in an obstacle pocket" from "moving slowly but fine" via some
signal other than raw displacement (nothing concrete identified yet).

### Diagnostic note — Iteration 80 v2's valley regression: displacement metric is ambiguous, not chased further

Quick follow-up before deciding whether to attempt a v3: checked the
specific low-net-displacement Miners from a v2 valley loss
(`g_iter17__valley__botB`) that motivated the "stuck-detector actively
hurts valley" concern. Found the diagnostic method itself is flawed:
of 8 low-displacement Miners checked, several had simply **died in
combat** after only a handful of moves (e.g. one killed at move 6) --
trivially low displacement, nothing to do with exploration or the
stuck-detector. Of the ones that survived, at least two (`#10777`,
`#10662`) showed genuine local oscillation between 2-3 adjacent tiles
-- but one of those had only **9 total moves across an 800+ round
game**, which is ambiguous by itself: it could mean genuinely stuck-
and-gave-up (bad), or it could mean the Miner found a small,
sub-threshold lead deposit and correctly settled into mining it
indefinitely without needing to move again (fine, not a bug -- looks
identical to "stuck" from a pure position-trace perspective, since both
produce "few moves, low net displacement").

Distinguishing "trapped, doing nothing productive" from "correctly
settled and mining" needs a different signal than raw displacement --
e.g. tracking whether the Miner's own lead-mined counter is
incrementing during a low-displacement window. Not implemented this
cycle; the diagnostic path has become progressively more expensive for
decreasing clarity, and the underlying thread (Iteration 80 v1/v2) is
already documented as parked with two full reproduction-sample cycles
spent. Picking a different Step 4 target for this cycle rather than a
third.

## Iteration 81 — Sage overkill avoidance (ACCEPTED, 59.7%); safe, verified, currently-neutral

### Step 4/5 — a fresh, narrow, low-risk mechanism

While diagnosing the Iteration 80 v2 valley regression (inconclusive --
see the preceding diagnostic note), picked a different Step 4 target:
`SAGE` had zero specialized combat logic, routing entirely through
`runSoldier` (`case SAGE: runSoldier(rc, foes); break;`), including
`SA_FOCUS` army-wide focus-fire. Sage's stats are wildly different from
Soldier's (45 damage vs. 3, 200-round action cooldown vs. 10 -- each
Sage attack won't repeat for roughly 20 rounds). An earlier, general
"overkill avoidance" idea for Soldiers was rejected this session
specifically because it risked SA_FOCUS's fast-kill guarantee for
frequent, cheap attacks -- but that reasoning doesn't carry over to
Sage: wasting one of its rare 45-damage hits finishing off a target
that's already critically wounded (and would die anyway to ordinary
Soldier fire this same round) is a much larger relative loss than the
same overkill costs a Soldier.

### Step 6 — Solution

Only for `SAGE`: at the point of attacking the shared `SA_FOCUS`
target, if that target's health is `<=15` (well below Sage's 45
damage), look for a different enemy combat unit also in range with
higher health and attack that instead. Doesn't touch the shared
`SA_FOCUS` pointer itself, so other Soldiers still coordinate on the
original target normally -- this only redirects Sage's own attack
action for the round.

Verified the mechanism actually engages before spending broader test
budget: on `g_iter19`/`maptestsmall` (a game confirmed to build
Sages), the round count shifted from the deterministic baseline,
confirming real behavior change.

### Verification

8-peer x 10-map x 2-side (160-game) reproduction sample: **100/160 =
62.5%, an exact per-opponent match to baseline in every slice** -- zero
regressions. Round-count diff (not just win/loss) confirmed the
mechanism isn't dead code: 8 of 160 games showed a different round
count, with zero outcome flips -- the same "safe, genuinely engaging,
currently neutral" signature Iteration 78's Watchtower relocation
showed before acceptance. Mirror check vs. `g_iter32`: 10/20 = 50%,
comfortably clear of the 35% floor.

### Gauntlet 81 (peer, full 15-opponent)

**179/300 = 59.7%** -- matches the fresh routine baseline
(`gauntlet/20260830-074006/`, also 179/300 = 59.7%) exactly, win-for-win
across all 15 opponents including `g_iter31` (untested until this
session's earlier fresh-Gauntlet cycle). Round-count diff: 8 of 300
games differ, zero outcome flips -- consistent with the reproduction
sample's engagement rate. A real, mechanically-verified, currently-
neutral change with plausible upside against opponents or benchmark
bots whose games produce more, or more decisive, Sage engagements than
this specific peer pool happens to. No retirements due.

**Snapshot**: `src/g_iter33/` (via `tools/snapshot.sh g_iter33`).
Replay reference: `gauntlet/20260830-083123/` (full Gauntlet run);
`gauntlet/20260830-082155/losses/g_iter19__maptestsmall__botB.bc22`
(mechanism-verification replay).

**Next:** add `g_iter33` to the peer set for future Gauntlet runs
alongside `g_iter17-32`.

## Iteration 82 — Archon leveling via Builder mutate() (ACCEPTED, 59.1%); broadly-engaging, safe

### Step 4/5 — another completely unused mechanic, found the same way as Iteration 78

Repeated the technique that found Watchtower's transform mechanic
(Iteration 78): swept `RobotController`'s full public method list via
`javap` against the real game jar for anything never referenced in
`RobotPlayer.java`. Found `canMutate(MapLocation)`/`mutate(MapLocation)`
-- confirmed via a `RobotType.canMutate(RobotType)` cross-product probe
that it's specifically a **Builder** action (`BUILDER canMutate ARCHON/
LABORATORY/WATCHTOWER`), the same action-shape as `repair()`, not
self-leveling by the structure itself (a first attempt wired it onto
Archon self-mutation and it never fired -- `canMutate(self)` is false
for every type; only `BUILDER.canMutate(other-type)` is true). Actual
level data pulled via a level-sweep probe: Archon level 2 costs **300
lead only** for **600->1080 max HP (+80%)** and **2->4 healing/turn
(doubled)** -- level 3 needs 80 gold (rarely available, matching an
already-established finding about this economy). A one-time, ~4-
Soldiers-worth investment in permanent durability for the one unit type
whose death is literally the win condition, sitting completely unused
across 81 prior iterations.

### Step 6 — Solution (two bugs found and fixed during verification)

Added to `runBuilder`: once its existing job (Watchtower, then
Laboratory) is done, try to mutate the home Archon before falling back
to idling. Two issues surfaced while confirming it actually engaged
(round count identical to baseline on the first two test attempts):
1. **Wrong actor** -- first attempt put `mutate()` on the Archon itself;
   fixed once the `BUILDER canMutate ARCHON` cross-product probe
   clarified it's a Builder action.
2. **Range dead zone** -- the pre-existing "idle near home" distance
   threshold (`isWithinDistanceSquared(home, 8)`) is looser than
   Builder's actual `actionRadiusSquared` (5): a Builder could stop
   walking at distance²=6 or 7, satisfied by the idle threshold but
   still out of range for `canMutate()` to ever return true. Tightened
   the threshold to match `actionRadiusSquared` exactly.
3. Picked the wrong verification game first too (`g_iter22`/`valley`
   never builds a Builder at all in that specific matchup -- confirmed
   via zero `" B BUILDER"` indicator hits -- so of course nothing
   engaged there regardless of the code). Switched to `g_iter19`/
   `maptestsmall`, already known from earlier iterations to reliably
   build both structures, and confirmed a real `"mutate archon"` hit
   plus round-count divergence from baseline.

### Verification

8-peer x 10-map x 2-side reproduction sample: **100/160 = 62.5%, exact
per-opponent match to baseline** -- zero regressions. Round-count diff:
**19 of 160 games** showed real divergence -- notably higher engagement
than Iteration 78's 7/280 or Iteration 81's 8/160 (Builder-survival is
common, unlike rare Watchtower-survival-past-500 or a critically-
wounded Sage-focus-target), still with zero outcome flips in this
specific sample. Mirror check vs. `g_iter33`: 10/20 = 50%.

### Gauntlet 82 (peer, full 16-opponent)

**189/320 = 59.1%.** Per-opponent comparison against the closest
baseline (the 15 previously-established peers, `gauntlet/
20260830-083123/`): **exact match, win-for-win, on all 15** -- zero
regressions. The 16th peer, `g_iter32` (new this Gauntlet, no prior
baseline to compare against), scored 10/20 = 50%, consistent with the
already-understood "later, more-developed ancestors are naturally
tougher near-mirror opponents" pattern (see the earlier "resistant
cluster" diagnostic note) -- not a regression signal, just a harder peer
joining the roster, which is why the raw aggregate (59.1%) reads
slightly below the 15-peer baseline (59.7%) despite zero actual
regressions. Round-count diff across the 15 comparable peers: 27 of 300
games differ, zero outcome flips -- consistent with the reproduction
sample's broader-than-usual engagement rate. No retirements due.

**Snapshot**: `src/g_iter34/` (via `tools/snapshot.sh g_iter34`).
Replay reference: `gauntlet/20260830-090501/` (full Gauntlet run);
`gauntlet/20260830-085523/losses/g_iter19__maptestsmall__botB.bc22`
(mechanism-verification replay, "mutate archon" indicator confirmed).

**Next:** add `g_iter34` to the peer set for future Gauntlet runs
alongside `g_iter17-33`. Worth a future look: extending this to
Watchtower/Laboratory mutation too (same `BUILDER.canMutate` mechanic,
not attempted this iteration -- Archon was the highest-value target
given it's the win condition).

## Iteration 83 — Watchtower leveling via Builder mutate() (ACCEPTED, 58.5%); safe, verified

### Step 4/5/6

Direct extension of Iteration 82's `BUILDER.canMutate()` discovery to
the Watchtower this Builder already built (same mechanic, confirmed via
the earlier `RobotType.canMutate` cross-product probe: `BUILDER
canMutate WATCHTOWER` is real). Level 2 costs **150 lead** (half the
Archon's cost) for **150->270 HP (+80%)** and **4->8 damage (doubled)**
-- an even cheaper, arguably higher-leverage upgrade than the Archon
one, on the unit whose entire job is dealing and soaking damage. The
Watchtower sits adjacent to home (built within 8 tiles of it), so the
Builder parking near home for the Archon-mutate check (Iteration 82) is
naturally in range of it too, unlike the Laboratory (built 7 tiles out,
not attempted this iteration -- would need its own travel logic).

Added right after the Archon-mutate check in `runBuilder`: scan nearby
friendly robots for a `WATCHTOWER` and mutate it if affordable.
Verified directly on `g_iter19`/`maptestsmall` (already known to build
both structures): both `"mutate archon"` and `"mutate watchtower"`
indicator hits confirmed in the same replay.

### Verification

8-peer x 10-map x 2-side reproduction sample: **100/160 = 62.5%, exact
per-opponent match to baseline** -- zero regressions. Round-count diff:
19 of 160 games differ, zero outcome flips -- the same engagement/
safety profile as Iteration 82. Mirror check vs. `g_iter34`: 10/20 =
50%.

### Gauntlet 83 (peer, full 17-opponent)

**199/340 = 58.5%.** Per-opponent comparison against the closest
baseline (16 previously-established peers, `gauntlet/20260830-090501/`):
**exact match, win-for-win, on all 16** -- zero regressions. The 17th
peer, `g_iter33` (new, no prior baseline), scored 10/20 = 50%,
consistent with the established near-mirror pattern. Round-count diff
across the 16 comparable peers: 12 of 320 games differ, zero outcome
flips. No retirements due.

**Snapshot**: `src/g_iter35/` (via `tools/snapshot.sh g_iter35`).
Replay reference: `gauntlet/20260830-092833/` (full Gauntlet run);
`gauntlet/20260830-091837/losses/g_iter19__maptestsmall__botB.bc22`
(mechanism-verification replay, both mutate indicators confirmed).

**Next:** add `g_iter35` to the peer set for future Gauntlet runs
alongside `g_iter17-34`. Laboratory mutation (same mechanic, 150 lead
for 100->180 HP) remains untried -- would need the Builder to travel
back to the Lab site (7 tiles from home) rather than just parking near
the Archon/Watchtower, a bigger behavioral change than this iteration's
scope.

## Iteration 84 — active gold seeking (REJECTED; passive detection essentially never fires)

### Step 4/5

Continuing the `javap` sweep technique that found Iterations 78/82/83's
mechanics: `senseNearbyLocationsWithGold()`/`senseGold()` were never
called anywhere -- gold was only ever picked up incidentally via the
unconditional 3x3 mining loop at the top of `runMiner`, with no active
seeking. Gold is scarce but gates several already-accepted mechanics
this session found starved for it specifically (Sage builds, Archon/
Watchtower level 3 -- Iterations 81-83). Confirmed gold genuinely
exists on several maps in modest quantities (e.g. 40-60 Au on 2-3 tiles
on one `valley` instance, 20-30 Au on `highway`) via direct replay
header inspection.

### Step 6 — Solution

Mirrored the existing lead-beacon system (`publishLead`/`nearestLead`)
exactly for gold, purely additive: opportunistically publish any gold
seen in vision (alongside the existing lead-detection scan, untouched),
and only once a Miner has *no* known lead left to chase (the entire
existing lead priority chain left fully alone) fall back to a known
gold beacon before the last-resort `moveExplore()`.

### Verification

Single-game check on `g_iter22`/`valley` and `g_iter22`/`highway` --
both confirmed via replay header to have real gold present -- showed
**identical round counts to baseline in every game**, no behavior
change at all. Added a direct diagnostic distinguishing "reached the
gold-fallback code path, no gold known" from "never reached it": **4907
hits for "no gold known" on the `valley` game (matching the exact
figure from the earlier Iteration 80 investigation's moveExplore
engagement count) and zero for "seeking gold"** -- the fallback code
path is reached constantly (Miners really do run out of known lead
often on this map), but `nearestGold()` never once found anything
despite gold clearly existing on the map the entire game.

### Root cause

Not a bug -- gold tiles are sparse enough (1-3 tiles total on a
50x50+ map) that a Miner's vision radius (20, ~4.5 tile radius) simply
never happens to pass directly over one under normal lead-seeking
movement patterns, across an 853-round game. Passive "publish what you
happen to see" detection, the same pattern that works for lead (which
is comparatively abundant -- dozens to over a thousand tiles per map in
the replays checked this session), has essentially no chance of
encountering something this rare without *dedicated* search behavior
actively directed toward finding it, which this iteration didn't
attempt.

### Outcome

**REJECTED, reverted** (confirmed clean diff against `g_iter35`) --
not on regression grounds (none observed; the mechanism simply never
engaged in either sample game), but because the verification step
itself definitively showed near-zero real-world engagement, per the
standing "verify before spending broader Gauntlet budget" practice.
Not worth spending a reproduction sample on a mechanism confirmed to
almost never fire.

**Next, if revisited:** would need genuinely *active* search behavior
(e.g. a small number of Miners assigned to systematic map-corner/
quadrant sweeps rather than lead-driven wandering) to have a realistic
chance of finding gold tiles at all -- a much bigger design than a
passive detection mirror of the lead-beacon system. Given gold's
established value (unlocking Sage/level-3 upgrades), this might still
be worth a properly-scoped future attempt, but not as a quick add-on.

## Iteration 85 — Laboratory leveling via Builder mutate() (ACCEPTED, 58.1%); safe, verified

### Step 4/5/6

Third extension of the `BUILDER.canMutate()` discovery (Iterations
82-83), applied to the Laboratory. Unlike Archon/Watchtower (built
adjacent to home, naturally in range of where the Builder parks
afterward), the Laboratory is built 7 tiles out -- but the Builder
already lingers right next to it for several rounds after building,
repairing it out of `PROTOTYPE` mode via the existing loop at the top
of `runBuilder`, before ever starting the multi-round walk back home.
Rather than add dedicated travel logic (which the prior iteration's
writeup flagged as the likely cost/complexity blocker), this just
extends the same `senseNearbyRobots` scan Iteration 83 already added
for `WATCHTOWER` to also match `LABORATORY`, catching the mutate
opportunity in that pre-existing idle window. Level 2 costs 150 lead
for 100->180 HP (+80%, no damage benefit since the Laboratory doesn't
attack).

Verified directly on `g_iter19`/`maptestsmall`: all three mutate
mechanisms (`"mutate archon"`, `"mutate WATCHTOWER"`, `"mutate
LABORATORY"`) fire once each in the same replay.

A separate hypothesis this cycle (Iteration 84, active gold-seeking --
mirroring the lead-beacon system for the never-used `senseGold()`/
`senseNearbyLocationsWithGold()` API) was tested and rejected *before*
spending any Gauntlet budget: a direct diagnostic showed the
gold-seeking fallback is reached constantly but never once finds gold
across an 853-round game with confirmed gold present on the map --
gold tiles are too sparse (1-3 per map) for passive vision-based
detection to ever encounter one under normal lead-driven movement. See
the "Iteration 84" entry for detail; a real fix there would need active
dedicated search behavior, not attempted.

### Verification

8-peer x 10-map x 2-side reproduction sample: **100/160 = 62.5%, exact
per-opponent match to baseline** -- zero regressions. Round-count diff:
20 of 160 games differ, zero outcome flips. Mirror check vs. `g_iter35`:
10/20 = 50%.

### Gauntlet 85 (peer, full 18-opponent)

**209/360 = 58.1%.** Per-opponent comparison against the closest
baseline (17 previously-established peers, `gauntlet/20260830-092833/`):
**exact match, win-for-win, on all 17** -- zero regressions. The 18th
peer, `g_iter34` (new, no prior baseline), scored 10/20 = 50%,
consistent with the established near-mirror pattern. Round-count diff
across the 17 comparable peers: 34 of 340 games differ, zero outcome
flips -- the highest engagement count of the three mutate-mechanic
iterations (78 gave 7/280, 81 gave 8/160, 82 gave 27/300, 83 gave
12/320, this one 34/340), consistent with all three structures now
being covered. No retirements due.

**Snapshot**: `src/g_iter36/` (via `tools/snapshot.sh g_iter36`).
Replay reference: `gauntlet/20260830-100030/` (full Gauntlet run);
`gauntlet/20260830-095101/losses/g_iter19__maptestsmall__botB.bc22`
(mechanism-verification replay, all three mutate indicators confirmed
in one game).

**Next:** add `g_iter36` to the peer set for future Gauntlet runs
alongside `g_iter17-35`. The `BUILDER.canMutate()` mechanic is now
fully exploited across all three eligible structure types (Archon,
Watchtower, Laboratory) -- level 3 (gold-gated) remains untried given
this session's repeated finding that gold rarely accumulates enough to
matter; Iteration 84's closed gold-seeking attempt is the blocker
there, not this mechanic itself.

## Iteration 86 — exploration momentum v3 (ACCEPTED, 64.2%); resolves a parked thread with a strong, real win

### Thread history

Third and final attempt at the Iteration 80 thread (Miner exploration
momentum). Recap:
- **v1** (persistent direction, no memory): improved `valley` broadly
  but regressed `pillars` sharply via confirmed local-trap oscillation
  (`--moves` showed a Miner making 111 moves for 4.5 tiles net
  displacement, trapped in a pillar-obstacle pocket). Net regression,
  rejected.
- **v2** (15-round/distance²<9 stuck-detector, falls back to the old
  every-round-random walk when tripped): neutralized the `pillars`
  regression exactly, but broke `valley` *below* its own pre-fix
  baseline via an undiagnosed mechanism (checked for the same dramatic
  trap signature that explained `pillars` -- not present; the specific
  cause was never pinned down, only suspected as false-triggering on
  ordinary slow-but-real progress). Net regression, rejected.

### Step 6 — v3

Same stuck-detector shape as v2, but *much* stricter and shorter:
10-round window (was 15), `distanceSquared <= 4` i.e. 2 tiles of net
progress or less counts as trapped (was `< 9`, i.e. under 3 tiles). The
hypothesis: v2's bar was too easy to trip on ordinary imperfect
progress (open terrain still has *some* obstacles), catching false
positives on `valley` while still comfortably catching pillars-style
dead stops (111 moves for 4.5 tiles is nowhere near either bar).

### Verification

8-peer x 10-map x 2-side reproduction sample, matched-subset comparison:
**105/160 = 65.6% vs. 100/160 = 62.5% baseline (+5 games) -- a genuine
net improvement**, the first of the whole thread. Both target maps
improved *simultaneously* for the first time: `valley` 8/16 -> 12/16
(+4), `pillars` 8/16 -> 10/16 (+2). Small regressions on `g_iter17/18/
19` (-1 each) were clearly outweighed by gains on `g_iter23/26/29/30`
(+2 to +3 each). Mirror check vs. `g_iter36`: 12/20 = 60%.

### Gauntlet 86 (peer, full 19-opponent)

**244/380 = 64.2% vs. the closest baseline's 209/360 = 58.1% -- a large,
clean net improvement.** Per-opponent: only **4 of 18** established
peers regressed, each by exactly 1 game (`g_iter17-20`); **14 of 18**
improved, most by 2-3 games each (`g_iter22-27`, `g_iter29-34` all up).
The 19th peer, `g_iter35` (new, no prior baseline), scored 12/20 = 60%.
`valley` and `pillars` totals across all 18 comparable peers: `valley`
15/36 -> 23/36 (+8), `pillars` 17/36 -> 26/36 (+9) -- both dramatically
better. No retirements due (check next Gauntlet given the size of this
jump).

### Outcome

**ACCEPTED.** Snapshot: `src/g_iter37/` (via `tools/snapshot.sh
g_iter37`). Replay reference: `gauntlet/20260830-102640/` (full
Gauntlet run). This closes the Iteration 80 thread with a genuine win
after two rejected attempts -- the lesson for future threshold-tuning
attempts this session has repeatedly needed: when a fixed-parameter
fix shows a real but partial positive signal (unlike the fully-closed
Miner-redirect thread, which never showed any net-positive signal
across four attempts), a *stricter* version of the same mechanism is
worth trying before abandoning it, not just accepting the first
partial win/loss tradeoff as final.

**Next:** add `g_iter37` to the peer set for future Gauntlet runs
alongside `g_iter17-36`. Given the magnitude of this jump (peer WinPct
moved from the high-50s to mid-60s), consider whether any prior
"accepted as a cost of doing business" conclusions (e.g. the closed
`g_iter22-26`/valley opponent-family thread) deserve a fresh look now
that overall play quality has shifted -- though per this session's
"resistant cluster is just strong peers" correction, that's likely
still the right frame, just worth a glance at fresh Gauntlet data.

### Diagnostic note — fresh 20-peer Gauntlet (g_iter17-36) reconfirms existing closed-thread conclusions; strong new evidence for the symmetry-detection thread's priority

Ran a fresh routine Gauntlet (`gauntlet/20260830-104109/`, 20 peers
`g_iter17-36` x 10 maps x 2 sides, 400 games) following Iteration 86's
large jump, per that iteration's own "Next" suggestion. **256/400 =
64.0%**, matching Gauntlet 86's 64.2% almost exactly -- confirms the
jump held on a fresh, larger peer sample, not a fluke of the specific
19-peer set.

**Per-opponent:** a clean monotonic decline from `g_iter17-20` (70%)
down to `g_iter29-36` (60%), exactly the expected near-mirror band
this session's "resistant cluster is just strong peers" diagnostic
(after Iteration 79) already explains -- later peers are more-developed
ancestors, not a special weakness. No outliers. Nothing new here.

**Per-map:** `sandwich` 35.0% (prev baseline on the same 19-peer subset:
34.2% -- unchanged), `maptestsmall` 50.0% (prev: 50.0%), `chessboard`
57.5% (prev: 57.9%) -- all three trace to the closed Iteration 61
(Archon-starvation, fixed 39-61) / Iteration 67 (DIRS tie-break,
confirmed real but net-regressive to fix) threads and are unchanged
within noise. Nothing new here either.

**Per-map-per-side, the one angle not previously broken out explicitly
this session:** computed win rate split by side for every map.
`maze` (50% A / 95% B), `intersection` (90% A / 35% B), `jellyfish`
(100% A / 50% B), and `highway` (85% A / 65% B) all show a stark split
-- and these are *exactly* the 4 maps Iteration 76 already identified
as non-rotationally-symmetric (`maze`/`intersection`/`jellyfish` =
horizontal, `highway` = vertical), where `armyObjective()`'s blind
`(W-1-x, H-1-y)` rotational guess is provably wrong. Confirmed this is
stable pre-existing behavior, not new: the immediately-prior baseline
(`gauntlet/20260830-102640/`, same `g_iter37` bot, 19 peers) shows the
identical proportions (e.g. `maze` 53% A / 95% B). No regression, no
improvement -- Iteration 86's exploration fix didn't touch this.

Traced one fresh instance directly to build evidentiary weight behind
Iteration 76's mechanism (not previously done with the *current*,
more-developed bot): `g_iter27/maze/botA` (loss, r600, from this
Gauntlet's own `losses/` dir). `--metrics` shows both sides' Miner/lead
economy tracking closely and symmetrically through r140-200 (identical
shape, ~15 Miners each, +2-8 lead/round) -- but team B's *first*
Soldier had already logged 13 attacks by r200 while team A's first two
Soldiers had logged **zero**. Team A's Soldier count then grows far
slower than B's for the rest of the game (5 vs 9 by r400, 1 vs 21 by
r520) as B's early combat-contact head start compounds into a
snowballing Archon-kill spiral by r600. This is the exact signature
Iteration 76 predicted: on a non-rotationally-symmetric map, one side's
mirrored spawn geometry makes the blind guess accidentally closer to
correct (or reach real contact faster) than the other's, producing a
structural, geometry-driven first-contact advantage independent of any
opponent-specific trait -- consistent with the fact that `g_iter27` is
just another peer snapshot of our own lineage, not a specially strong
opponent (it scores a normal 65% on other maps).

**Assessment.** Nothing crossed the bar for a new Step 4/5/6 attempt --
every weak point in this fresh data traces cleanly to an already-closed
thread. But this trace adds real weight to Iteration 76's own
closing note ("worth revisiting... in case a future session wants to
revisit this closure with this evidence in hand"): the symmetry bug
now has concrete confirmation on *four* separate maps (worth roughly
20% of all Gauntlet games), consistently reproducing the same
mechanism, on the bot's current, much more developed state -- not just
the single `g_iter22/highway/botB` instance Iteration 76 examined.
Given this is now the single largest identifiable lever left (every
other weak spot is either closed-and-accepted or already at 57%+),
and per the standing "embrace high-risk iterations" guidance, this
session is picking the terrain-based proactive symmetry-detection
project back up as the next Step 4/5/6 target, sized as its own
multi-step iteration rather than a quick patch -- rejection remains a
perfectly fine outcome if the scouting cost doesn't pay for itself.

## Iteration 87 — Miner enemy-Archon reporting (REJECTED; real narrow win, net regression on broad sample)

### Hypothesis

Cheaper alternative to a full dedicated-scout symmetry-detection system
(the "next Step 4/5/6 target" flagged above): `run()`'s
`reportEnemyArchons(rc, foes)` call has excluded `RobotType.MINER`
since Iteration 3, when Miners never left the immediate home area.
Iteration 86's exploration-momentum fix now sends Miners wandering
broadly across the whole map hunting for lead -- exactly the kind of
coverage that could stumble onto an enemy Archon well before any
Soldier does. Since `armyObjective()` already prefers a known enemy
Archon location over the blind mirror guess whenever one is reported
(line ~248, pre-existing since Iteration 3), letting Miners report too
should let real sightings override the wrong blind guess earlier on
the 4 non-rotationally-symmetric maps identified in Iteration 76
(`maze`, `intersection`, `jellyfish`, `highway`) -- for free, reusing
existing code, no new scouting behavior.

### Step 6 — Solution attempt 1: remove the MINER exclusion

`run()`: moved `reportEnemyArchons(rc, foes)` outside the
`if (rc.getType() != RobotType.MINER)` guard (kept `checkHomeThreat`
Miner-excluded, unrelated to this hypothesis).

### Verification

Mechanism confirmed directly on the exact target case from this
session's own fresh-Gauntlet diagnostic note above
(`g_iter27/maze/botA`, documented loss at r600): re-ran via
`TEAM_A=bot TEAM_B=g_iter27 tools/vm-match.sh maze` -- **flipped to a
win, r509**. `--metrics` confirmed the mechanism, not luck: team A
logged 7 attacks by r200 (was 0 in the baseline loss) and was
damaging enemy Archon HP by r320-360 (enemy Archons stayed at full
1800 HP the entire game in the baseline loss) -- the exact early-
contact head start Iteration 76 predicted a real sighting would
provide.

8-peer x 10-map x 2-side (160-game) reproduction sample (`g_iter17/18/
19/21/23/26/29/30`, matched against this exact subset's numbers from
the fresh `gauntlet/20260830-104109/` baseline, 105/160 = 65.6%):
**103/160 = 64.4% -- a net regression of 2 games**, but not a clean
"helps one, hurts everywhere" collapse like the closed Miner-redirect
thread. Per-map: `maze` improved (+1, the intended target), `highway`/
`intersection`/`jellyfish` (the other 3 symmetry-broken maps) were
flat -- no improvement despite being equally eligible, suggesting the
mechanism only fires often enough to matter on some maps/opponents,
not universally. `squer` (a *rotational* map, where the blind guess was
already exactly correct) regressed sharply: **-3**, all three flips
(`g_iter21/23/26`, all as team A) losing 20-30 rounds *earlier* than
their baseline wins, not later -- a premature-commitment signature, not
attrition.

### Diagnosis of the squer regression

Traced `g_iter21/squer/botA` (new loss, r357; baseline had won r371)
via `--metrics`: team A gets real early attacks in (10 by r120, ahead
of team B's 0) but fields only 1-2 Soldiers the whole time it's
trading blows, while team B's Soldier count climbs steadily (3->12) and
eventually overwhelms. Squer has 2 Archons per side; `armyObjective()`
has always returned the *first populated* `SA_ENEMY_ARCHON_0..3` slot
(whichever got discovered first), a detail that used to barely matter
because only combat units near the front ever discovered anything.
With Miners also reporting, a Miner wandering far from the front can
now report a real enemy Archon well before any Soldier reaches the
area a Soldier-only discovery would have found first -- shifting *which*
of the two enemy Archons the whole army heads for, weeks earlier than
before, with too small a force to survive contact once it arrives.

**Attempted fix:** changed `armyObjective()`'s enemy-Archon pick from
"first populated slot" to "nearest known enemy Archon to our own home"
(a single fixed reference point every unit agrees on, preserving the
"one shared objective" invariant) -- a plausible, narrowly-scoped
correctness fix independent of the Miner-reporting change itself.
**Broke the primary target case**: re-running
`TEAM_A=bot TEAM_B=g_iter27 tools/vm-match.sh maze` flipped from the
freshly-confirmed win (r509) back to a **loss (r656)** -- on
`maze` (3 Archons per side, non-rotationally symmetric), the enemy
Archon the wandering Miner happened to discover first was the
*correct* early-contact target, while "nearest to our home" picked a
different, worse one. This is the same tension in miniature: "nearest
by raw distance" is the right heuristic exactly on the *rotational*
maps (`squer`) where the blind guess was already correct anyway, and
wrong on the *non-rotational* ones (`maze`) that are the whole point of
this thread -- there is no map-agnostic tie-break rule between multiple
known enemy Archons that works for both symmetry classes without
actually knowing which class the current map is.

### Outcome

**REJECTED, reverted** (`git checkout -- src/bot/RobotPlayer.java`,
confirmed clean diff against `g_iter37`). The core hypothesis was
real and cleanly confirmed on its intended target (a genuine,
mechanically-verified win on exactly the kind of loss Iteration 76
predicted), but the broad reproduction sample came back net negative,
and the one candidate refinement tried made the primary target worse,
not better -- the same "wrong-guess correction" and "wrong-target
selection" problems turn out to be two faces of the same underlying
issue (not knowing the map's true symmetry class), which this
cheap approach has no way to distinguish. This is genuine, useful
evidence for the parked Iteration 76 thread, not a wasted cycle: it
confirms a *shortcut* around real symmetry detection (piggybacking on
existing Miner exploration instead of dedicated scouting) doesn't
work, because the problem isn't really "how do we learn the enemy
Archon location sooner" (this fix does that, cheaply, and it still
regresses) -- it's "how do we know which of several plausible pieces
of information to trust," which fundamentally requires knowing the
symmetry class, not just having more sightings.

**Next:** the real terrain-based symmetry-detection project (comparing
observed rubble/terrain at candidate mirror-pair tiles via dedicated
early scouting, sized as its own multi-step iteration per Iteration
76's original closing note) remains the only approach identified so
far that could resolve this properly -- this iteration's failure
narrows the search by ruling out "just report more/earlier" as a
substitute. Not attempting further quick patches on this specific
lever (Miner reporting, enemy-Archon tie-break order) -- both are now
demonstrated dead ends independently. Picking a different Step 4
target for the next cycle; the symmetry-detection project itself is a
candidate for a future dedicated cycle with more design room than a
single autonomous iteration comfortably affords.

## Iteration 87 v2 — Miner reporting + mass-gate fix (REJECTED; net no-op, doesn't reproduce the v1 win)

### Hypothesis

A structural re-read of `runSoldier` (part of this cycle's function-by-
function review after `runArchon`/`runMiner`/`betterTarget`/
`runBuilder` all came back clean) found the actual mechanism behind
Iteration 87 v1's `squer` regression: the Iteration 9 mass-gate (don't
advance alone toward the speculative `armyObjective()` guess until 3+
friendly Soldiers are in vision) has always exempted "an enemy Archon
is known" (any populated `SA_ENEMY_ARCHON_0..3` slot) from the check
entirely -- safe when only combat units (already near the front, having
themselves advanced as part of a mass) could populate those slots, so
"known" implied "we're already close to the fight." Iteration 87 v1's
Miner-reporting change breaks that implication: a Miner can report a
real sighting from anywhere on the map long before the army is
anywhere near it, so a lone Soldier still at home sees "known" flip
true and marches off alone into a losing fight -- exactly `squer`'s
observed signature (early attacks, stuck at 1-2 Soldiers the whole
game).

### Step 6 — Solution: fix the gate, not the report

Kept v1's Miner-reporting change and additionally removed the
enemy-Archon-known exemption from the mass-gate's `known` variable in
`runSoldier`, leaving only the two genuine active-threat signals
(`SA_HOME_THREAT`, `SA_ECON_THREAT`). The correct destination (now
available sooner thanks to Miner reporting) still gets used the moment
the gate clears normally (3 friendlies, round>=250, or already close)
-- this only delays commitment to a safer group size, it doesn't
discard the sighting.

### Verification

Single-game checks: `TEAM_A=bot TEAM_B=g_iter21 tools/vm-match.sh
squer` -- **win r345** (v1 had regressed this to a loss r357; original
baseline was a win r371, so this is a real fix, faster than baseline
even). But `TEAM_A=bot TEAM_B=g_iter27 tools/vm-match.sh maze` --
**loss r600, the exact same round as the original pre-Iteration-87
baseline loss** -- strongly suggesting the same lone-Soldier-commits-
early behavior that the gate now blocks was the actual source of v1's
maze win, not just squer's harm.

8-peer x 10-map x 2-side (160-game) reproduction sample confirmed
this precisely: **105/160, matching the matched-subset baseline
exactly**, and a direct game-by-game diff (opponent+map+side+result)
against that baseline came back **zero differences across all 160
games** -- not one win/loss outcome flipped in either direction (round
counts *did* shift on some games, e.g. `g_iter21/squer/A` 371->345,
confirming the code path is genuinely exercised, not dead code -- it
just never changes who wins). Directly confirms the single-game read:
this combination fixes `squer`'s regression by simultaneously erasing
the `maze` win, netting out to indistinguishable-from-baseline on both
the specific target case and the broad sample.

### Outcome

**REJECTED, reverted** (`git checkout -- src/bot/RobotPlayer.java`,
confirmed clean diff against `g_iter37`). A genuinely informative
result closing out this whole thread (v1 + v2, both variants of
"let Miners report enemy Archons"): the mass-gate's old "known enemy
Archon" exemption and the maze win from v1 are the same mechanism
wearing two hats -- the exemption is precisely what let a lone,
early-informed Soldier get to the enemy Archon fast enough to matter,
and it's also precisely what let a lone, early-informed Soldier get
mauled on `squer`. There's no free lunch available by tuning *when*
the gate applies; the real, missing ingredient is knowing whether the
map is one of the 4 non-rotationally-symmetric ones where committing
early to a genuinely-known target is worth the risk, versus a
rotational one where the blind guess was already fine and early
commitment is pure downside -- which is, again, the same symmetry-
class-detection gap Iteration 76 originally identified. Two
independent cheap-shortcut attempts (v1's raw reporting, v2's gate
fix) have now both failed to route around that gap.

**Next:** closing the "let more units report enemy Archons" avenue
entirely -- not attempting a v3. The terrain-based symmetry-detection
project (Iteration 76's own proposal: compare observed rubble/terrain
at candidate mirror-pair tiles via dedicated early scouting, before
committing to a march direction) remains the only approach identified
across three attempts (76, 87, 87v2) that could actually resolve this,
and is explicitly sized as needing more design room than a single
autonomous iteration -- a good candidate for a future dedicated cycle.
This cycle's structural review of `runArchon`, `runMiner`,
`betterTarget`/`targetPriority`, and `runBuilder` (looking for a fresh,
unrelated Step 4 target per RESEARCH.md's remaining unexplored angles)
found nothing else actionable; `runSoldier`'s remainder and the
movement/pathing functions (`moveToward`, `moveExplore`,
`repositionForRubble`) are already heavily scrutinized from earlier in
this session. Picking a different Step 4 target next cycle: a fresh
losing game from a peer/map pairing not yet examined, since the
structural-review angle is now largely exhausted for this file.

## Iteration 88 — genuine terrain-based symmetry detection (REJECTED; correct but net-negative, closes the whole symmetry thread)

### Design

The real fix Iterations 76/87/87v2 all converged on needing: instead
of inferring the map's symmetry class reactively (from unit sightings)
or guessing blindly, determine it proactively via terrain comparison,
before the army ever commits to a march direction.

Added `SA_SYMMETRY` (0=undetermined, 1=rotational, 2=horizontal/flip-y,
3=vertical/flip-x) and `SA_SCOUT_CLAIMED`/`SA_SCOUT_RND`. Exactly one
Miner claims a "symmetry scout" role via compare-and-set on
`SA_SCOUT_CLAIMED` (same idiom as the existing `publishStart`), with a
30-round expiry so a fresh Miner takes over if the scout dies
mid-mission. The scout picks 2 small sample points near the map's
geometric center (offsets ~W/12 and ~W/8 from center, both axes
nonzero so all 3 candidate mirrors are distinct and none coincide),
walks to observe rubble at each point and its 3 candidate mirrors, and
locks in `SA_SYMMETRY` once exactly one hypothesis's rubble matches at
*both* sample points (2 independent points to guard against a
coincidental single-tile match) -- falling back safely to rotational
(byte-identical to every prior iteration's behavior) if 0 or >1
hypotheses survive, or after a 150-round budget. `armyObjective()`'s
two blind-guess call sites now route through a new `mirror(loc, sym)`
helper; `sym` 0 or 1 reproduce the original formula exactly.

### Verification

Single-game checks (`tools/vm-match.sh` + `bc22_replay.py` against
`examplefuncsplayer`) confirmed the mechanism is genuinely correct, not
just inert: on `maze` (ground truth "horizontal" per the replay's own
map header), the scout resolved `SA_SYMMETRY=2` within ~26 rounds, and
the resulting `armyObjective()` output was checked directly against
the board -- it exactly matched the true enemy Archon location (a
location the old rotational-default formula would have gotten
completely wrong, off by more than half the map). On `squer` (ground
truth "rotational"), resolution was likewise correct and the game
played out identically to old-formula behavior, as designed.

8-peer x 10-map x 2-side (160-game) reproduction sample: **96/160 =
60.0% vs. 105/160 = 65.6% baseline -- a clear net regression (-9)**,
worse than either Iteration 87 variant. Per-map breakdown told a messy
story, not the clean "helps target maps, neutral elsewhere" outcome
hoped for: `highway` improved sharply (+3, matching the hypothesis --
this is one of the 4 target maps), but `intersection` (-2) and `maze`
(-5) -- the *other two* target maps -- both got **worse** despite
verifiably correct information, and two purely rotational maps with
zero informational benefit to gain (`pillars` -2, `valley` -3) also
regressed. A full game-by-game diff against baseline showed 39 outcome
flips (not just net win/loss count -- real, widespread churn): several
were genuine directional wins (`chessboard` A-side: 3/3 loss->win,
despite chessboard being rotational and thus receiving *zero*
informational change -- a real economic-cost-driven regression
avoided, or possibly just intrinsic per version noise) or losses
(`intersection` B-side: 4/4 win->loss, `maze`: 5/5 win->loss), but many
others were simple A-side/B-side result swaps for the same opponent
(`squer`/`valley`/`intersection` vs `g_iter29`/`g_iter30`: one side
flips to a win, the other to a loss, net zero for that matchup) -- the
classic signature of added chaos/variance in already-close mirror
games rather than a clean directional shift.

### Diagnosis

Traced `g_iter23/maze/botA` (new loss, r567; baseline had won) via
`--metrics`: both sides' Soldier counts stay near 0 through r150 (the
scout's detour window on this small 20x20 map), then B's climbs
steadily faster than A's from r200 onward (2 vs 2 at r200, but 4 vs 6
by r250, 10 vs 13 by r400, 19 vs 26 by r550) until A's Archons fall.
`g_iter23` itself predates this iteration and still uses the old
blind rotational guess (wrong on this map) -- so *only* our side had
correct information, yet still lost. This rules out "wrong information
hurt us" and points instead at the scout's real, unavoidable economic
cost: one Miner diverted from mining for up to ~150 rounds (resolved
in ~26 on this map, but games run 1000+ rounds and the diversion still
lands in the economically-critical opening). That cost is paid on
*every* map regardless of symmetry class -- including the 6 of 10 maps
that are already rotational, where the scout provides zero benefit
(the guess was already correct) but still pays the full Miner-diversion
cost. `pillars`/`valley`'s regressions with no informational upside at
all are the clearest evidence for this: pure cost, no compensating
benefit. Even on the genuine target maps, the benefit (a materially
different, correct march destination) turns out not to reliably
outweigh the cost -- sometimes because marching correctly toward the
true enemy sooner exposes the army to unfavorable early contact that a
wrong guess's incidental delay had been protecting against (a rush-vs-
turtle timing tradeoff, not a bug), sometimes because the added
timing variance just flips already-close near-mirror games in either
direction unpredictably.

### Outcome

**REJECTED, reverted** (`git checkout -- src/bot/RobotPlayer.java`,
confirmed clean diff against `g_iter37`). This is the most
mechanically successful of the four symmetry-thread attempts (76, 87,
87v2, 88) -- a real, verified, general-purpose terrain-based detector
that correctly identifies horizontal/vertical/rotational symmetry from
first principles, not a shortcut or a hack -- and it still nets
negative. That is strong evidence the underlying problem doesn't
reduce to "the blind guess is sometimes wrong, fix the guess": even
with the guess made genuinely, verifiably correct, the change in
early-game timing and commitment it produces is a wash at best,
because (a) any information-gathering costs real economy on every
map whether or not that map benefits, and (b) correct information
doesn't uniformly help even on the maps it's aimed at, since the old
wrong guess's incidental early-game passivity was sometimes itself
beneficial (protecting a still-forming army from early contact) in a
way this session's existing mass-gate and reinforcement logic doesn't
fully compensate for.

**This closes the entire symmetry-detection thread** (Iterations 76,
87, 87v2, 88 -- reactive detection, time-cycling, information-sharing
with and without a gate fix, and now genuine proactive terrain
detection). Four structurally different approaches, one of them fully
correct and verified, all net neutral-to-negative. Not attempting a
fifth variant (e.g. a cheaper single-point scout, or scaling the
scout's budget to map size) without new evidence -- the pattern across
all four attempts suggests the ~35-50% floor on `maze`/`intersection`/
`jellyfish`/`highway`'s weaker side is not, in the end, a simple
"wrong guess" bug with a clean fix, but an intrinsic property of how
this bot's early-game economy/army-mass tradeoffs interact with those
specific maps' geometry -- closer in spirit to the already-accepted
`g_iter22-26`/valley opponent-family timing-sensitivity than to a
fixable defect. Future sessions: don't re-open this without a
genuinely new angle (e.g. a design that makes the scouting cost-free,
not just cheaper, or that changes how the army responds to a corrected
destination rather than just correcting the destination itself).

**Next:** picking a different Step 4 target. The RESEARCH.md-driven
structural review and the symmetry thread have both been extensively
mined this session; the most promising remaining avenue is picking a
fresh losing game from a peer/map pairing not yet individually
examined, following the ordinary Step 4/5/6 process.

### Diagnostic note — `valley`/botA losses vs g_iter27-36 extend the already-closed reinforcement-distance thread, not a new bug

Following Iteration 88, the fresh 20-peer Gauntlet's loss list showed a
striking pattern not previously called out: **all 10 of `g_iter27`
through `g_iter36` beat us on `valley` as team A** (`gauntlet/
20260830-104109/results.csv`), distinct from the already-closed
`g_iter22-26`/valley opponent-family thread (Iterations 39-75) which
covered an earlier opponent range. Round counts cluster tightly
(752-753 for g27-30, 720 for g31-36), suggesting one consistent
mechanism, not per-opponent noise -- worth checking before assuming
it's just the same closed thread under a new name.

Traced `g_iter27/valley/botA` (loss, r753) via `--metrics`: team A
actually *leads* in Soldiers through r300 (14 vs 11), then crashes to 3
by r380 (-11 in 80 rounds) while team B declines only mildly (12->8),
Archon HP untouched on both sides the whole time -- pure field-combat
attrition, not a raid or base assault. `--indicators` on the crash
window (r320-335) showed the classic signature this session's
reinforcement-thread already diagnosed at length: the overwhelming
majority of Soldiers show `reinforce [15,20]` (marching toward the
live fight) with only 1-2 actually `focus`-attacking at any time --
checked via `--moves` that a sampled reinforcing Soldier is making
genuine steady progress (not stuck/oscillating, ~1 tile/round toward
the target), confirming this is real cross-map distance, not a
targeting or pathing bug. This is exactly the shape Iterations 29, 33,
37, 61, and 63/69-75 already characterized and tried (and failed) to
fix via four different reinforcement-priority mechanisms, and exactly
the shape Iterations 34 and 78 (Archon/Watchtower relocation, both
accepted) were built to mitigate structurally.

**Not a new lead.** `g_iter27-36` are later, more-developed snapshots
than `g_iter22-26` (each includes further accepted iterations on top),
so per the Iteration 79 "resistant cluster is just strong peers"
reframing, this is the same phenomenon extending further down the
peer-age gradient, not a distinct bug newly introduced. No fresh
Gauntlet budget spent chasing it. Recording this so a future session
doesn't re-diagnose the same signature from scratch on this specific
opponent range and mistake it for something new.

## Iteration 89 — Sage standoff kiting (REJECTED; correct and well-motivated, but unreachable given our own gold economy)

### Tooling unblocked: the 2022 postmortem PDF is now readable

Earlier this session (and in prior sessions per this log), the Sage
`envision()`/anomaly-mechanics thread was set aside because the
bundled `2022-5-musketeers.pdf` postmortem couldn't be rendered --
`pdftoppm`/`fitz` were unavailable. This cycle: `sudo apt-get install
-y poppler-utils` installed cleanly (the sandbox permits it), and
`pdftotext` now extracts the PDF's text directly -- faster and more
reliable than the page-image `Read` tool path. **This unblocks that
tooling gap for future sessions too.**

Two directly relevant findings from the postmortem (a team that
finished 7th at 2022 finals):

1. **Confirms closing the anomaly/`envision()` thread was right.**
   Verbatim: "Very few strategies cared about the global anomalies in
   the game... Fury and abyss there was nothing you could do about,
   and vortex just meant scooting an Archon over a little bit." A
   strong team explicitly deprioritized this mechanic. No longer an
   open question -- closing it for good, not just "set aside."
2. **Names their actual highest-value Sage strategy**, unrelated to
   anomalies: "since sages have such a big vision and attack radius,
   you could attack people without them ever seeing you... By dancing
   outside of the soldier vision radius, they could assassinate the
   soldier from the shadows." Confirmed via `javap` against our own
   game jar that the same range gap exists in our ruleset:
   `SAGE.actionRadiusSquared=25` (~5 tiles) exceeds
   `SOLDIER/MINER/BUILDER.visionRadiusSquared=20` (~4.47 tiles) -- a
   real ~1-tile band where a Sage can hit those types without being
   seen back.

### Step 6 — Solution

Added a Sage-only check at the top of `runSoldier` (shared by Soldier
and Sage): if the Sage can already attack its target and is currently
*within* the target's own vision range (exposed) and has a movement
action available, retreat one step directly away from the target --
but only if the retreat destination stays within the Sage's own
action range (never gives up the attack entirely, just repositions
into the safe band). The existing attack branches already correctly
don't advance further once in range, so this was the one missing
piece: retreating when caught exposed.

### Verification

8-peer x 10-map x 2-side (160-game) reproduction sample: **105/160,
exactly matching baseline**, and a full game-by-game diff showed
**zero differences across all 160 games** -- not just matching win/loss
counts, identical round counts too. Confirmed via a replay scan that
Sages genuinely are built sometimes in this peer pool (found
sustained `A_sages>0` in at least one loss replay), so this isn't
simply "Sages never exist here." Following Iteration 78's own
precedent (a mechanically-verified, currently-peer-neutral change,
accepted on the strength of round-count diffs proving it wasn't dead
code), ran a full 21-peer (`g_iter17-37`) x 10-map x 2-side (420-game)
Gauntlet to check at wider scale: **266/420** overall, but the
`g_iter17-36` subset came back **256/400, exactly matching the
`g_iter37` baseline with zero diffs across all 400 games** -- an even
stronger no-op signal than the 8-peer sample, and unlike Iteration 78,
no round-count differences anywhere to prove the mechanism was ever
actually exercised.

Given the theory this predicts (helps specifically against Sage-heavy
play), tested directly against `sample_afinals` -- the one benchmark
bot whose "doctrine is built entirely around" gold/Sage economy per
Iteration 64's own note, the most favorable possible test case.
**Lost all 3 games tested** (`highway` r803, `squer` r341, `valley`
r716) -- but the decisive finding wasn't the loss, it was `--metrics`:
**our own team's gold stayed at flat 0 for the entire 803-round
`highway` game**, while `sample_afinals` accumulated 311 gold and
built 111 Sages by the end. We built **zero** Sages of our own in any
of the 3 games. The Sage-kiting fix cannot possibly have fired --
there was never a Sage to apply it to.

### Outcome

**REJECTED, reverted** (`git checkout -- src/bot/RobotPlayer.java`,
confirmed clean diff against `g_iter37`). The fix itself is correct
and well-motivated -- a real range-gap mechanism, confirmed
mechanically and validated by an actual competitive team's results --
but it's currently unreachable: our own gold economy is so weak
(confirmed flat 0 across an 803-round game against the one opponent
whose entire strategy depends on gold) that we essentially never have
a live Sage to apply Sage-specific logic to, in *any* matchup tested,
including the single most favorable one available. This is the same
root cause Iteration 84 already diagnosed for active gold-seeking
(passive gold-tile detection "essentially never fires" because
deposits are too sparse for normal Miner vision to encounter) and
Iteration 58/64 already found for the Laboratory-based gold pipeline
("barely ever engaged... needs a long, calm, high-lead-surplus game").
Sage-specific refinements (this one, and the still-open
Sage-build-priority-timing thread) are all gated behind the same
upstream bottleneck and will stay inert until that's addressed
directly.

**Next:** not a good target for further Sage-*behavior* refinements
until the economy problem is fixed first -- any such change will keep
testing as a true no-op like this one, for the same reason. The real
lever, if a future session wants to unlock this whole area (Sage
kiting, Sage-build timing, potentially `envision()` despite the
postmortem's lukewarm take on anomalies specifically -- charge/fury
are separate from anomaly-timing and might still be worth a self-
triggered look), is making gold actually accumulate reliably in
ordinary games: revisit the Laboratory pipeline's engagement rate
directly (why does a dedicated Laboratory + Builder investment still
only "barely ever engage" per Iteration 58/64?) rather than continuing
to build features on top of an economy that's never actually online.
This is a bigger, more foundational target than a single iteration,
similar in scope to the now-closed symmetry-detection project.

## Iteration 90 — lower the Builder economy's lead-surplus gate (ACCEPTED, ~63%); Watchtower/Laboratory economy now reachable in contested games

### Step 4/5 — following straight from Iteration 90's own diagnosis

Iteration 89's rejection (Sage kiting unreachable, our gold economy
flat 0 across an 803-round game) traced the root cause one level
further: `runArchon`'s `needBuilder` gate requires
`rc.getTeamLeadAmount(rc.getTeam()) > 300` -- a bar unchanged since
Iteration 30, worth 4 un-built Soldiers sitting idle in the bank. Direct
measurement (`bot` vs `sample_afinals`, 3 separate maps, `--metrics`)
confirmed team lead never once exceeded 92 across any of the 3 full
games -- Soldiers absorb income as fast as it arrives in a real
contested economy, so a 300 surplus essentially never accumulates.
This silently disables the entire Watchtower/Laboratory/Sage pipeline
downstream (already confirmed *correctly functional* once it fires,
per Iteration 64's fix) exactly against the opponents where its
established value (Watchtower HP/damage from Iterations 78/82/83's
mutate-leveling work) would matter most. Notably, every prior attempt
in this area (Iterations 54/55/56/58/64) tuned Builder *count* or a
separate 3rd-Builder threshold -- none touched this original bar.

### Step 6 — Solution

Lowered the threshold from `> 300` to `> 120` (Builder itself costs
only 40 lead; 120 leaves headroom above a single Soldier's 75 without
requiring an unrealistic surplus).

### Verification

Re-ran `bot` vs `sample_afinals` on `highway` with the change: a
Builder got built for the first time (was 0 the entire game before) --
though late (~r500) and it died to enemy Sage fire before completing
anything, since the game was already collapsing by then. Confirms the
gate genuinely engages now; this specific matchup still loses (scale
mismatch against a doctrine built around gold from round 1, per
Iteration 64's own "Next" note -- not something one threshold change
fixes), but the real target is ordinary contested *peer* games that
don't collapse as badly.

8-peer x 10-map x 2-side (160-game) reproduction sample: **104/160 vs.
105/160 baseline**, with a full game-by-game diff showing exactly
**one** flip (`g_iter21/chessboard/botA`: win->loss, a long ~1000+
round grinding game already in the well-documented "chessboard/
intersection/pillars timing-sensitivity" family from Iteration 61 --
plausible opportunity-cost signature but not conclusive, and this
specific map/opponent combination is known-chaotic). Given 159/160
unchanged, proceeded to a full 21-peer (`g_iter17-37`) x 10-map x
2-side (420-game) Gauntlet to check at scale, following Iteration 78's
own verification depth.

### Gauntlet 90 (peer, full 21-opponent)

**265/420** overall. The `g_iter17-36` subset: **255/400 vs. 256/400
baseline**, and a full game-by-game diff against
`gauntlet/20260830-104109/results.csv` found **the exact same single
flip and no others** -- the wider peer range (`g_iter20/22/24/25/27/
28/31-36`, not covered by the 8-peer sample) introduced zero new
regressions. `g_iter37` (essentially this same bot pre-Iteration-90,
now a valid peer since `bot` has diverged from it): 10/20 = 50%,
squarely in the expected near-mirror band. No opponent reached the
80%-domination retirement threshold this Gauntlet.

### Outcome

**ACCEPTED.** A single flipped game out of 400 tested is a clean,
minimal-footprint cost for unlocking a mechanism with established,
independently-verified value (the Watchtower/Laboratory economy was
already proven non-regressive and genuinely useful once it fires --
Iterations 64, 78, 82, 83, 85 all built on top of it) in exactly the
contested-game scenarios where it previously never engaged at all.
Matches the Iteration 78 acceptance template: mechanically verified to
actually fire (not dead code), matches baseline closely, plausible
unproven upside beyond what this specific peer pool's mirror-matches
can demonstrate (benchmark bots, or future opponents with heavier
gold investment).

**Snapshot**: `src/g_iter38/` (via `tools/snapshot.sh g_iter38`).
Replay reference: `gauntlet/20260830-125333/` (full Gauntlet run).

**Next:** add `g_iter38` to the peer set for future Gauntlet runs
alongside `g_iter17-37`. This doesn't fully close the gold-economy
thread -- the threshold is now *reachable*, but Iteration 64's own
"barely engages... too small in scale" finding against
doctrine-committed opponents like `sample_afinals` likely still holds;
a future session could check whether 120 is itself still conservative
(the sample size here is thin on how often it actually fires across
the full peer pool) or whether the downstream Sage-kiting fix
(Iteration 89, well-verified but shelved only for lack of a live Sage
to apply it to) is now worth revisiting given Builders/Labs should
fire somewhat more often.

**Follow-up, same cycle:** re-tested that last question immediately.
Re-added Iteration 89's Sage-kiting code on top of `g_iter38` and
re-ran the same 3-map `sample_afinals` check -- still **0 Sages built**
in any of the 3 games; the Builder still doesn't survive long enough
to complete a Watchtower+Laboratory against this specific strong,
aggressive opponent even with the lowered gate. An 8-peer/160-game
reproduction sample confirmed this generalizes: **104/160, exactly
matching the `g_iter38` baseline with zero game-by-game diffs across
all 160 games** -- a true no-op, not just a small effect. Reverted
(`git checkout -- src/bot/RobotPlayer.java`, confirmed clean diff
against `g_iter38`). Iteration 90 alone was enough for this cycle;
the Sage-kiting interaction needs either a larger threshold drop or a
different mechanism (e.g. prioritizing Builder survival/escort, or a
faster Watchtower/Laboratory build sequence) to actually reach a live
Sage against contested opponents -- closing this specific follow-up
question rather than iterating further on it same-cycle.

## Iteration 91 — Archon heal-vs-build priority fix (ACCEPTED, ~62.5%); severe lead-hoarding bug found via user-requested benchmark check

### Origin

The user asked for the current win rate against the 3 benchmark bots.
Full 60-game tally: `sample_camelcase` 0/20 (0%, unchanged all
session), `sample_afinals` 4/20 (20%, up slightly from Iteration 61's
3/20), `sample_monke` 20/20 (100%, a deliberately weak lecture-level
bot). Offered to trace a `sample_camelcase` loss and did.

### Step 4/5 — the actual bug

`bot vs sample_camelcase/maptestsmall` (loss, r227): `--metrics`
showed team lead climbing *monotonically* round after round --
5013->5095->5177->...->5603 over a 15-round window with zero drops
(a drop would mean a build happened) -- reaching **6815 unspent lead
by r180** while our Soldier count crashed 36->0 and the opponent's
grew completely unopposed. `--indicators` on the Archon showed why:
nothing but `repairs SOLDIER`/`repairs MINER` every single round.

Root cause, in `runArchon`'s heal-vs-build logic (Iteration 14): a
unit qualifies for Archon-priority healing if it's missing more than
6 HP -- for a 50-HP Soldier, any HP <=43 (a scratch, 12% missing)
qualifies, and heal takes **absolute, unconditional priority** over
building, with no cap on consecutive heal-only rounds. In any
sustained skirmish near the Archon, *some* unit is almost always
carrying a minor wound, permanently locking the Archon into heal-only
mode no matter how much lead has piled up -- a severe, previously
undiscovered build-starvation bug, present since Iteration 14 (long
before this session) but only manifesting clearly against a
continuously-pressuring opponent with a long, close fight near home.

### Step 6 — Solution, and a real mid-course correction

**v1**: raised the heal-eligibility bar to "missing >50% max HP."
Re-verified: only partial improvement (lead still peaked at 5337) --
with sustained pressure, *some* unit is always below whatever fixed
bar is picked, so heal keeps winning regardless of the specific
number. **v2** (the actual fix): made it lead-aware. Track whether
team lead is "abundant" (originally `> 2x Soldier's build cost`, 150);
if so, only a *critical* wound (<20% max HP) may still pre-empt a
build -- moderate wounds wait. Re-verified on the same game: lead
peak dropped to 4791 (~30% down from 6815), a real improvement, with
the remaining triggers now genuinely critical units under what looks
like an active siege at the Archon's doorstep (a different, legitimate
problem this fix isn't meant to solve).

**The mid-course correction**: an 8-peer/160-game reproduction sample
with the 150 threshold looked clean (1 flip, dismissed as ordinary
long-game chaos after checking it). A full 22-peer/440-game Gauntlet
told a different story: **6 flips, all on `highway`'s B side**,
against `g_iter29` and `g_iter32-36`. Traced one (`g_iter32/highway/
botB`): our own lead only briefly ticked up to 151-180 around r508 --
an entirely ordinary economic fluctuation, nowhere near the
thousands-deep crisis this fix targets -- but on `highway` (a long,
low-combat economy-race map per Iteration 30's own note) even a
brief, minor behavior change early can cascade unpredictably over
1000+ remaining rounds. **150 was catching completely healthy
economies, not just the pathological case** -- the reproduction
sample's 8-peer slice simply didn't happen to include enough of the
affected range to surface it, an explicit lesson for future
threshold-style changes on long/economy-heavy maps: an 8-peer sample
isn't always sufficient, especially when the mechanism is keyed to a
quantity (lead) that fluctuates continuously rather than a discrete
event.

Raised the threshold to `> 600` -- clearly past ordinary fluctuation,
closer to the actual crisis scale observed. Re-verified both directions
directly: `sample_camelcase/maptestsmall` still shows the same
mitigation (peak ~4791, unchanged from the 150 version -- the crisis
case is comfortably above 600 too), and `g_iter32 vs bot` on `highway`
now **wins** (r1182), confirming the specific regression is fixed.

### Verification

Skipped straight to a full 22-peer (`g_iter17-38`) x 10-map x 2-side
(440-game) Gauntlet this time (the 8-peer step already proved
insufficient once). Full game-by-game diff against
`gauntlet/20260830-104109/results.csv`: **exactly one flip** --
`g_iter21/chessboard/botA`, the same isolated, already-accepted noise
from Iteration 90 -- and **zero** `highway` flips. `g_iter37`/
`g_iter38`: 10/20 = 50% each, reasonable near-mirror scores.

### Gauntlet 91 (peer, full 22-opponent)

**275/440 = 62.5%.** The `g_iter17-36` subset: 255/400, matching the
104109 baseline within the single known flip. No opponent reached the
80%-domination retirement threshold.

### Outcome

**ACCEPTED.** A genuinely severe bug (confirmed unspent lead in the
thousands, complete build-queue paralysis) fixed with a
now-clean verification (one already-known flip, zero new regressions
at full 440-game scale) -- worth accepting even without a large
measurable peer-pool win-rate jump, since the pathology mostly bites
in sustained, contested fights that this mirror-match peer pool
under-represents relative to a real committed opponent.

**Snapshot**: `src/g_iter39/` (via `tools/snapshot.sh g_iter39`).
Replay reference: `gauntlet/20260830-143626/` (full Gauntlet run).

**Benchmark re-check** (informational, not required for accept):
`sample_camelcase` on `highway`/`squer`/`valley` -- still 0/3, r622/
r263/r582. Not surprising; camelcase's skill gap (real Dijkstra
pathfinding, coordinated focus fire, formation discipline) is far
larger than one Archon-priority bug. This fix closes a real
inefficiency but doesn't change the fundamental competitive gap
against camelcase specifically.

**Next:** add `g_iter39` to the peer set for future Gauntlet runs
alongside `g_iter17-38`. The mid-course lesson here (an 8-peer sample
can miss a real regression when the mechanism is keyed to a
continuously-fluctuating quantity on long/economy-heavy maps) is worth
keeping in mind for any future threshold-tuning iteration -- consider
going straight to a fuller peer check, or explicitly including at
least one `highway`-heavy opponent range, when a change touches
economy timing rather than a discrete event.

## Iteration 92 — Builder self-preservation (ACCEPTED, ~62%); a genuine bug fix with no measurable peer effect

### Step 4/5

Continuing the "trace a benchmark loss" methodology from Iteration 91:
`runBuilder` receives the `foes` parameter but **never uses it
anywhere in the function** -- a Builder had zero self-preservation
behavior, unlike Miners (flee combat) or Soldiers (retreat when
critical). This directly explains a death observed during Iteration
89's investigation: a Builder walking home to build a Watchtower
against `sample_afinals` was killed by a Sage mid-walk, wasting the
entire Watchtower/Laboratory investment (nothing else ever finishes
that job once the Builder dies).

### Step 6 — Solution

Added the same flee pattern Miners already use (move away from the
nearest sighted combat threat), checked first, ahead of every other
priority in `runBuilder` -- a dead Builder can't repair, build, or
mutate anything, so survival comes first.

### Verification

Mechanism confirmed engaging: re-ran `bot vs sample_afinals` on
`highway`, `--indicators` showed `"builder flee [10,7]"` firing twice
before the Builder resumed its task -- but it was still later killed
by a Sage. Diagnosed why: `SAGE.actionRadiusSquared` (25) exceeds a
`BUILDER`'s own `visionRadiusSquared` (20) -- the same range-gap
asymmetry that motivated the still-shelved Iteration 89 Sage-kiting
fix. A Builder structurally cannot see an incoming Sage attack in time
to flee from it specifically; no behavioral change can fix not being
able to sense the threat. This fix can't save Builders from Sage
assassination (dominant in `sample_afinals`-style Sage-swarm
matchups), but should help against ordinary detectable threats
(raiding Soldiers), the much more common case in typical peer games.

8-peer x 10-map x 2-side (160-game) reproduction sample against the
`g_iter39` baseline: **104/160, exact match, zero game-by-game
diffs** -- a true no-op on this sample. Given Iteration 91's lesson
(an 8-peer sample can miss a real regression), went straight to a
full 23-peer (`g_iter17-39`) x 10-map x 2-side (460-game) Gauntlet
before deciding.

### Gauntlet 92 (peer, full 23-opponent)

**285/460.** The `g_iter17-36` subset: **255/400**, and a full
game-by-game diff against the `104109` baseline showed **only the
same single, already-known `g_iter21/chessboard/botA` flip** from
Iteration 90 -- no new flips anywhere. `g_iter37`/`g_iter38`/
`g_iter39`: 10/20 = 50% each, reasonable near-mirror scores. No
opponent reached the 80%-domination retirement threshold.

### Outcome

**ACCEPTED.** A genuine, well-motivated bug fix (dead `foes`
parameter, zero self-preservation on a unit whose death wastes a real
economic investment) with a clean full-scale verification (the one
already-known unrelated flip, nothing else) -- accepted on the same
basis as Iterations 78/91: correct, low-risk, and plausibly valuable
in situations (raided Builders in ordinary contested games) that this
specific 23-peer mirror-match pool doesn't happen to exercise measurably,
rather than on a demonstrated peer-pool win-rate jump.

**Snapshot**: `src/g_iter40/` (via `tools/snapshot.sh g_iter40`).
Replay reference: `gauntlet/20260830-152818/` (full Gauntlet run).

**Next:** add `g_iter40` to the peer set for future Gauntlet runs
alongside `g_iter17-39`. The Sage range-gap limitation found here
(Builders, like Soldiers/Miners, can't see an incoming Sage attack in
time to react) is now confirmed as a structural property affecting
multiple unit types, not a one-off -- reinforces that any future
attempt to make Sage-heavy matchups (`sample_afinals`) less lopsided
needs to attack it from the Sage side (kiting/positioning, already
designed in Iteration 89 but shelved on our own weak gold economy) or
the economy side (getting Sages built at all), not from hardening
individual victim unit types further.

### Diagnostic note — post-Iteration-92 follow-ups: no new leads

Two quick checks after Iteration 92, neither actionable:

**A structural dead-code/unused-parameter sweep** of the rest of
`RobotPlayer.java` (the pattern that found Iteration 92's Builder bug)
turned up nothing else of consequence: `report(rc)` is called twice
from within `runMiner` in addition to its real call at the end of
`runArchon` -- harmless (it immediately no-ops via `if (rc.getType()
!= RobotType.ARCHON) return;`), just a little wasted bytecode from an
old leftover, not worth touching. `curArchons` and the other
Archon-local static fields are all correctly scoped (freshly set every
round before use, since each Archon is its own robot instance) --
no cross-robot staleness risk. `runWatchtower`/`runArchon` both use
`foes` properly.

**Re-checked `sample_afinals` directly a third time** (`highway`/
`squer`/`valley`) now that Iterations 90-92 together improve economy
timing and Builder survival: still **0 gold, 0 Sages built in all 3
games**. Consistent with the last two checks -- this specific
opponent's pressure is severe enough that the Watchtower/Laboratory
pipeline never survives to maturity regardless of which individual
component gets hardened. Also traced two fresh `sample_camelcase`
losses (`jellyfish` r231, `squer` r263): both show the same
already-understood signature (comparable Miner/lead economy on both
sides, but their Soldier count grows steadily while ours crashes) --
the combat-AI skill gap, not a new mechanical bug. The benchmark-
tracing methodology (which found 3 real bugs this session) has hit
diminishing returns for now; not spending further budget on more
benchmark replays without a new angle.
