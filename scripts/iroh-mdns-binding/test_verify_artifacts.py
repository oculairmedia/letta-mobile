"""Path confinement and a real minimal Linux artifact fixture."""
import contextlib
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
from zipfile import ZipFile

import verify_artifacts as verifier


class ArtifactVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / "repo"
        self.unit = self.repo / "android-compose/native/iroh-mdns-binding"
        self.source = self.unit / "build/upstream"
        self.output = self.unit / "build/artifacts"
        self.source.mkdir(parents=True)
        self.source_arg = str(self.source.relative_to(self.repo))
        self.output_arg = str(self.output.relative_to(self.repo))
        old_cwd = Path.cwd()
        os.chdir(self.repo)
        self.addCleanup(os.chdir, old_cwd)

    def run_verifier(self, source: str | None = None, output: str | None = None) -> None:
        args = ["verify_artifacts.py", source or self.source_arg, "linux", output or self.output_arg]
        with mock.patch.object(verifier, "UNIT", self.unit), mock.patch.object(sys, "argv", args):
            with contextlib.redirect_stdout(io.StringIO()):
                verifier.main()

    def fixture(self) -> None:
        binding = self.source / "kotlin/lib/src/main/kotlin/computer/iroh/iroh_ffi.kt"
        binding.parent.mkdir(parents=True)
        binding.write_text("generated binding")
        (self.source / "Cargo.lock").write_text("pinned lock")
        native = self.source / "target/release/libiroh_ffi.so"
        native.parent.mkdir(parents=True)
        native.write_bytes(b"native bytes")
        jar = self.source / "kotlin/lib/build/libs/iroh.jar"
        jar.parent.mkdir(parents=True)
        with ZipFile(jar, "w") as packaged:
            packaged.writestr("linux-x86-64/libiroh_ffi.so", native.read_bytes())
            for name in ("LICENSE-APACHE", "LICENSE-MIT", "THIRD-PARTY-NOTICES.txt"):
                packaged.writestr(f"META-INF/{name}", "license")

    def test_valid_artifact_is_copied_and_hashed(self) -> None:
        self.fixture()
        self.run_verifier()
        self.assertEqual((self.output / "iroh.jar").read_bytes(),
                         (self.source / "kotlin/lib/build/libs/iroh.jar").read_bytes())
        self.assertTrue((self.output / "proof.json").is_file())

    def test_input_archive_name_does_not_select_output_path(self) -> None:
        self.fixture()
        jar = self.source / "kotlin/lib/build/libs/iroh.jar"
        jar.rename(jar.with_name("build-controlled-name.jar"))
        self.run_verifier()
        self.assertEqual({path.name for path in self.output.iterdir()}, {"iroh.jar", "proof.json"})

    def test_absolute_and_traversal_arguments_rejected(self) -> None:
        for source, output in (
            (str(self.source), self.output_arg),
            (self.source_arg, str(self.output)),
            (self.source_arg.replace("/build/upstream", "/build/../build/upstream"), self.output_arg),
            (self.source_arg, self.output_arg.replace("/build/artifacts", "/build/../build/artifacts")),
            (self.source_arg, "elsewhere"),
        ):
            with self.subTest(source=source, output=output), self.assertRaises(SystemExit):
                self.run_verifier(source, output)
        self.assertFalse(self.output.exists())

    def test_source_directory_symlink_escape_rejected(self) -> None:
        outside = Path(self.temp.name) / "outside-source"
        outside.mkdir()
        self.source.rmdir()
        self.source.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(SystemExit):
            self.run_verifier()

    def test_generated_binding_symlink_escape_rejected(self) -> None:
        self.fixture()
        outside = Path(self.temp.name) / "outside-binding"
        outside.write_text("untrusted")
        binding = self.source / "kotlin/lib/src/main/kotlin/computer/iroh/iroh_ffi.kt"
        binding.unlink()
        binding.symlink_to(outside)
        with self.assertRaises(SystemExit):
            self.run_verifier()

    def test_jar_symlink_escape_rejected(self) -> None:
        self.fixture()
        jar = self.source / "kotlin/lib/build/libs/iroh.jar"
        outside = Path(self.temp.name) / "outside.jar"
        jar.replace(outside)
        jar.symlink_to(outside)
        with self.assertRaises(SystemExit):
            self.run_verifier()

    def test_output_directory_symlink_escape_rejected(self) -> None:
        self.fixture()
        outside = Path(self.temp.name) / "outside-output"
        outside.mkdir()
        self.output.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(SystemExit):
            self.run_verifier()
        self.assertEqual(list(outside.iterdir()), [])

    def test_output_file_symlink_escape_rejected(self) -> None:
        self.fixture()
        self.output.mkdir()
        outside = Path(self.temp.name) / "outside-file"
        outside.write_bytes(b"sentinel")
        (self.output / "iroh.jar").symlink_to(outside)
        with self.assertRaises(SystemExit):
            self.run_verifier()
        self.assertEqual(outside.read_bytes(), b"sentinel")


if __name__ == "__main__":
    unittest.main()
