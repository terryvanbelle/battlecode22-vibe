#!/usr/bin/env python3
"""Tests for bc22_replay.py.

Run:  tools/.venv/bin/python tools/test_bc22_replay.py
      tools/.venv/bin/python -m unittest -v test_bc22_replay   (from tools/)

Most tests run against a synthetic in-memory replay built with the FlatBuffers
builder (no fixture file needed).  The TestRealReplay case additionally runs the
full pipeline against any matches/*.bc22 present, and is skipped if there are
none (those files are git-ignored).
"""
import contextlib
import glob
import gzip
import io
import os
import re
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import flatbuffers
import bc22_schema as S
import bc22_replay as R


# ---------------------------------------------------------------------------
# synthetic replay builder
# ---------------------------------------------------------------------------
def _i32vec(b, vals):
    vals = list(vals)
    b.StartVector(4, len(vals), 4)
    for v in reversed(vals):
        b.PrependInt32(v)
    return b.EndVector()


def _i8vec(b, vals):
    vals = list(vals)
    b.StartVector(1, len(vals), 1)
    for v in reversed(vals):
        b.PrependInt8(v)
    return b.EndVector()


def _offvec(b, offs):
    offs = list(offs)
    b.StartVector(4, len(offs), 4)
    for o in reversed(offs):
        b.PrependUOffsetTRelative(o)
    return b.EndVector()


def _vectable(b, pts):
    xs = _i32vec(b, [p[0] for p in pts])
    ys = _i32vec(b, [p[1] for p in pts])
    S.VecTableStart(b)
    S.VecTableAddXs(b, xs)
    S.VecTableAddYs(b, ys)
    return S.VecTableEnd(b)


def _spawned(b, bodies):
    """bodies: list of (rid, team, btype, x, y)."""
    locs = _vectable(b, [(x, y) for (_, _, _, x, y) in bodies])
    ids = _i32vec(b, [r[0] for r in bodies])
    teams = _i8vec(b, [r[1] for r in bodies])
    types = _i8vec(b, [r[2] for r in bodies])
    S.SpawnedBodyTableStart(b)
    S.SpawnedBodyTableAddRobotIds(b, ids)
    S.SpawnedBodyTableAddTeamIds(b, teams)
    S.SpawnedBodyTableAddTypes(b, types)
    S.SpawnedBodyTableAddLocs(b, locs)
    return S.SpawnedBodyTableEnd(b)


def _round(b, rid, lead_changes=(0, 0), gold_changes=(0, 0), spawns=(), moved=(),
           actions=(), lead_drops=(), gold_drops=(), died=()):
    """moved: (id, x, y). actions: (actor_id, action, target). *_drops: (x, y, value)."""
    sb = _spawned(b, spawns) if spawns else None
    mlocs = _vectable(b, [(x, y) for (_, x, y) in moved]) if moved else None
    mids = _i32vec(b, [m[0] for m in moved]) if moved else None
    aids = _i32vec(b, [a[0] for a in actions]) if actions else None
    acts = _i8vec(b, [a[1] for a in actions]) if actions else None
    atgt = _i32vec(b, [a[2] for a in actions]) if actions else None
    ldl = _vectable(b, [(x, y) for (x, y, _) in lead_drops]) if lead_drops else None
    ldv = _i32vec(b, [v for (_, _, v) in lead_drops]) if lead_drops else None
    gdl = _vectable(b, [(x, y) for (x, y, _) in gold_drops]) if gold_drops else None
    gdv = _i32vec(b, [v for (_, _, v) in gold_drops]) if gold_drops else None
    tids = _i32vec(b, [1, 2])
    tlc = _i32vec(b, list(lead_changes))
    tgc = _i32vec(b, list(gold_changes))
    dv = _i32vec(b, list(died)) if died else None

    S.RoundStart(b)
    S.RoundAddRoundId(b, rid)
    S.RoundAddTeamIds(b, tids)
    S.RoundAddTeamLeadChanges(b, tlc)
    S.RoundAddTeamGoldChanges(b, tgc)
    if sb is not None:
        S.RoundAddSpawnedBodies(b, sb)
    if mids is not None:
        S.RoundAddMovedIds(b, mids)
        S.RoundAddMovedLocs(b, mlocs)
    if aids is not None:
        S.RoundAddActionIds(b, aids)
        S.RoundAddActions(b, acts)
        S.RoundAddActionTargets(b, atgt)
    if ldv is not None:
        S.RoundAddLeadDropLocations(b, ldl)
        S.RoundAddLeadDropValues(b, ldv)
    if gdv is not None:
        S.RoundAddGoldDropLocations(b, gdl)
        S.RoundAddGoldDropValues(b, gdv)
    if dv is not None:
        S.RoundAddDiedIds(b, dv)
    return S.RoundEnd(b)


def _event(b, etype, off):
    S.EventWrapperStart(b)
    S.EventWrapperAddEType(b, etype)
    S.EventWrapperAddE(b, off)
    return S.EventWrapperEnd(b)


# 4x4 map.  rubble = 0,6,12,...,90 (row-major, idx = x + y*4).
RUBBLE0 = [i * 6 for i in range(16)]
# lead: (0,0)=10  (1,1)=5  (3,3)=20
LEAD0 = [0] * 16
LEAD0[0] = 10
LEAD0[1 + 1 * 4] = 5
LEAD0[3 + 3 * 4] = 20


def build_replay():
    b = flatbuffers.Builder(1024)

    spec = b.CreateString("2.2.1")
    na, pa = b.CreateString("alpha"), b.CreateString("alpha")
    nb, pb = b.CreateString("beta"), b.CreateString("beta")
    S.TeamDataStart(b); S.TeamDataAddName(b, na); S.TeamDataAddPackageName(b, pa)
    S.TeamDataAddTeamId(b, 1); tA = S.TeamDataEnd(b)
    S.TeamDataStart(b); S.TeamDataAddName(b, nb); S.TeamDataAddPackageName(b, pb)
    S.TeamDataAddTeamId(b, 2); tB = S.TeamDataEnd(b)
    teams = _offvec(b, [tA, tB])

    metas = []
    for bt, hp in [(S.BodyType.ARCHON, 600), (S.BodyType.MINER, 40), (S.BodyType.SOLDIER, 50)]:
        S.BodyTypeMetadataStart(b)
        S.BodyTypeMetadataAddType(b, bt)
        S.BodyTypeMetadataAddHealth(b, hp)
        S.BodyTypeMetadataAddBuildCostLead(b, 50)
        metas.append(S.BodyTypeMetadataEnd(b))
    bmeta = _offvec(b, metas)

    S.ConstantsStart(b)
    S.ConstantsAddIncreasePeriod(b, 3)
    S.ConstantsAddLeadAdditiveIncease(b, 5)
    consts = S.ConstantsEnd(b)

    S.GameHeaderStart(b)
    S.GameHeaderAddSpecVersion(b, spec)
    S.GameHeaderAddTeams(b, teams)
    S.GameHeaderAddBodyTypeMetadata(b, bmeta)
    S.GameHeaderAddConstants(b, consts)
    gh = S.GameHeaderEnd(b)

    mapname = b.CreateString("tiny")
    bodies = _spawned(b, [(0, 1, S.BodyType.ARCHON, 0, 0),
                          (1, 2, S.BodyType.ARCHON, 3, 3)])
    rubble = _i32vec(b, RUBBLE0)
    lead = _i32vec(b, LEAD0)
    S.GameMapStart(b)
    S.GameMapAddName(b, mapname)
    S.GameMapAddMinCorner(b, S.CreateVec(b, 0, 0))
    S.GameMapAddMaxCorner(b, S.CreateVec(b, 4, 4))
    S.GameMapAddSymmetry(b, 0)
    S.GameMapAddBodies(b, bodies)
    S.GameMapAddRubble(b, rubble)
    S.GameMapAddLead(b, lead)
    S.GameMapAddRandomSeed(b, 42)
    gmap = S.GameMapEnd(b)

    S.MatchHeaderStart(b)
    S.MatchHeaderAddMap(b, gmap)
    S.MatchHeaderAddMaxRounds(b, 4)
    mh = S.MatchHeaderEnd(b)

    r1 = _round(b, 1, lead_changes=(200, 200),
                spawns=[(100, 1, S.BodyType.MINER, 1, 0),
                        (101, 2, S.BodyType.MINER, 2, 3)],
                actions=[(0, S.Action.SPAWN_UNIT, 100), (1, S.Action.SPAWN_UNIT, 101)])
    r2 = _round(b, 2, lead_changes=(2, 2), moved=[(100, 1, 1)],
                actions=[(100, S.Action.MINE_LEAD, 1 + 1 * 4),
                         (100, S.Action.MINE_LEAD, 1 + 1 * 4)],
                lead_drops=[(1, 1, -1), (1, 1, -1)])
    r3 = _round(b, 3, lead_changes=(2, 2),
                actions=[(101, S.Action.ATTACK, 100),
                         (101, S.Action.CHANGE_HEALTH, -40)],
                lead_drops=[(1, 1, 10)], died=[100])
    r4 = _round(b, 4, lead_changes=(2, 2),
                actions=[(-1, S.Action.VORTEX, 1)])

    S.MatchFooterStart(b)
    S.MatchFooterAddWinner(b, 1)
    S.MatchFooterAddTotalRounds(b, 4)
    mf = S.MatchFooterEnd(b)
    S.GameFooterStart(b)
    S.GameFooterAddWinner(b, 1)
    gf = S.GameFooterEnd(b)

    evs = _offvec(b, [
        _event(b, S.Event.GameHeader, gh),
        _event(b, S.Event.MatchHeader, mh),
        _event(b, S.Event.Round, r1),
        _event(b, S.Event.Round, r2),
        _event(b, S.Event.Round, r3),
        _event(b, S.Event.Round, r4),
        _event(b, S.Event.MatchFooter, mf),
        _event(b, S.Event.GameFooter, gf),
    ])
    mhv = _i32vec(b, [1])
    mfv = _i32vec(b, [6])
    S.GameWrapperStart(b)
    S.GameWrapperAddEvents(b, evs)
    S.GameWrapperAddMatchHeaders(b, mhv)
    S.GameWrapperAddMatchFooters(b, mfv)
    gw = S.GameWrapperEnd(b)
    b.Finish(gw)
    return bytes(b.Output())


def run_tool(argv):
    """Run bc22_replay.main(argv + ['-o', tmp]) and return the output text."""
    fd, out = tempfile.mkstemp(suffix=".txt")
    os.close(fd)
    try:
        with contextlib.redirect_stderr(io.StringIO()):
            R.main(list(argv) + ["-o", out])
        with open(out) as fh:
            return fh.read()
    finally:
        os.unlink(out)


# ---------------------------------------------------------------------------
# pure helpers
# ---------------------------------------------------------------------------
class TestGlyphs(unittest.TestCase):
    def test_rubble_buckets(self):
        self.assertEqual(R.rubble_glyph(0), ".")
        self.assertEqual(R.rubble_glyph(9), ".")
        self.assertEqual(R.rubble_glyph(10), ":")
        self.assertEqual(R.rubble_glyph(33), ":")
        self.assertEqual(R.rubble_glyph(34), "o")
        self.assertEqual(R.rubble_glyph(66), "o")
        self.assertEqual(R.rubble_glyph(67), "#")
        self.assertEqual(R.rubble_glyph(100), "#")

    def test_lead_buckets(self):
        self.assertEqual(R.lead_glyph(0), " ")
        self.assertEqual(R.lead_glyph(1), ",")
        self.assertEqual(R.lead_glyph(9), ",")
        self.assertEqual(R.lead_glyph(10), ":")
        self.assertEqual(R.lead_glyph(24), ":")
        self.assertEqual(R.lead_glyph(25), "+")
        self.assertEqual(R.lead_glyph(49), "+")
        self.assertEqual(R.lead_glyph(50), "#")
        self.assertEqual(R.lead_glyph(99), "#")
        self.assertEqual(R.lead_glyph(100), "@")

    def test_team_letter(self):
        self.assertEqual(R.team_letter(1), "A")
        self.assertEqual(R.team_letter(2), "B")
        self.assertEqual(R.team_letter(0), "?")


class TestTransformGrid(unittest.TestCase):
    G = list(range(9))  # 3x3, idx = x + y*3

    def test_mirror_columns(self):
        self.assertEqual(R.transform_grid(self.G, 3, 3, 1),
                         [2, 1, 0, 5, 4, 3, 8, 7, 6])

    def test_mirror_rows(self):
        self.assertEqual(R.transform_grid(self.G, 3, 3, 2),
                         [6, 7, 8, 3, 4, 5, 0, 1, 2])

    def test_rotate_is_permutation(self):
        out = R.transform_grid(self.G, 3, 3, 0)
        self.assertEqual(sorted(out), list(range(9)))
        self.assertNotEqual(out, self.G)

    def test_rotate_noop_on_nonsquare(self):
        g = list(range(6))
        self.assertEqual(R.transform_grid(g, 3, 2, 0), g)

    def test_two_mirror_columns_is_identity(self):
        once = R.transform_grid(RUBBLE0, 4, 4, 1)
        twice = R.transform_grid(once, 4, 4, 1)
        self.assertEqual(twice, RUBBLE0)
        self.assertEqual(once, [18, 12, 6, 0, 42, 36, 30, 24,
                                66, 60, 54, 48, 90, 84, 78, 72])


class TestRenderBoard(unittest.TestCase):
    class _M:
        width = height = 4

    def test_places_robots_with_team_case(self):
        robots = {
            10: R.Robot(10, 1, S.BodyType.SOLDIER, 0, 0, 50),
            11: R.Robot(11, 2, S.BodyType.MINER, 3, 3, 40),
        }
        out = R.render_board(self._M(), robots, "none", RUBBLE0, LEAD0)
        rows = [ln for ln in out.splitlines() if "|" in ln]
        self.assertTrue(rows[0].strip().startswith("3 |"))      # top row is y=3
        self.assertIn("m", rows[0])                             # team B miner, lowercase
        self.assertTrue(rows[-1].strip().startswith("0 |"))
        self.assertIn("S", rows[-1])                            # team A soldier, uppercase

    def test_terrain_backdrop(self):
        out = R.render_board(self._M(), {}, "rubble", RUBBLE0, LEAD0)
        row0 = [ln for ln in out.splitlines() if ln.strip().startswith("0 |")][0]
        # idx 0..3 rubble 0,6,12,18 -> '.', '.', ':', ':'
        self.assertIn("|..::|", row0)


# ---------------------------------------------------------------------------
# parsing + reconstruction, against the synthetic replay
# ---------------------------------------------------------------------------
class TestLoad(unittest.TestCase):
    def setUp(self):
        self.raw = build_replay()

    def test_load_raw_and_gzip(self):
        for blob in (self.raw, gzip.compress(self.raw)):
            fd, path = tempfile.mkstemp(suffix=".bc22")
            os.write(fd, blob)
            os.close(fd)
            try:
                gh, matches = R.load(path)
            finally:
                os.unlink(path)
            self.assertEqual(len(matches), 1)
            m = matches[0]
            self.assertEqual((m.width, m.height), (4, 4))
            self.assertEqual(m.map_name, "tiny")
            self.assertEqual(m.rubble0, RUBBLE0)
            self.assertEqual(m.lead0, LEAD0)
            self.assertEqual(m.max_rounds, 4)
            self.assertEqual(len(m.rounds), 4)
            self.assertEqual(m.footer.Winner(), 1)
            # two starting archons, offset by minCorner (0,0)
            self.assertEqual(sorted((t, x, y) for (_, t, _, x, y) in m.start_bodies),
                             [(1, 0, 0), (2, 3, 3)])

    def test_bad_input_exits(self):
        fd, path = tempfile.mkstemp(suffix=".bc22")
        os.write(fd, b"\x02\x00")
        os.close(fd)
        try:
            with self.assertRaises(SystemExit):
                R.load(path)
        finally:
            os.unlink(path)

    def test_missing_file_exits(self):
        with self.assertRaises(SystemExit):
            R.load("/no/such/replay.bc22")


class TestDumpMatch(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fd, cls.path = tempfile.mkstemp(suffix=".bc22")
        os.write(fd, build_replay())
        os.close(fd)
        cls.out = run_tool([cls.path])

    @classmethod
    def tearDownClass(cls):
        os.unlink(cls.path)

    def _round_block(self, n):
        m = re.search(r"^----- Round %d/4 .*?(?=^----- Round |\nFINAL:)" % n,
                      self.out, re.S | re.M)
        self.assertIsNotNone(m, "round %d block not found" % n)
        return m.group(0)

    def test_header(self):
        self.assertIn("map: tiny", self.out)
        self.assertIn("(4 x 4)", self.out)
        self.assertRegex(self.out, r"team A \(id 1\)\s*:\s*alpha")
        self.assertRegex(self.out, r"team B \(id 2\)\s*:\s*beta")
        self.assertRegex(self.out, r"spec version\s*:\s*2\.2\.1")
        self.assertRegex(self.out, r"lead passive gain\s*:\s*\+5 Pb every 3 rounds")
        self.assertRegex(self.out, r"result\s*:\s*team A \(alpha\) wins after 4 rounds")
        self.assertIn("STARTING RUBBLE MAP", self.out)
        self.assertIn("STARTING LEAD MAP", self.out)

    def test_all_rounds_present(self):
        for n in (1, 2, 3, 4):
            self.assertIn("----- Round %d/4" % n, self.out)

    def test_round1_spawns_and_board(self):
        blk = self._round_block(1)
        self.assertRegex(blk, r"team A: lead\s+200 \(\+200\).*units:.*MINERx1.*ARCHONx1")
        self.assertIn("+ A MINER #100 at (1,0)", blk)
        self.assertIn("+ B MINER #101 at (2,3)", blk)
        self.assertIn("ROBOTS", blk)
        self.assertIn("LEAD  (live: 35 Pb on 3 tiles)", blk)
        self.assertRegex(blk, r"(?m)^\s*0 \|AM::\|")     # archon+miner on row y=0
        self.assertRegex(blk, r"(?m)^\s*3 \|##ma\|")     # team B miner+archon on row y=3

    def test_round2_mining_depletes_lead(self):
        blk = self._round_block(2)
        self.assertIn("LEAD  (live: 33 Pb on 3 tiles)", blk)   # 35 - 2 mined
        self.assertRegex(blk, r"map:\s+33 Pb \(-2\) on 3 tiles")
        self.assertRegex(blk, r"team A mining ops: 2 lead")

    def test_round3_death_reclaim_and_regen(self):
        blk = self._round_block(3)
        self.assertIn("B MINER #101 attacks A MINER #100", blk)
        self.assertIn("x A MINER #100 at (1,1)", blk)
        # idx5: 3 (+10 reclaim) = 13; regen round -> +5 to each of 3 live tiles
        # totals: 15 + 18 + 25 = 58
        self.assertIn("LEAD  (live: 58 Pb on 3 tiles)", blk)
        self.assertRegex(blk, r"\[\+5 Pb regen x3 tiles\]")

    def test_round4_vortex_transforms_rubble(self):
        blk = self._round_block(4)
        self.assertIn("VORTEX reshuffled the rubble (mirror columns)", blk)
        self.assertIn("RUBBLE  (live, just changed by Vortex)", blk)

    def test_footer(self):
        self.assertRegex(self.out, r"FINAL: team A \(alpha\) wins after 4 rounds")
        # stockpile 200 + 2 + 2 + 2
        self.assertRegex(self.out, r"team A stockpile: 206 Pb")

    def test_range_and_step_options(self):
        out = run_tool([self.path, "--from", "2", "--to", "3"])
        self.assertNotIn("----- Round 1/4", out)
        self.assertIn("----- Round 2/4", out)
        self.assertIn("----- Round 3/4", out)
        self.assertNotIn("----- Round 4/4", out)

        out = run_tool([self.path, "--step", "2"])
        self.assertIn("----- Round 1/4", out)
        self.assertNotIn("----- Round 2/4", out)
        self.assertIn("----- Round 3/4", out)
        self.assertIn("----- Round 4/4", out)   # last round always shown

    def test_no_lead_map_option(self):
        out = run_tool([self.path, "--no-lead-map"])
        self.assertIn("ROBOTS", out)
        self.assertNotIn("LEAD  (live:", out)

    def test_no_board_option(self):
        out = run_tool([self.path, "--no-board"])
        self.assertNotIn("ROBOTS", out)
        self.assertNotIn("LEAD  (live:", out)
        self.assertIn("----- Round 4/4", out)


# ---------------------------------------------------------------------------
# end-to-end against real replays, if any are checked out
# ---------------------------------------------------------------------------
_REAL = sorted(glob.glob(os.path.join(HERE, "..", "matches", "*.bc22")))


@unittest.skipUnless(_REAL, "no matches/*.bc22 present (they are git-ignored)")
class TestRealReplay(unittest.TestCase):
    def test_pipeline_and_invariants(self):
        out = run_tool([_REAL[0], "--step", "50"])
        self.assertIn("MATCH 1/", out)
        self.assertRegex(out, r"FINAL: team [AB] .* wins after \d+ rounds")
        self.assertRegex(out, r"lead left on map: \d+ Pb on \d+ tiles")
        self.assertNotIn("Traceback (most recent call last)", out)

        # reconstructed lead must never be reported negative
        for total, tiles in re.findall(r"LEAD  \(live: (-?\d+) Pb on (-?\d+) tiles\)", out):
            self.assertGreaterEqual(int(total), 0)
            self.assertGreaterEqual(int(tiles), 0)

        # round headers are consistent with the final round count
        last = int(re.search(r"wins after (\d+) rounds", out).group(1))
        seen = [int(n) for n in re.findall(r"----- Round (\d+)/", out)]
        self.assertEqual(seen[0], 1)
        self.assertEqual(seen[-1], last)
        self.assertTrue(all(a < b for a, b in zip(seen, seen[1:])))


if __name__ == "__main__":
    unittest.main()
