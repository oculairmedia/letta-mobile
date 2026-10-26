#!/usr/bin/env python3
"""Bounded GDB replay inside linux-package-smoke.sh's disposable profile and WM.

This collects evidence only. It cannot turn the original smoke failure green.
GDB may give the inferior its own process group, so teardown covers every group
in the private debugger session, never the caller's session or services.
"""
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def session_groups(session):
    groups = set()
    for stat in Path('/proc').glob('[0-9]*/stat'):
        try:
            fields = stat.read_text().rsplit(') ', 1)[1].split()
            if fields[0] != 'Z' and int(fields[3]) == session:
                groups.add(int(fields[2]))
        except (FileNotFoundError, ProcessLookupError):
            pass
    return groups


def signal_groups(groups, sig):
    for group in groups:
        try:
            os.killpg(group, sig)
        except ProcessLookupError:
            pass


def stop_debugger_session(process):
    signal_groups(session_groups(process.pid), signal.SIGTERM)
    # Finish before the outer smoke supervisor's three-second KILL deadline.
    deadline = time.monotonic() + 1
    while session_groups(process.pid) and time.monotonic() < deadline:
        time.sleep(0.1)
    signal_groups(session_groups(process.pid), signal.SIGKILL)
    try:
        process.wait(timeout=1)
    except subprocess.TimeoutExpired:
        pass


def diagnostic_summary(trace):
    required = ['XSetInputFocus', 'XSetErrorHandler', '_XDefaultError']
    wrappers = ['Java_sun_awt_X11_XlibWrapper_XSetInputFocus' + suffix for suffix in ['', '2']]
    complete = (all('TRACE ' + name + ' thread=' in trace for name in required)
                and any('TRACE ' + name + ' thread=' in trace for name in wrappers))
    if all([complete, 'BadMatch' in trace, 'X_SetInputFocus' in trace]):
        return ('REPRODUCED: fatal X_SetInputFocus BadMatch with native trace. '
                'Interpret handler/focus stacks before proposing a runtime fix.\n')
    return ('INCONCLUSIVE: the diagnostic did not capture the complete fatal focus sequence. '
            'Debugger timing, timeout or missing symbols may affect reproduction. '
            'This is not a smoke pass and does not disprove the original failure.\n')


def run_debugger(root, work):
    script = Path(__file__).with_name('linux-startup-diagnostic.gdb')
    command = ['gdb', '--nx', '--batch', '--quiet', '-x', str(script),
               '--args', '/bin/bash', str(root / 'AppRun')]
    # AppRun must derive APPDIR from this extraction, including its library paths.
    env = dict(os.environ)
    for key in ['APPDIR', 'APPIMAGE', 'APPIMAGE_EXIT_AFTER_INSTALL']:
        env.pop(key, None)
    with (work / 'gdb.log').open('w') as log:
        process = subprocess.Popen(command, cwd=work, env=env, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        status = 'interrupted'
        try:
            limit = min(90, max(1, int(env.get('LETTA_X11_DIAGNOSTIC_TIMEOUT_SECONDS', '90'))))
            status = process.wait(timeout=limit)
        except subprocess.TimeoutExpired:
            status = 124
        finally:
            # Record status and flush evidence before signalling any owned process.
            (work / 'gdb-status.txt').write_text(str(status) + '\n')
            log.flush()
            stop_debugger_session(process)


def main(root, work):
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    signal.signal(signal.SIGINT, lambda *_: sys.exit(130))
    summary = work / 'diagnostic-summary.txt'
    summary.write_text('INCONCLUSIVE: diagnostic did not finish; original smoke remains failed.\n')
    run_debugger(root, work)
    summary.write_text(diagnostic_summary((work / 'gdb.log').read_text(errors='replace')))
    print(summary.read_text(), end='')


if __name__ == '__main__':
    main(Path(sys.argv[1]), Path(sys.argv[2]))
