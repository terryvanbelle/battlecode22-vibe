# battlecode22-vibe

A [Battlecode 2022](https://play.battlecode.org) ("Mutation") contest entry,
built on the official [`battlecode22-scaffold`](https://github.com/battlecode/battlecode22-scaffold)
(engine/client **2.2.1**, Java 8).

## Layout

| Path | What |
|------|------|
| `src/examplefuncsplayer/` | the stock example bot (our bot will live in `src/<teamname>/`) |
| `SCAFFOLD.md` | the upstream scaffold README (gradle tasks, etc.) |
| `SETUP.md` | how this checkout is set up — **builds/matches run on a GCE VM**, not locally |
| `tools/bc22_replay.py` | replay (`.bc22`) → human-readable text transcript with a one-char-per-square ASCII board per round ([tools/README.md](tools/README.md)) |
| `tools/vm-match.sh` | run headless matches on the VM and pull replays + logs back |
| `tools/gen-schema.sh` | regenerate the FlatBuffers Python bindings for the replay format |

Generated artifacts — `matches/` (`.bc22` replays), `logs/`, `client/`, `.tools/`
— are git-ignored.

## Quick start

```bash
# inspect a replay
python3 -m venv tools/.venv && tools/.venv/bin/pip install -r tools/requirements.txt
tools/.venv/bin/python tools/bc22_replay.py matches/some-replay.bc22 --step 25

# run the example bot against itself on two maps (spins up the GCE VM)
tools/vm-match.sh maptestsmall eckleburg
```

See `SETUP.md` for the VM details and why the build runs there.
