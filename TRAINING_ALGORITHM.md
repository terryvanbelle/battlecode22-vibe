# Battlecode Automatic Learning Algorithm

This document outlines a technique for writing a world-class Battlecode bot.

## The Gauntlet

Define "The Gauntlet" as a set of Battlecode bot implementations, which will be used to test our current implementation. Initially it will consist of the Battlecode example solution, plus whatever bot implementations we can find on the web.

To "run the Gauntlet", the current implementation will play games against each bot in the Gauntlet. For each bot in the Gauntlet, it will play on all boards provided by the Battlecode system. For each board, the implementation and the bot will play a game, and then a second game on the same board with the sides switched. Thus, running the Gauntlet means playing `2 * B * N` games, where `B` is the number of boards available, and `N` is the number of bots in the Gauntlet.

### Growing the Gauntlet

The Gauntlet should keep improving as a measure of fitness:

- **New iterations.** Every implementation that passes Step 3 is added (as in the main algorithm).
- **External bots.** Periodically — at least once every few iterations — search the internet (GitHub especially) for other Battlecode bot implementations, particularly strong ones (tournament finalists, well-documented post-mortems). Vendor any that compile cleanly as new opponents. Record each bot's source URL and licence.

### Peer opponents vs. benchmark opponents

Each bot in the Gauntlet is classified **peer** or **benchmark**:

- **Peer** — an opponent of roughly comparable strength: the current implementation wins somewhere in the ~30-90% range against it. Every frozen prior iteration starts as a peer.
- **Benchmark** — an opponent markedly stronger than the current implementation: it wins **< 30%** of that opponent's games. Newly vendored external bots that turn out to be much stronger (tournament finalists, etc.) start here.

Reclassification happens after each Gauntlet from that Gauntlet's result: a benchmark bot the current implementation now beats **≥ 30%** becomes a peer; a peer bot that has crushed the current implementation to **< 20%** for **two consecutive** Gauntlets becomes a benchmark.

Why the split: a benchmark bot is the *target*, not noise — the point of the exercise is to learn to beat bots like it — so it must stay in the Gauntlet as a scoreboard even while we lose every game to it. But it should not gate progress or dominate the game budget while that gap is being closed.

- **Step 3 (the accept gate) is evaluated over peer games only.** Benchmark games are recorded and tracked in the logfile but excluded from the `WinPct` calculation. When every peer bar is cleared and only benchmark bots remain unbeaten, the implementation still passes Step 3 and is snapshotted.
- **Step 4 (pick a losing game) draws from *all* games, benchmark included.** A benchmark loss is often the most valuable thing to work on — but see the note on benchmark losses under Step 5: they very often reveal a real, worth-fixing bug that doesn't itself flip the benchmark matchup, because the benchmark gap is structural (an economy/production-scale mismatch) rather than a chain of small tactical bugs. Treat a benchmark loss as a rich *source* of hypotheses, not a target that Step 6.4 needs to flip.
- **Benchmark bots are played only every `BenchmarkEvery` Gauntlets** (see hyperparameters), to save the game budget. On the Gauntlets in between, run peers only. Always play them on the Gauntlet where an iteration is a snapshot candidate.

### Retiring bots from the Gauntlet

To keep `N` (and therefore the number of games) bounded, retire opponents that no longer provide signal:

> After a Gauntlet completes, any opponent that the current implementation has beaten in **at least 80%** of that opponent's `2 * B` games in **two consecutive** Gauntlets is removed from the Gauntlet.

This applies to reference bots, external bots, and frozen prior iterations alike. Track each opponent's per-Gauntlet win rate in the logfile so the two-consecutive-Gauntlet condition can be evaluated. A retired bot may be re-added later if a new iteration regresses badly against the opponents nearest it in strength.

Opponents we *lose* to are **not** retired — a benchmark bot at 0% is the target, not noise (see above). Only the ≥80%-domination rule retires bots.

### The baseline, and comparing Gauntlet runs by shape

Every accept/reject decision in Step 3 and Step 6 below is made by **diffing two Gauntlet runs game-by-game** (same `(opponent, map, side)` key in both), not by comparing aggregate win rates alone. Aggregate win rate hides exactly the information that matters: *which* games changed and *how consistently*.

- **The baseline** is the most recently accepted iteration's own full Gauntlet run. It is superseded every time Step 3 accepts a new iteration — always diff against the *current* baseline, not an older one. This keeps every diff small and attributable to the one change actually being evaluated, instead of accumulating a growing list of "already-known" flips to remember.
- **Reading a diff's shape.** A handful of the Gauntlet's maps (this project has repeatedly found `chessboard`, `squer`, `sandwich`, `maptestsmall`, and `pillars` to be the worst offenders, but any map can show this) are near-mirror-sensitive between closely-related iterations: a tiny, unrelated perturbation early in the game can cascade into a flipped outcome hundreds of rounds later, in either direction, for no causal reason connected to the change being tested. Use this rule of thumb:
  - **Likely noise, not disqualifying:** a small number of diffs, scattered across different maps/opponents, mixed in direction (some win→loss, some loss→win), especially if concentrated on maps already known to be fragile.
  - **Likely a real, causal regression — investigate before accepting:** diffs that are **one-directional** (overwhelmingly or entirely win→loss, or vice versa) and/or **concentrated on one map/side across many different opponents**. A regression that's real will tend to reproduce the same way against every opponent sharing that map/side, because the cause is in the code, not in opponent-specific chaos; noise won't show that consistency.
  - When in doubt, reproduce the specific flipped game directly (`TEAM_A=<opponent> TEAM_B=bot ...`) and trace it (`--metrics`/`--indicators`/`--all-actions`) before deciding — the diff tells you *where* to look, not the final answer.

## Iteration 0

The initial implementation of our Battlecode bot will be very simple. The bot will create a single instance of a low-cost unit, which will be instructed to move about the board at random. It will be instrumented to store arbitrary strings of information in the gameplay save file, for use in analyzing games.

## The Algorithm

The algorithm for generating and improving a bot is defined in this section. This algorithm relies on the following hyperparameters:

- *WinPct*: The percentage of **peer** games (see "Peer opponents vs. benchmark opponents") that the current implementation must win to be added to the Gauntlet. Set this to 60%.
- *MaxHypothesisIterations*: The maximum number of times to try generating a verified hypothesis. Set this to 10.
- *MaxSolutionsIterations*: The maximum number of times to try generating a winning solution. Set this to 10.
- *BenchmarkEvery*: Benchmark opponents are played only once every this many Gauntlets (peers are played every Gauntlet). Set this to 3.
- *ReproSampleSize*: The number of peers used in Step 6.5's cheap reproduction sample. Set this to 8. Each time Step 6.5 runs, pick this many peers evenly spaced across the *current* peer roster, sorted by iteration number — recomputed fresh each time, so the sample tracks retirements and new additions automatically rather than going stale.
- *NearMissMargin*: How many percentage points below *WinPct* still counts as a "near miss" worth refining further, rather than abandoning outright. Set this to 5.
- *MaxNearMissRefinements*: The number of additional solution refinements allowed for a hypothesis whose solution qualifies as a near miss (Step 3.1), on top of *MaxSolutionsIterations*. Set this to 3.
- *MaxConsecutiveRejects*: The number of consecutive Step 6 solution attempts — rejected at any stage, whether Step 6.4.3's mechanistic check, Step 6.5's early-abort, or Step 3 itself — allowed before the *next* attempt is barred from targeting the same functional area again. Set this to 3. See "Never idle" under Step 4.

1. Create Iteration 0. Set this to be our current implementation.
2. Run the current implementation against the Gauntlet (all peers, all boards, both sides; benchmark bots too if this Gauntlet is due one per *BenchmarkEvery*).
3. Evaluate the Step 2 Gauntlet against the current baseline (see "The baseline" above), by shape (see "Reading a diff's shape" above), not aggregate win rate alone.
   1. **Accept:** if peer *WinPct* is met or exceeded, and the diff shows no unresolved real regression (an accepted candidate can still contain diffs, as long as they read as noise, or as real-but-already-intentional changes this iteration was specifically trying to make) — add it to the Gauntlet, snapshot it as the new most-recently-accepted iteration, **set this Gauntlet run as the new baseline**, and go to Step 4.
   2. **Near miss:** if this Gauntlet run was evaluating a Step 6 solution (not Iteration 0, and not a Step 2 loop with no pending solution), peer *WinPct* is within *NearMissMargin* points of *WinPct*, the diff shows no real regression by the shape heuristic, and fewer than *MaxNearMissRefinements* refinements have been spent on the current hypothesis: treat this as license to keep refining the same solution — go back to Step 6.1. This refinement does not count against *MaxSolutionsIterations*.
   3. **Reject:** otherwise — the diff shows a real regression, or the shortfall is too large to be a near miss.
      1. Reproduce and trace the flipped game(s) directly to characterize *why* they flipped (see "Reading a diff's shape").
      2. If this produces a specific, well-understood failure mode of the just-tested solution (not just "it didn't work," but a concrete causal account — e.g. "this map's total army is too small for a per-unit distance comparison to leave any numerical slack") — this is grounds for an immediate, targeted refinement addressing exactly that failure mode: go back to Step 6.1. This still counts against *MaxSolutionsIterations*.
      3. Otherwise, undo the implementation changes back to the last-accepted iteration and go to Step 4.
4. Select a game from Step 2 where the current implementation lost the game. If no such game exists, then go to Step 2.
   - **Prefer fresh territory.** If the last several Step 4/5 cycles have converged on the same "this functional area is already correctly tuned, no safe fix available" conclusion (log-visible in `TRAINING_LOG.md`), prefer a losing game whose likely cause lies in a different, less-recently-explored part of the bot. This is a soft preference, not a hard rule — a genuinely new, concrete trace in a "settled" area is still worth pursuing; the point is to not re-litigate a settled trade-off from a stale or repeated angle.
   - **Never idle.** There is no valid state where nothing is being attempted or verified. Waiting on a Gauntlet run you already started is normal execution, not idling — but finishing a search, finding nothing new, and simply stopping is not an acceptable outcome of this step, ever. If the last *MaxConsecutiveRejects* Step 6 attempts were all rejected, the *next* attempt is barred from being another incremental try in the *same functional area* as those rejects (see "Track areas, not just games" below) — it must be either a losing game in a genuinely different area, or a "High-risk structural exploration" attempt (below). Pick whichever is better-motivated; high-risk is not a last resort reached only once incremental options run out; it's an equally valid, expected first move once a series of incremental attempts has stalled. A session is never "done" short of the user ending it — if literally nothing else presents itself, go straight to "High-risk structural exploration."
   - **Track areas, not just games.** Maintain a running sense — visible in `TRAINING_LOG.md`, not just implicit — of which functional areas of the bot (e.g. Miner economy/targeting, Archon build priority, Soldier combat targeting, Soldier reinforcement/movement, Builder/Watchtower/Laboratory placement, gold economy, map adaptation) have been recently attempted, so a string of rejects can be recognized as concentrated in one area rather than read as "many different things failed."

### High-risk structural exploration

Closing the gap against an opponent whose whole doctrine differs structurally from ours (see "Peer opponents vs. benchmark opponents") will not happen through incremental, single-losing-game tactical fixes alone. Some of that gap only closes by trying a fundamentally different mechanism or strategy outright and letting the Gauntlet judge it — the way Iterations 34 (Archon relocation), 58/64 (the gold economy build-out), and 78 (Watchtower relocation) already did in this project's history. This is a first-class, equally legitimate track alongside Step 4-6's incremental loop, not a fallback to reach for only once every incremental idea is exhausted — use it regularly, on its own merits, whenever it's the better-motivated move.

- **Trigger.** Use this track whenever any of: (a) the *MaxConsecutiveRejects* condition above fires, (b) Step 4 finds no fresh incremental target after genuinely checking several different losing games, or (c) a bold idea has simply been sitting unactioned in `TRAINING_LOG.md`'s "Next" notes for a while and nothing more urgent is pending. Don't wait for all three, and don't wait for a "good moment" — the point is to default to action.
- **Process.** Skip Step 4/5's "find one losing game, form a narrow hypothesis" framing:
  1. Name a capability gap or strategic difference versus a strong opponent — a benchmark bot's doctrine, or a mechanic never attempted — not necessarily traced to one specific game.
  2. Describe the change at whatever scope it actually needs. A new mechanic, a restructured subsystem, a different opening — don't artificially shrink an idea to fit a one-line diff if it genuinely needs more than that.
  3. Implement it, then verify with the same discipline as Step 6.4-6.5 (mechanistic check, reproduction sample, full Gauntlet, diff by shape) before Step 3 decides accept/reject.
  4. A rejected structural attempt is not a failure state, any more than a rejected tactical one is. Log what was learned and immediately pick the next thing — exactly what this whole section exists to make automatic.
5. Hypothesis generation
   1. Form a hypothesis for why the current implementation lost.
   2. Determine a set of variables and threshold values that would, if passed, verify that the hypothesis is true.
   3. If necessary, update the instrumentation of the current implementation to store the information required to verify the hypothesis as defined in Step 5.2.
   4. Re-run the losing game selected in Step 4.
   5. From the game's save file, extract the necessary variables defined in Step 5.2. If the hypothesis is not verified according to the criteria defined in Step 5.2, go to Step 5.9.
   6. Generality check: if one or more *other* losing games from Step 2 exist (prefer one against a different opponent or map than Step 4's selection, since a same-symptom game against a different opponent is the strongest test), re-run at least one of them and check whether the same Step 5.2 variables/thresholds verify the hypothesis there too. Record the result in the logfile either way.
   7. If the hypothesis held on the game(s) checked in Step 5.6 (or no other losing game existed to check against), continue to Step 5.8. If it did not hold, the games most likely share a symptom but not a root cause — conflating them wastes a Step 6 solution attempt on a hypothesis that only explains part of what it claims to. Either narrow the hypothesis to the scope it actually explains and continue to Step 5.8 with that narrower scope noted in the logfile, or, if it has no independent support left, treat it as unverified and go to Step 5.9.
   8. **Act-on-it check.** A verified hypothesis is not automatically worth a Step 6 attempt. Check `TRAINING_LOG.md` for whether a prior iteration deliberately established the behavior the hypothesis wants to change, and why. If implementing the obvious fix would revert or directly conflict with that documented reasoning — rather than genuinely superseding it with new evidence — log this as a verified-but-not-actionable diagnostic finding and go to Step 4 to select a different losing game. This does not count against *MaxHypothesisIterations*. Otherwise, continue to Step 6.
   9. If *MaxHypothesisIterations* hypotheses have been generated for this game, and none of them have been verified, go back to Step 4 and select a different losing game.
   10. Otherwise, go back to step 5.1.
6. Solution generation
   1. Based on the verified hypothesis from Step 5, describe a solution that would cause the current implementation to win instead of lose.
   2. Implement this solution in the current implementation.
   3. Re-run the losing game selected in Step 4.
   4. **Mechanistic verification.** Determine which of the following holds, using the replay's own instrumentation (`--metrics`/`--indicators`/`--all-actions`, or equivalent) as evidence, not just the win/loss column:
      1. **The game is now won.** Strong, direct evidence — go to Step 6.5.
      2. **The game is still lost, but the fix demonstrably engaged and produced the behavior change it was designed to produce** (e.g. the traced mechanism fires and does what it should, a measured quantity moves in the intended direction, or the game survives measurably longer/the death cause changes) **and there is a specific, evidenced account for why this particular game couldn't flip regardless** (e.g. the opponent's scale advantage in the relevant resource is too large for this fix alone to close). This is a real, valid basis for proceeding — most of this session's accepted iterations took this path. Log the mechanistic evidence and the account for the non-flip, and go to Step 6.5.
      3. **Neither of the above** — no evidence the fix changed anything, or it demonstrably didn't engage. Go to Step 6.7.
   5. **Cheap reproduction sample.** Run the current implementation against *ReproSampleSize* peers (see hyperparameters), all boards, both sides. Diff game-by-game against the current baseline, by shape (see "Reading a diff's shape").
      - If the diff already shows an unambiguous real regression at this small scale (a one-directional and/or single-map/multi-opponent sweep), don't spend a full Gauntlet confirming what's already clear: treat this as a failed solution and go to Step 6.7.
      - Otherwise (clean, or ambiguous, or ordinary noise) go to Step 2 to run the full Gauntlet. This is true even when the reproduction sample looks completely clean: several real regressions this project has found (see "The baseline, and comparing Gauntlet runs by shape") were invisible at 8-peer scale and only showed up at full scale, particularly for changes to build/production priority, resource thresholds, or any priority ordering between competing objectives read from a shared signal. Don't skip Step 2 to save time on this category of change.
   6. (Step 2/Step 3 now runs, using this solution as the candidate. Return here only if Step 3 rejects it.)
   7. Undo the implementation changes from Step 6.2.
   8. If *MaxSolutionsIterations* solutions have been generated for this hypothesis, and none of them has passed Step 3, go back to Step 4 and select a different losing game.
   9. Otherwise, go back to step 6.1.

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
path in the corresponding `TRAINING_LOG.md` entry. **Do this in the same
commit as the iteration's accept/reject decision, not as a follow-up** --
this fell behind for 14 straight iterations in practice when treated as a
separate step to remember later.

### Round-count metric

Win/loss alone discards information: a change that makes us win faster, or
lose more slowly, is real progress even when it doesn't flip the outcome
column -- and the reverse is a real (if invisible-to-WinPct) regression.
This is exactly the kind of evidence Step 6.4.2 looks for. On every
reproduction sample (Step 6.5) or full Gauntlet run, run
`tools/compare_gauntlets.py <baseline_dir> <candidate_dir>` (the baseline
being the current baseline's own Gauntlet run) and log the result: how many
games flipped outcome, and for the games that didn't, the net round-delta
(wins counted faster-is-positive, losses counted slower-is-positive) plus
the improved/worsened/unchanged split.

This is a supporting signal, not a standalone gate -- round count doesn't
capture everything that matters on its own (a game that drags to the
2000-round cap in a stall isn't obviously "better" than a clean loss at
round 400, and the metric has no way to distinguish those). Use it to back
up a Step 6.4.2 mechanistic-verification claim, and to judge whether a Step
3.2 near miss represents real margin progress across the pool or is just
treading water.
