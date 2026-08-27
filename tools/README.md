# bc22_replay.py — Battlecode 2022 replay → human-readable text

Turns a `.bc22` replay file into a plain-text transcript: a per-round event
log plus a one-character-per-square ASCII rendering of the board at every game
step, with resource totals and unit counts.

## Setup

`.bc22` files are gzipped [FlatBuffers](https://flatbuffers.dev/) (schema:
[`battlecode.fbs`](battlecode.fbs), root type `GameWrapper`). You need the
`flatbuffers` Python runtime (`numpy` optional, speeds up vector reads):

```
python3 -m venv tools/.venv
tools/.venv/bin/pip install flatbuffers numpy
```

`bc22_schema.py` is the generated binding, already checked in. To regenerate it
(e.g. after a schema change), run [`gen-schema.sh`](gen-schema.sh) — needs
`flatc` (`brew install flatbuffers`).

## Tests

```
tools/run-tests.sh            # or: tools/.venv/bin/python tools/test_bc22_replay.py
```

`test_bc22_replay.py` builds a tiny synthetic replay in memory with the
FlatBuffers builder (no fixture file) and checks parsing, the live
lead/gold/rubble reconstruction, the Vortex transform, board rendering, and the
CLI options. If any `matches/*.bc22` are checked out it also runs the full
pipeline against the first one and asserts invariants (lead never negative,
round numbering consistent with the footer).

## Usage

```
tools/.venv/bin/python tools/bc22_replay.py REPLAY [options]

  REPLAY                a .bc22 file (gzipped or raw)
  -o FILE               write here instead of stdout
  --match N             only this match (multi-match files)
  --from R  --to R      restrict the rendered round range
  --step N              render every Nth round (default: every round)
  --terrain rubble|lead|none   backdrop for empty squares on the ROBOTS board
                               (default rubble)
  --no-board            skip the ASCII boards entirely
  --no-lead-map         show only the ROBOTS board, drop the per-round LEAD map
  --no-events           skip the event log (boards + resources only)
  --moves               list every individual robot move
  --health              per-robot health deltas each round
  --all-actions         one line per mining op (default: per-team count)
  --map-detail          also show the live RUBBLE map every round
  --indicators          show robots' indicator strings (their debug logs)
  --metrics             emit a per-round CSV of per-team aggregates, no narrative
```

### `--metrics`

`--metrics` replaces the narrative output with one CSV row per (selected) round:

```
round,winner,A_lead,A_gold,A_miners,A_soldiers,A_builders,A_sages,A_labs,
A_watchtowers,A_archons,A_archonHP,A_attacks,A_solCx,A_solCy,A_solSpread,B_...
```

`A_attacks` is cumulative; `A_solCx/Cy` is the soldier centroid and `A_solSpread`
the mean soldier distance from it (army cohesion). Respects `--match/--from/--to
/--step`. Use it to diff the current bot against an opponent and see where a game
turned:

```
tools/.venv/bin/python tools/bc22_replay.py game.bc22 --metrics --step 25 | column -t -s,
```

Each rendered round shows, by default, the **ROBOTS** board (units over the live
rubble) and the live **LEAD** map beside it; the **GOLD** map appears on rounds
where gold is on the field, and the **RUBBLE** map re-prints whenever a Vortex
changes it. All are one character per square, so you can watch the lead field
deplete and regenerate over the course of the game.

Examples:

```
# full transcript, every round
tools/.venv/bin/python tools/bc22_replay.py matches/foo.bc22 -o foo.txt

# quick skim: board every 25 rounds
tools/.venv/bin/python tools/bc22_replay.py matches/foo.bc22 --step 25

# zoom in on a fight with full detail
tools/.venv/bin/python tools/bc22_replay.py matches/foo.bc22 \
    --from 300 --to 340 --moves --health --indicators --all-actions --map-detail
```

## Board legend

```
robots : A L W M B S G  = Archon Lab Watchtower Miner Builder Soldier saGe
         UPPERCASE = team A (id 1), lowercase = team B (id 2)
terrain (--terrain rubble, default): '.' 0-9  ':' 10-33  'o' 34-66  '#' 67-100
terrain (--terrain lead): ' ' 0  ',' 1-9  ':' 10-24  '+' 25-49  '#' 50-99  '@' 100+
y axis points north (up); origin (0,0) is bottom-left.
```

The header prints the STARTING rubble and lead maps once. The board and the
per-round `map:` line then track the **live** state as it changes.

## Live map reconstruction

The maps are not static, and the tool reconstructs their per-round state:

- **Lead / gold per tile.** The replay streams every per-tile resource change
  (`leadDropLocations`/`Values`, `goldDrop…`): −1 per mine action, the reclaim
  drop when a robot dies, and the −10% hit from an Abyss. These are applied
  verbatim. **Lead regeneration is not streamed** — the engine adds +5 Pb to
  every non-empty tile every 20 rounds *silently* — so the tool recomputes it
  from `GameHeader.constants` (`ADD_LEAD` / `ADD_LEAD_EVERY_ROUNDS`), applied at
  end of round, matching `GameWorld.processEndOfRound`. With regen modelled the
  reconstruction stays exactly non-negative for whole games (verified on maps
  with Abyss anomalies).
- **Rubble.** Static except the **Vortex** anomaly, which mirrors or rotates the
  whole rubble grid. The `VORTEX` action carries the mode (0 rotate / 1 mirror
  columns / 2 mirror rows); the tool applies the same transform as the engine's
  `flipRubble*`/`rotateRubble` and re-prints the rubble map after each Vortex.

## Known limitations

- Team resource totals are reconstructed by summing the per-round deltas; the
  round-1 delta already includes the 200 Pb starting stipend, so totals start
  from 0 + deltas and match the engine.
- Robot HP is `base health + Σ CHANGE_HEALTH deltas`; building "prototype"
  starting HP is not modelled (buildings are shown at full base HP until a
  CHANGE_HEALTH event corrects them).
- Charge / Fury anomalies affect robots, not the map, and are shown only as
  events (robot deaths / health deltas flow through normally).
