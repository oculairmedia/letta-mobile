#!/usr/bin/env python3
"""Pack the desktop installer icons from the brand source.

Reads android-compose/branding/brand.properties and writes brand.icon.ico (Windows, 16-256 px) and
brand.icon.icns (macOS, 16-1024 px) from brand.icon.png. When cairosvg is installed, brand.icon.png
is first re-rendered from brand.icon.svg at 512 px; otherwise the committed PNG is the master (render
the SVG with any SVG tool to replace it). Commit the files this writes.

    python scripts/brand/pack_desktop_icons.py
"""
from __future__ import annotations

import io
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2] / "android-compose"
ICO_SIZES = [(size, size) for size in (16, 20, 24, 32, 40, 48, 64, 96, 128, 256)]
PNG_SIZE = 512
ICNS_MASTER_SIZE = 1024


def brand_properties() -> dict[str, str]:
    values = {}
    for line in (ROOT / "branding/brand.properties").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def render_svg(svg: Path, size: int) -> Image.Image | None:
    try:
        import cairosvg  # optional: only needed to re-render the master from the SVG
    except ImportError:
        return None
    png = cairosvg.svg2png(url=str(svg), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def main() -> None:
    brand = brand_properties()
    png_path = ROOT / brand["brand.icon.png"]
    master = render_svg(ROOT / brand["brand.icon.svg"], ICNS_MASTER_SIZE)
    if master is not None:
        master.resize((PNG_SIZE, PNG_SIZE), Image.LANCZOS).save(png_path)
    else:
        master = Image.open(png_path).convert("RGBA")
    master.save(ROOT / brand["brand.icon.ico"], sizes=ICO_SIZES)
    master.resize((ICNS_MASTER_SIZE, ICNS_MASTER_SIZE), Image.LANCZOS).save(ROOT / brand["brand.icon.icns"])
    print(f"Packed {brand['brand.icon.ico']} and {brand['brand.icon.icns']} from {brand['brand.icon.png']}")


if __name__ == "__main__":
    main()
