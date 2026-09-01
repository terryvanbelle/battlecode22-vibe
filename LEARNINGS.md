# Learnings

A synthesis of what this project has learned building a Battlecode 2022 bot,
from Iteration 0 through the present (128 logged iterations, ~62 accepted
snapshots). This is organized by theme, not chronology — `TRAINING_LOG.md` is
the chronological record; this document is what to tell someone joining the
project or starting a similar one from scratch.

## Battlecode 2022 engine facts learned the hard way

Most of these were not obvious from the API surface and had to be confirmed
via `javap` decompilation of the real game jar, direct replay-header
inspection, or live in-game instrumentation — several early guesses in this
project's history turned out to be wrong (see "Anti-patterns" below for the
methodology lesson).

- **Unit stats** (confirmed via a match replay's own header, which prints
  a full `hp/dmg/actCD/movCD/actR2/visR2/costPb/costAu/bytecode` table):
  `ARCHON` hp=600, actCD=10, movCD=24, actionRadiusSq=20, visionRadiusSq=34,
  bytecode=20000. `LABORATORY` hp=100, actionRadiusSq=0, visionRadiusSq=53,
  costPb=180, bytecode=5000. `WATCHTOWER` hp=150, dmg=4, actionRadiusSq=20,
  visionRadiusSq=34, costPb=150, bytecode=10000. `SAGE` hp=100, dmg=45,
  actCD=200 (~once every 20 rounds), buildCost=0 lead/20 gold.
- **A real, exploitable asymmetry**: `SAGE.actionRadiusSquared` (25, ~5
  tiles) exceeds `SOLDIER`/`MINER`/`BUILDER.visionRadiusSquared` (20, ~4.47
  tiles) — confirmed via `javap`. A Sage can hit those unit types from a
  ~1-tile band where it's never seen coming. This came from reading the
  actual 2022 tournament postmortem PDF (a team that finished 7th) once PDF
  tooling was unblocked (`sudo apt-get install poppler-utils`); the same
  mechanism ("dancing outside vision radius... assassinate from the
  shadows") had already won a real tournament team games. Confirmed
  independently as a real cause of otherwise-inexplicable Builder/Miner
  deaths against a Sage-heavy opponent — see Iterations 89/96/97.
- **`RobotMode.PORTABLE`/`TURRET`**, decompiled directly with `javap -c`
  since the semantics weren't obvious from the method names alone:
  `TURRET` is `canAct=true, canMove=false, canTransform=true`; `PORTABLE`
  is `canAct=false, canMove=true, canTransform=true`. A relocating
  Archon/Watchtower genuinely cannot build, repair, or attack while
  portable or mid-transform — this is real, bounded downtime, not free
  movement (Iterations 34, 78).
- **`mutate()` is a `BUILDER` action performed on another structure, not
  self-leveling.** Confirmed via a `RobotType.canMutate(RobotType)`
  cross-product probe: `canMutate(self)` is false for every type; only
  `BUILDER.canMutate(ARCHON/LABORATORY/WATCHTOWER)` is true, the same
  action-shape as `repair()`. A first attempt wiring `mutate()` onto the
  Archon itself silently never fired (Iteration 82). Level costs/effects
  (level-sweep probes): Archon level 2 = 300 lead for 600→1080 HP
  (+80%) and 2→4 healing/turn; level 3 = 80 gold (rarely affordable — see
  the gold-economy findings below). Watchtower level 2 = 150 lead for
  150→270 HP and 4→8 damage. Laboratory level 2 = 150 lead for 100→180
  HP. This mechanic sat completely unused for 81 iterations before being
  found via a `javap` sweep of `RobotController`'s full public method list
  against everything actually referenced in the codebase — the same
  technique that found the `PORTABLE`/`TURRET` transform mechanic
  (Iteration 78) and the Watchtower/Laboratory leveling (Iterations 82-83).
  **Worth repeating periodically**: sweep the full API surface for
  anything never called, rather than assuming the obvious methods are the
  whole interface.
- **The bytecode limiter pauses execution mid-instruction and resumes on
  the next round — it does not throw an exception or reset the call
  stack.** This has a directly exploitable detection method: capture
  `rc.getRoundNum()` at the top of a robot's per-round logic and again
  right before yielding; if it changed, that robot's turn was genuinely
  truncated by the engine this round (Iteration 127). `Clock.getBytecodeNum()`
  gives bytecodes used so far this round; `RobotType.bytecodeLimit` is a
  public field. A one-off offline profiler check earlier in the project
  (enabling `-Dbc.engine.enable-profiler=true` and parsing the resulting
  `ProfilerFile` structures) found comfortable headroom at that point in
  time (worst case ~50% of budget) — but that was a single point-in-time
  snapshot, and later live monitoring across a real competitive Gauntlet
  found real overruns concentrated in the longest, highest-unit-count
  games (Iteration 127), showing the earlier one-off check couldn't be
  trusted to stay valid as more logic accumulated.
- **Indicator strings have an undocumented length limit and truncate
  silently, with no error.** Discovered when new fields appended to an
  already-near-the-limit status string simply never appeared in replay
  output — the string had already been truncating mid-word (`"attacks="`
  cut to `"atta"`) before the new fields were even added, and nothing
  before that ever surfaced the failure (Iteration 127). Debug/status
  strings that grow over a project's lifetime should be periodically
  checked for truncation, not assumed to keep working.
- **Maps are guaranteed one of a small number of symmetry types**
  (rotational/horizontal/vertical), confirmed directly via a replay
  header's `symmetry:` field — this is real, verifiable per-map metadata,
  not something to assume. An early attempt (Iteration 7-era and again
  Iteration 76) assumed rotational symmetry unconditionally in the
  bot's own "no information yet, guess the enemy is at my mirror point"
  fallback; direct inspection later showed 4 of the original 10-map pool
  are *not* rotationally symmetric, making that guess provably wrong on
  40% of maps (see "Open questions" for how this thread eventually
  resolved).
- **`senseNearbyRobots()`/`senseNearbyLocationsWithLead()` return results
  in the engine's own internal scan order** (very likely a fixed
  absolute-coordinate order, e.g. row-major), not randomized and not
  relative to the calling robot. Code that picks the first result
  satisfying a condition, rather than explicitly finding the best one,
  silently inherits an absolute-position bias from this — see the
  "Play symmetry" pitfall below, one of this project's largest and most
  subtle discoveries.
- **`rc.getID()` is unique per robot regardless of team** and unused by
  the engine for anything team-correlated — safe to use for genuinely
  random, non-team-correlated per-robot behavior (e.g. seeding a personal
  tiebreak order), unlike the bot's own shared `Random` instance (below).
- **A shared, fixed-seed `Random` instance produces identical output for
  "corresponding" robots on both teams.** This project's `rng` field is
  seeded with a literal constant; since Java's `Random` is fully
  deterministic given a seed and call count, a robot on Team A and a
  robot on Team B that have each made the same number of prior calls to
  the shared RNG get the *same* next value — meaning a naive attempt to
  use it for decorrelating some absolute-position bias between the two
  teams can silently reproduce the exact same bug it was meant to fix,
  one level removed (learned expensively in Iteration 126's v2/v3
  attempts — see "Play symmetry" below).
- **Tile occupancy, not just resource affordability, gates
  `canBuildRobot()`.** An Archon's 8-tile build ring can be permanently
  self-blocked by its own structures (a Watchtower, and doubly so once a
  Laboratory was briefly attempted directly adjacent too) — confirmed via
  `javap` that this is a real occupancy check, not a resource check
  failing silently. This bit the project at least twice: once
  discovered and characterized on `sandwich` specifically (a long,
  multi-iteration thread), and once self-inflicted catastrophically when
  a Laboratory was first added directly onto the Archon's own ring
  (Iteration 57 — team lead ballooned to 9750 unspent while the build
  queue fully paralyzed). The general lesson — permanent structures
  compete for an Archon's adjacent tiles and more of them makes tile
  contention worse, not better — is now load-bearing design knowledge
  (Laboratories are placed 7 tiles clear of the Archon specifically
  because of this).
- **Static fields are per-robot instance, not shared globally across a
  team's whole codebase.** Every robot effectively gets its own copy of
  the class's static state (confirmed by extensive practical use
  throughout the project — e.g. `builtWatchtower`, a per-Builder
  one-shot flag). This is *not* documented anywhere obvious and is easy
  to get backwards; genuine team-wide coordination requires the shared
  array, not static fields.
- **`rc.setIndicatorString`/replay indicator strings are the primary
  debugging surface**, alongside `tools/bc22_replay.py --metrics`
  (per-round CSV of team aggregates) and `--indicators`/`--all-actions`/
  `--moves` for finer-grained event traces. Nearly every root-cause in
  this project's history was found by reading one of these, not by code
  review or intuition (see "Process lessons" below).

## Architecture patterns that worked

- **Shared array as a small, purpose-built message bus, not a generic
  key-value store.** Fixed slots per purpose (`SA_FOCUS`, `SA_HOME_THREAT`,
  `SA_ENEMY_ARCHON_0..3`, etc.), each with a clear single owner/writer
  discipline and an explicit clearing condition. The single biggest
  design lesson here, learned the hard way: **a signal that only clears
  under a rare game state can get permanently stuck "open."** This
  pattern recurred at least three separate times under three different
  names — `contact`/`recentEnemyContact` ("no enemy sighted recently")
  almost never goes false in a sustained real war, so gates built on it
  (a Miner-quota cut, a Watchtower-build gate, a Sage-build gate) were
  each, independently, found to be silently inert for most of a real
  game; `SA_HOME_THREAT` had an analogous "never actually clears"
  failure mode fixed early (Iteration 37). Whenever a shared-array
  boolean/flag is gated on "X hasn't happened recently," verify
  concretely (via `--metrics`/`--indicators` on a real long game) that
  the condition actually becomes false sometimes, not just that it's
  false at round 1.
- **Census via accumulator + first-actor-publishes-last-round's-total.**
  Real per-round live unit counts, not a cumulative-ever counter that
  never decrements. The cumulative-vs-live distinction caused a genuine,
  repeated bug early in the project (a build rule reading a cumulative
  Soldier count kept concluding "we have enough Soldiers" while the live
  army was actually near zero, because dead Soldiers were never
  subtracted) — fixed multiple times because early fixes were reverted
  alongside unrelated regressions before the isolated, correct version
  landed (Iteration 11-12).
- **Focus-fire coordination via a single shared target slot
  (`SA_FOCUS`).** Each combat unit promotes its own best local pick if
  it beats the current shared target (or the target is confirmed dead/
  gone), and everyone prefers the shared target when it's in range. This
  concentrates damage so enemies die faster and return less fire — but
  it only started paying off once units *stayed alive* long enough to
  benefit from concentration (retreat-to-heal, Archon repair) and once
  reinforcing units actually *reinforced the live fight* instead of a
  stale static guess-point (`SA_FOCUS`-aware reinforcement, not just
  `armyObjective()`). All three had to land together as one coherent
  package (Iteration 12) after multiple isolated attempts (Iterations
  8-11) proved individually inert or regressive on top of code tuned
  around the old broken counter.
- **`richHome` (lead sensed within an Archon's own vision at spawn
  `> 600`) as the primary economic-doctrine branch.** A one-time,
  per-Archon latch computed on turn 1, used throughout the bot to decide
  Miner quotas, Builder caps, and (much later in the project) Watchtower/
  Laboratory investment scale. This became the single most-reused
  conditional in the codebase — a clean example of finding one cheap,
  stable signal and branching many downstream decisions on it rather than
  re-deriving "is this a rich map" repeatedly in different ways.
- **Escalating-threshold pattern for discretionary surplus spending.**
  Once a Builder finishes its committed build queue (Watchtower, then
  Laboratory), further investment (a second Watchtower, a second/third
  Laboratory) is gated on team lead surplus with an *increasing* bar for
  each additional unit (e.g. 1000 → 2500 → 5000 lead for successive extra
  Watchtowers). This intentionally distinguishes a committed investment
  (always worth pursuing) from a discretionary one (only worth it once
  genuinely lead-rich), and scales naturally with however many Builders
  the economy can afford without a single flat threshold either starving
  early games or under-investing in very long ones. Proven out repeatedly
  across a whole thread (Iterations 117, 118, 122, 123, 125).
- **A sticky/committed target beats "recompute nearest every round."**
  A Miner that recomputes "nearest known lead beacon" fresh every round
  can ping-pong between two similarly-distant beacons as either its own
  position or a beacon's remaining lead changes, wasting many rounds
  moving without ever mining (found via direct movement-trace inspection,
  not aggregate stats — see "Process lessons"). The fix (`myLeadTarget`,
  Iteration 35) was one of the single largest peer-win-rate jumps of the
  whole project (60.3% → 69.7%) from a small, surgical change: commit to
  one beacon until it's actually reached or depleted, don't re-evaluate
  "nearest" every round.
- **Self-calibrating thresholds beat hand-tuned constants for
  opponent-variable behaviors.** A Miner-rescue response gated on a fixed
  distance/priority threshold went through four rejected variants (each
  trading one opponent group's gain for another's loss) before the
  winning design threw away the fixed constant entirely and instead
  *counted observed raid events within the game itself*, throttling only
  once a real pattern was demonstrated (Iteration 73). This is a general,
  reusable pattern: when a fixed threshold repeatedly helps one opponent
  and hurts another, consider whether the right threshold is actually
  opponent-dependent, and if so, derive it from in-game observation
  rather than search over more fixed constants. (Note: this pattern
  isn't universal — an analogous self-calibrating throttle tried on
  Archon repair-vs-build allocation was tried and failed three separate
  times, Iterations 63/74/75, because the underlying bottleneck there
  turned out to not be Archon-turn allocation at all — see "Anti-patterns.")

## Recurring pitfalls and anti-patterns

- **Fixed absolute-order tie-breaks silently encode positional bias, and
  this project independently rediscovered the same bug class at least
  twice, roughly 60 iterations apart.** Around Iteration 64, a mirror
  match (the bot against a byte-identical copy of itself) showed a
  100%/0% side split on one map — direct proof the code itself, not
  opponent strategy, decides the outcome purely from team assignment.
  This was correctly traced to *not* being a uniform engine-level
  first-mover effect (a true mirror match split roughly 6-4 across the
  10-map pool, different maps favoring different sides) and correctly
  pointed at "something in our own movement/pathfinding code correlates
  with map-specific spawn geometry" — but the investigation stopped short
  of finding the actual mechanism and was parked as a known, unfixed,
  high-value lead. Much later (this session, Iterations 126+), the same
  phenomenon was rediscovered from a different angle and this time fully
  root-caused: several sites iterated a fixed compass-order `Direction[]`
  array (prefer North, then NE, then E, ...) for tie-breaking among
  equally-good choices — harmless-looking in isolation, but not actually
  neutral, because it interacts with each map's specific (but symmetric)
  spawn geometry: whichever side's "forward" direction happens to align
  with the fixed compass preference gets a small, compounding per-round
  tempo edge, and *which* side that is depends on the map's own layout,
  not on team identity. A parallel, less obvious instance of the same bug
  class was found in `senseNearbyRobots()`/`senseNearbyLocationsWithLead()`
  consumers that picked the *first* result satisfying a condition instead
  of explicitly finding the best one — the engine's own scan order is
  itself a fixed absolute order, so "first found" inherits the same kind
  of hidden bias even when no `Direction[]` array is involved. **Lesson
  for any future project with tie-breaks among symmetric options:
  audit for this class of bug from day one** (now written directly into
  `TRAINING_ALGORITHM.md`'s "Play symmetry" section as a standing
  requirement), rather than relying on a mirror-match spot-check to
  eventually surface it by accident.
- **Fixing a confirmed-real asymmetry is not automatically a net win —
  the "unfair" behavior can be providing real, separately-valuable
  benefit that a "fair" replacement doesn't.** Three different tiebreak
  redesigns were tried for the DIRS-order bug above: a geometrically-
  motivated one (tiebreak toward map center) narrowed the fairness gap
  but caused a much *worse* asymmetry on one specific map (structures
  built closer to center turned out to be more exposed, not safer, on a
  maze-like map); a purely-random one and a per-robot-fixed-random one
  both narrowed the gap too, but both caused a real net regression in
  overall win rate — most likely because a *consistent* (even arbitrary)
  per-round preference was giving the army real movement cohesion that
  pure randomization loses (echoing an earlier, independent finding
  about spawn-order-driven formation coherence). The eventual shipped fix
  accepted this cost explicitly, on direct user instruction, rather than
  either declaring the whole thing unfixable or pretending the tradeoff
  didn't exist.
- **A signal gated on "no contact recently" is a trap in a sustained
  real war.** Already covered above under shared-array design, but worth
  restating as its own anti-pattern since it recurred independently
  across unrelated features (Miner quota, Watchtower gating, Sage
  gating): "has X happened in the last N rounds" freshness windows work
  fine for genuinely rare events, but for anything that becomes true
  early and stays true for most of a real competitive game, the gate is
  effectively a permanent off-switch, not the conditional it looks like.
- **Small reproduction samples can miss regressions concentrated on one
  map.** An 8-peer sample looked completely clean for one change; the
  full peer Gauntlet found a real, concentrated 6-flip regression on a
  single map the small sample happened not to sample heavily enough.
  This is why `TRAINING_ALGORITHM.md`'s Step 6.5 explicitly does *not*
  let a clean small sample skip the full Gauntlet — particularly for
  changes to build/production priority, resource thresholds, or any
  priority ordering read from a shared signal, which this project found
  repeatedly capable of being invisible at small scale and real at full
  scale.
- **Map-fragile long games can flip outcome from a tiny, unrelated
  change — don't over-read a single flipped game.** Certain maps
  (`chessboard`, `squer`, `sandwich`, `maptestsmall`, `pillars` were the
  worst repeat offenders, though the phenomenon isn't limited to those)
  are near-mirror-sensitive between closely related bot versions: a
  perturbation early in the game, unrelated to the actual change being
  tested, can cascade over hundreds of rounds into a flipped final
  outcome in either direction. A dedicated trace on one persistent flip
  (`g_iter21`/`chessboard`, appearing in nearly every full-Gauntlet diff
  for most of a session) confirmed it was a *real, reproducible*
  phenomenon (not literal randomness) but also confirmed the
  Soldier-movement code itself hadn't meaningfully changed since that
  opponent snapshot — the divergence was emergent from ~90 iterations of
  accumulated *other* timing changes, with no single fixable cause. The
  practical rule this produced (formalized in `TRAINING_ALGORITHM.md`'s
  "reading a diff's shape" section): a diff that's small, scattered
  across maps/opponents, and mixed in direction is likely this kind of
  noise; a diff that's one-directional and/or concentrated on one
  map/side across *many different* opponents is much more likely a real,
  causal regression, because a real bug reproduces consistently while
  chaos-sensitivity doesn't.
- **"Helps the exact case it was diagnosed on, hurts broadly elsewhere"
  is a recognizable, recurring failure shape**, not a one-off bad
  guess — it showed up independently across an Archon repair-vs-build
  throttle, a Soldier distress-reinforcement signal, and a symmetry-
  detection scout, each of which produced a real, sometimes dramatic
  improvement on its one target matchup while causing a broader net
  regression elsewhere. The common thread each time: the condition that
  looked narrow and specific when diagnosed on one replay (e.g. "locally
  outnumbered") turned out to be common enough across many *other*,
  otherwise-fine matchups that gating behavior on it fired far more
  promiscuously than the diagnosis suggested. Worth explicitly checking,
  before implementing a fix, how often the triggering condition would
  have fired across other recent games, not just the one it was found on.
- **A verified, mechanistically-correct fix can still be a genuine no-op
  if the game state it needs never actually arises.** Sage-specific
  combat logic (a real, `javap`-confirmed vision-gap exploit, independently
  validated by an actual tournament team's results) tested as a complete
  no-op — zero behavioral difference across hundreds of games — not
  because the logic was wrong, but because the bot's own gold economy was
  so underdeveloped that it essentially never had a live Sage to apply
  the logic to, in any matchup tested, including the single most
  favorable one available. Several Sage/gold-economy refinements were
  independently blocked on this exact same upstream bottleneck before it
  was directly addressed — a reminder to check "does this feature ever
  actually engage" before concluding a fix doesn't work, and conversely
  that a fix judged not to matter *yet* can become valuable later once an
  upstream dependency changes (this exact Sage fix was successfully
  re-attempted, and worked, only after unrelated later iterations made
  real Sage production possible).
- **A permanent structure competing for an Archon's own build-ring tiles
  makes tile-occupancy blocking worse the more of them there are** — see
  the engine-facts section above. Any new structure type added near an
  Archon should be checked against this specific failure mode before
  being trusted, not just checked for resource cost.
- **When an unusual, consistent pattern shows up across several
  unrelated changes (same per-opponent numbers shifting the same way
  under three structurally different mechanisms), test whether it's a
  shared incidental effect (e.g. a bytecode-budget shift) before trusting
  any of the individual results** — done once directly in this project by
  deliberately testing a genuinely inert one-line change and confirming
  it reproduced the *original* baseline, not the anomalous shared
  pattern, which correctly falsified the "any code change" theory and
  narrowed the real (still narrow, real) common factor to changes
  touching one specific subsystem (Builder timing/count/placement).

## Strategic and gameplay insights specific to this matchup space

- **Two structurally different benchmark doctrines, each requiring a
  different answer.** `sample_camelcase` (a real tournament competitor,
  13th-16th at finals) never builds Laboratory or Sage at all — its whole
  strategy is a continuous Soldier-heavy production cycle plus static
  Watchtower defense, winning through early mass and per-unit combat
  efficiency, not economic sophistication. `sample_afinals` (a different
  finalist, "Most Adaptive Strategy" prize) is the opposite: it commits
  entirely to a gold/Laboratory/Sage economy, at one point building 111
  Sages and zero regular Soldiers in a single game. Confusing the two
  wastes iterations chasing the wrong lever against the wrong opponent —
  this happened at least once early in the project (attributing the
  gold/Sage economy to camelcase from an unverified assumption, corrected
  only once the actual vendored source was read directly).
- **Raw mass frequently beats accumulated economic sophistication in this
  ruleset**, at least against this project's own bot. Old, code-simple
  snapshots that never learned Watchtower/Laboratory/Sage investment at
  all can still win decisively against the current, far more
  sophisticated bot by simply out-producing Soldiers, because every
  economic investment (a Watchtower, a Laboratory, a mutate upgrade) is a
  build-turn *not* spent on a Soldier from the same single-Archon
  production bottleneck. This tension — more sophisticated economy vs.
  more raw army — never fully resolved in this project's favor and is
  one of the clearer remaining open questions (see below).
- **Combat "trade efficiency" gaps often trace back to production/
  survival asymmetries, not per-unit tactical quality.** Several
  extended investigations that started from "our Soldiers lose fights at
  even numbers" eventually found the real driver was upstream: which
  side loses Archons (and therefore production capacity) earlier, which
  side's units die in isolation vs. reinforced, or simple numeric
  compounding once one side's economy pulls ahead — not a per-engagement
  kill-ratio problem at all. A dedicated per-round attack/kill-volume
  comparison in one such case found combat was essentially *even*
  (10 kills vs. 9, comparable attack counts) while troop counts were
  still even, with the whole apparent "combat gap" only opening up after
  an Archon-count divergence had already occurred. Before attributing a
  loss to combat quality, check whether troop-count and Archon-count
  parity actually held during the window being blamed.
- **A single-Archon production bottleneck is a hard, structural ceiling**
  (roughly one build action per ~2 rounds) that no amount of Soldier-tier
  micro can overcome on its own — several combat-tactics threads
  eventually converged on this as the real limiting factor rather than
  targeting quality or formation. Multi-Archon maps partially route
  around this, but introduce their own failure mode: shared lead-pool
  contention between Archons is a *turn-order dominance* problem (whoever
  acts first in a round wins any contested lead surplus that round), not
  an aggregate-income problem — a 23-iteration investigation thread
  (Iterations 39-61) spent its first ~10 iterations on income-side fixes
  that provably never moved the starved Archon's build count at all,
  before correctly diagnosing the mechanism and eventually finding a
  fairness-yield fix that worked.
- **The vision-radius/action-radius asymmetries in this ruleset are real
  and exploitable in both directions** — Sage against smaller units (see
  engine facts above), and by extension worth checking for other unit
  pairs, though this project only found and exploited the one instance.

## Process and methodology lessons

- **The training algorithm evolved from a strict "did it win the
  motivating game" gate to a three-outcome mechanistic-verification
  framework**, because in practice, iterations that never won their
  specific motivating game but demonstrably engaged as designed and had
  a concrete, evidenced account for why that particular game couldn't
  flip anyway (usually a scale mismatch against a benchmark opponent)
  were nonetheless real, valuable, low-risk improvements — several of
  this project's best-verified accepts took exactly this path. The
  current framework (see `TRAINING_ALGORITHM.md` Step 6.4) makes this
  explicit as a first-class outcome, not a fallback rationalization.
- **Aggregate win-rate comparison hides exactly the information that
  matters; diffing two Gauntlet runs game-by-game and reading the diff's
  *shape* is much more informative.** This shift (documented at length
  under "Anti-patterns" above) came directly from noticing, repeatedly,
  that a small aggregate win-rate delta could mean either "a few maps
  flipped noise-style" or "one real, causal, concentrated regression" —
  and that the two require completely different responses (accept vs.
  reject-and-diagnose). The whole "baseline" concept — always diff
  against the most-recently-accepted iteration's own full Gauntlet run,
  never a stale reference — exists specifically so this diffing stays
  small and attributable to one change at a time.
- **A near-miss on the accept threshold is worth refining, not
  necessarily reverting outright** — several genuinely good ideas
  bounced between 58-59% for multiple attempts before a small
  parameter refinement (not a different mechanism) cleared the bar; the
  algorithm's "near miss" provision exists to capture this rather than
  discarding an idea that's directionally correct but not yet tuned.
- **Full-Gauntlet-scale verification catches regressions an 8-peer
  reproduction sample misses, disproportionately for changes to
  build/production priority.** Documented above under anti-patterns;
  formalized as an explicit exception in Step 6.5 (don't skip the full
  Gauntlet on a clean small sample for this category of change).
- **Never settle into an idle "nothing more to try" state — treat it as
  a signal to escalate to a bolder, structural attempt, not a stopping
  point.** This became an explicit standing rule after direct user
  feedback mid-project ("we're never going to defeat camelcase by being
  cautiously incremental") and was later formalized further into
  `TRAINING_ALGORITHM.md`'s "Never idle" and "High-risk structural
  exploration" sections: after a run of consecutive rejected incremental
  attempts in the same functional area, the next attempt must either
  target a genuinely different area or be a first-class bold structural
  change (a new mechanic, a re-architected subsystem), not a smaller
  version of the same idea tried again. Several of the project's
  highest-value accepts (Archon relocation, Watchtower/Laboratory
  build-out, the symmetry-tiebreak fix) came from exactly this track,
  not incremental tuning.
- **Track areas, not just individual games, to recognize when a string
  of rejects is concentrated in one functional area** rather than reading
  as diffuse bad luck — several multi-iteration threads (sandwich
  Archon-starvation, Miner-rescue, symmetry detection, repair-vs-build)
  only became recognizable as *closed, exhausted threads* once their
  member iterations were viewed together rather than each as an isolated
  attempt.
- **Read the actual opponent source code directly before hypothesizing
  about what it does** — an early, unverified assumption about which
  benchmark bot used the gold/Sage economy was wrong and cost at least
  one iteration's effort chasing the wrong opponent's mechanics before a
  direct source read corrected it. Whenever a "what does the opponent do
  differently" hypothesis is available to check against actual vendored
  source (this project vendors external bots specifically to enable
  this), check it directly rather than inferring purely from replay
  behavior.
- **Deep single-replay tracing consistently outperforms aggregate
  statistics for finding root causes.** Nearly every serious bug in this
  project's history was found by watching a specific game's `--metrics`/
  `--indicators`/`--all-actions`/`--moves` output end unexpectedly, not
  by code review or by staring at win-rate tables. The Miner beacon
  ping-pong bug (one of the largest single wins) was only found by
  looking at individual unit movement traces after aggregate mining-rate
  comparisons had already ruled out the obvious explanations.

## Open questions / unresolved threads

- **The per-map side (A/B) tempo asymmetry is only partially fixed.**
  All 8 originally-identified fixed-compass-order tie-break sites were
  converted to relative (map-center- or target/goal-proximity-based)
  tiebreaks, plus a related bug in combat target selection
  (`betterTarget`) and the Miner's rich-lead-tile search — together a net
  win on both fairness (the aggregate A/B win-rate gap roughly halved)
  and raw performance (overall win rate ended *higher* than the
  pre-fix baseline, not just fairer). But per-map, `chessboard` and
  `squer` remain sharply asymmetric even after the fix (`chessboard`:
  81% B-side losses / 0% A-side; `squer`: 89% B-side losses / 19%
  A-side, with the favored side having *flipped* during this same pass).
  A follow-up hypothesis (multi-Archon home-threat prioritization using
  "last writer wins" instead of comparing severity) was implemented,
  mechanistically real, but demonstrably didn't engage in the specific
  `chessboard` mirror-match used to test it (only one Archon was ever
  seriously besieged at a time in that game) — reverted as an honest
  negative result, not a disproof of the underlying bug. Two other
  candidate root causes were raised but not yet pursued: the vendored,
  2388-line generated `Dijkstra20` pathfinder was never fully audited for
  its own internal tie-breaking (a live experiment that bypassed it
  entirely made results *worse*, not better, suggesting it probably
  isn't the culprit, but this is inference from one indirect test, not a
  direct audit); and whatever mechanism produces `chessboard`'s specific
  81/0 split hasn't been traced to a specific line of code the way the
  original DIRS-order bug was.
- **`sample_camelcase` remains fully unbeaten (0/20) for the entire
  project's history**, despite dozens of iterations targeting adjacent
  mechanisms (targeting priority, retreat thresholds, focus fire,
  reinforcement, Watchtower defense, rubble-repositioning kiting). The
  single-Archon production ceiling and the raw-mass-beats-economy
  dynamic (see "Strategic insights" above) are the best-supported
  explanations for why, but no iteration has yet found a lever that
  closes this gap rather than narrowing it at the margins.
- **`sample_afinals` has plateaued around 15% (3/20)** across many
  consecutive checks spanning several economy-scaling iterations
  (more Watchtowers, a second and third Laboratory, the Sage retreat-HP
  fix) — each mechanistically verified to engage and help survival time
  in the motivating traces, none moving this specific tally. The
  diagnosed reason (afinals still runs meaningfully more Labs/Sages than
  our own scaled-up economy) is a scale-mismatch story consistent with
  the rest of this project's findings, but hasn't been independently
  re-verified recently at the current investment scale.
- **Live bytecode monitoring (Iteration 127) found real, if rare,
  overruns** (2 confirmed in a 720-game competitive Gauntlet, both in the
  longest, most unit-dense games, concentrated on the same map/side as
  the tempo-asymmetry findings above) directly tied to the recent
  Watchtower-count scaling threads. Not yet addressed with a targeted
  fix — current status is "monitored, not yet mitigated." Worth
  rechecking as more scaling iterations land, since the trend (not just
  the current rate) is the more important signal per the standing
  Iteration-0 requirement to keep this check permanently in place.
- **The gold/Sage economy is functional but still small in absolute
  scale relative to what `sample_afinals` runs.** Every individual piece
  (Laboratory placement, transmute, Sage build priority, Sage retreat
  threshold, Sage vision-gap kiting) is now verified working — the
  remaining question is whether further scaling (more Laboratories,
  earlier/more aggressive Sage investment) continues to pay off or hits
  its own structural ceiling the way Watchtower scaling appears to have
  (see "Strategic insights" above on single-Archon production limits).
