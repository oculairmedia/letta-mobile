#!/usr/bin/env python3
"""Compact TLC counterexample: one action per line, only changed state fields."""
import pathlib
import re
import sys

if len(sys.argv) != 2:
    sys.exit("usage: trace.py tools/subagent-lifecycle/out/<config>.<property>.out")
# Only read TLC output this tool wrote: resolve and confine the argument to its out/ directory.
out_dir = (pathlib.Path(__file__).resolve().parent / "out").resolve()
trace_file = pathlib.Path(sys.argv[1]).resolve()
if not trace_file.is_relative_to(out_dir) or trace_file.suffix != ".out":
    sys.exit(f"trace.py only reads .out files under {out_dir}")
text = trace_file.read_text()
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
