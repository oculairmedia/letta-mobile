"""The contract check: `python -m unittest test_contract`. Standard library only, no Rive CLI.

The SHIPPED `.riv` files are read directly with `native/rivdump/rivfile.py`, and their surface is
compared with `rig/contract.py` by `native/rivdump/check_contract.py`'s own file checks (the ones
that hold the mascot to its contract), so a drift in a name, a type, an enum key or its order
fails here and names itself.

  shipped   both copies byte-identical (and equal to build/baken_mark.riv when it exists); the
            BrandMark view model is exactly the contract, in order; the enums carry exactly the
            documented keys in order; the BrandMark state machine and BakenMark artboard exist
  drift     a renamed, retyped, missing or extra property, or a reordered enum, is reported
  docs      the README's contract table lists exactly the contract
"""
import os
import re
import sys
import types
import unittest
from pathlib import Path

import rig  # noqa: F401 - path bootstrap
from rig import contract

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.abspath(os.path.join(HERE, "..", ".."))
RIVDUMP = os.path.join(MODULE, "native", "rivdump")
SHIPPED = [Path(MODULE, "src", "androidMain", "res", "raw", "baken_mark.riv"),
           Path(MODULE, "src", "jvmMain", "resources", "baken-mark", "baken_mark.riv")]
BUILT = Path(HERE, "build", "baken_mark.riv")
README = os.path.join(HERE, "README.md")

sys.path.insert(0, RIVDUMP)
import check_contract  # noqa: E402
from rivfile import read  # noqa: E402


def rivs():
    return SHIPPED + ([BUILT] if BUILT.exists() else [])


def surface():
    return check_contract.file_surface(read(str(SHIPPED[0])))


def problems(surf, c=contract):
    return check_contract.file_problems(surf, c)


def _with(**changes):
    """A copy of the contract module with some attributes replaced (for the drift tests)."""
    c = types.SimpleNamespace(**{k: getattr(contract, k) for k in
                                 ("AVATAR", "ENUMS", "STATE_MACHINE", "VIEW_MODEL")})
    for k, v in changes.items():
        setattr(c, k, v)
    return c


class Shipped(unittest.TestCase):
    def test_both_copies_exist(self):
        for p in SHIPPED:
            self.assertTrue(p.exists(), f"no shipped .riv at {p}")

    def test_every_copy_is_byte_identical(self):
        self.assertEqual(check_contract.copy_problems(rivs()), [])

    def test_the_file_matches_the_contract(self):
        found = problems(surface())
        self.assertEqual(found, [], "\n".join(found))

    def test_the_view_model_is_exactly_the_contract_in_order(self):
        props = surface()["models"][contract.VIEW_MODEL]
        self.assertEqual([(n, k) for n, k, _ in props], [(p.name, p.type) for p in contract.BRAND_MARK])

    def test_the_enums_in_order(self):
        enums = surface()["enums"]
        for name, keys in contract.ENUMS.items():
            self.assertEqual(enums.get(name), keys, f"enum {name}")

    def test_the_states_are_app_states_only(self):
        """The mark never animates a turn: those states belong to the mascots."""
        turn = {"listening", "thinking", "working", "speaking", "waitingInput", "dreaming", "sleeping"}
        self.assertEqual(set(surface()["enums"]["BakenMarkState"]) & turn, set())

    def test_the_state_machine_and_artboard(self):
        objs = read(str(SHIPPED[0])).objects
        self.assertIn(contract.STATE_MACHINE, surface()["machines"])
        boards = [o.props.get("name") for o in objs if o.type == "Artboard"]
        self.assertEqual(boards, [contract.ARTBOARD])

    def test_every_type_the_contract_uses_is_a_rive_type(self):
        for p in contract.BRAND_MARK:
            self.assertIn(p.type, contract.RIVE_TYPE)


class Drift(unittest.TestCase):
    """A deliberate drift on either side must fail, and the message must name it."""

    def test_a_renamed_property_fails(self):
        renamed = [p._replace(name="reducedMotion") if p.name == "reduceMotion" else p for p in contract.BRAND_MARK]
        found = problems(surface(), _with(AVATAR=renamed))
        self.assertTrue(any("reducedMotion" in p for p in found), found)
        self.assertTrue(any("reduceMotion" in p for p in found), found)

    def test_a_retyped_property_fails(self):
        retyped = [p._replace(type="boolean") if p.name == "success" else p for p in contract.BRAND_MARK]
        found = problems(surface(), _with(AVATAR=retyped))
        self.assertTrue(any("'success'" in p and "trigger" in p for p in found), found)

    def test_a_reordered_enum_fails(self):
        keys = list(contract.ENUMS["BakenMarkState"])
        keys[1], keys[2] = keys[2], keys[1]
        found = problems(surface(), _with(ENUMS={**contract.ENUMS, "BakenMarkState": keys}))
        self.assertTrue(any("BakenMarkState" in p and "order" in p for p in found), found)

    def test_a_file_property_the_contract_lacks_fails(self):
        surf = surface()
        surf["models"][contract.VIEW_MODEL] = surf["models"][contract.VIEW_MODEL] + [("color", "color", None)]
        found = problems(surf)
        self.assertTrue(any("undocumented" in p and "color" in p for p in found), found)

    def test_a_missing_state_machine_fails(self):
        surf = surface()
        surf["machines"] = ["Avatar"]
        self.assertTrue(any("BrandMark" in p for p in problems(surf)))


class Docs(unittest.TestCase):
    def test_the_readme_contract_table_is_the_contract(self):
        text = Path(README).read_text(encoding="utf-8")
        section = text.split("## Contract", 1)[1].split("\n## ", 1)[0]
        rows = re.findall(r"^\| `(\w+)` \| (\w+) \|", section, re.M)
        self.assertEqual(rows, [(p.name, p.type) for p in contract.BRAND_MARK])
        for name, keys in contract.ENUMS.items():
            for k in keys:
                self.assertIn(f"`{k}`", section, f"README does not list {name} key {k}")


if __name__ == "__main__":
    unittest.main()
