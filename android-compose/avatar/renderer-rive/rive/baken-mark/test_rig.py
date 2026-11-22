"""The cheap gate: `python -m unittest test_rig`. Standard library only, no Rive CLI.

  parity        the committed scene.rml is exactly what gen_scene.py writes (no hand edits)
  references    every transition target and animationId resolves; ids are unique
  siblings      every animation of a layer keys the same properties (no value leaks through)
  grounded      at rest both stones stand on the ground line, side by side, without touching
  loops         every sustained track ends where it starts, so a loop has no seam
  reduceMotion  the calm twins are single static poses, and the calm one-shots move nothing
"""
import os
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET

import rig  # noqa: F401 - path bootstrap
import gen_scene
from rig import constants, ids, motion, stones

HERE = os.path.dirname(os.path.abspath(__file__))
SCENE = os.path.join(HERE, "scene.rml")
TRANSFORM_KEYS = {"13", "14", "15", "16", "17"}   # x y rotation scaleX scaleY


def _doc():
    with open(SCENE, encoding="utf-8") as f:
        return f.read()


def _keyed(anim):
    """{(objectId, propertyKey): [values]} of one LinearAnimation element."""
    out = {}
    for ko in anim.findall("KeyedObject"):
        for kp in ko.findall("KeyedProperty"):
            out[(ko.get("objectId"), kp.get("propertyKey"))] = [k.get("value") for k in kp]
    return out


def _animations():
    root = ET.fromstring(_doc())
    return {a.get("name"): a for a in root.iter("LinearAnimation")}


class Parity(unittest.TestCase):
    def test_scene_rml_is_what_the_generator_writes(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "scene.rml")
            gen_scene.main([out])
            with open(out, encoding="utf-8") as f:
                fresh = f.read()
        self.assertEqual(fresh, _doc(), "scene.rml is stale or hand-edited: run python gen_scene.py")


class References(unittest.TestCase):
    def test_every_id_is_unique(self):
        found = re.findall(r'\bid="(\d+:\d+)"', _doc())
        dupes = sorted({i for i in found if found.count(i) > 1})
        self.assertEqual(dupes, [])

    def test_every_transition_and_state_resolves(self):
        doc = _doc()
        anims = set(re.findall(r'<LinearAnimation[^>]*\bid="([^"]+)"', doc))
        states = set(re.findall(r'<AnimationState[^>]*\bid="([^"]+)"', doc))
        for target in re.findall(r'stateToId="([^"]+)"', doc):
            self.assertIn(target, states, f"transition to unknown state {target}")
        for aid in re.findall(r'animationId="([^"]+)"', doc):
            self.assertIn(aid, anims, f"state plays unknown animation {aid}")

    def test_every_keyed_object_exists(self):
        doc = _doc()
        declared = set(re.findall(r'\bid="([^"]+)"', doc))
        for obj in re.findall(r'objectId="([^"]+)"', doc):
            self.assertIn(obj, declared, f"animation keys unknown object {obj}")


class Siblings(unittest.TestCase):
    """Every animation in a layer keys the same (object, property) set."""

    def _same(self, names):
        anims = _animations()
        sets = {n: set(_keyed(anims[n])) for n in names}
        first = sets[names[0]]
        for n, s in sets.items():
            self.assertEqual(s, first, f"{n} keys a different set than {names[0]}: "
                                       f"missing {sorted(first - s)}, extra {sorted(s - first)}")

    def test_theme(self):
        self._same([f"Theme_{t}" for t in ids.THEMES])

    def test_pose(self):
        self._same([f"Pose_{s}" for s in ids.STATES] + [f"PoseCalm_{s}" for s in ids.STATES])

    def test_light(self):
        self._same([f"Light_{s}" for s in ids.STATES] + [f"LightCalm_{s}" for s in ids.STATES])

    def test_event(self):
        self._same(["Boot", "Success", "BootCalm", "SuccessCalm"])

    def test_hover(self):
        self._same(["HoverRest", "HoverLean"])


class Grounded(unittest.TestCase):
    """The core physics rule: real stones on a ground plane, nothing floating."""

    def _extent(self, stone):
        pts = [(x, y) for x, y, _ci, _co in stones.stone_vertices(stone)]
        ox, oy = (constants.art(v) for v in constants.PIVOT[stone])
        return [(x + ox, y + oy) for x, y in pts]

    def test_both_stones_stand_on_the_ground_line(self):
        ground = constants.art(constants.GROUND_Y)
        for stone in ids.STONES:
            lowest = max(y for _x, y in self._extent(stone))
            self.assertAlmostEqual(lowest, ground, delta=constants.ART_SCALE * 2,
                                   msg=f"{stone} does not rest on the ground")

    def test_the_pivots_are_the_spec_contact_points(self):
        self.assertEqual(tuple(round(constants.art(v)) for v in constants.PIVOT["pebble"]), (335, 420))
        self.assertAlmostEqual(constants.art(constants.PIVOT["anchor"][0]), 162, delta=1)

    def test_the_stones_do_not_touch_at_rest(self):
        anchor_right = max(x for x, _y in self._extent("anchor"))
        pebble_left = min(x for x, _y in self._extent("pebble"))
        self.assertGreater(pebble_left, anchor_right, "the stones overlap at rest")


class Loops(unittest.TestCase):
    def test_every_sustained_track_closes(self):
        for state, (_ms, _bez, tracks) in constants.POSE.items():
            for stone, track in tracks.items():
                self.assertEqual(track[0][1], track[-1][1], f"Pose {state}/{stone} does not loop")
        for state, (_ms, _bez, track) in constants.LIGHT.items():
            first, last = dict(track[0][1]), dict(track[-1][1])
            for prop, hider in constants.LOOP_WRAPS_HIDDEN.items():
                if first.get(prop) != last.get(prop):
                    self.assertEqual((first.get(hider), last.get(hider)), (0, 0),
                                     f"Light {state}: {prop} wraps while {hider} is visible")
                    first.pop(prop), last.pop(prop)
            self.assertEqual(first, last, f"Light {state} does not loop")

    def test_the_spec_timings(self):
        self.assertEqual(constants.IDLE_MS, 5200)
        self.assertEqual(constants.LOADING_MS, 1500)
        self.assertEqual(constants.AMBIENT_MS, 3400)
        idle = constants.POSE["idle"][2]
        self.assertEqual(idle["anchor"][1][1]["rot"], -0.85)
        self.assertEqual(idle["pebble"][1][1]["rot"], 1.35)
        self.assertEqual(constants.POSE["connecting"][2]["anchor"][0][1]["x"], -5.5)
        self.assertEqual(constants.POSE["connecting"][2]["pebble"][0][1]["x"], 8)
        self.assertEqual(constants.POSE_CALM["degraded"]["pebble"], {"x": 10, "rot": 10})


class ReduceMotion(unittest.TestCase):
    """reduceMotion: no loops; states only through a static parting or brightness."""

    def test_every_calm_twin_is_one_static_pose(self):
        anims = _animations()
        for s in ids.STATES:
            for name in (f"PoseCalm_{s}", f"LightCalm_{s}"):
                a = anims[name]
                self.assertEqual(a.get("loopValue"), "oneShot", f"{name} loops")
                self.assertEqual(a.get("duration"), "1", f"{name} is not a single pose")
                for prop, values in _keyed(a).items():
                    self.assertEqual(len(values), 1, f"{name} keys {prop} more than once")

    def test_the_calm_one_shots_move_nothing(self):
        anims = _animations()
        for name in ("BootCalm", "SuccessCalm"):
            for (obj, key), values in _keyed(anims[name]).items():
                if key in TRANSFORM_KEYS:
                    self.assertEqual(len(set(values)), 1, f"{name} moves {obj}/{key}: {values}")

    def test_every_sustained_state_has_a_calm_entry(self):
        doc = _doc()
        for s in ids.STATES:
            for node in (ids.pose_calm_node[s], ids.light_calm_node[s]):
                self.assertIn(f'stateToId="{node}"', doc, f"no way into the calm twin {node}")

    def test_calm_states_differ_only_by_parting_and_light(self):
        """A calm pose may part the stones (x) or tip the rolled-away pebble; it never lifts one."""
        for s, poses in constants.POSE_CALM.items():
            for stone, pose in poses.items():
                self.assertNotIn("y", pose, f"calm {s}/{stone} lifts a stone")
                self.assertFalse({"sx", "sy"} & set(pose), f"calm {s}/{stone} squashes a stone")


class Motion(unittest.TestCase):
    def test_track_keys_follow_css_keyframe_rules(self):
        track = [(0, {}), (50, {"rot": 2}), (100, {})]
        self.assertEqual(motion.track_keys(track, "rot", 0, 1000), [(0, 0), (30, 2), (60, 0)])
        self.assertEqual(motion.track_keys(track, "x", 0, 1000), [(0, 0)])


if __name__ == "__main__":
    unittest.main()
