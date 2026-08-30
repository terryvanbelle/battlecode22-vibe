# Battlecode Design Principles: A Cross-Year Synthesis

This document summarizes bot-design principles that recur across multiple years of
Battlecode postmortems, despite each year's game mechanics being completely different
(economy-and-conviction in 2021, mining-and-islands in 2023, paint-and-towers in 2025,
lead-and-gold in our own 2022). The goal is to extract what keeps working *independent*
of the specific ruleset, since Teh Devs redesign the game from scratch every January.

Sources read in full: Isaac Liao / **wololo** (2021, 7th), Gone Fishin' (2023, 2nd),
4 Musketeers (2023, top-4 finalist), Michael Hahn / **confused** (2025, 2nd). Summarized
via secondary sources: Stone Tao (2020, 5th-6th), Baby Ducks (2021 champion).

---

## 1. Communication is a scarce, structured resource — design the schema before the logic

Every year imposes a tiny shared-memory channel (a bit-packed global array, or a 24-bit
per-robot flag) and every strong team treats *what to store and how to compress it* as a
first-class design problem, not an afterthought:

- **Coordinates are expensive; abstract them.** wololo (2021) found that transmitting
  `location mod 128` was enough to uniquely reconstruct a position on any map that year,
  since no map exceeded 64 tiles per axis. 4 Musketeers (2023) went further and stopped
  communicating exact locations at all, dividing the map into **sectors** and broadcasting
  which sector an enemy/well/island was in (7 bits) instead of its exact tile (12 bits) —
  cheaper to send, and appropriately coarse since units re-resolve exact positions with
  their own vision once they arrive.
- **Batch writes.** When writes to the shared array are expensive or rate-limited (both
  true in different years), don't write field-by-field as information arrives. Read the
  whole array into a local copy, mutate the copy with dirty flags, and flush once — 4
  Musketeers explicitly borrowed the database "buffer pool" pattern for this after
  write-amplification from a naive sector-packing scheme ate half a robot's bytecode
  budget.
- **Log-encode wide-range, coarse-precision quantities.** wololo needed to transmit
  "conviction," a quantity spanning many orders of magnitude, in a handful of bits;
  transmitting `log(quantity)` and exponentiating on read preserved useful precision at
  both ends of the range.
- **A local per-robot database, refreshed lazily, beats a global source of truth every
  robot re-derives from scratch.** 4 Musketeers gave every robot a `SectorInfo` cache so
  it could accumulate 100+ rounds of observations before ever getting a chance to write
  them back, without needing to re-scan.

**Takeaway independent of any year's rules:** decide the units of information that
actually matter (which map region, which target, which threat) before deciding the bit
layout, and design for infrequent, batched, lossy-but-good-enough writes rather than
trying to keep every robot's state perfectly synchronized.

---

## 2. Pathfinding: hybrid bug-navigation, not pure BFS/A*

This is the single most consistent engineering story across every year read. In each
case, teams start with textbook A*/BFS, discover it blows the bytecode budget, and
converge on the same family of solution:

1. **Bug navigation as the fallback**: move directly toward the target; when blocked,
   follow the obstacle's boundary (turning consistently in one direction) until the
   direct path is clear again. Cheap, bytecode-bounded, and correct on convex obstacles.
2. **A stack of past turns to escape concave ("C"-shaped") obstacles**, which plain
   bug-nav gets stuck in — Gone Fishin' (2023) added a directional stack so a robot could
   detect it had fully circumnavigated an obstacle and reset to direct movement.
3. **An unrolled, unit-distance BFS/Bellman-Ford for local/tactical movement**, generated
   or hand-written as a flat sequence of `if` statements over a fixed radius (radius² ≤ 20
   is a number that appears independently in both wololo's 2021 solution and 4
   Musketeers' 2023 solution) — a real shortest-path search, but one whose *shape* is
   fixed at compile time so it costs a predictable, small amount of bytecode instead of
   scaling with a dynamically-sized frontier.
4. **Simulate before committing on hard cases.** Rather than always turning the same way
   around an obstacle, wololo's later versions ran a bounded-round simulation of turning
   left vs. right and picked whichever finished (or made more progress) within the
   bytecode budget, falling back to a coin flip only if neither simulation finished.
5. **Treat friendly units as soft, not hard, obstacles**, and add outright randomness as
   a last resort. Gone Fishin' found the best late-tournament setting was "friendly units
   are walls 75% of the time, empty tiles 25% of the time" — a compromise between
   "friendlies never block" (causes pileups) and "friendlies always block" (causes
   gridlock once a formation stalls). Multiple teams independently added randomized
   tie-breaking specifically to escape movement loops that a fully deterministic
   algorithm could get stuck in forever.
6. **Detect obstacle endpoints ("points of interest") rather than tracing the whole
   boundary.** 4 Musketeers' 2023 solution approximated an obstacle's graph diameter
   (its two farthest points) across several turns of budgeted background computation,
   then always knew the correct direction to go around it without re-tracing.

**Takeaway:** don't chase an asymptotically-optimal pathfinder. The winning pattern is a
fast, good-enough greedy step, with a bounded, incrementally-computed escape mechanism
for the specific obstacle shapes that greedy movement actually gets stuck on, plus a
randomized tie-break as a last-resort safety valve against infinite loops.

---

## 3. Never assume map symmetry — detect it, and exploit it once detected

Every Battlecode map is guaranteed one of a small number of symmetry types (rotational,
horizontal, or vertical) for fairness. This is free information a bot can and should
extract:

- **Detecting it wrong is a costly, avoidable mistake.** Gone Fishin' (2023) assumed
  rotational symmetry for their first tournament because of time pressure, and it "backfired
  quite heavily" the moment they faced a non-rotational map — they built a proper detector
  (a `MapRecorder` tracking seen walls/terrain, eliminating symmetry hypotheses as
  contradicting evidence arrives) immediately afterward.
- **Detection can be made cheap by only checking the informative half of the map.**
  confused (2025) only sensed cells in the *opposite* quadrant/half from newly-seen
  terrain when checking a candidate symmetry, since only that side can disprove it —
  roughly halving the bytecode cost of the check.
- **Once symmetry is even partially known, it collapses the search space for scouting.**
  If a map might be horizontally, vertically, or rotationally symmetric and you've
  already ruled out two of the three, you've already narrowed the enemy base to one of
  two candidate locations instead of an unknown point somewhere on the map. 4 Musketeers
  explicitly had units "invalidate" candidate symmetric locations as they explored them,
  turning scouting into a shrinking checklist rather than open-ended wandering.
- **Send scouting units toward the map center by default**, since that's the position
  that both (a) helps disambiguate symmetry fastest and (b) minimizes worst-case distance
  to wherever the enemy base turns out to be, under any of the three symmetry types.

---

## 4. Micro (per-unit combat tactics) outweighs macro (production/economy tuning) by a wide margin

Gone Fishin' (2023) state this as an explicit, quantified claim from their own
experience: "a slightly improved macro strategy might increase our win rate by 5%, but
micro can do a 30%-50% increase." Recurring specific micro techniques, independently
discovered by multiple teams across multiple years:

- **Kite after every attack, unconditionally.** If your unit can attack every round but
  can only move every *other* round (a common asymmetry), attacking then immediately
  retreating to just outside enemy range means you take a hit ~1 round out of every 4
  while the enemy — who has to close the gap again before it can return fire — only gets
  a hit every other exchange. Gone Fishin' tried conditional kiting (only retreat in
  certain matchups) and found "always kite back" outperformed it in every test, a finding
  corroborated independently by that year's tournament winner.
- **If you can attack, attack — even blindly.** When vision radius and attack radius
  don't match, or terrain obscures vision (clouds, fog), a unit that still has an attack
  available should fire on its last-known enemy position or a plausible guess rather than
  passing the turn, since the cooldown resets either way. Multiple teams converged on
  "attack the tile closest to the enemy base, or the last confirmed enemy location" as the
  guess heuristic once no visible target remains.
- **Prefer axis-aligned movement over diagonal on tied distance.** When two directions
  are otherwise equally good, choosing the non-diagonal one keeps a moving formation from
  spreading out (diagonal steps cover more Euclidean distance per action, which
  desynchronizes units that started aligned).
- **Retreat thresholds should be aggressive (near-death only), not conservative.** Gone
  Fishin' (2023), citing an earlier finding from camelcase, found that retreating at the
  first scratch of damage cost far more in lost damage-per-turn than it saved in unit
  survival; raising the retreat threshold to "critical HP only" (and *only* if the unit
  isn't already deep in a fight it's winning) let units keep trading instead of
  perpetually running home half-healed. A near-identical finding shows up independently
  in this project's own iteration history (Iteration 18: raising a retreat threshold from
  "any damage, anywhere" to "critical HP only unless already near home" fixed an army
  grinding itself down through premature retreats).
- **Retreat and heal as a group, not individually.** 4 Musketeers (2023) found that if 3
  of 4 units in a fight are low on health and peel off to heal, the 4th (undamaged) unit
  left alone dies anyway — so the correct rule is "if any nearby ally needs to retreat and
  you're not clearly winning without them, retreat together," even for units that
  personally could keep fighting.
- **Target-prioritize by kill-efficiency, not just raw threat.** Multiple teams computed,
  for each visible enemy, how many friendly units could hit it this turn and how many
  hits it would take to kill — then preferred targets killable in the fewest turns
  ("one-shots") over higher-value but slower kills, since a fast kill removes a
  damage-dealer from the fight immediately.
- **Reposition onto favorable terrain after attacking, if movement is a free action.**
  wherever move and attack draw from separate cooldowns, always taking a "free" step onto
  lower-cost terrain (faster future cooldown recovery) or defensible ground after firing
  is close to strictly better than standing still.

---

## 5. Identify what the actual scarce resources are — they aren't always the obvious ones

wololo's (2021) economic analysis is the clearest articulation of this: a "resource"
worth optimizing for is anything where (a) having none of it is close to a loss condition,
(b) you can remove it from the opponent, and (c) the opponent can make it hard for you to
get. By that definition, the year's headline resource (conviction/gold/mana) is *one*
resource, but so is **total unit count** — and recognizing unit count as an independently
tradeable currency, separate from the raw material it's built from, opened an entire
strategic layer (forcing unfavorable material-for-unit-count trades on the opponent) that
a team focused only on the headline resource would miss entirely.

Concrete instances of this insight recurring under different game rules:

- **Denial of enemy production capacity is often cheaper than direct combat.** wololo's
  "bury" tactic — permanently occupying every tile adjacent to an enemy spawner so it
  physically cannot place new units — forced the opponent into an unfavorable trade
  (spend expensive defensive units to clear cheap besieging units, or produce nothing) at
  a fraction of the cost of destroying the spawner outright. confused's (2025) "tainting"
  tactic — partially completing a shared objective (a tower-under-construction) just
  enough that the enemy can no longer complete it without specialized counter-units — is
  the same idea in a completely different ruleset: denial by partial commitment, not
  destruction.
- **A fully idle economic lane, if genuinely free of trade-offs against the main
  strategy, is worth pursuing even late.** wololo noted gold/conviction produced through
  a side mechanism that never competed with the main unit-count economy was "free" value
  most opponents left on the table entirely.
- **Congestion is a real, easily-overlooked resource cost.** Both Gone Fishin' and 4
  Musketeers (2023, independently) found that over-producing a cheap unit type (carriers,
  in that year's game) didn't just fail to help — it actively clogged pathing near the
  spawn point and reduced the throughput of everything else. Both teams added explicit
  production throttling keyed off local unit density, not just "should I afford to build
  this."

---

## 6. Build a genuine trading/exchange-rate model, even a crude one

Related to #5: once you've identified the resources that matter, the next-order insight
that separates strong teams from average ones is having *any* explicit model of how much
one resource is worth in terms of another, so that individual unit decisions (should this
unit convert/attack/retreat) can be judged against a consistent standard instead of ad hoc
rules. wololo's minimax empowerment-radius calculator explicitly converted "unit count"
and "conviction" into a single comparable currency via a tunable exchange rate before
deciding whether an action was favorable — a genuinely more disciplined decision procedure
than "empower if you can," and one that automatically produced sensible emergent behavior
(avoiding bad trades, seeking good ones, healing allies when nothing better was available)
without those behaviors being individually hand-coded.

You don't need this to be sophisticated or dynamically tuned — wololo's own version was
just a linear function of income rate, and still outperformed opponents with no exchange
rate model at all, "because most other teams did not make use of exchange rates anyways."

---

## 7. Don't commit early to one strategic paradigm — read the map and adapt

Every year features some version of the classic *rush* (win fast before the opponent's
plan matures) vs. *turtle* (out-economy them, defend until you can overwhelm) tension, and
the strongest performances consistently come from bots that **decide which paradigm to
follow per-game, from measurable signals**, rather than committing to one at compile time:

- wololo's (2021) Enlightenment Centers measured the approximate distance/passability to
  the opponent (via scout reports) and used that purely to decide whether an early
  rush was even *feasible* before investing turns in the slower economic buildup a turtle
  needs — on close, low-passability maps, only rushing was viable at all, since a turtle
  economy couldn't mature before the opponent's units arrived; on distant, open maps, the
  reverse was true.
- 4 Musketeers (2023) sized their resource-gathering ratio (aggression-favoring vs.
  economy-favoring) off how close their first guess at the enemy base turned out to be,
  switching a 2:1 economic ratio to 3:1 rush-favoring on small/close maps automatically.
- confused (2025) built a completely different, more defensive fallback specifically for
  the final tournament's larger, lower-passability maps, without discarding the rush code
  that had won them Sprint 1 — the two coexisted, chosen by measured map properties, not
  a single hardcoded doctrine.

**Takeaway:** a fixed strategic identity is a liability. Measure the properties that
distinguish rush-favorable maps from turtle-favorable ones early in the game (distance to
opponent, terrain passability, resource density) and branch on them, ideally continuously
re-evaluating rather than committing once at round 1.

---

## 8. The metagame shifts under balance patches — build for adaptability, not just correctness

Every single year's postmortems mention at least one mid-tournament rule change that
invalidated a leading strategy overnight:

- 2021: an early "self-empowerment" exploit that let a team snowball its own economy
  after exposing enemy weaknesses was patched out between weeks, forcing every team using
  it to pivot.
- 2023: headquarters gained the ability to passively damage anything standing in their
  action radius specifically because "literally every team" was using the same siege
  tactic — the counter-adaptation (stand one tile further back) was trivial once
  recognized, but only for teams paying attention to *why* the rule changed, not just
  *that* it changed.
- 2025: a flat 500-HP buff to starting towers "effectively nullified" a rush-centric
  strategy that had placed top-16 in the first sprint tournament, forcing a same-week
  pivot to a fundamentally different "towers as top priority, not aggression as top
  priority" philosophy.

**Takeaway:** treat any strategy that exploits a razor-thin numeric edge (a slightly
favorable trade ratio, a barely-affordable rush timing) as inherently fragile to balance
changes, and keep the underlying game *mechanics* — communication, pathfinding, the
resource/trading model — decoupled enough from the specific strategy on top that a
strategy pivot doesn't require rewriting the whole codebase. Teams that could re-derive a
new strategy from first principles within days consistently recovered; teams that had
deeply coupled their infrastructure to one strategic assumption did not.

---

## 9. Formation and coordination can emerge from simple local rules, without explicit communication

Because every robot runs independent code and communication bandwidth is scarce, several
of the more elegant solutions found ways to produce *emergent* group behavior from purely
local decisions:

- **Spawn order as implicit coordination.** Gone Fishin' (2023) found that spawning the
  first four units of a rush in a specific square arrangement — with the earliest-spawned
  (and therefore first-to-act-in-tiebreaks) units placed farthest from the base — reliably
  produced a coherent marching formation with zero explicit signaling, because units acted
  in spawn-order and outer units moving first meant inner units never mistook them for
  static obstacles. This one change alone won roughly two-thirds of self-play games
  against an otherwise-identical bot without it.
- **Repulsion fields for automatic map coverage.** wololo's (2021) explorer units treated
  every other visible explorer as a repulsive point-charge (but only if that other
  explorer was *at least as close* to an unexplored area), which caused units to spread
  themselves as thinly as possible across unexplored territory without any unit needing
  to know the others' assignments — a self-organizing search pattern from a purely local
  rule.
- **A momentum/heading component, not just a pure force vector, avoids re-exploring the
  same ground** — wololo's explorers preferred continuing in roughly their existing
  direction over immediately reacting to the instantaneous force, which produced straight,
  efficient sweeps outward rather than jittery back-and-forth.

---

## 10. Infrastructure first; measure before you optimize

Stone Tao's 2020 postmortem states this most directly: "working on infrastructure first
is always much more beneficial" than chasing whatever the current strategic fad is, since
pathfinding, communication, and basic combat rarely need rewriting even as the specific
strategy on top of them evolves through balance patches. Corollaries that show up
repeatedly:

- **Automated, repeatable A/B testing beats manual spot-checks.** 4 Musketeers (2023)
  didn't build automated version-vs-version testing (via CI) until partway through the
  season, after which testing "two versions of our player" against each other went from
  a multi-hour manual process that monopolized one teammate's machine to something
  routine — after which they iterated meaningfully faster.
- **Bytecode-aware custom data structures, not standard-library defaults.** Nearly every
  team mentions replacing generic collections (sets, queues, maps) with purpose-built
  structures (a "reversed for-loop" for cheap iteration, custom circular-buffer "integer
  cyclers," unrolled fixed-size scans) once profiling showed the standard-library
  equivalents ate a disproportionate share of the turn's computation budget — but this
  optimization work only happened *after* a working, correct version existed to profile.
- **Copying and adapting other teams' well-tested code (with attribution) is standard
  practice, not a shortcut to be ashamed of.** Nearly every postmortem explicitly credits
  a previous year's open-sourced pathfinder, communication library, or testing script
  that they built on rather than re-deriving. confused (2025), an individual competitor
  under severe time pressure, explicitly adapted a prior year's champion team's
  pathfinding and OOP structure wholesale rather than starting from scratch, and still
  reached the finals.
- **Deep replay analysis beats algorithmic sophistication.** confused's (2025) closing
  reflection is blunt about this: "a deep understanding of the game's fundamentals and
  thorough analysis of gameplay can lead to strong results, even without relying heavily
  on advanced algorithms or extensive bytecode optimization." Their single highest-value
  strategic pivot (a resource-conversion exploit dubbed "tower flickering") wasn't
  invented independently — it was noticed by watching a different team's replay and
  recognized as compatible with their own already-articulated strategic philosophy within
  days of the final tournament.

---

## 11. Root-cause real failures rather than working around them

Postmortems are consistently candid about concrete bugs, and the pattern in how they were
found is itself instructive: nearly every serious bug was caught by watching an actual
game replay end unexpectedly, not by code review or intuition.

- 4 Musketeers (2023) lost a top-seed qualifier match to a null-pointer exception:
  headquarters clustered in a map corner exhausted their bytecode budget scanning for
  build locations before ever reaching the step that recorded sibling HQ locations,
  silently leaving those references null for the rest of the game. The fix (a hard
  bytecode budget check) was trivial once the failure mode was understood — the hard part
  was noticing it happened at all.
- Gone Fishin' (2023) diagnosed a finals loss directly from a specific mechanical cause
  (a spiral scouting pattern optimized for a central spawn performed badly on a map with
  distant resources) rather than attributing it to vague bad luck, which let them state
  precisely what to fix for next year instead of guessing.
- Both wololo (2021) and 4 Musketeers (2023) describe multi-day root-cause chases for
  pathfinding deadlocks (map "jail" shapes, current-driven pits) that only got fixed once
  someone found the *specific* obstacle geometry breaking their assumptions, rather than
  patching around the symptom.

**Takeaway:** treat "why did this specific game end this way" as more valuable than
aggregate win-rate statistics alone. A single well-understood loss, traced to its actual
mechanical cause via replay inspection, produces a more reliable fix than a broad
statistical pattern without a mechanism.

---

## Sources

- Isaac Liao (wololo), *Battlecode 2021 Postmortem*
- Carl Guo, Ray Wang & Yuxuan Chen (Gone Fishin'), *BattleCode 2023 Strategy Report / Postmortem*
- Winston Cheung, Maxwell Jones, David Lyons & Bharath Sreenivas (4 Musketeers), *Battlecode 2023 Strategy Guide*
- Michael Hahn (confused), *Battlecode 2025 Postmortem*
- Stone Tao, *Battlecode 2020 Postmortem* (secondary summary)
- Josh Brunner, Anthony Grebe, Jason Ye & Wesley Zhang (Baby Ducks), *Battlecode 2021 Postmortem* (secondary summary)

All postmortems are hosted at `battlecode.org/assets/files/postmortem-<year>-<team>.pdf`,
except wololo's (`battlecode.org/assets/files/postmortem-2021-wololo.pdf`) and Stone Tao's
(`stonet2000.github.io/battlecode/2020/`).
