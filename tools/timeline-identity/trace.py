#!/usr/bin/env python3
"""Condense a TLC counterexample from TimelineIdentity into one line per step:
the action, the durable prefix, the presented pages as lo..hi@n=<ledger it read>:[key{members}] and the live rows.
Usage: trace.py out/<cfg>.<Property>.out"""
import re, sys
text = open(sys.argv[1]).read()
if 'violated' not in text:
    sys.exit('no counterexample in ' + sys.argv[1])
body = text[text.index('violated'):]
states = re.split(r'\nState (\d+): <([A-Za-z]+)[^>]*>\n', body)
def field(block, name):
    m = re.search(r'/\\ ' + name + r' = (.*?)(?=\n/\\ |\n\n|\Z)', block, re.S)
    return ' '.join(m.group(1).split()) if m else '?'
def rows(s):
    return ', '.join(f'{k}{{{m}}}' for m, k in
                     [(mm.group(2), mm.group(1)) for mm in re.finditer(r'key \|-> "([^"]+)", members \|-> \{([^}]*)\}', s)])
for i in range(1, len(states) - 1, 3):
    num, action, block = states[i], states[i + 1], states[i + 2]
    pages = field(block, 'pages')
    page_txt = ' | '.join((f'{lo}..{hi}@n={m}:[{rows(r)}]' if m else f'{lo}..{hi}:[{rows(r)}]') for lo, hi, m, r in
                          re.findall(r'lo \|-> (\d+),\s*hi \|-> (\d+),\s*(?:m \|-> (\d+),\s*)?rows \|->\s*(<<.*?>>)\s*\]', pages))
    live = field(block, 'live')
    print(f'{num:>2} {action:<13} n={field(block, "n")} streamed={field(block, "streamed")} '
          f'anchor={field(block, "anchor")} resident={field(block, "resident")}\n'
          f'     pages(newest first): {page_txt or "-"}\n     live: {rows(live) or "-"}')
m = re.search(r'Error: (Invariant \w+ is violated|Action property \w+ .*?violated|Temporal properties were violated)', text)
print(m.group(1) if m else '')
