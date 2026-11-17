"""The tool boundary rejects malformed evidence arguments."""
from pathlib import Path
import tempfile
import unittest
from tool_boundary import evidence_path, number_arg, region_arg, serial_arg, run_tool


class ToolBoundaryTest(unittest.TestCase):
    def test_paths_resolve_inside_evidence_root(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.assertEqual(evidence_path(root / 'pixels' / '..' / 'screen.mp4', root), str(root / 'screen.mp4'))
            with self.assertRaises(ValueError):
                evidence_path(root / '..' / 'outside', root)
            (root / 'link').symlink_to(root.parent, target_is_directory=True)
            with self.assertRaises(ValueError):
                evidence_path(root / 'link' / 'outside', root)

    def test_region_and_serial_reject_shell_metacharacters(self):
        self.assertEqual(region_arg('1,2,3,4'), '1,2,3,4')
        self.assertEqual(serial_arg('abc:1-2._'), 'abc:1-2._')
        for value in ('1,2,3,4;id', '-1,2,3,4', '1,2,3'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                region_arg(value)
        with self.assertRaises(ValueError):
            serial_arg('device;id')
        with self.assertRaises(ValueError):
            run_tool(['adb', '-s', 'device;id', 'get-state'])

    def test_numbers_are_finite_and_formatted(self):
        self.assertEqual(number_arg('1.25'), '1.25')
        for value in ('nan', 'inf', '1;id'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                number_arg(value)
