"""Tests for check-vendored-iroh-native.py: python3 -m unittest discover -s scripts/ci -p 'test_vendored_iroh_native.py'."""
import importlib.util
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    "vendored_iroh_native", Path(__file__).with_name("check-vendored-iroh-native.py")
)
check = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(check)

SYMS = b"\x00uniffi_iroh_ffi_fn_func_preset_n0\x00uniffi_iroh_ffi_fn_func_preset_iroh_services\x00"


class VendoredIrohNativeTest(unittest.TestCase):
    def test_reads_symbol_names_from_the_binary(self):
        self.assertEqual(
            check.exported_symbols(SYMS),
            {"uniffi_iroh_ffi_fn_func_preset_n0", "uniffi_iroh_ffi_fn_func_preset_iroh_services"},
        )

    def test_matching_version_and_symbols_pass(self):
        manifest = {"irohVersion": "1.2.0", "requiredSymbols": ["uniffi_iroh_ffi_fn_func_preset_iroh_services"]}
        self.assertEqual(check.problems("1.2.0", manifest, check.exported_symbols(SYMS)), [])

    def test_a_bumped_pin_without_a_re_vendored_library_fails(self):
        manifest = {"irohVersion": "1.1.0", "requiredSymbols": []}
        found = check.problems("1.2.0", manifest, check.exported_symbols(SYMS))
        self.assertEqual(len(found), 1)
        self.assertIn("re-vendor", found[0])

    def test_the_1_2_0_regression_a_missing_symbol_fails(self):
        old_library = b"\x00uniffi_iroh_ffi_fn_func_preset_n0\x00"
        manifest = {"irohVersion": "1.2.0", "requiredSymbols": ["uniffi_iroh_ffi_fn_func_preset_iroh_services"]}
        found = check.problems("1.2.0", manifest, check.exported_symbols(old_library))
        self.assertEqual(len(found), 1)
        self.assertIn("preset_iroh_services", found[0])

    def test_the_pin_is_read_from_the_version_catalog(self):
        self.assertRegex(check.pinned_version(check.VERSIONS), r"^\d+\.\d+\.\d+")


if __name__ == "__main__":
    unittest.main()
