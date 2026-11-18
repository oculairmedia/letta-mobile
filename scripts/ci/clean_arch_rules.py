"""Kotlin source rules for the clean architecture gate."""
import re

from kotlin_source_scan import blank_literals, tokens

STATE = re.compile(r'\bandroidx\.compose\.runtime\.(?:\*|(?:snapshots\.)?(?:Snapshot\w*|(?:mutable|derived)\w*State\w*|State|MutableState|remember)\b)')


def ui_findings(text):
    for token in tokens(text):
        if token.kind == 'string' and text[token.start:token.end] in ('"agent_task"', '"""agent_task"""'):
            yield 'task-wire-tag', token.start
    if 'AppServerBackgroundProcess' not in text:
        return
    code = blank_literals(text, frozenset({'comment', 'char', 'string'}))
    for match in re.finditer(r'\bprocess\s*\??\.\s*type\b', code):
        yield 'raw-process-type', match.start()


def findings(path, text):
    if '/sharedLogic/' in path:
        if not any(part in path.lower() for part in ('/repository/', '/transport/', 'reducer')):
            return
        code = blank_literals(text, frozenset({'comment', 'char', 'string'}))
        for match in STATE.finditer(code):
            yield 'compose-state', match.start()
    else:
        yield from ui_findings(text)
