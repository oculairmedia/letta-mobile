"""Review sheets for the mark, from real CLI renders of the built rig (needs the Rive CLI and Pillow).

    python review.py                    # every sheet below, into review/
    python review.py --only connecting  # one motion
    python review.py --gif              # also review/baken-mark.gif (every state in turn)

Per motion (each sustained state, boot, success, and the reduceMotion picture):
  review/<motion>.png   a contact strip of the frames across one cycle, and an onion skin of the
                        same frames overlaid (older fainter, the mascot's `onion.py` compositing) -
                        arcs, spacing and the contact pivots read at a glance
And:
  review/themes.png     every state's calm picture in each of the four themes, on that theme's
                        own background

Frames come from `rive <temp project> --screenshot --data=... --advance=N`, the same document as
the shipped file (built into a temp project; scene.rml is never written). Renders are cached in
build/review/ by their flags; `--refresh` re-renders.
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

import rig
from PIL import Image, ImageDraw

import probe as _probe
from rig.constants import BOOT_MS, IDLE_MS, LOADING_MS, CONNECTING_MS, DEGRADED_MS, AMBIENT_MS, PALETTE, SUCCESS_MS, frames
from rig.ids import STATES, THEMES
from rivecli import rive_bin

_onion = rig.mascot_module("onion")

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "review")
CACHE = os.path.join(HERE, "build", "review")
CELL = 168
GROUND = (12, 12, 12)
CROP = (50, 30, 450, 430)          # the artboard corner the mascot's sheets use; the ground is y=420

SETTLE = 40      # frames: past the conditional entry, into the loop proper
CYCLE = {"idle": IDLE_MS, "loading": LOADING_MS, "connecting": CONNECTING_MS, "degraded": DEGRADED_MS,
         "ambient": AMBIENT_MS}


def motions():
    """name -> (flags, frames to render, label)."""
    out = {}
    for s in STATES:
        n = frames(CYCLE[s])
        out[s] = ([f"--data=state={s}"], [SETTLE + round(n * k / 8) for k in range(8)],
                  f"{s}  ({CYCLE[s]} ms cycle)")
    out["boot"] = (["--data=boot=true"], [1, 8, 14, 20, 28, 36, 48, frames(BOOT_MS)], f"boot  ({BOOT_MS} ms)")
    out["success"] = (["--data=success=true"], [1, 10, 18, 27, 34, 42, 52, frames(SUCCESS_MS)],
                      f"success  ({SUCCESS_MS} ms)")
    out["reduce-motion"] = (None, None, "reduceMotion: one static picture per state")
    return out


class Renderer:
    """CLI renders, one temp project per backdrop (None = the shipped document as it is)."""

    def __init__(self, refresh=False):
        self.tmp = tempfile.mkdtemp(prefix="bakenreview-")
        self.refresh = refresh
        self.projects = {}
        os.makedirs(CACHE, exist_ok=True)

    def close(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def project(self, backdrop):
        if backdrop not in self.projects:
            into = os.path.join(self.tmp, backdrop or "plain")
            os.makedirs(into)
            project, _ = _probe.temp_project(into, ["--backdrop", backdrop] if backdrop else [])
            with open(os.path.join(project, "scene.rml"), "rb") as f:
                self.projects[backdrop] = (project, hashlib.sha1(f.read()).hexdigest()[:10])
        return self.projects[backdrop]

    def shot(self, flags, frame, backdrop=None):
        project, doc_hash = self.project(backdrop)
        key = hashlib.sha1((doc_hash + " ".join(flags) + f" {frame}").encode()).hexdigest()[:16]
        png = os.path.join(CACHE, key + ".png")
        if self.refresh or not os.path.exists(png):
            cmd = [rive_bin(), project, f"--screenshot={png}", "--quiet"] + flags + [f"--advance={frame}"]
            p = subprocess.run(cmd, capture_output=True, text=True)
            if p.returncode or not os.path.exists(png):
                raise SystemExit("rive failed:\n  " + " ".join(cmd) + "\n" + (p.stderr or p.stdout))
        return png

    def cell(self, flags, frame, theme="dark", size=CELL):
        """One frame on the theme's own background, painted inside the artboard (--backdrop) so
        Rive composites the translucent lights and the dimmed stones exactly as the app will."""
        png = self.shot([f"--data=theme={theme}"] + flags, frame, PALETTE[theme]["bg"])
        return Image.open(png).convert("RGB").crop(CROP).resize((size, size), Image.LANCZOS)


def rgb(argb):
    return tuple(int(argb[i:i + 2], 16) for i in (2, 4, 6))


def label(im, text, colour=(225, 228, 232)):
    ImageDraw.Draw(im).text((6, 5), text, fill=colour)
    return im


def motion_sheet(r, name, motion):
    """A strip of the frames, then their onion skin, with the title above. `motion` is one entry of
    motions(): (flags, frames, title)."""
    flags, frame_list, title = motion
    cells = [label(r.cell(flags, f), str(f)) for f in frame_list]
    paths = [r.shot(flags, f) for f in frame_list]      # plain renders: onion.load cuts them out
    look = _onion.Look(tint=False, edges=False, min_alpha=0.18)
    skin = _onion.onion(paths, frame_list, name, look).convert("RGB")
    skin = skin.crop((0, 0, skin.width, skin.height - 26)).resize((CELL * 2, CELL * 2), Image.LANCZOS)
    w = CELL * 4 + CELL * 2
    sheet = Image.new("RGB", (w, 22 + CELL * 2), GROUND)
    for i, c in enumerate(cells):
        sheet.paste(c, ((i % 4) * CELL, 22 + (i // 4) * CELL))
    sheet.paste(skin, (CELL * 4, 22))
    ImageDraw.Draw(sheet).text((6, 5), f"{title}   |   onion skin of the same frames ->", fill=(235, 235, 235))
    return sheet


def reduce_motion_sheet(r):
    """Each state's reduceMotion picture at 1 s and 10 s - they must be identical (no loop)."""
    sheet = Image.new("RGB", (CELL * len(STATES), 22 + CELL * 2), GROUND)
    for i, s in enumerate(STATES):
        flags = ["--data=reduceMotion=true", f"--data=state={s}"]
        sheet.paste(label(r.cell(flags, 60), f"{s} @1s"), (i * CELL, 22))
        sheet.paste(label(r.cell(flags, 600), f"{s} @10s"), (i * CELL, 22 + CELL))
    ImageDraw.Draw(sheet).text((6, 5), "reduceMotion: a static parting / brightness per state; "
                               "top 1 s, bottom 10 s (identical = no loop)", fill=(235, 235, 235))
    return sheet


def themes_sheet(r):
    sheet = Image.new("RGB", (CELL * len(STATES), 22 + CELL * len(THEMES)), GROUND)
    for j, t in enumerate(THEMES):
        bg = rgb(PALETTE[t]["bg"])
        ink = (20, 30, 40) if sum(bg) > 380 else (225, 228, 232)
        for i, s in enumerate(STATES):
            flags = ["--data=reduceMotion=true", f"--data=state={s}"]
            sheet.paste(label(r.cell(flags, 60, t), f"{t} / {s}", ink), (i * CELL, 22 + j * CELL))
    ImageDraw.Draw(sheet).text((6, 5), "themes x states (calm pictures, each on its theme's background)",
                               fill=(235, 235, 235))
    return sheet


def gif(r, out):
    """Every sustained state for one cycle, then boot and success, dark theme, every 6th frame at 10 fps, 160 px (kept small to commit)."""
    seq = []
    for s in STATES:
        n = frames(CYCLE[s])
        seq += [([f"--data=state={s}"], SETTLE + f, s) for f in range(0, n, 3)]
    seq += [(["--data=boot=true"], f, "boot") for f in range(1, frames(BOOT_MS) + 20, 3)]
    seq += [(["--data=success=true"], f, "success") for f in range(1, frames(SUCCESS_MS) + 10, 3)]
    ims = [label(r.cell(fl, f, size=160), name).quantize(64) for fl, f, name in seq[::2]]
    ims[0].save(out, save_all=True, append_images=ims[1:], duration=100, loop=0, optimize=True)
    return out


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--only")
    p.add_argument("--gif", action="store_true")
    p.add_argument("--refresh", action="store_true")
    a = p.parse_args(argv)
    os.makedirs(OUT, exist_ok=True)
    r = Renderer(a.refresh)
    try:
        for name, (flags, frame_list, title) in motions().items():
            if a.only and a.only != name:
                continue
            sheet = reduce_motion_sheet(r) if name == "reduce-motion" else motion_sheet(r, name, (flags, frame_list, title))
            path = os.path.join(OUT, f"{name}.png")
            sheet.save(path, optimize=True)
            print("wrote", path)
        if not a.only:
            path = os.path.join(OUT, "themes.png")
            themes_sheet(r).save(path, optimize=True)
            print("wrote", path)
        if a.gif:
            print("wrote", gif(r, os.path.join(OUT, "baken-mark.gif")))
    finally:
        r.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
