"""Structural checks on the committed scene.rml: `python -m unittest test_rig`.

What they hold down (each was a real way to break the rig):

  regenerate   `python gen_scene.py` must reproduce the committed file once push-assigned ids
               are stripped. A committed scene.rml that the generator no longer produces is a
               hand-edit or a stale commit - see README, "Push before you commit".
  unique ids   Rive resolves everything by id; a duplicate silently rebinds an object.
  references   every transition target and every animationId must resolve, in its own layer.
  contract     check_contract.py: the file still exposes what RiveAvatarContract.kt writes.
  timeline     timeline.py still reads the document (the review tool, README "The loop").
  seams        rig/seams.py still reads the document, and the count of seams nobody has signed
               off is exactly what it was: a new hard cut across a real delta, or a new one-shot
               that hands a node back to the layers below, fails here and names itself.

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


def duplicate_ids(root):
    """{id: [every element carrying it]} for the ids that appear more than once."""
    where = {}
    for el in root.iter():
        if el.get("id") is not None:
            where.setdefault(el.get("id"), []).append(f"{el.tag} {el.get('name') or ''}".strip())
    return {i: places for i, places in where.items() if len(places) > 1}


def describe_duplicates(duplicates):
    return "duplicate ids: " + "; ".join(f"{i} on {' / '.join(w)}" for i, w in sorted(duplicates.items()))


class TestIds(unittest.TestCase):
    def test_every_id_in_the_committed_scene_is_unique(self):
        # The committed file, not a regeneration: `rive push` writes its own 0:n ids into it, and
        # the parity test strips those, so a duplicate push-assigned id is only visible here.
        duplicates = duplicate_ids(scene_tree().getroot())
        self.assertFalse(duplicates, describe_duplicates(duplicates))

    def test_every_generated_id_is_unique(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "scene.rml")
            proc = run([sys.executable, "gen_scene.py", out])
            self.assertEqual(proc.returncode, 0, f"gen_scene.py failed:\n{proc.stderr}")
            duplicates = duplicate_ids(ET.parse(out).getroot())
        self.assertFalse(duplicates, describe_duplicates(duplicates))

    def test_debug_variants_need_an_explicit_output(self):
        for flags in (["--probe"], ["--solo", "IdleBounce"]):
            proc = run([sys.executable, "gen_scene.py"] + flags)
            self.assertNotEqual(proc.returncode, 0, f"gen_scene.py {flags} fell back to scene.rml")
            self.assertIn("output path", proc.stderr)

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


class TestSeams(unittest.TestCase):
    """The seam ledger and the blend policy (MOTION-PIPELINE step A)."""

    # Every seam in the committed scene.rml that nobody has signed. It is zero, and it stays zero.
    # It was 61, all hand-backs: a one-shot ended holding Face/Body where the state that followed
    # never keyed it, so the pose snapped back to rest at a 0 ms hand-off. 57 of those were the
    # generic entries, which now travel the source state's held pose to the target's rest
    # (letta-mobile-r4bbm); the remaining four are the error flash, three of them signed
    # `hold=True` because the Expression layer underneath is holding that same pose, and one that
    # became an ordinary signed cut once SuccessFlash keyed Body.y again (letta-mobile-uesod).
    # A new hard cut is refused outright by the Layer policy; a new hand-back fails here.
    UNEXPLAINED = 0

    @classmethod
    def setUpClass(cls):
        from rig.seams import ledger, object_names, unexplained
        cls.rows = ledger(SCENE)
        cls.loose = unexplained(cls.rows)
        cls.names = object_names(SCENE)

    def test_the_ledger_reads_the_committed_scene(self):
        self.assertTrue(self.rows, "rig/seams.py found no seams at all in scene.rml; the ledger "
                                   "is not reading the document")
        signed = [r for r in self.rows if r.cut]
        self.assertTrue(signed, "no seam is signed cut=True; the cut marks are not reaching the "
                                "ledger (rig.layers.CUT_MARKS)")

    def test_unexplained_seams_are_the_ones_we_know_about(self):
        listing = "\n".join(
            f"  {r.machine}/{r.layer}: {r.frm} -> {r.to} ({r.kind}, {r.ms} ms) "
            f"{self.names.get(r.obj, r.obj)}.{r.prop} {r.from_value} -> {r.to_value} "
            f"({r.normalised:.1f}x tolerance)" for r in self.loose)
        self.assertEqual(
            len(self.loose), self.UNEXPLAINED,
            f"the seam ledger now finds {len(self.loose)} unsigned seams, not {self.UNEXPLAINED}.\n"
            f"Either a 0 ms hand-off was added across a real delta (blend it, or mark the "
            f"transition cut=True with a reason), or one was fixed - in which case move the pin.\n"
            f"python -m rig.seams prints this list:\n{listing}")

    def test_the_policy_refuses_a_hard_cut_and_takes_a_signed_one(self):
        from rml import Y, animation
        from rig.layers import Layer, OnBool, State
        from rig.seams import animation_index

        index = animation_index("\n".join([
            animation("Up", "9:1", 10, {"9:90": {Y: [(0, 0), (10, -40)]}}),
            animation("Down", "9:2", 10, {"9:90": {Y: [(0, 0), (10, 40)]}})]))
        layer = lambda t: Layer("Synthetic", "9:10", "9:20",
                                states=[State("9:1", "9:20", 0, transitions=[t]),
                                        State("9:2", "9:21", 1)])
        # Up leaves the node at -40, Down starts it at 0: 40 px across a 0 ms cut.
        with self.assertRaises(ValueError) as caught:
            layer(OnBool("9:21", "1:8", "true", 0, None)).rml(seams=index)
        message = str(caught.exception)
        for expected in ("Synthetic", "9:20", "9:21", "9:90", "y", "40"):
            self.assertIn(expected, message, f"the refusal should name {expected}: {message}")
        # Signed, it is the author's decision and the layer builds.
        signed = layer(OnBool("9:21", "1:8", "true", 0, None, cut=True, reason="a test cut"))
        self.assertIn('duration="0"', signed.rml(seams=index))
        # A blend is never a seam, signed or not.
        self.assertIn('duration="200"', layer(OnBool("9:21", "1:8", "true", 200, None)).rml(seams=index))
        # And a signature with nothing to say is refused too.
        with self.assertRaises(ValueError):
            layer(OnBool("9:21", "1:8", "true", 0, None, cut=True)).rml(seams=index)


if __name__ == "__main__":
    unittest.main()
