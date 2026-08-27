#!/usr/bin/env python3
"""Turn a Battlecode 2022 ``.bc22`` replay into a human-readable text dump.

Includes, for every game round, a plain-ASCII rendition of the board
(one character per map square) plus resource totals and an event log.

Usage:
    python3 tools/bc22_replay.py matches/foo.bc22
    python3 tools/bc22_replay.py matches/foo.bc22 -o foo.txt --step 10
    python3 tools/bc22_replay.py matches/foo.bc22 --from 100 --to 120 --moves --indicators

The replay format is gzipped FlatBuffers (schema: tools/battlecode.fbs, root
type GameWrapper).  Parsing needs the ``flatbuffers`` runtime; ``numpy`` is
optional but makes vector reads faster.  Use tools/.venv (see tools/README.md).
"""
from __future__ import annotations

import argparse
import gzip
import os
import sys
import textwrap

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    import bc22_schema as S
except ImportError as exc:  # pragma: no cover
    sys.exit(f"cannot import generated schema bindings (tools/bc22_schema.py): {exc}")

try:
    import flatbuffers  # noqa: F401  (imported for the clear error if missing)
except ImportError:
    sys.exit(
        "the 'flatbuffers' package is required.\n"
        "  python3 -m venv tools/.venv && tools/.venv/bin/pip install flatbuffers numpy\n"
        "  tools/.venv/bin/python tools/bc22_replay.py ..."
    )

# ---------------------------------------------------------------------------
# enum name tables
# ---------------------------------------------------------------------------
BODY_NAME = {
    S.BodyType.MINER: "MINER",
    S.BodyType.BUILDER: "BUILDER",
    S.BodyType.SOLDIER: "SOLDIER",
    S.BodyType.SAGE: "SAGE",
    S.BodyType.ARCHON: "ARCHON",
    S.BodyType.LABORATORY: "LABORATORY",
    S.BodyType.WATCHTOWER: "WATCHTOWER",
}
# board glyph per body type (team 1 -> upper, team 2 -> lower)
BODY_GLYPH = {
    S.BodyType.ARCHON: "A",
    S.BodyType.LABORATORY: "L",
    S.BodyType.WATCHTOWER: "W",
    S.BodyType.MINER: "M",
    S.BodyType.BUILDER: "B",
    S.BodyType.SOLDIER: "S",
    S.BodyType.SAGE: "G",
}
ACTION_NAME = {
    S.Action.ATTACK: "ATTACK",
    S.Action.SPAWN_UNIT: "SPAWN_UNIT",
    S.Action.MINE_LEAD: "MINE_LEAD",
    S.Action.MINE_GOLD: "MINE_GOLD",
    S.Action.TRANSMUTE: "TRANSMUTE",
    S.Action.TRANSFORM: "TRANSFORM",
    S.Action.MUTATE: "MUTATE",
    S.Action.REPAIR: "REPAIR",
    S.Action.CHANGE_HEALTH: "CHANGE_HEALTH",
    S.Action.FULLY_REPAIRED: "FULLY_REPAIRED",
    S.Action.LOCAL_ABYSS: "LOCAL_ABYSS",
    S.Action.LOCAL_CHARGE: "LOCAL_CHARGE",
    S.Action.LOCAL_FURY: "LOCAL_FURY",
    S.Action.ABYSS: "ABYSS",
    S.Action.CHARGE: "CHARGE",
    S.Action.FURY: "FURY",
    S.Action.VORTEX: "VORTEX",
    S.Action.DIE_EXCEPTION: "DIE_EXCEPTION",
}
ANOMALY_NAME = {0: "ABYSS", 1: "CHARGE", 2: "FURY", 3: "VORTEX"}
SYMMETRY_NAME = {0: "rotational", 1: "horizontal", 2: "vertical"}


def team_letter(team_id: int) -> str:
    return {1: "A", 2: "B"}.get(team_id, "?")


# ---------------------------------------------------------------------------
# flatbuffer helpers
# ---------------------------------------------------------------------------
def event_table(ev, cls):
    """Wrap a union member (`EventWrapper.E()`) in the concrete table class."""
    inner = ev.E()
    obj = cls()
    obj.Init(inner.Bytes, inner.Pos)
    return obj


def vectable_pairs(vt):
    if vt is None:
        return []
    return [(vt.Xs(i), vt.Ys(i)) for i in range(vt.XsLength())]


def decode_str(b) -> str:
    if b is None:
        return ""
    return b.decode("utf-8", "replace") if isinstance(b, (bytes, bytearray)) else str(b)


# ---------------------------------------------------------------------------
# replay model
# ---------------------------------------------------------------------------
class Robot:
    __slots__ = ("rid", "team", "btype", "x", "y", "hp")

    def __init__(self, rid, team, btype, x, y, hp):
        self.rid, self.team, self.btype = rid, team, btype
        self.x, self.y, self.hp = x, y, hp

    def label(self):
        return f"{team_letter(self.team)} {BODY_NAME.get(self.btype, self.btype)} #{self.rid}"


class Match:
    def __init__(self, header, footer, rounds, game_header):
        self.header = header
        self.footer = footer
        self.rounds = rounds
        self.game_header = game_header

        mp = header.Map()
        self.map_name = decode_str(mp.Name())
        self.min_x, self.min_y = mp.MinCorner().X(), mp.MinCorner().Y()
        self.max_x, self.max_y = mp.MaxCorner().X(), mp.MaxCorner().Y()
        self.width = self.max_x - self.min_x
        self.height = self.max_y - self.min_y
        self.symmetry = mp.Symmetry()
        self.seed = mp.RandomSeed()
        self.max_rounds = header.MaxRounds()
        self.rubble = [mp.Rubble(i) for i in range(mp.RubbleLength())]
        self.lead0 = [mp.Lead(i) for i in range(mp.LeadLength())]
        self.anomalies = [
            (mp.AnomalyRounds(i), ANOMALY_NAME.get(mp.Anomalies(i), mp.Anomalies(i)))
            for i in range(mp.AnomaliesLength())
        ]
        # starting bodies (Archons)
        b = mp.Bodies()
        self.start_bodies = [
            (b.RobotIds(i), b.TeamIds(i), b.Types(i),
             b.Locs().Xs(i) - self.min_x, b.Locs().Ys(i) - self.min_y)
            for i in range(b.RobotIdsLength())
        ]

    def idx(self, x, y):
        return x + y * self.width


# ---------------------------------------------------------------------------
# parsing
# ---------------------------------------------------------------------------
def load(path):
    try:
        raw = open(path, "rb").read()
    except OSError as exc:
        sys.exit(f"cannot read replay: {exc}")
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    try:
        gw = S.GameWrapper.GetRootAs(raw, 0)
    except Exception as exc:  # noqa: BLE001
        sys.exit(f"not a valid Battlecode 2022 replay ({path}): {exc}")

    game_header = None
    matches = []
    cur_header = cur_rounds = None
    for i in range(gw.EventsLength()):
        ev = gw.Events(i)
        et = ev.EType()
        if et == S.Event.GameHeader:
            game_header = event_table(ev, S.GameHeader)
        elif et == S.Event.MatchHeader:
            cur_header = event_table(ev, S.MatchHeader)
            cur_rounds = []
        elif et == S.Event.Round:
            cur_rounds.append(event_table(ev, S.Round))
        elif et == S.Event.MatchFooter:
            footer = event_table(ev, S.MatchFooter)
            matches.append(Match(cur_header, footer, cur_rounds, game_header))
            cur_header = cur_rounds = None
        elif et == S.Event.GameFooter:
            pass
    return game_header, matches


# ---------------------------------------------------------------------------
# rendering
# ---------------------------------------------------------------------------
def body_meta_table(gh) -> str:
    rows = [("type", "hp", "dmg", "actCD", "movCD", "actR2", "visR2", "costPb", "costAu", "bytecode")]
    for i in range(gh.BodyTypeMetadataLength()):
        m = gh.BodyTypeMetadata(i)
        rows.append((
            BODY_NAME.get(m.Type(), str(m.Type())),
            m.Health(), m.Damage(), m.ActionCooldown(), m.MovementCooldown(),
            m.ActionRadiusSquared(), m.VisionRadiusSquared(),
            m.BuildCostLead(), m.BuildCostGold(), m.BytecodeLimit(),
        ))
    widths = [max(len(str(r[c])) for r in rows) for c in range(len(rows[0]))]
    out = []
    for r in rows:
        out.append("  " + "  ".join(str(v).rjust(widths[c]) for c, v in enumerate(r)))
    return "\n".join(out)


def rubble_glyph(v: int) -> str:
    """'.' clear (0-9) | ':' light (10-33) | 'o' heavy (34-66) | '#' severe (67-100)"""
    if v <= 9:
        return "."
    if v <= 33:
        return ":"
    if v <= 66:
        return "o"
    return "#"


def lead_glyph(v: int) -> str:
    """' ' none | ',' 1-9 | ':' 10-24 | '+' 25-49 | '#' 50-99 | '@' 100+"""
    if v <= 0:
        return " "
    if v < 10:
        return ","
    if v < 25:
        return ":"
    if v < 50:
        return "+"
    if v < 100:
        return "#"
    return "@"


def terrain_char(m: Match, x: int, y: int, mode: str) -> str:
    """Backdrop glyph for an unoccupied square on the per-round board."""
    i = m.idx(x, y)
    if mode == "none":
        return "."
    if mode == "lead":
        return lead_glyph(m.lead0[i])
    return rubble_glyph(m.rubble[i])  # mode == "rubble"


def render_board(m: Match, robots: dict, terrain: str) -> str:
    w, h = m.width, m.height
    grid = [[terrain_char(m, x, y, terrain) for x in range(w)] for y in range(h)]
    for r in robots.values():
        if 0 <= r.x < w and 0 <= r.y < h:
            g = BODY_GLYPH.get(r.btype, "?")
            grid[r.y][r.x] = g.upper() if r.team == 1 else g.lower()

    # column ruler (tens digit then ones digit), y axis labelled, north = up
    tens = "     " + "".join(str((x // 10) % 10) if x % 10 == 0 else " " for x in range(w))
    ones = "     " + "".join(str(x % 10) for x in range(w))
    lines = [tens, ones]
    for y in range(h - 1, -1, -1):
        lines.append(f"{y:3d} |" + "".join(grid[y]) + "|")
    lines.append("     " + "-" * w)
    return "\n".join(lines)


def static_map(m: Match, values, buckets) -> str:
    """One-char-per-square dump of a static int grid (rubble or lead)."""
    w, h = m.width, m.height
    lines = []
    ones = "     " + "".join(str(x % 10) for x in range(w))
    lines.append(ones)
    for y in range(h - 1, -1, -1):
        row = []
        for x in range(w):
            v = values[m.idx(x, y)]
            row.append(buckets(v))
        lines.append(f"{y:3d} |" + "".join(row) + "|")
    return "\n".join(lines)


def counts_by_type(robots, team):
    c = {}
    for r in robots.values():
        if r.team == team:
            c[r.btype] = c.get(r.btype, 0) + 1
    return " ".join(f"{BODY_NAME.get(bt, bt)}x{n}" for bt, n in sorted(c.items())) or "(none)"


# ---------------------------------------------------------------------------
# main dump
# ---------------------------------------------------------------------------
def dump_match(m: Match, gh, out, args, match_no, n_matches):
    p = lambda s="": print(s, file=out)

    teams = [gh.Teams(i) for i in range(gh.TeamsLength())]
    tname = {t.TeamId(): decode_str(t.Name()) for t in teams}
    tpkg = {t.TeamId(): decode_str(t.PackageName()) for t in teams}

    p("=" * 78)
    p(f"MATCH {match_no}/{n_matches}   map: {m.map_name}   ({m.width} x {m.height})")
    p("=" * 78)
    p(f"spec version     : {decode_str(gh.SpecVersion())}")
    p(f"team A (id 1)     : {tname.get(1,'?')}  [package {tpkg.get(1,'?')}]")
    p(f"team B (id 2)     : {tname.get(2,'?')}  [package {tpkg.get(2,'?')}]")
    p(f"map bounds        : ({m.min_x},{m.min_y}) - ({m.max_x},{m.max_y})")
    p(f"symmetry          : {SYMMETRY_NAME.get(m.symmetry, m.symmetry)}")
    p(f"random seed       : {m.seed}")
    p(f"max rounds        : {m.max_rounds}")
    if gh.Constants():
        p(f"lead passive gain : +{gh.Constants().LeadAdditiveIncease()} Pb "
          f"every {gh.Constants().IncreasePeriod()} rounds (per lead tile)")
    if m.anomalies:
        p("anomaly schedule  : " + ", ".join(f"round {r}: {name}" for r, name in m.anomalies))
    else:
        p("anomaly schedule  : (none; Singularity at final round)")

    win = m.footer.Winner()
    p(f"result            : team {team_letter(win)} ({tname.get(win,'?')}) wins "
      f"after {m.footer.TotalRounds()} rounds")
    p()
    p("robot types (from game header):")
    p(body_meta_table(gh))
    p()
    p("BOARD LEGEND")
    p("  robots : A/L/W/M/B/S/G  = Archon Lab Watchtower Miner Builder Soldier saGe")
    p("           UPPERCASE = team A, lowercase = team B")
    if args.terrain == "rubble":
        p("  terrain: rubble  '.' 0-9   ':' 10-33   'o' 34-66   '#' 67-100")
    elif args.terrain == "lead":
        p("  terrain: INITIAL lead  ' ' 0   ',' 1-9   ':' 10-24   '+' 25-49   '#' 50-99   '@' 100+")
    else:
        p("  terrain: '.' everywhere")
    p("  NOTE: per-tile lead depletion from mining is not recorded in the replay stream;")
    p("        the INITIAL LEAD MAP below shows the starting lead layout only.")
    p("  y axis points north (up); origin (0,0) is bottom-left.")
    p("  starting resources: 200 Pb / 0 Au per team (paid out inside the round 1 delta).")
    p()
    p("INITIAL RUBBLE MAP   '.' 0-9   ':' 10-33   'o' 34-66   '#' 67-100")
    p(static_map(m, m.rubble, rubble_glyph))
    p()
    p("INITIAL LEAD MAP   ' ' 0   ',' 1-9   ':' 10-24   '+' 25-49   '#' 50-99   '@' 100+")
    p(static_map(m, m.lead0, lead_glyph))
    p()

    # ---- simulate ----
    robots: dict[int, Robot] = {}
    btype_hp = {}
    for i in range(gh.BodyTypeMetadataLength()):
        mm = gh.BodyTypeMetadata(i)
        btype_hp[mm.Type()] = mm.Health()
    for rid, team, bt, x, y in m.start_bodies:
        robots[rid] = Robot(rid, team, bt, x, y, btype_hp.get(bt, 0))

    lead = {1: 0, 2: 0}
    gold = {1: 0, 2: 0}

    p("#" * 78)
    p("# ROUND-BY-ROUND")
    p("#" * 78)

    lo = args.from_round or 1
    hi = args.to_round or m.max_rounds

    for rnd in m.rounds:
        rid = rnd.RoundId()

        # team resource deltas (round 1 delta already includes the starting stipend)
        d_lead = {1: 0, 2: 0}
        d_gold = {1: 0, 2: 0}
        for k in range(rnd.TeamIdsLength()):
            tid = rnd.TeamIds(k)
            d_lead[tid] = rnd.TeamLeadChanges(k)
            d_gold[tid] = rnd.TeamGoldChanges(k)
        for tid in (1, 2):
            lead[tid] += d_lead[tid]
            gold[tid] += d_gold[tid]

        # spawns
        sb = rnd.SpawnedBodies()
        spawn_events = []
        for k in range(sb.RobotIdsLength()):
            rid_ = sb.RobotIds(k)
            team = sb.TeamIds(k)
            bt = sb.Types(k)
            x = sb.Locs().Xs(k) - m.min_x
            y = sb.Locs().Ys(k) - m.min_y
            robots[rid_] = Robot(rid_, team, bt, x, y, btype_hp.get(bt, 0))
            spawn_events.append(f"  + {robots[rid_].label()} at ({x},{y})")

        # moves
        move_events = []
        ml = rnd.MovedLocs()
        for k in range(rnd.MovedIdsLength()):
            rid_ = rnd.MovedIds(k)
            x = ml.Xs(k) - m.min_x
            y = ml.Ys(k) - m.min_y
            r = robots.get(rid_)
            if r:
                if args.moves:
                    move_events.append(f"  > {r.label()} -> ({x},{y})")
                r.x, r.y = x, y

        # actions -- apply health changes immediately, aggregate the rest
        # (the engine emits one entry per unit mined / per repeat hit, so
        # identical (actor, action, target) triples are collapsed with "xN")
        agg: dict = {}
        hp_delta: dict = {}
        for k in range(rnd.ActionsLength()):
            aid = rnd.ActionIds(k)
            act = rnd.Actions(k)
            tgt = rnd.ActionTargets(k)
            if act == S.Action.CHANGE_HEALTH:
                r = robots.get(aid)
                if r:
                    r.hp += tgt
                hp_delta[aid] = hp_delta.get(aid, 0) + tgt
                continue
            if act == S.Action.SPAWN_UNIT:
                continue  # already emitted as a spawn
            key = (aid, act, tgt)
            agg[key] = agg.get(key, 0) + 1

        action_events = []
        mine_tally = {1: [0, 0], 2: [0, 0]}  # team -> [lead ops, gold ops]
        for (aid, act, tgt), n in agg.items():
            actor = robots.get(aid)
            who = actor.label() if actor else f"#{aid}"
            times = f" x{n}" if n > 1 else ""
            if act in (S.Action.MINE_LEAD, S.Action.MINE_GOLD) and not args.all_actions:
                if actor:
                    mine_tally[actor.team][0 if act == S.Action.MINE_LEAD else 1] += n
                continue
            if act == S.Action.ATTACK:
                victim = robots.get(tgt)
                action_events.append(f"  * {who} attacks {victim.label() if victim else f'#{tgt}'}{times}")
            elif act in (S.Action.MINE_LEAD, S.Action.MINE_GOLD):
                tx, ty = tgt % m.width, tgt // m.width
                kind = "lead" if act == S.Action.MINE_LEAD else "gold"
                action_events.append(f"  * {who} mines {kind} at ({tx},{ty}){times}")
            elif act == S.Action.REPAIR:
                tr = robots.get(tgt)
                action_events.append(f"  * {who} repairs {tr.label() if tr else f'#{tgt}'}{times}")
            elif act == S.Action.MUTATE:
                tr = robots.get(tgt)
                action_events.append(f"  * {who} mutates {tr.label() if tr else f'#{tgt}'}{times}")
            elif act == S.Action.TRANSMUTE:
                action_events.append(f"  * {who} transmutes lead -> gold{times}")
            elif act == S.Action.TRANSFORM:
                action_events.append(f"  * {who} transforms (turret <-> portable)")
            elif act == S.Action.FULLY_REPAIRED:
                action_events.append(f"  * {who} finished construction (prototype -> turret)")
            elif act in (S.Action.LOCAL_ABYSS, S.Action.LOCAL_CHARGE, S.Action.LOCAL_FURY):
                tx, ty = tgt % m.width, tgt // m.width
                action_events.append(f"  ! {who} unleashes {ACTION_NAME[act]} at ({tx},{ty})")
            elif act in (S.Action.ABYSS, S.Action.CHARGE, S.Action.FURY):
                action_events.append(f"  !! GLOBAL ANOMALY: {ACTION_NAME[act]}")
            elif act == S.Action.VORTEX:
                action_events.append(f"  !! GLOBAL ANOMALY: VORTEX (mode {tgt})")
            elif act == S.Action.DIE_EXCEPTION:
                action_events.append(f"  x {who} died from an uncaught exception")
            else:
                action_events.append(f"  ? {who} {ACTION_NAME.get(act, act)} target={tgt}{times}")
        for tid in (1, 2):
            nl, ng = mine_tally[tid]
            if nl or ng:
                bits = []
                if nl:
                    bits.append(f"{nl} lead")
                if ng:
                    bits.append(f"{ng} gold")
                action_events.append(f"  * team {team_letter(tid)} mining ops: " + ", ".join(bits)
                                     + "  (use --all-actions for per-tile detail)")
        if args.health:
            for aid, d in hp_delta.items():
                if d == 0:
                    continue
                r = robots.get(aid)
                who = r.label() if r else f"#{aid}"
                cur = f" -> {r.hp}" if r else ""
                action_events.append(f"  ~ {who} health {d:+d}{cur}")

        # deaths
        death_events = []
        for k in range(rnd.DiedIdsLength()):
            rid_ = rnd.DiedIds(k)
            r = robots.pop(rid_, None)
            if r:
                death_events.append(f"  x {r.label()} at ({r.x},{r.y})")
            else:
                death_events.append(f"  x #{rid_}")

        # indicator strings
        indicator_events = []
        if args.indicators:
            for k in range(rnd.IndicatorStringsLength()):
                rid_ = rnd.IndicatorStringIds(k)
                msg = decode_str(rnd.IndicatorStrings(k)).strip()
                if not msg:
                    continue
                r = robots.get(rid_)
                indicator_events.append(f"  \" {r.label() if r else f'#{rid_}'}: {msg}")

        if rid < lo or rid > hi or ((rid - lo) % args.step != 0 and rid != hi):
            continue

        p()
        p(f"----- Round {rid}/{m.max_rounds} " + "-" * 40)
        for tid in (1, 2):
            p(f"  team {team_letter(tid)}: lead {lead[tid]:5d} ({d_lead[tid]:+d})   "
              f"gold {gold[tid]:4d} ({d_gold[tid]:+d})   "
              f"units: {counts_by_type(robots, tid)}")
        if not args.no_events:
            for grp in (spawn_events, death_events, action_events, move_events, indicator_events):
                for line in grp:
                    p(line)
            if not args.moves and rnd.MovedIdsLength():
                p(f"  ({rnd.MovedIdsLength()} robots moved; use --moves to list)")
        if not args.no_board:
            p()
            p(render_board(m, robots, args.terrain))

    p()
    p("=" * 78)
    p(f"FINAL: team {team_letter(m.footer.Winner())} ({tname.get(m.footer.Winner(),'?')}) "
      f"wins after {m.footer.TotalRounds()} rounds")
    p(f"  team A lead {lead[1]}  gold {gold[1]}   |   team B lead {lead[2]}  gold {gold[2]}")
    p("=" * 78)


def main(argv=None):
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("replay", help="path to a .bc22 replay file (gzipped or raw)")
    ap.add_argument("-o", "--output", help="write to this file instead of stdout")
    ap.add_argument("--match", type=int, help="only dump this match number (1-based)")
    ap.add_argument("--from", dest="from_round", type=int, help="first round to render")
    ap.add_argument("--to", dest="to_round", type=int, help="last round to render")
    ap.add_argument("--step", type=int, default=1,
                    help="render every Nth round (default 1 = every round)")
    ap.add_argument("--terrain", choices=("rubble", "lead", "none"), default="rubble",
                    help="backdrop for empty squares on the per-round board (default rubble)")
    ap.add_argument("--no-board", action="store_true", help="omit the ASCII board")
    ap.add_argument("--no-events", action="store_true", help="omit the per-round event log")
    ap.add_argument("--moves", action="store_true", help="list every individual robot move")
    ap.add_argument("--health", action="store_true", help="list per-robot health changes each round")
    ap.add_argument("--all-actions", action="store_true",
                    help="list every mining op per tile (default: collapse to a per-team count)")
    ap.add_argument("--indicators", action="store_true",
                    help="show robot indicator strings (their debug logs)")
    args = ap.parse_args(argv)
    if args.step < 1:
        ap.error("--step must be >= 1")

    gh, matches = load(args.replay)
    if not matches:
        sys.exit("no matches found in replay")

    out = open(args.output, "w") if args.output else sys.stdout
    try:
        sel = range(len(matches))
        if args.match:
            if not 1 <= args.match <= len(matches):
                sys.exit(f"--match {args.match} out of range (1..{len(matches)})")
            sel = [args.match - 1]
        print(f"# {os.path.basename(args.replay)}  -  {len(matches)} match(es)", file=out)
        for mi in sel:
            dump_match(matches[mi], gh, out, args, mi + 1, len(matches))
    finally:
        if out is not sys.stdout:
            out.close()
            print(f"wrote {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
