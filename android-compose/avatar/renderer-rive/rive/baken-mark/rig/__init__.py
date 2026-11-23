"""The Baken mark rig, with the mascot rig's shared tools underneath it.

This package holds only what is the mark's own (ids, constants, contract, geometry, motion, the
state machine, the probe table). Everything that is shared tooling - the typed layer builder
(`rig.layers`), the seam ledger and blend policy (`rig.seams`), timing charts (`rig.chart`) - is
the mascot's, imported unchanged: `__path__` falls through to `../mascot/rig` for any module this
package does not define, and the mascot directory goes on `sys.path` (after this one) for `rml`,
`svgpath`, `timeline` and `rivecli`.

A module defined here shadows the mascot's of the same name. That is deliberate for
`rig.constants`: the shared layer builder reads `MIN_TRANSITION_MS` from it, and the mark keeps
the same floor (see constants.py).

`rig.seams` inserts the mascot directory at the FRONT of `sys.path` when it is imported, which
would make `import probe` / `import scenarios` find the mascot's modules instead of ours. So it
is imported here, once, and the mascot directory is moved back to the end straight after.
"""
import os
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MASCOT = os.path.normpath(os.path.join(HERE, "..", "mascot"))

__path__.append(os.path.join(MASCOT, "rig"))  # noqa: F821 - a package's own __path__


def _mascot_last():
    """Keep the mark's own directory ahead of the mascot's on sys.path."""
    norm = os.path.normcase(MASCOT)
    sys.path[:] = [p for p in sys.path if os.path.normcase(os.path.normpath(p or ".")) != norm]
    sys.path.append(MASCOT)


_mascot_last()
from rig import seams as _seams  # noqa: E402,F401 - imported for its sys.path side effect, then undone
_mascot_last()


def mascot_module(name):
    """A mascot top-level module loaded under `mascot_<name>`, for the two whose names the mark
    also uses (`probe`, `scenarios`): their helpers are reused, their tables are not."""
    import importlib.util
    key = f"mascot_{name}"
    if key in sys.modules:
        return sys.modules[key]
    spec = importlib.util.spec_from_file_location(key, os.path.join(MASCOT, f"{name}.py"))
    mod = importlib.util.module_from_spec(spec)
    sys.modules[key] = mod
    spec.loader.exec_module(mod)
    return mod
