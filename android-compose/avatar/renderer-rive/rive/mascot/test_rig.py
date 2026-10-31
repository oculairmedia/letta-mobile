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

    # Every seam in the committed scene.rml that nobody has signed. The ledger got stricter with
    # the minimum transition time (rig/constants.py MIN_TRANSITION_MS): it now weighs every frame
    # a source can be left at, not its last key, and hand-backs at ANY duration - Rive plays the
    # outgoing animation at full weight for the whole blend and lets the layers below have the
    # property only when it ends, so a blend does not soften one.
    #
    # Under that ledger the scene before the floor had 1924 sub-100 ms hand-offs that moved a pose
    # (AnyState expanded) and 291 hand-back rows. Every one of the first kind is gone and the gate
    # in rig/layers.py refuses a new one outright. What is pinned here is the rest of the second
    # kind: 195 rows, all a higher layer letting go of a node a lower layer also keys - an idle or
    # wander beat dropped mid-move by reduceMotion or sleep, hover/drag/pet out, one reaction
    # interrupting another that keyed something it does not. A duration cannot fix those; the
    # one-shot has to end where the layers below are, or the two layers stop sharing the node.
    # The number may only go down.
    UNEXPLAINED = 195

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
            f"Either a hand-back was added (end the one-shot where the layers below are, or sign "
            f"it hold=True when they hold that pose by design), or one was fixed - move the pin "
            f"down.\n"
            f"python -m rig.seams prints this list:\n{listing}")

    def test_the_floor_refuses_a_one_frame_pose_change_signed_or_not(self):
        from rml import Y, animation
        from rig.constants import MIN_TRANSITION_MS
        from rig.layers import Layer, OnBool, State
        from rig.seams import animation_index

        index = animation_index("\n".join([
            animation("Up", "9:1", 10, {"9:90": {Y: [(0, 0), (10, -40)]}}),
            animation("Down", "9:2", 10, {"9:90": {Y: [(0, 0), (10, 40)]}})]))
        layer = lambda t: Layer("Synthetic", "9:10", "9:20",
                                states=[State("9:1", "9:20", 0, transitions=[t]),
                                        State("9:2", "9:21", 1)])
        # Up leaves the node anywhere in 0..-40, Down starts it at 0: up to 40 px in one frame.
        with self.assertRaises(ValueError) as caught:
            layer(OnBool("9:21", "1:8", "true", 0, None)).rml(seams=index)
        message = str(caught.exception)
        for expected in ("Synthetic", "9:20", "9:21", "9:90", "y", "40", f"{MIN_TRANSITION_MS} ms floor"):
            self.assertIn(expected, message, f"the refusal should name {expected}: {message}")
        # A signature no longer excuses a pose: cut=True is for discrete properties only.
        with self.assertRaises(ValueError):
            layer(OnBool("9:21", "1:8", "true", 0, None, cut=True, reason="a test cut")).rml(seams=index)
        # Nor does a blend that is shorter than the floor.
        with self.assertRaises(ValueError):
            layer(OnBool("9:21", "1:8", "true", MIN_TRANSITION_MS - 1, None)).rml(seams=index)
        # At the floor it builds.
        self.assertIn(f'duration="{MIN_TRANSITION_MS}"',
                      layer(OnBool("9:21", "1:8", "true", MIN_TRANSITION_MS, None)).rml(seams=index))

    def test_a_discrete_swap_may_cut_when_signed_and_an_index_may_not_blend(self):
        from rml import NESTED_VALUE, animation
        from rig.layers import Layer, OnBool, State
        from rig.seams import animation_index

        index = animation_index("\n".join([
            animation("GlyphA", "9:3", 10, {"9:91": {NESTED_VALUE: 2}}),
            animation("GlyphB", "9:4", 10, {"9:91": {NESTED_VALUE: 9}})]))
        layer = lambda t: Layer("Synthetic", "9:11", "9:22",
                                states=[State("9:3", "9:22", 0, transitions=[t]),
                                        State("9:4", "9:23", 1)])
        with self.assertRaises(ValueError):          # unsigned: say why
            layer(OnBool("9:23", "1:8", "true", 0, None)).rml(seams=index)
        signed = layer(OnBool("9:23", "1:8", "true", 0, None, cut=True, reason="a glyph swap"))
        self.assertIn('duration="0"', signed.rml(seams=index))
        with self.assertRaises(ValueError) as caught:  # a blend would show glyphs 3..8 on the way
            layer(OnBool("9:23", "1:8", "true", 200, None)).rml(seams=index)
        self.assertIn("sweeps", str(caught.exception))
        with self.assertRaises(ValueError):          # and a signature with nothing to say
            layer(OnBool("9:23", "1:8", "true", 0, None, cut=True)).rml(seams=index)

    def test_leaving_a_loop_mid_bob_is_weighed_not_just_its_last_key(self):
        """The bug the floor exists for: StateIdle's last key equals Enter_idle_thinking's first,
        so an ends-only check passed a 0 ms cut - but the loop is left wherever the request lands,
        up to 11 px into its bob, and that snapped in one frame."""
        from rig.ids import enter_anim, enter_node, root_state_anim, root_state_node, state_enum_ids
        from rig.layers import Layer, OnEnum, State, When
        from rig.seams import animation_index
        from rml import VM_STATE, when_enum

        index = animation_index(SCENE)
        idle, entry = root_state_node["idle"], enter_node[("idle", "thinking")]
        nxt = enter_node[("thinking", "speaking")]

        def layer(into, onward=()):
            return Layer("Expression", "9:12", idle, states=[
                State(root_state_anim["idle"], idle, 0, transitions=[into]),
                State(enter_anim[("idle", "thinking")], entry, 1, transitions=list(onward)),
                State(enter_anim[("thinking", "speaking")], nxt, 2)])
        cut = OnEnum(entry, state_enum_ids["thinking"], 0, None, cut=True, reason="the old blink excuse")
        with self.assertRaises(ValueError) as caught:
            layer(cut).rml(seams=index)
        self.assertIn("StateIdle", str(caught.exception))
        # The blend into an entry must be over before the entry flips `expr`, or it sweeps.
        with self.assertRaises(ValueError):
            layer(OnEnum(entry, state_enum_ids["thinking"], 150)).rml(seams=index)
        ok = OnEnum(entry, state_enum_ids["thinking"], 100)
        layer(ok).rml(seams=index)
        # An entry interrupted before its flip would blend two indices; gated past it, it is fine.
        onward = lambda gate: When(nxt, (when_enum(VM_STATE, state_enum_ids["speaking"]),), 100, None,
                                   after_ms=gate)
        with self.assertRaises(ValueError):
            layer(ok, [onward(None)]).rml(seams=index)
        layer(ok, [onward(100)]).rml(seams=index)


if __name__ == "__main__":
    unittest.main()
