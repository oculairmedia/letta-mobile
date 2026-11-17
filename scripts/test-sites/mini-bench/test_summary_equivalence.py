"""Compare saved scorer summaries without treating float rounding as a regression."""

import json
import math
from pathlib import Path
import sys
import unittest


def assert_equal(condition, expected, actual, path):
    if not condition:
        raise AssertionError(f"{path}: {expected!r} != {actual!r}")


def compare_numbers(expected, actual, path):
    equal = expected == actual if "Latency" in path else math.isclose(expected, actual, rel_tol=1e-9, abs_tol=0.0)
    assert_equal(equal, expected, actual, path)


def compare_dicts(expected, actual, path):
    assert_equal(expected.keys() == actual.keys(), expected, actual, path)
    for key in expected:
        assert_equivalent(expected[key], actual[key], f"{path}.{key}")


def compare_lists(expected, actual, path):
    assert_equal(len(expected) == len(actual), expected, actual, path)
    for index, (left, right) in enumerate(zip(expected, actual)):
        assert_equivalent(left, right, f"{path}[{index}]")


def comparison_kind(value):
    if isinstance(value, bool):
        return bool
    if isinstance(value, (int, float)):
        return float
    return type(value)


def assert_equivalent(expected, actual, path="$"):
    left, right = comparison_kind(expected), comparison_kind(actual)
    assert_equal(left is right, expected, actual, path)
    comparators = {float: compare_numbers, dict: compare_dicts, list: compare_lists}
    if left in comparators:
        comparators[left](expected, actual, path)
    else:
        assert_equal(expected == actual, expected, actual, path)


class SummaryEquivalenceTest(unittest.TestCase):
    def test_tolerance_and_exact_evidence(self):
        baseline = {"status": "FAIL", "errors": ["row"], "storedCopyLatencyMs": 42.0, "correlation": 0.9}
        assert_equivalent(baseline, {**baseline, "correlation": 0.9 + 1e-12})
        for changed in (
            {"status": "PASS"},
            {"errors": []},
            {"storedCopyLatencyMs": 42.000000001},
            {"correlation": 0.91},
        ):
            with self.subTest(changed=changed), self.assertRaises(AssertionError):
                assert_equivalent(baseline, {**baseline, **changed})


if __name__ == "__main__":
    if len(sys.argv) == 3:
        assert_equivalent(*(json.loads(Path(name).read_text()) for name in sys.argv[1:]))
        print("summaries equivalent")
    else:
        unittest.main()
