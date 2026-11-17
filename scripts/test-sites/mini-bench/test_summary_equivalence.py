"""Compare saved scorer summaries without treating float rounding as a regression."""
import json
import math
from pathlib import Path
import sys
import unittest


def assert_equivalent(expected, actual, path='$'):
    if isinstance(expected, bool) or isinstance(actual, bool):
        if expected is not actual:
            raise AssertionError(f'{path}: {expected!r} != {actual!r}')
    elif isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        # Latencies are evidence timestamps, so require exact equality.
        if 'Latency' in path:
            equal = expected == actual
        else:
            equal = math.isclose(expected, actual, rel_tol=1e-9, abs_tol=0.0)
        if not equal:
            raise AssertionError(f'{path}: {expected!r} != {actual!r}')
    elif isinstance(expected, dict) and isinstance(actual, dict):
        if expected.keys() != actual.keys():
            raise AssertionError(f'{path}: keys differ')
        for key in expected:
            assert_equivalent(expected[key], actual[key], f'{path}.{key}')
    elif isinstance(expected, list) and isinstance(actual, list):
        if len(expected) != len(actual):
            raise AssertionError(f'{path}: lengths differ')
        for index, (left, right) in enumerate(zip(expected, actual)):
            assert_equivalent(left, right, f'{path}[{index}]')
    elif expected != actual or type(expected) is not type(actual):
        raise AssertionError(f'{path}: {expected!r} != {actual!r}')


class SummaryEquivalenceTest(unittest.TestCase):
    def test_tolerance_and_exact_evidence(self):
        baseline = {'status': 'FAIL', 'errors': ['row'], 'storedCopyLatencyMs': 42.0, 'correlation': 0.9}
        assert_equivalent(baseline, {**baseline, 'correlation': 0.9 + 1e-12})
        for changed in ({'status': 'PASS'}, {'errors': []}, {'storedCopyLatencyMs': 42.000000001}, {'correlation': 0.91}):
            with self.subTest(changed=changed), self.assertRaises(AssertionError):
                assert_equivalent(baseline, {**baseline, **changed})


if __name__ == '__main__':
    if len(sys.argv) == 3:
        assert_equivalent(*(json.loads(Path(name).read_text()) for name in sys.argv[1:]))
        print('summaries equivalent')
    else:
        unittest.main()
