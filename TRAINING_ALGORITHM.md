# Battlecode Automatic Learning Algorithm

This document outlines a technique for writing a world-class Battlecode bot.

## The Gauntlet

Define "The Gauntlet" as a set of Battlecode bot implementations, which will be used to test our current implementation. Initially it will consist of the Battlecode example solution, plus whatever bot implementations we can find on the web.

To "run the Gauntlet", the current implementation will play games against each bot in the Gauntlet. For each bot in the Gauntlet, it will play on all boards provided by the Battlecode system. For each board, the implementation and the bot will play a game, and then a second game on the same board with the sides switched. Thus, running the Gauntlet means playing `2 * B * N` games, where `B` is the number of boards available, and `N` is the number of bots in the Gauntlet.

### Growing the Gauntlet

The Gauntlet should keep improving as a measure of fitness:

- **New iterations.** Every implementation that passes Step 3 is added (as in the main algorithm).
- **External bots.** Periodically — at least once every few iterations — search the internet (GitHub especially) for other Battlecode 2022 bot implementations, particularly strong ones (tournament finalists, well-documented post-mortems). Vendor any that compile cleanly as new opponents. Record each bot's source URL and licence.

### Peer opponents vs. benchmark opponents

Each bot in the Gauntlet is classified **peer** or **benchmark**:

- **Peer** — an opponent of roughly comparable strength: the current implementation wins somewhere in the ~30–90% range against it. Every frozen prior iteration starts as a peer.
- **Benchmark** — an opponent markedly stronger than the current implementation: it wins **< 30%** of that opponent's games. Newly vendored external bots that turn out to be much stronger (tournament finalists, etc.) start here.

Reclassification happens after each Gauntlet from that Gauntlet's result: a benchmark bot the current implementation now beats **≥ 30%** becomes a peer; a peer bot that has crushed the current implementation to **< 20%** for **two consecutive** Gauntlets becomes a benchmark.

Why the split: a benchmark bot is the *target*, not noise — the point of the exercise is to learn to beat bots like it — so it must stay in the Gauntlet as a scoreboard even while we lose every game to it. But it should not gate progress or dominate the game budget while that gap is being closed.

- **Step 3 (the accept gate) is evaluated over peer games only.** Benchmark games are recorded and tracked in the logfile but excluded from the `WinPct` calculation. When every peer bar is cleared and only benchmark bots remain unbeaten, the implementation still passes Step 3 and is snapshotted.
- **Step 4 (pick a losing game) draws from *all* games, benchmark included.** A benchmark loss is often the most valuable thing to work on.
- **Benchmark bots are played only every `BenchmarkEvery` Gauntlets** (see hyperparameters), to save the game budget. On the Gauntlets in between, run peers only. Always play them on the Gauntlet where an iteration is a snapshot candidate.

### Retiring bots from the Gauntlet

To keep `N` (and therefore the number of games) bounded, retire opponents that no longer provide signal:

> After a Gauntlet completes, any opponent that the current implementation has beaten in **at least 80%** of that opponent's `2 * B` games in **two consecutive** Gauntlets is removed from the Gauntlet.

This applies to reference bots, external bots, and frozen prior iterations alike. Track each opponent's per-Gauntlet win rate in the logfile so the two-consecutive-Gauntlet condition can be evaluated. A retired bot may be re-added later if a new iteration regresses badly against the opponents nearest it in strength.

Opponents we *lose* to are **not** retired — a benchmark bot at 0% is the target, not noise (see above). Only the ≥80%-domination rule retires bots.

## Iteration 0

The initial implementation of our Battlecode bot will be very simple. The bot will create a single instance of a low-cost unit, which will be instructed to move about the board at random. It will be instrumented to store arbitrary strings of information in the gameplay save file, for use in analyzing games.

## The Algorithm

The algorithm for generating and improving a bot is defined in this section. This algorithm relies on the following hyperparameters:

- *WinPct*: The percentage of **peer** games (see "Peer opponents vs. benchmark opponents") that the current implementation must win to be added to the Gauntlet. Set this to 60%.
- *MaxHypothesisIterations*: The maximum number of times to try generating a verified hypothesis. Set this to 10.
- *MaxSolutionsIterations*: The maximum number of times to try generating a winning solution. Set this to 10.
- *BenchmarkEvery*: Benchmark opponents are played only once every this many Gauntlets (peers are played every Gauntlet). Set this to 3.
- *MirrorCheckMinWinPct*: The minimum win rate a solution must achieve in a mirror check (Step 6.5) against the most recently accepted iteration before it's worth spending a full Gauntlet run on. Set this to 35%. (A near-mirror matchup between adjacent iterations of the same lineage is inherently noisy and usually lands in a 40-55% band even for a genuinely good solution -- this threshold exists only to catch outright collapses, not to demand parity.)
- *NearMissMargin*: How many percentage points below *WinPct* still counts as a "near miss" worth refining further, rather than abandoning outright. Set this to 5.
- *MaxNearMissRefinements*: The number of additional solution refinements allowed for a hypothesis whose solution qualifies as a near miss (Step 3.1), on top of *MaxSolutionsIterations*. Set this to 3.

1. Create Iteration 0. Set this to be our current implementation.
2. Run the current implementation against the Gauntlet.
3. If the current implementation wins at least *WinPct* of the **peer** games in Step 2, add it to the Gauntlet, snapshot it as the new most-recently-accepted iteration, and go to Step 4.
   1. Otherwise, if this Gauntlet run was evaluating a Step 6 solution (not Iteration 0, and not a Step 2 loop with no pending solution) and it qualifies as a **near miss** -- peer WinPct is within *NearMissMargin* points of *WinPct*, **and** comparing this Gauntlet's per-opponent peer win rates to the last-accepted baseline shows no peer opponent's win rate dropping (a uniform-or-improving shortfall, not a mixed regression) -- and fewer than *MaxNearMissRefinements* refinements have been spent on the current hypothesis, treat this as license to keep refining the same solution: go back to Step 6.1. This refinement does not count against *MaxSolutionsIterations*.
   2. Otherwise, undo the implementation changes back to the last-accepted iteration and go to Step 4.
4. Select a game from Step 2 where the current implementation lost the game. If no such game exists, then go to Step 2.
5. Hypothesis generation
   1. Form a hypothesis for why the current implementation lost.
   2. Determine a set of variables and threshold values that would, if passed, verify that the hypothesis is true.
   3. If necessary, update the instrumentation of the current implementation to store the information required to verify the hypothesis as defined in Step 5.2.
   4. Re-run the losing game selected in Step 4.
   5. From the game's save file, extract the necessary variables defined in Step 5.2. If the hypothesis is not verified according to the criteria defined in Step 5.2, go to Step 5.8.
   6. Generality check: if one or more *other* losing games from Step 2 exist (prefer one against a different opponent or map than Step 4's selection, since a same-symptom game against a different opponent is the strongest test), re-run at least one of them and check whether the same Step 5.2 variables/thresholds verify the hypothesis there too. Record the result in the logfile either way.
   7. If the hypothesis held on the game(s) checked in Step 5.6 (or no other losing game existed to check against), continue to Step 6. If it did not hold, the games most likely share a symptom but not a root cause -- conflating them wastes a Step 6 solution attempt on a hypothesis that only explains part of what it claims to. Either narrow the hypothesis to the scope it actually explains and continue to Step 6 with that narrower scope noted in the logfile, or, if it has no independent support left, treat it as unverified and go to Step 5.8.
   8. If *MaxHypothesisIterations* hypothesis have been generated for this game, and none of them have been verified, go back to Step 4 and select a different losing game.
   9. Otherwise, go back to step 5.1.
6. Solution generation
   1. Based on the verified hypothesis from Step 5, describe a solution that would cause the current implementation to win instead of lose.
   2. Implement this solution in the current implementation.
   3. Re-run the losing game selected in Step 4.
   4. If the current implementation has not won the game, go to Step 6.6.
   5. Mirror check: play the current implementation against the most recently accepted iteration (`2 * B` games, one per board per side, as in a normal Gauntlet round). If the win rate is below *MirrorCheckMinWinPct*, treat this as a failed solution and go to Step 6.6. Otherwise, go back to Step 2 -- this is a cheap (`2 * B`-game) gate against an expensive (`2 * B * N`-game) full Gauntlet run, meant to catch a collapse before paying for one.
   6. Undo the implementation changes from Step 6.2.
   7. If *MaxSolutionsIterations* solutions have been generated for this hypothesis, and none of them has passed the mirror check, go back to Step 4 and select a different losing game.
   8. Otherwise, go back to step 6.1.

## Logging

At each step, log any summary statistics and observations in a logfile.

### Replay archive

For each iteration (accepted or rejected), check the single most interesting
game from that iteration's Gauntlet run into `replays/` in git -- the replay
that best illustrates the iteration's hypothesis or result (e.g. the losing
game analyzed in Step 4/5, or the game whose outcome changed most clearly
after the Step 6 fix). Name it
`replays/iterNN_<opponent>_<map>_bot<side>.bc22`, where `NN` is the iteration
number. This is a human-browsable record of how play evolved over the course
of the run, independent of `gauntlet/` (git-ignored, ephemeral) and the
per-iteration source snapshots in `src/g_iterNN/`. Reference the checked-in
path in the corresponding `TRAINING_LOG.md` entry.

### Round-count metric (tracked, not yet gating)

Win/loss alone discards information: a change that makes us win faster, or
lose more slowly, is real progress even when it doesn't flip the outcome
column -- and the reverse is a real (if invisible-to-WinPct) regression. On
every full Gauntlet run that's a Step 3 candidate or a near-miss under Step
3.1, run `tools/compare_gauntlets.py <baseline_dir> <candidate_dir>` (the
baseline being the last-accepted iteration's own Gauntlet run) and log the
result: how many games flipped outcome, and for the games that didn't, the
net round-delta (wins counted faster-is-positive, losses counted
slower-is-positive) plus the improved/worsened/unchanged split.

This is deliberately **not** part of the Step 3 accept gate yet -- round
count doesn't capture everything that matters on its own (a game that drags
to the 2000-round cap in a stall isn't obviously "better" than a clean loss
at round 400, and the metric has no way to distinguish those), and it needs
more runs under its belt before trusting it to gate anything. Use it
descriptively for now, especially to judge whether a Step 3.1 near miss
represents real margin progress across the pool or is just treading water.
