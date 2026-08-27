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
        self.rubble0 = [mp.Rubble(i) for i in range(mp.RubbleLength())]
        self.lead0 = [mp.Lead(i) for i in range(mp.LeadLength())]
        self.anomalies = [
            (mp.AnomalyRounds(i), ANOMALY_NAME.get(mp.Anomalies(i), mp.Anomalies(i)))
            for i in range(mp.AnomaliesLength())
        ]
        # starting bodies (Archons)
        b = mp.Bodies()
        self.start_bodies = [] if b is None else [
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
        with open(path, "rb") as fh:
            raw = fh.read()
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


def transform_grid(grid, w, h, mode):
    """Apply a Vortex terrain transform to a flat (idx = x + y*w) grid.
    mode: 0 = rotate 90 CW (square maps only), 1 = mirror columns, 2 = mirror rows.
    Mirrors the battlecode22 engine's flipRubble*/rotateRubble."""
    if mode == 1:  # flipRubbleHorizontally: x <-> w-1-x
        return [grid[(w - 1 - (i % w)) + (i // w) * w] for i in range(len(grid))]
    if mode == 2:  # flipRubbleVertically: y <-> h-1-y
        return [grid[(i % w) + (h - 1 - (i // w)) * w] for i in range(len(grid))]
    if mode == 0 and w == h:  # rotateRubble: old (x,y) -> new (y, n-1-x)
        n = w
        out = grid[:]
        for x in range(n):
            for y in range(n):
                out[y + (n - 1 - x) * n] = grid[x + y * n]
        return out
    return grid  # unknown / non-square rotate: leave unchanged


def render_board(m: Match, robots: dict, terrain: str, rubble, lead) -> str:
    """Board with live terrain: robots over the current rubble (or lead) grid."""
    w, h = m.width, m.height
    if terrain == "none":
        grid = [["." for _ in range(w)] for _ in range(h)]
    elif terrain == "lead":
        grid = [[lead_glyph(lead[x + y * w]) for x in range(w)] for y in range(h)]
    else:
        grid = [[rubble_glyph(rubble[x + y * w]) for x in range(w)] for y in range(h)]
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
    """One-char-per-square dump of an int grid (rubble / lead / gold)."""
    w, h = m.width, m.height
    tens = "     " + "".join(str((x // 10) % 10) if x % 10 == 0 else " " for x in range(w))
    ones = "     " + "".join(str(x % 10) for x in range(w))
    lines = [tens, ones]
    for y in range(h - 1, -1, -1):
        row = "".join(buckets(values[m.idx(x, y)]) for x in range(w))
        lines.append(f"{y:3d} |{row}|")
    lines.append("     " + "-" * w)
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
_METRIC_COLS = ["lead", "gold", "miners", "soldiers", "builders", "sages", "labs",
                "watchtowers", "archons", "archonHP", "attacks", "solCx", "solCy", "solSpread"]


def _dump_metrics(m: Match, gh, out, args):
    """`--metrics`: one CSV row per (selected) round with per-team aggregates.

    Columns per team X in {A,B}: X_lead X_gold X_miners X_soldiers X_builders
    X_sages X_labs X_watchtowers X_archons X_archonHP X_attacks(cumulative)
    X_solCx X_solCy (soldier centroid) X_solSpread (mean soldier dist from centroid
    -- army cohesion). Robot HP = base + Σ CHANGE_HEALTH (buildings shown at full
    base HP until first corrected -- see README).
    """
    BT = S.BodyType
    btype_hp = {gh.BodyTypeMetadata(i).Type(): gh.BodyTypeMetadata(i).Health()
                for i in range(gh.BodyTypeMetadataLength())}
    robots: dict[int, Robot] = {}
    for rid, team, bt, x, y in m.start_bodies:
        robots[rid] = Robot(rid, team, bt, x, y, btype_hp.get(bt, 0))
    tl = {1: 0, 2: 0}
    tg = {1: 0, 2: 0}
    atk = {1: 0, 2: 0}
    lo = args.from_round or 1
    hi = args.to_round or m.max_rounds
    print("round,winner," + ",".join(f"A_{c}" for c in _METRIC_COLS)
          + "," + ",".join(f"B_{c}" for c in _METRIC_COLS), file=out)

    for rnd in m.rounds:
        rid = rnd.RoundId()
        for k in range(rnd.TeamIdsLength()):
            t = rnd.TeamIds(k)
            tl[t] += rnd.TeamLeadChanges(k)
            tg[t] += rnd.TeamGoldChanges(k)
        sb = rnd.SpawnedBodies()
        for k in range(sb.RobotIdsLength() if sb is not None else 0):
            i = sb.RobotIds(k)
            robots[i] = Robot(i, sb.TeamIds(k), sb.Types(k),
                              sb.Locs().Xs(k) - m.min_x, sb.Locs().Ys(k) - m.min_y,
                              btype_hp.get(sb.Types(k), 0))
        ml = rnd.MovedLocs()
        for k in range(rnd.MovedIdsLength()):
            r = robots.get(rnd.MovedIds(k))
            if r:
                r.x = ml.Xs(k) - m.min_x
                r.y = ml.Ys(k) - m.min_y
        for k in range(rnd.ActionsLength()):
            act = rnd.Actions(k)
            if act == S.Action.CHANGE_HEALTH:
                r = robots.get(rnd.ActionIds(k))
                if r:
                    r.hp += rnd.ActionTargets(k)
            elif act == S.Action.ATTACK:
                a = robots.get(rnd.ActionIds(k))
                if a:
                    atk[a.team] += 1
        for k in range(rnd.DiedIdsLength()):
            robots.pop(rnd.DiedIds(k), None)

        if rid < lo or rid > hi or ((rid - lo) % args.step != 0 and rid != hi):
            continue

        row = [str(rid), team_letter(m.footer.Winner())]
        for tid in (1, 2):
            c: dict = {}
            hp = sx = sy = sn = 0
            for r in robots.values():
                if r.team != tid:
                    continue
                c[r.btype] = c.get(r.btype, 0) + 1
                if r.btype == BT.ARCHON:
                    hp += r.hp
                if r.btype == BT.SOLDIER:
                    sx += r.x
                    sy += r.y
                    sn += 1
            cx = sx / sn if sn else 0.0
            cy = sy / sn if sn else 0.0
            spread = (sum(((r.x - cx) ** 2 + (r.y - cy) ** 2) ** 0.5
                          for r in robots.values()
                          if r.team == tid and r.btype == BT.SOLDIER) / sn) if sn else 0.0
            row += [
                str(tl[tid]), str(tg[tid]),
                str(c.get(BT.MINER, 0)), str(c.get(BT.SOLDIER, 0)), str(c.get(BT.BUILDER, 0)),
                str(c.get(BT.SAGE, 0)), str(c.get(BT.LABORATORY, 0)), str(c.get(BT.WATCHTOWER, 0)),
                str(c.get(BT.ARCHON, 0)), str(hp), str(atk[tid]),
                f"{cx:.1f}", f"{cy:.1f}", f"{spread:.1f}",
            ]
        print(",".join(row), file=out)


def dump_match(m: Match, gh, out, args, match_no, n_matches):
    p = lambda s="": print(s, file=out)

    teams = [gh.Teams(i) for i in range(gh.TeamsLength())]
    tname = {t.TeamId(): decode_str(t.Name()) for t in teams}
    tpkg = {t.TeamId(): decode_str(t.PackageName()) for t in teams}
    if args.metrics:
        return _dump_metrics(m, gh, out, args)

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
        p("  terrain: live rubble  '.' 0-9   ':' 10-33   'o' 34-66   '#' 67-100")
    elif args.terrain == "lead":
        p("  terrain: live lead  ' ' 0   ',' 1-9   ':' 10-24   '+' 25-49   '#' 50-99   '@' 100+")
    else:
        p("  terrain: '.' everywhere")
    p("  Maps are LIVE: lead is depleted by mining and regenerates +5 Pb on non-empty")
    p("  tiles every 20 rounds; rubble is reshuffled by the Vortex anomaly. The board")
    p("  and the per-round 'map:' line reflect the reconstructed state at end of round.")
    p("  y axis points north (up); origin (0,0) is bottom-left.")
    p("  starting resources: 200 Pb / 0 Au per team (paid out inside the round 1 delta).")
    p()
    p("STARTING RUBBLE MAP   '.' 0-9   ':' 10-33   'o' 34-66   '#' 67-100")
    p(static_map(m, m.rubble0, rubble_glyph))
    p()
    p("STARTING LEAD MAP   ' ' 0   ',' 1-9   ':' 10-24   '+' 25-49   '#' 50-99   '@' 100+")
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

    team_lead = {1: 0, 2: 0}
    team_gold = {1: 0, 2: 0}
    map_rubble = m.rubble0[:]        # live grids, mutated each round
    map_lead = m.lead0[:]
    map_gold = [0] * len(m.lead0)
    rubble_dirty = False            # set by a Vortex, cleared once re-printed
    REGEN_PERIOD = gh.Constants().IncreasePeriod() if gh.Constants() else 20
    REGEN_AMOUNT = gh.Constants().LeadAdditiveIncease() if gh.Constants() else 5

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
            team_lead[tid] += d_lead[tid]
            team_gold[tid] += d_gold[tid]

        # spawns  (the spawnedBodies table is absent on rounds with no spawns)
        sb = rnd.SpawnedBodies()
        spawn_events = []
        for k in range(sb.RobotIdsLength() if sb is not None else 0):
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

        # ---- live map updates (engine order: drops during the round, then
        # Vortex, then lead regen at end of round) ----
        lead_before = sum(map_lead)
        gold_before = sum(map_gold)
        ldl = rnd.LeadDropLocations()
        for k in range(rnd.LeadDropValuesLength()):
            map_lead[ldl.Xs(k) + ldl.Ys(k) * m.width] += rnd.LeadDropValues(k)
        gdl = rnd.GoldDropLocations()
        for k in range(rnd.GoldDropValuesLength()):
            map_gold[gdl.Xs(k) + gdl.Ys(k) * m.width] += rnd.GoldDropValues(k)

        for k in range(rnd.ActionsLength()):
            if rnd.Actions(k) == S.Action.VORTEX:
                mode = rnd.ActionTargets(k)
                map_rubble = transform_grid(map_rubble, m.width, m.height, mode)
                rubble_dirty = True
                action_events.append("  !! VORTEX reshuffled the rubble ("
                                     + {0: "rotate 90 CW", 1: "mirror columns",
                                        2: "mirror rows"}.get(mode, f"mode {mode}") + ")")

        regen_n = 0
        if REGEN_PERIOD and rid % REGEN_PERIOD == 0:
            for i in range(len(map_lead)):
                if map_lead[i] > 0:
                    map_lead[i] += REGEN_AMOUNT
                    regen_n += 1

        map_lead_total = sum(map_lead)
        map_gold_total = sum(map_gold)

        if rid < lo or rid > hi or ((rid - lo) % args.step != 0 and rid != hi):
            continue

        p()
        p(f"----- Round {rid}/{m.max_rounds} " + "-" * 40)
        for tid in (1, 2):
            p(f"  team {team_letter(tid)}: lead {team_lead[tid]:5d} ({d_lead[tid]:+d})   "
              f"gold {team_gold[tid]:4d} ({d_gold[tid]:+d})   "
              f"units: {counts_by_type(robots, tid)}")
        n_lead_tiles = sum(1 for v in map_lead if v > 0)
        map_line = (f"  map:  {map_lead_total:6d} Pb ({map_lead_total - lead_before:+d}) "
                    f"on {n_lead_tiles} tiles")
        if map_gold_total or gold_before:
            n_gold_tiles = sum(1 for v in map_gold if v > 0)
            map_line += (f"   |   {map_gold_total} Au ({map_gold_total - gold_before:+d}) "
                         f"on {n_gold_tiles} tiles")
        if regen_n:
            map_line += f"   [+{REGEN_AMOUNT} Pb regen x{regen_n} tiles]"
        p(map_line)
        if not args.no_events:
            for grp in (spawn_events, death_events, action_events, move_events, indicator_events):
                for line in grp:
                    p(line)
            if not args.moves and rnd.MovedIdsLength():
                p(f"  ({rnd.MovedIdsLength()} robots moved; use --moves to list)")
        if not args.no_board:
            p()
            p(f"  ROBOTS  (A/L/W/M/B/S/G, upper=A lower=B; backdrop = live {args.terrain})")
            p(render_board(m, robots, args.terrain, map_rubble, map_lead))
            if not args.no_lead_map:
                p()
                p(f"  LEAD  (live: {map_lead_total} Pb on {n_lead_tiles} tiles)"
                  "   ' ' 0  ',' 1-9  ':' 10-24  '+' 25-49  '#' 50-99  '@' 100+")
                p(static_map(m, map_lead, lead_glyph))
            if map_gold_total:
                p()
                p(f"  GOLD  (live: {map_gold_total} Au)"
                  "   ' ' 0  ',' 1-9  ':' 10-24  '+' 25-49  '#' 50+")
                p(static_map(m, map_gold, lead_glyph))
            if rubble_dirty or args.map_detail:
                p()
                tag = "live, just changed by Vortex" if rubble_dirty else "live"
                p(f"  RUBBLE  ({tag})   '.' 0-9  ':' 10-33  'o' 34-66  '#' 67-100")
                p(static_map(m, map_rubble, rubble_glyph))
                rubble_dirty = False

    p()
    p("=" * 78)
    p(f"FINAL: team {team_letter(m.footer.Winner())} ({tname.get(m.footer.Winner(),'?')}) "
      f"wins after {m.footer.TotalRounds()} rounds")
    p(f"  team A stockpile: {team_lead[1]} Pb / {team_gold[1]} Au   |   "
      f"team B stockpile: {team_lead[2]} Pb / {team_gold[2]} Au")
    p(f"  lead left on map: {sum(map_lead)} Pb on {sum(1 for v in map_lead if v > 0)} tiles")
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
    ap.add_argument("--no-board", action="store_true", help="omit the ASCII boards entirely")
    ap.add_argument("--no-lead-map", action="store_true",
                    help="show only the robot board, not the live lead map, each round")
    ap.add_argument("--no-events", action="store_true", help="omit the per-round event log")
    ap.add_argument("--map-detail", action="store_true",
                    help="also show the live rubble map every round (not just after a Vortex)")
    ap.add_argument("--moves", action="store_true", help="list every individual robot move")
    ap.add_argument("--health", action="store_true", help="list per-robot health changes each round")
    ap.add_argument("--all-actions", action="store_true",
                    help="list every mining op per tile (default: collapse to a per-team count)")
    ap.add_argument("--indicators", action="store_true",
                    help="show robot indicator strings (their debug logs)")
    ap.add_argument("--metrics", action="store_true",
                    help="emit a per-round CSV of per-team aggregates (no narrative "
                         "output); respects --match/--from/--to/--step")
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
        if not args.metrics:
            print(f"# {os.path.basename(args.replay)}  -  {len(matches)} match(es)", file=out)
        for mi in sel:
            dump_match(matches[mi], gh, out, args, mi + 1, len(matches))
    finally:
        if out is not sys.stdout:
            out.close()
            print(f"wrote {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
