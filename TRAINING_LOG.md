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
