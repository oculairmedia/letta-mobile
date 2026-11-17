"""The tool boundary rejects malformed evidence arguments."""

from pathlib import Path
import tempfile
import unittest
from tool_boundary import evidence_path, number_arg, region_arg, serial_arg, run_tool


class ToolBoundaryTest(unittest.TestCase):
    def test_paths_resolve_inside_evidence_root(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.assertEqual(evidence_path(root / "pixels" / ".." / "screen.mp4", root), str(root / "screen.mp4"))
            with self.assertRaises(ValueError):
                evidence_path(root / ".." / "outside", root)
            (root / "link").symlink_to(root.parent, target_is_directory=True)
            with self.assertRaises(ValueError):
                evidence_path(root / "link" / "outside", root)

    def test_region_and_serial_reject_shell_metacharacters(self):
        self.assertEqual(region_arg("1,2,3,4"), "1,2,3,4")
        self.assertEqual(serial_arg("abc:1-2._"), "abc:1-2._")
        for value in ("1,2,3,4;id", "-1,2,3,4", "1,2,3"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                region_arg(value)
        with self.assertRaises(ValueError):
            serial_arg("device;id")
        with self.assertRaises(ValueError):
            run_tool(["adb", "-s", "device;id", "get-state"])

    def test_numbers_are_finite_and_formatted(self):
        self.assertEqual(number_arg("1.25"), "1.25")
        for value in ("nan", "inf", "1;id"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                number_arg(value)

    def test_raw_caller_argv_never_reaches_subprocess(self):
        from unittest.mock import patch

        with patch("tool_boundary.subprocess.run") as process:
            with self.assertRaises((ValueError, TypeError)):
                run_tool(["adb", "-s", "valid-device", "unexpected;argument"])
            process.assert_not_called()

    def test_typed_arguments_build_a_new_allowlisted_argv(self):
        from unittest.mock import patch
        from tool_boundary import Tool, device_arg, text_arg, path_arg, numeric_arg, crop_arg

        with tempfile.TemporaryDirectory() as temp, patch("tool_boundary.subprocess.run") as process:
            root = Path(temp)
            args = (
                device_arg("device:1"),
                path_arg(root / "some file.mp4", root),
                numeric_arg("1.25"),
                crop_arg("01,2,3,4"),
                text_arg("--region"),
            )
            run_tool(Tool.PYTHON, args, check=True)
            process.assert_called_once_with(
                ["/usr/bin/python3", "device:1", str(root / "some file.mp4"), "1.25", "1,2,3,4", "--region"], check=True
            )

    def test_raw_arguments_and_executables_are_rejected(self):
        from unittest.mock import patch
        from tool_boundary import Tool

        with patch("tool_boundary.subprocess.run") as process:
            with self.assertRaises(ValueError):
                run_tool("/tmp/adb", ())
            with self.assertRaises(TypeError):
                run_tool(Tool.ADB, ("-s", "device"))
            process.assert_not_called()

    def test_argument_factories_reject_invalid_typed_values(self):
        from tool_boundary import TextArg, NumberArg, RegionArg, FilterArg

        for factory, value in ((TextArg, "device;id"), (NumberArg, float("nan")), (RegionArg, (1, 2, -3, 4))):
            with self.subTest(factory=factory), self.assertRaises(ValueError):
                factory(value)
        with self.assertRaises(ValueError):
            FilterArg(0, float("inf"))

    def test_requested_executable_path_cannot_replace_the_allowlist(self):
        from tool_boundary import Tool, tool_arguments

        tool, args = tool_arguments(["/untrusted/adb", "-s", "device", "get-state"])
        self.assertIs(tool, Tool.ADB)
        self.assertEqual([arg.render() for arg in args], ["-s", "device", "get-state"])
