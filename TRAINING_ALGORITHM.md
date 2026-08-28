# Battlecode Automatic Learning Algorithm

This document outlines a technique for writing a world-class Battlecode bot.

## The Gauntlet

Define "The Gauntlet" as a set of Battlecode bot implementations, which will be used to test our current implementation. Initially it will consist of the Battlecode example solution, plus whatever bot implementations we can find on the web.

To "run the Gauntlet", the current implementation will play games against each bot in the Gauntlet. For each bot in the Gauntlet, it will play on all boards provided by the Battlecode system. For each board, the implementation and the bot will play a game, and then a second game on the same board with the sides switched. Thus, running the Gauntlet means playing `2 * B * N` games, where `B` is the number of boards available, and `N` is the number of bots in the Gauntlet.

### Growing the Gauntlet

The Gauntlet should keep improving as a measure of fitness:

- **New iterations.** Every implementation that passes Step 3 is added (as in the main algorithm).
- **External bots.** Periodically — at least once every few iterations — search the internet (GitHub especially) for other Battlecode 2022 bot implementations, particularly strong ones (tournament finalists, well-documented post-mortems). Vendor any that compile cleanly as new opponents. Record each bot's source URL and licence.

### Retiring bots from the Gauntlet

To keep `N` (and therefore the number of games) bounded, retire opponents that no longer provide signal:

> After a Gauntlet completes, any opponent that the current implementation has beaten in **at least 90%** of that opponent's `2 * B` games in **two consecutive** Gauntlets is removed from the Gauntlet.

This applies to reference bots, external bots, and frozen prior iterations alike. Track each opponent's per-Gauntlet win rate in the logfile so the two-consecutive-Gauntlet condition can be evaluated. A retired bot may be re-added later if a new iteration regresses badly against the opponents nearest it in strength.

## Iteration 0

The initial implementation of our Battlecode bot will be very simple. The bot will create a single instance of a low-cost unit, which will be instructed to move about the board at random. It will be instrumented to store arbitrary strings of information in the gameplay save file, for use in analyzing games.

## The Algorithm

The algorithm for generating and improving a bot is defined in this section. This algorithm relies on the following hyperparameters:

- *WinPct*: The percentage of games that the current implementation must win to be added to the Gauntlet. Set this to 60%.
- *MaxHypothesisIterations*: The maximum number of times to try generating a verified hypothesis. Set this to 5.
- *MaxSolutionsIterations*: The maximum number of times to try generating a winning solution. Set this to 5.

1. Create Iteration 0. Set this to be our current implementation.
2. Run the current implementation against the Gauntlet.
3. If the current implementation wins at least *WinPct* of the games in Step 2, add it to the Gauntlet.
4. Select a game from Step 2 where the current implementation lost the game. If no such game exists, then go to Step 2.
5. Hypothesis generation
   1. Form a hypothesis for why the current implementation lost.
   2. Determine a set of variables and threshold values that would, if passed, verify that the hypothesis is true.
   3. If necessary, update the instrumentation of the current implementation to store the information required to verify the hypothesis as defined in Step 5.2.
   4. Re-run the losing game selected in Step 4.
   5. From the game's save file, extract the necessary variables defined in Step 5.2. If the hypothesis is verified according to the criteria defined in Step 5.2, continue to step 6.
   6. If *MaxHypothesisIterations* hypothesis have been generated for this game, and none of them have been verified, go back to Step 4 and select a different losing game.
   7. Otherwise, go back to step 5.1.
6. Solution generation
   1. Based on the verified hypothesis from Step 5, describe a solution that would cause the current implementation to win instead of lose.
   2. Implement this solution in the current implementation.
   3. Re-run the losing game selected in Step 4.
   4. If the current implementation now has won the game, go back to Step 2.
   5. Undo the implementation changes from Step 6.2.
   6. If *MaxSolutionsIterations* solutions have been generated for this hypothesis, and none of them has caused the current implementation to win the game, go back to Step 4 and select a different losing game.
   7. Otherwise, go back to step 6.1.

## Logging

At each step, log any summary statistics and observations in a logfile.
