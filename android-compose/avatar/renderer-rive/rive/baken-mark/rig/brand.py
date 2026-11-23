"""The Baken brand as the app ships it, read from its single sources of truth - never copied here.

  sharedUI/.../ui/icons/BakenMark.kt        the mark's two paths (STONE_PATH, PEBBLE_PATH), 200x200
  sharedLogic/.../ui/theme/ThemeTokens.kt   the palette (LettaColorTokens): the stone per theme
                                            (DARK_MARK_STONE / LIGHT_MARK_STONE), the vermilion
                                            (DARK_PRIMARY / LIGHT_PRIMARY), the backgrounds

The rig's geometry and its theme colours come from these at generation time, so the animated mark
cannot drift from the static one the app draws (#2626). What only the animation needs - the warm
and dim tones of the pebble's states, the glow colours - stays in rig/constants.py as motion data;
the brand kit's motion lab is its source.

Standard library only: the two Kotlin files are read as text.
"""
import os
import re

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COMPOSE = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
MARK_KT = os.path.join(COMPOSE, "sharedUI", "src", "commonMain", "kotlin", "com", "letta", "mobile", "ui", "icons",
                       "BakenMark.kt")
TOKENS_KT = os.path.join(COMPOSE, "sharedLogic", "src", "commonMain", "kotlin", "com", "letta", "mobile", "ui",
                         "theme", "ThemeTokens.kt")

# The rig's stone names -> the BakenMark.kt constants that draw them.
PATH_CONSTANT = {"anchor": "STONE_PATH", "pebble": "PEBBLE_PATH"}


def _read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def mark_path(stone):
    """The SVG path data of one stone, exactly as BakenMark.kt builds the static mark."""
    src = _read(MARK_KT)
    m = re.search(r"const val %s\s*=\s*((?:\s*\"[^\"]*\"\s*\+?)+)" % PATH_CONSTANT[stone], src)
    if not m:
        raise SystemExit(f"{MARK_KT}: no {PATH_CONSTANT[stone]}")
    return "".join(re.findall(r'"([^"]*)"', m.group(1)))


def token(name):
    """A LettaColorTokens colour as an ARGB hex string (FFRRGGBB)."""
    m = re.search(r"const val %s\s*=\s*0x([0-9A-Fa-f]{8})\b" % re.escape(name), _read(TOKENS_KT))
    if not m:
        raise SystemExit(f"{TOKENS_KT}: no {name}")
    return m.group(1).upper()


def theme_inks():
    """{theme: (stone, pebble, background)} from the tokens. The mono themes are one ink: the stone
    colour of the theme whose ground they are drawn on (BakenMark.mono is one ink too)."""
    dark = (token("DARK_MARK_STONE"), token("DARK_PRIMARY"), token("DARK_BACKGROUND"))
    light = (token("LIGHT_MARK_STONE"), token("LIGHT_PRIMARY"), token("LIGHT_BACKGROUND"))
    return {"dark": dark, "light": light,
            "monoDark": (light[0], light[0], light[2]),     # one dark ink, on a light ground
            "monoLight": (dark[0], dark[0], dark[2])}       # one pale ink, on a dark ground
