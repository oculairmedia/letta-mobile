#!/usr/bin/env python3
"""Compatibility entry point for all desktop packaging test areas.

CI invokes this file directly; focused discovery also selects this filename. Broad
unittest discovery finds the area files itself, so it must not load them twice.
"""
from pathlib import Path
import unittest


def load_tests(loader, standard_tests, pattern):
    if pattern is None or pattern == Path(__file__).name:
        return loader.discover(str(Path(__file__).parent), pattern='test_desktop_packaging_*.py')
    return standard_tests


if __name__ == '__main__':
    unittest.main(verbosity=2)
