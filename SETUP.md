# Battlecode 2022 — dev environment

Contest: **Battlecode 2022 ("Mutation")**. Engine/client version **2.2.1** (latest & final for bc22).

- Docs / getting started: https://github.com/battlecode/battlecode22-scaffold (README)
- Formal spec: https://releases.battlecode.org/specs/battlecode22/2.2.1/specs.md.html
- Episode API: https://api.battlecode.org/api/episode/e/bc22/?format=json
- Language: **Java 8** (required — `sourceCompatibility = 1.8`)

## Layout

This repo *is* the `battlecode22-scaffold` (cloned from `origin`). Key paths:

- `src/examplefuncsplayer/RobotPlayer.java` — the example bot. Our bot goes in `src/<teamname>/`.
- `gradle.properties` — default teams / maps / flags for `./gradlew run`.
- `build.gradle` — `run`, `build`, `listMaps`, `listPlayers`, `update`, `verify` tasks.
- `matches/*.bc22` — replay files (the "game logs"; open in the client).
- `logs/*` — captured stdout from headless runs (gzipped) + short summaries.
- `.tools/` — JDK download + helper scripts (git-ignored).
- `tools/bc22_replay.py` — converts a `.bc22` replay into a human-readable text
  transcript with a one-char-per-square ASCII board at every round. See
  `tools/README.md`. Runs locally (Python + `flatbuffers`, venv at `tools/.venv`).

## Why the build runs on GCP

This Mac is arm64 with no JVM and no Rosetta 2; macOS Temurin 8 is x64-only and its
installer needs an interactive sudo password. Per the standing "use the GCloud account
for compute" instruction, builds/matches run on a GCE VM:

- VM `battlecode-dev`, `e2-standard-2`, zone `us-west1-b`, project `tvanbelle-vibecode`
- JDK: Temurin `8u504-b01` (x64 linux) unpacked at `~/jdk8` on the VM
- Scaffold cloned at `~/battlecode22-scaffold` on the VM
- **VM is currently STOPPED** (no CPU charges; ~20 GB disk only)

### Run matches

```
.tools/vm-match.sh maptestsmall eckleburg          # example bot vs itself, 2 maps
TEAM_A=mybot TEAM_B=examplefuncsplayer .tools/vm-match.sh colosseum
```

The script starts the VM if stopped, syncs `src/`, runs each match headless, and copies
`*.bc22` + logs back. Stop the VM when done:

```
gcloud compute instances stop battlecode-dev --zone=us-west1-b --project=tvanbelle-vibecode
```

To view replays locally you'd need the client GUI (`client/` folder, downloaded by
`./gradlew build`) which needs a local JVM — not set up yet. Replays can also be
watched at https://play.battlecode.org (upload the `.bc22`).

## Verification runs (2026-08-26)

`examplefuncsplayer` vs itself, headless, engine 2.2.1:

| Map          | Result                        | Replay                                                        |
|--------------|-------------------------------|--------------------------------------------------------------|
| maptestsmall | Team B wins @ round 2000 (lead net worth tiebreaker) | `matches/examplefuncsplayer-vs-examplefuncsplayer-on-maptestsmall.bc22` |
| eckleburg    | Team A wins @ round 2000 (lead net worth tiebreaker) | `matches/examplefuncsplayer-vs-examplefuncsplayer-on-eckleburg.bc22`    |

Both games ran the full 2000 rounds and produced valid replay files → environment works.
