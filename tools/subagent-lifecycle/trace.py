#!/usr/bin/env python3
"""Compact TLC counterexample: one action per line, only changed state fields."""
import pathlib
import re
import sys

if len(sys.argv) != 2:
    sys.exit("usage: trace.py tools/subagent-lifecycle/out/<config>.<property>.out")
text = pathlib.Path(sys.argv[1]).read_text()
if "State 1:" not in text:
    sys.exit("no counterexample in TLC output")
previous = {}
for block in re.split(r"(?=^State \d+:|^Back to state)", text, flags=re.M)[1:]:
    heading = block.splitlines()[0]
    values = dict(re.findall(r"^/\\ (\w+) = (.+)$", block, flags=re.M))
    changes = ", ".join(f"{key}={value}" for key, value in values.items()
                        if previous.get(key) != value)
    print(f"{heading} {changes}")
    previous.update(values)
