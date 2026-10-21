"""Self-tests for the parity guardrails (letta-mobile-ewtaq): each gate fails on a crafted violation."""
import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ci"))
import parity_gates as gates  # noqa: E402

import importlib.util  # noqa: E402

_SPEC = importlib.util.spec_from_file_location("ratchet_guard", Path(__file__).resolve().parents[1] / "ci/baseline-ratchet-guard.py")
guard = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(guard)

SHARED_CONTRACTS = """\
package com.letta.mobile.ui.shell
interface ChatPort
class PanelSlots(
    val main: () -> Unit,
    val card: (() -> Unit)? = null,
    val pane: () -> Unit = {},
)
class ShellHost(val slots: PanelSlots)
"""


def write(root: Path, rel: str, text: str) -> Path:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


class FixtureRepo:
    """A tree with sharedUI, desktop, web and app, all binding PanelSlots fully."""

    def __init__(self, root: Path):
        self.root = root
        write(root, "android-compose/sharedUI/src/commonMain/kotlin/Shared.kt", SHARED_CONTRACTS)
        for module, platform in (("desktop", "main/kotlin"), ("web", "wasmJsMain/kotlin"), ("app", "main/java")):
            write(root, f"android-compose/{module}/src/{platform}/Bind.kt", self.binding(card="{ Card() }"))
        write(root, "scripts/ci/parity/parity-contracts.txt", "PanelSlots\nShellHost\n")
        write(root, "scripts/ci/parity/parity-exemptions.txt", "# header\n")
        write(root, "scripts/ci/parity/platform-code-baseline.txt", "# header\n")
        write(root, "scripts/ci/parity/mirror-name-baseline.txt", "# header\n")

    @staticmethod
    def binding(card: str, pane: str = "{ Pane() }") -> str:
        return f"""\
package com.letta.mobile.platform
import com.letta.mobile.ui.shell.PanelSlots
import com.letta.mobile.ui.shell.ShellHost
import com.letta.mobile.ui.shell.ChatPort
class PortBinding : ChatPort {{
    val host = ShellHost(slots = PanelSlots(main = {{ Main() }}, card = {card}, pane = {pane}))
}}
"""

    def run(self, gate: str) -> tuple[int, str]:
        out = io.StringIO()
        with redirect_stdout(out):
            status = gates.main([gate, "--root", str(self.root)])
        return status, out.getvalue()


class ParityGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = FixtureRepo(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def test_full_bindings_pass(self):
        status, out = self.repo.run("parity")
        self.assertEqual(0, status, out)

    def test_null_empty_lambda_and_default_bindings_fail_with_what_to_do(self):
        web = self.repo.root / "android-compose/web/src/wasmJsMain/kotlin/Bind.kt"
        web.write_text(FixtureRepo.binding(card="null", pane="{}"), encoding="utf-8")
        app = self.repo.root / "android-compose/app/src/main/java/Bind.kt"
        app.write_text(FixtureRepo.binding(card="UnavailableCard()").replace(", pane = { Pane() }", ""), encoding="utf-8")
        status, out = self.repo.run("parity")
        self.assertEqual(1, status)
        for gap in ("web leaves this slot unbound: PanelSlots.card", "web leaves this slot unbound: PanelSlots.pane",
                    "android leaves this slot unbound: PanelSlots.card", "android leaves this slot unbound: PanelSlots.pane"):
            self.assertIn(gap, out)
        self.assertIn("Bind the slot on that platform", out)

    def test_a_platform_that_never_constructs_a_core_contract_fails(self):
        (self.repo.root / "android-compose/app/src/main/java/Bind.kt").write_text("package x\n", encoding="utf-8")
        status, out = self.repo.run("parity")
        self.assertEqual(1, status)
        self.assertIn("android does not construct the core contract: ShellHost", out)

    def test_an_exemption_needs_a_bead_and_a_bound_slot_makes_it_stale(self):
        web = self.repo.root / "android-compose/web/src/wasmJsMain/kotlin/Bind.kt"
        web.write_text(FixtureRepo.binding(card="null"), encoding="utf-8")
        exemptions = self.repo.root / "scripts/ci/parity/parity-exemptions.txt"
        exemptions.write_text("web PanelSlots.card | no card yet\n", encoding="utf-8")
        status, out = self.repo.run("parity")
        self.assertEqual(1, status)
        self.assertIn("EXEMPTION WITHOUT A REASON AND BEAD", out)
        exemptions.write_text("web PanelSlots.card | no card yet (letta-mobile-crvvv)\n", encoding="utf-8")
        self.assertEqual(0, self.repo.run("parity")[0])
        web.write_text(FixtureRepo.binding(card="{ Card() }"), encoding="utf-8")
        status, out = self.repo.run("parity")
        self.assertEqual(1, status)
        self.assertIn("STALE EXEMPTION", out)

    def test_platform_logic_file_fails_and_a_binding_passes(self):
        self.assertEqual(0, self.repo.run("platform-code")[0])
        write(self.repo.root, "android-compose/desktop/src/main/kotlin/Logic.kt",
              "package com.letta.mobile.desktop\nclass DesktopReducer {\n    fun reduce(a: Int) = a + 1\n}\n")
        status, out = self.repo.run("platform-code")
        self.assertEqual(1, status)
        self.assertIn("NEW PLATFORM LOGIC FILE: android-compose/desktop/src/main/kotlin/Logic.kt", out)
        self.assertIn("Move the logic to sharedLogic/commonMain", out)

    def test_a_listed_logic_file_may_not_grow_past_its_cap(self):
        path = write(self.repo.root, "android-compose/web/src/wasmJsMain/kotlin/Logic.kt", "package x\nfun f() = 1\n")
        baseline = self.repo.root / "scripts/ci/parity/platform-code-baseline.txt"
        baseline.write_text("android-compose/web/src/wasmJsMain/kotlin/Logic.kt 2\n", encoding="utf-8")
        self.assertEqual(0, self.repo.run("platform-code")[0])
        path.write_text("package x\nfun f() = 1\n" + "fun g() = 2\n" * 6, encoding="utf-8")
        status, out = self.repo.run("platform-code")
        self.assertEqual(1, status)
        self.assertIn("PLATFORM LOGIC GREW", out)

    def test_mirrored_type_fails_unless_it_implements_the_shared_one(self):
        write(self.repo.root, "android-compose/web/src/wasmJsMain/kotlin/Mirror.kt",
              "package com.letta.mobile.web\nclass WebChatPort {\n}\n")
        status, out = self.repo.run("mirror-names")
        self.assertEqual(1, status)
        self.assertIn("NEW MIRRORED TYPE: WebChatPort mirrors shared ChatPort", out)
        write(self.repo.root, "android-compose/web/src/wasmJsMain/kotlin/Mirror.kt",
              "package com.letta.mobile.web\nimport com.letta.mobile.ui.shell.ChatPort\nclass WebChatPort : ChatPort\n")
        self.assertEqual(0, self.repo.run("mirror-names")[0])

    def test_two_platforms_mirroring_each_other_fail(self):
        write(self.repo.root, "android-compose/web/src/wasmJsMain/kotlin/A.kt", "package w\nclass WebRosterLoader\n")
        write(self.repo.root, "android-compose/desktop/src/main/kotlin/A.kt", "package d\nclass DesktopRosterLoader\n")
        status, out = self.repo.run("mirror-names")
        self.assertEqual(1, status)
        self.assertIn("WebRosterLoader mirrors platform DesktopRosterLoader", out)
        self.assertIn("belongs in sharedLogic/sharedUI", out)


def git(root: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(root), "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false",
                           "-c", "core.autocrlf=false", *args], check=True, capture_output=True, text=True).stdout


class RatchetGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        git(self.root, "init", "-q")
        write(self.root, "scripts/ci/ratchet-baselines.txt", "set s.txt\ncount c.txt\ngrow-only g.txt\n")
        write(self.root, "s.txt", "# h\na | why (letta-mobile-x)\nb | why (letta-mobile-x)\n")
        write(self.root, "c.txt", "p1 10 legacy\np2 5\n")
        write(self.root, "g.txt", "Contract\n")
        git(self.root, "add", "-A")
        git(self.root, "commit", "-qm", "base")
        self.base = git(self.root, "rev-parse", "HEAD").strip()

    def tearDown(self):
        self.tmp.cleanup()

    def run_guard(self) -> tuple[int, str]:
        out = io.StringIO()
        with redirect_stdout(out):
            status = guard.main(["--diff-base", self.base, "--root", str(self.root)])
        return status, out.getvalue()

    def test_decreases_and_deletions_pass(self):
        write(self.root, "s.txt", "# h\na | why (letta-mobile-x)\n")
        write(self.root, "c.txt", "p1 7 legacy\n")
        write(self.root, "g.txt", "Contract\nAnother\n")
        status, out = self.run_guard()
        self.assertEqual(0, status, out)

    def test_adds_and_raises_fail(self):
        write(self.root, "s.txt", "# h\na | why\nb | why\nc | new (letta-mobile-x)\n")
        write(self.root, "c.txt", "p1 11 legacy\np2 5\np3 1\n")
        write(self.root, "g.txt", "")
        status, out = self.run_guard()
        self.assertEqual(1, status)
        self.assertIn("s.txt: line added: c", out)
        self.assertIn("c.txt: raised p1 10 -> 11", out)
        self.assertIn("c.txt: line added: p3 1", out)
        self.assertIn("g.txt: line removed or changed: Contract", out)
        self.assertIn("never add", out)

    def test_dropping_a_ratchet_fails_and_a_new_one_is_seeded(self):
        write(self.root, "scripts/ci/ratchet-baselines.txt", "set s.txt\ncount c.txt\ncount new.txt\n")
        write(self.root, "new.txt", "anything 99\n")
        status, out = self.run_guard()
        self.assertEqual(1, status)
        self.assertIn("ratchet dropped: grow-only g.txt", out)
        self.assertIn("new.txt is seeded by this change", out)


if __name__ == "__main__":
    unittest.main()
