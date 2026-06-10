#!/usr/bin/env python3
"""
export-design-tokens.py — Kotlin design tokens → DTCG-style JSON for Penpot.

Code is the source of truth (docs/design-sync-with-penpot.md goal #1). This
script extracts the Letta token definitions from the Kotlin `object`
declarations and emits a Design-Tokens-Community-Group (DTCG) shaped JSON file
that Penpot's native token catalog (design-tokens/v1) round-trips.

It does NOT push to Penpot — emitting the JSON is the contract; the push is a
separate step (REST `update-file` change-ops, see the sync doc). Keeping export
and push separate means the JSON is reviewable in the PR diff before anything
touches the Penpot file.

Sources parsed:
  - sharedLogic/.../ui/theme/ThemeTokens.kt   (LettaColorTokens + 6 presets × light/dark)
  - sharedLogic/.../ui/theme/DesignTokens.kt  (LettaSpacingTokens scale + aliases)
  - designsystem/.../ui/theme/Type.kt         (body prose roles → typography tokens)

Output:
  - docs/design/penpot-tokens.json

Usage:
  python3 scripts/export-design-tokens.py            # write the JSON
  python3 scripts/export-design-tokens.py --check     # verify it's up to date (CI)
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
THEME_KT = REPO / "android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/ui/theme/ThemeTokens.kt"
DESIGN_KT = REPO / "android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/ui/theme/DesignTokens.kt"
TYPE_KT = REPO / "android-compose/designsystem/src/main/java/com/letta/mobile/ui/theme/Type.kt"
OUT = REPO / "docs/design/penpot-tokens.json"

PRESETS = ["default", "ocean", "amoledBlack", "sakura", "autumn", "spring"]
ROLES = ["primary", "primaryContainer", "secondary", "tertiary",
         "background", "surface", "surfaceVariant", "outline"]


def argb_to_hex(argb: str) -> str:
    """0xFF00897B / 0xFF00897BL -> #00897B (drop alpha if FF, else #AARRGGBB)."""
    v = argb.strip().rstrip("Ll")
    n = int(v, 16)
    a = (n >> 24) & 0xFF
    rgb = n & 0xFFFFFF
    if a == 0xFF:
        return f"#{rgb:06X}"
    return f"#{a:02X}{rgb:06X}"


def parse_base_palette(text: str) -> dict:
    """LettaColorTokens base palette: name -> hex, so references resolve."""
    base = {}
    for m in re.finditer(r"const val (\w+)\s*=\s*(0x[0-9A-Fa-f]+L?)", text):
        base[m.group(1)] = argb_to_hex(m.group(2))
    return base


def _resolve_color(raw: str, base: dict) -> str | None:
    """Resolve a value that's either a literal 0xAARRGGBB or LettaColorTokens.name."""
    raw = raw.strip().rstrip(",").strip()
    if raw.startswith("0x"):
        return argb_to_hex(raw)
    m = re.match(r"LettaColorTokens\.(\w+)", raw)
    if m:
        return base.get(m.group(1))
    return None


def parse_color_presets(text: str, base: dict) -> dict:
    """Extract each preset's light+dark palette (8 roles) as resolved hex."""
    out: dict[str, dict] = {}
    for preset in PRESETS:
        m = re.search(rf"val\s+{preset}\s*=\s*LettaThemePresetTokens\((.*?)\n    \)",
                      text, re.DOTALL)
        if not m:
            continue
        block = m.group(1)
        modes: dict[str, dict] = {}
        for mode in ("light", "dark"):
            mm = re.search(rf"{mode}\s*=\s*LettaThemePaletteTokens\((.*?)\n        \)",
                           block, re.DOTALL)
            if not mm:
                continue
            body = mm.group(1)
            roles: dict[str, str] = {}
            for role in ROLES:
                rm = re.search(rf"\b{role}Argb\s*=\s*([^\n,]+)", body)
                if rm:
                    hexv = _resolve_color(rm.group(1), base)
                    if hexv:
                        roles[role] = hexv
            modes[mode] = roles
        out[preset] = modes
    return out


def parse_spacing(text: str) -> dict:
    """Core spacing scale + semantic aliases from LettaSpacingTokens."""
    scale = {}
    for name in ["none", "xxxs", "xxs", "xs", "sm", "md", "lg", "xl", "xxl", "xxxl"]:
        m = re.search(rf"const val {name}\s*=\s*([0-9.]+)f", text)
        if m:
            scale[name] = float(m.group(1))
    # Semantic aliases that reference the scale (value is a scale name)
    aliases = {}
    for m in re.finditer(r"const val (\w+)\s*=\s*(\w+)\b", text):
        name, ref = m.group(1), m.group(2)
        if ref in scale and name not in scale:
            aliases[name] = ref
    # Chat editorial rhythm explicitly (named for clarity even if alias)
    return {"scale": scale, "aliases": aliases}


def parse_body_typography(text: str) -> dict:
    """Editorial body prose roles from Type.kt (bodyMedium/bodyLarge)."""
    out = {}
    for role in ("bodyMedium", "bodyLarge"):
        m = re.search(rf"{role}\s*=\s*TextStyle\((.*?)\n    \)", text, re.DOTALL)
        if not m:
            continue
        body = m.group(1)

        def grab(pat, default=None):
            mm = re.search(pat, body)
            return mm.group(1) if mm else default

        out[role] = {
            "fontSize": grab(r"fontSize\s*=\s*([0-9.]+)\.sp"),
            "lineHeight": grab(r"lineHeight\s*=\s*([0-9.]+)\.sp"),
            "letterSpacing": grab(r"letterSpacing\s*=\s*([0-9.]+)\.sp"),
            "fontWeight": grab(r"fontWeight\s*=\s*FontWeight\.(\w+)"),
            "fontFeatureSettings": grab(r'fontFeatureSettings\s*=\s*"([^"]*)"'),
        }
    return out


def color_token(value: str) -> dict:
    return {"$type": "color", "$value": value}


def dim_token(value: float, unit: str = "px") -> dict:
    return {"$type": "dimension", "$value": f"{value:g}{unit}"}


def build_dtcg() -> dict:
    theme_text = THEME_KT.read_text()
    design_text = DESIGN_KT.read_text()
    type_text = TYPE_KT.read_text()

    base_palette = parse_base_palette(theme_text)
    presets = parse_color_presets(theme_text, base_palette)
    spacing = parse_spacing(design_text)
    typo = parse_body_typography(type_text)

    doc: dict = {
        "$description": (
            "Letta / Meridian design tokens — generated from Kotlin by "
            "scripts/export-design-tokens.py. Code is the source of truth "
            "(docs/design-sync-with-penpot.md). DTCG shape for Penpot's "
            "design-tokens/v1 native catalog."
        ),
    }

    # ── Color: one token set per preset, light/dark groups ────────────────
    for preset, modes in presets.items():
        group: dict = {}
        for mode, roles in modes.items():
            group[mode] = {role: color_token(hexv) for role, hexv in roles.items()}
        doc[f"color/{preset}"] = group

    # ── Spacing: core scale (concrete dimensions) + semantic aliases ──────
    scale_set = {name: dim_token(v) for name, v in spacing["scale"].items()}
    doc["spacing/scale"] = scale_set
    # Aliases reference the scale via DTCG {} syntax
    alias_set = {}
    for name, ref in spacing["aliases"].items():
        alias_set[name] = {"$type": "dimension", "$value": f"{{spacing/scale.{ref}}}"}
    if alias_set:
        doc["spacing/semantic"] = alias_set

    # ── Typography: editorial body prose (composite tokens) ───────────────
    typo_set = {}
    for role, attrs in typo.items():
        comp = {"$type": "typography", "$value": {}}
        v = comp["$value"]
        if attrs.get("fontSize"):
            v["fontSize"] = f"{attrs['fontSize']}px"
        if attrs.get("lineHeight"):
            v["lineHeight"] = f"{attrs['lineHeight']}px"
        if attrs.get("letterSpacing"):
            v["letterSpacing"] = f"{attrs['letterSpacing']}px"
        if attrs.get("fontWeight"):
            v["fontWeight"] = attrs["fontWeight"]
        if attrs.get("fontFeatureSettings"):
            v["fontFeatureSettings"] = attrs["fontFeatureSettings"]
        typo_set[role] = comp
    if typo_set:
        doc["typography/prose"] = typo_set

    return doc


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="verify the committed JSON matches the current Kotlin (CI)")
    args = ap.parse_args()

    doc = build_dtcg()
    rendered = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"

    if args.check:
        if not OUT.exists():
            print(f"MISSING: {OUT} — run scripts/export-design-tokens.py", file=sys.stderr)
            return 1
        current = OUT.read_text()
        if current != rendered:
            print(f"STALE: {OUT} is out of date with the Kotlin tokens. "
                  f"Run scripts/export-design-tokens.py and commit.", file=sys.stderr)
            return 1
        print(f"OK: {OUT} is up to date.")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(rendered)
    # Summary
    n_color = sum(len(m.get("light", {})) + len(m.get("dark", {}))
                  for k, m in doc.items() if k.startswith("color/"))
    n_space = len(doc.get("spacing/scale", {})) + len(doc.get("spacing/semantic", {}))
    n_typo = len(doc.get("typography/prose", {}))
    print(f"wrote {OUT.relative_to(REPO)}")
    print(f"  color tokens:      {n_color} (across {len([k for k in doc if k.startswith('color/')])} presets × light/dark)")
    print(f"  spacing tokens:    {n_space}")
    print(f"  typography tokens: {n_typo}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
