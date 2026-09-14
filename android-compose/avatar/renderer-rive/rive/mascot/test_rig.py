"""Structural checks on the committed scene.rml: `python -m unittest test_rig`.

What they hold down (each was a real way to break the rig):

  regenerate   `python gen_scene.py` must reproduce the committed file once push-assigned ids
               are stripped. A committed scene.rml that the generator no longer produces is a
               hand-edit or a stale commit - see README, "Push before you commit".
  unique ids   Rive resolves everything by id; a duplicate silently rebinds an object.
  references   every transition target and every animationId must resolve, in its own layer.
  contract     check_contract.py: the file still exposes what RiveAvatarContract.kt writes.
  timeline     timeline.py still reads the document (the review tool, README "The loop").

Standard library only, no pytest. Nothing here writes into the working tree: the regenerate
goes to a tempfile.TemporaryDirectory.
"""
import os
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
SCENE = os.path.join(HERE, "scene.rml")
CONTRACT_CHECK = os.path.abspath(os.path.join(HERE, "..", "..", "native", "rivdump", "check_contract.py"))
CONTRACT_KT = os.path.abspath(os.path.join(
    HERE, "..", "..", "src", "commonMain", "kotlin", "com", "letta", "mobile", "avatar", "rive",
    "RiveAvatarContract.kt"))

# `rive push` writes an id onto every object the generator left unnamed, so a fresh generate
# always differs from the committed file by exactly those. Strip them from both sides.
PUSH_ID = re.compile(r' id="0:\d+"')

_TREE = None


def scene_tree():
    global _TREE
    if _TREE is None:
        _TREE = ET.parse(SCENE)
    return _TREE


def run(args, **kwargs):
    return subprocess.run(args, cwd=HERE, capture_output=True, text=True, timeout=120, **kwargs)


class TestGenerator(unittest.TestCase):
    def test_regenerates_to_the_committed_scene(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "scene.rml")
            proc = run([sys.executable, "gen_scene.py", out])
            self.assertEqual(proc.returncode, 0, f"gen_scene.py failed:\n{proc.stderr}")
            with open(out, encoding="utf-8") as f:
                generated = PUSH_ID.sub("", f.read())
        with open(SCENE, encoding="utf-8") as f:
            committed = PUSH_ID.sub("", f.read())
        if generated == committed:
            return
        gen_lines, com_lines = generated.splitlines(), committed.splitlines()
        first = next((i for i, (a, b) in enumerate(zip(com_lines, gen_lines)) if a != b),
                     min(len(gen_lines), len(com_lines)))
        detail = [
            f"committed scene.rml is not what gen_scene.py produces "
            f"({len(com_lines)} committed lines vs {len(gen_lines)} generated)",
            "regenerate and commit what `rive push` writes (README: 'Push before you commit').",
            f"first difference at line {first + 1}:",
            f"  committed: {com_lines[first] if first < len(com_lines) else '<end of file>'}",
            f"  generated: {gen_lines[first] if first < len(gen_lines) else '<end of file>'}",
        ]
        self.fail("\n".join(detail))


class TestIds(unittest.TestCase):
    def test_every_id_is_unique(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "scene.rml")
            proc = run([sys.executable, "gen_scene.py", out])
            self.assertEqual(proc.returncode, 0, f"gen_scene.py failed:\n{proc.stderr}")
            root = ET.parse(out).getroot()
        seen, duplicates = {}, {}
        for el in root.iter():
            i = el.get("id")
            if i is None:
                continue
            if i in seen:
                duplicates.setdefault(i, [seen[i]]).append(f"{el.tag} {el.get('name') or ''}".strip())
            else:
                seen[i] = f"{el.tag} {el.get('name') or ''}".strip()
        self.assertFalse(duplicates, "duplicate ids: " + "; ".join(
            f"{i} on {' / '.join(where)}" for i, where in sorted(duplicates.items())))

    def test_ids_are_client_object_pairs(self):
        bad = [el.get("id") for el in scene_tree().getroot().iter()
               if el.get("id") is not None and not re.fullmatch(r"\d+:\d+", el.get("id"))]
        self.assertFalse(bad, f"ids must be numeric client:object - {sorted(set(bad))[:10]}")


class TestReferences(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = scene_tree().getroot()
        cls.animation_ids = {a.get("id") for a in cls.root.iter("LinearAnimation")}

    def test_animation_states_point_at_real_animations(self):
        missing = []
        for board in self.root.iter("Artboard"):
            board_anims = {a.get("id") for a in board.iter("LinearAnimation")}
            for st in board.iter("AnimationState"):
                aid = st.get("animationId")
                if aid not in board_anims:
                    missing.append(f"state {st.get('id')} -> animation {aid}")
        self.assertFalse(missing, "AnimationState animationId does not resolve: " + "; ".join(missing))

    def test_transitions_stay_inside_their_layer(self):
        stray = []
        for layer in self.root.iter("StateMachineLayer"):
            targets = {el.get("id") for el in layer.iter()
                       if el.tag in ("AnimationState", "AnyState", "EntryState", "ExitState", "BlendState1D")}
            for t in layer.iter("StateTransition"):
                to = t.get("stateToId")
                if to not in targets:
                    stray.append(f"layer {layer.get('name')}: transition {t.get('id')} -> {to}")
        self.assertFalse(stray, "StateTransition stateToId leaves its layer: " + "; ".join(stray))

    def test_keyed_objects_point_at_real_objects(self):
        known = {el.get("id") for el in self.root.iter() if el.get("id")}
        missing = sorted({ko.get("objectId") for ko in self.root.iter("KeyedObject")
                          if ko.get("objectId") not in known})
        self.assertFalse(missing, f"KeyedObject objectId does not resolve: {missing}")


class TestContract(unittest.TestCase):
    def test_check_contract_passes(self):
        if not os.path.exists(CONTRACT_CHECK):
            self.skipTest(f"no check_contract.py at {CONTRACT_CHECK}")
        if not os.path.exists(CONTRACT_KT):
            self.skipTest(f"no RiveAvatarContract.kt at {CONTRACT_KT}")
        proc = run([sys.executable, CONTRACT_CHECK, HERE, CONTRACT_KT])
        self.assertEqual(proc.returncode, 0,
                         f"check_contract.py failed:\n{proc.stdout}\n{proc.stderr}")


class TestTimeline(unittest.TestCase):
    def test_list_runs_and_names_known_animations(self):
        proc = run([sys.executable, "timeline.py", "--list"])
        self.assertEqual(proc.returncode, 0, f"timeline.py --list failed:\n{proc.stderr}")
        self.assertIn("Breath", proc.stdout)
        self.assertIn("Enter_idle_listening", proc.stdout)

    def test_one_animation_prints_a_curve(self):
        proc = run([sys.executable, "timeline.py", "IdleBounce", "--width", "40"])
        self.assertEqual(proc.returncode, 0, f"timeline.py IdleBounce failed:\n{proc.stderr}")
        self.assertIn("IdleBounce", proc.stdout)
        self.assertIn("(frames)", proc.stdout)

    def test_layers_runs(self):
        proc = run([sys.executable, "timeline.py", "--layers"])
        self.assertEqual(proc.returncode, 0, f"timeline.py --layers failed:\n{proc.stderr}")
        self.assertIn("layer Expression", proc.stdout)


if __name__ == "__main__":
    unittest.main()
