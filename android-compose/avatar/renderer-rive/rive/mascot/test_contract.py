"""The contract check, as tests that run in CI: `python -m unittest test_contract`.

Standard library only and no Rive CLI: `native/rivdump/check_contract.py` reads the SHIPPED
`.riv` files directly (`rivfile.py`), so this runs on any runner (.github/workflows/mascot-rig.yml).

  shipped       both shipped copies are byte-identical, and the file exposes exactly the
                documented view model (rig/contract.py): every property with its type, the
                AvatarState / MascotShape keys in order, the Avatar state machine - and every name
                RiveAvatarContract.kt writes is a contract property of the right kind
  drift         a renamed app constant, a trigger written as an input, a renamed or retyped file
                property, a reordered enum: each one fails, and names itself
  docs          the input tables in MASCOT.md and ARTIST.md list exactly the contract
  cli           the script runs from a relative path with `..` in it
"""
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.abspath(os.path.join(HERE, "..", ".."))
RIVDUMP = os.path.join(MODULE, "native", "rivdump")
CONTRACT_KT = os.path.join(MODULE, "src", "commonMain", "kotlin", "com", "letta", "mobile", "avatar", "rive",
                           "RiveAvatarContract.kt")
MASCOT_MD = os.path.join(MODULE, "MASCOT.md")
ARTIST_MD = os.path.join(HERE, "ARTIST.md")

sys.path.insert(0, RIVDUMP)
sys.path.insert(0, HERE)

import check_contract  # noqa: E402
from rig import contract  # noqa: E402
from rivfile import read  # noqa: E402


def shipped():
    return check_contract.default_rivs(HERE)[:2]


def surface():
    return check_contract.file_surface(read(str(shipped()[0])))


class Shipped(unittest.TestCase):
    def test_the_shipped_file_matches_the_contract(self):
        problems = check_contract.check(HERE, CONTRACT_KT, shipped())
        self.assertEqual(problems, [], "\n".join(problems))

    def test_both_shipped_copies_are_identical(self):
        android, desktop = shipped()
        self.assertEqual(android.read_bytes(), desktop.read_bytes(),
                         "the Android and desktop mascot.riv differ: copy build/mascot.riv to both")

    def test_the_view_model_is_exactly_the_contract_in_order(self):
        props = surface()["models"][contract.VIEW_MODEL]
        self.assertEqual([(n, k) for n, k, _ in props], [(p.name, p.type) for p in contract.AVATAR])

    def test_the_new_states_are_enum_keys(self):
        self.assertIn("working", surface()["enums"]["AvatarState"])
        self.assertIn("dreaming", surface()["enums"]["AvatarState"])


class Drift(unittest.TestCase):
    """A deliberate drift in each party must fail, and the message must name it."""

    def _app(self, edit):
        kt = Path(CONTRACT_KT).read_text(encoding="utf-8")
        drifted = edit(kt)
        self.assertNotEqual(drifted, kt, "the drift edit did not change the Kotlin source")
        return check_contract.app_problems(check_contract.app_wants(drifted), contract)

    def test_a_renamed_app_input_fails(self):
        problems = self._app(lambda kt: kt.replace('INPUT_LOOK_X: String = "lookX"', 'INPUT_LOOK_X: String = "lookXX"'))
        self.assertTrue(any("lookXX" in p for p in problems), problems)

    def test_a_trigger_written_as_an_input_fails(self):
        problems = self._app(lambda kt: kt.replace('TRIGGER_BLINK: String = "blink"', 'INPUT_BLINK: String = "blink"'))
        self.assertTrue(any("'blink'" in p and "trigger" in p for p in problems), problems)

    def test_an_unknown_state_key_fails(self):
        problems = self._app(lambda kt: kt.replace('AvatarState.IDLE -> "idle"', 'AvatarState.IDLE -> "idel"'))
        self.assertTrue(any("idel" in p for p in problems), problems)

    def test_a_renamed_file_property_fails(self):
        s = surface()
        props = s["models"][contract.VIEW_MODEL]
        s["models"][contract.VIEW_MODEL] = [("pokee" if n == "poke" else n, k, e) for n, k, e in props]
        problems = check_contract.file_problems(s, contract)
        self.assertTrue(any("'poke'" in p for p in problems), problems)
        self.assertTrue(any("'pokee'" in p for p in problems), problems)

    def test_a_retyped_file_property_fails(self):
        s = surface()
        props = s["models"][contract.VIEW_MODEL]
        s["models"][contract.VIEW_MODEL] = [(n, "number" if n == "reduceMotion" else k, e) for n, k, e in props]
        problems = check_contract.file_problems(s, contract)
        self.assertTrue(any("reduceMotion" in p and "boolean" in p for p in problems), problems)

    def test_a_reordered_enum_fails(self):
        s = surface()
        s["enums"]["AvatarState"] = list(reversed(s["enums"]["AvatarState"]))
        problems = check_contract.file_problems(s, contract)
        self.assertTrue(any("AvatarState" in p and "order" in p for p in problems), problems)

    def test_a_copy_that_differs_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            other = Path(tmp) / "mascot.riv"
            data = shipped()[0].read_bytes()
            other.write_bytes(data[:-1] + bytes([data[-1] ^ 0xFF]))
            problems = check_contract.copy_problems([shipped()[0], other])
        self.assertTrue(problems and "byte-identical" in problems[0], problems)


def doc_table(path, heading):
    """(name, type) per row of the first table under `heading` - rows like | `name` | type | ..."""
    text = Path(path).read_text(encoding="utf-8")
    start = text.index(heading)
    rows = []
    for line in text[start:].splitlines()[1:]:
        if line.startswith("#"):
            break
        m = re.match(r"\|\s*`(\w+)`\s*\|\s*([^|]+?)\s*\|", line)
        if m:
            rows.append((m.group(1), m.group(2).split()[0]))
    return rows


class Docs(unittest.TestCase):
    WANT = [(p.name, p.type) for p in contract.AVATAR]

    def test_mascot_md_lists_the_contract(self):
        self.assertEqual(doc_table(MASCOT_MD, "### The view-model contract"), self.WANT)

    def test_artist_md_lists_the_contract(self):
        self.assertEqual(doc_table(ARTIST_MD, "## What the app writes"), self.WANT)


class Cli(unittest.TestCase):
    def test_runs_from_relative_paths_with_dotdot(self):
        rel = lambda p: os.path.relpath(p, HERE)
        proc = subprocess.run([sys.executable, rel(os.path.join(RIVDUMP, "check_contract.py")), ".",
                               rel(CONTRACT_KT)] + [rel(str(p)) for p in shipped()],
                              cwd=HERE, capture_output=True, text=True, timeout=120)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("contract OK", proc.stdout)


if __name__ == "__main__":
    unittest.main()
