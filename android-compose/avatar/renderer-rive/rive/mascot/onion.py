"""Onion-skin motion validation: several frames of one motion overlaid in one image.

The classic animator's check. Older frames are fainter (and optionally tinted cool-to-warm like
animation paper), so an arc, the spacing between frames, an overshoot or a pop all read at a
glance without playing anything - which the CLI cannot do anyway.

    python onion.py --state listening --frames 10
    python onion.py --animation IdleBounce --tint --edges
    python onion.py --trigger success --span 800ms --diff

Two ways to pick a motion:

  --state <name>      start in idle (the default) and write `state`, so the Enter_idle_<name>
                      one-shot plays. `--trigger success|error|blink` fires a trigger, `--hover`
                      moves the pointer onto the character, `--drag` presses and drags it.

                      There is no `--from`. There was, and it was a lie: it wrote the starting
                      state, advanced, then wrote the target - but the CLI applies every `--data`
                      before the run whatever its position, so only the last write ever happened
                      and `--state x --from error` rendered exactly `--state x`. An entry out of
                      a state other than idle is seen with `--animation Enter_error_x`, which
                      plays that one-shot from its own frame 0 through a solo document.
  --animation <name>  a LinearAnimation by name (IdleBounce, WanderSpin, HoverPerk...). These sit
                      behind random waits in the state machine, so this asks gen_scene.py for a
                      SOLO document - the artboard's default state machine becomes a throwaway
                      one-state machine playing just that animation - and screenshots a temp
                      project holding it (plus a rive.yaml with the push: section stripped, so a
                      stray push can never happen from there). `Avatar` itself is untouched.

Frames are evenly spaced over --span (the animation's duration for --animation, else 900 ms), or
listed explicitly with --at. One CLI run per frame, cached under build/onion/<key>/frame-<n>.png,
so re-running with different compositing options is instant (--refresh recaptures).

--strip also writes the raw frames as a horizontal contact strip; --diff adds a motion-energy
heatmap strip plus a printed "frame -> % pixels changed" table, which is the numeric pop
detector: a spike between two neighbours is a snap or a cut.

Needs Pillow and the Rive CLI (~/.rive/bin/rive.exe). Never writes scene.rml.
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from typing import NamedTuple

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from rivecli import rive_bin, temp_project

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "build", "onion")
CROP = (50, 30, 450, 430)          # the character's corner of the 500x500 artboard (sheet.py's crop)
GROUND = (12, 12, 12)
COOL, WARM = (90, 150, 255), (255, 150, 60)   # oldest -> newest paper tint
FPS = 60.0
SPIKE_RATIO = 2.5                  # a pair moving this many times its busiest neighbour...
SPIKE_FLOOR = 1.0                  # ...and more than this % of pixels is an isolated spike


class Shot(NamedTuple):
    """What one capture renders: the project, the CLI arguments before --advance, the cache dir."""
    project: str
    args: list
    cache: str


class Look(NamedTuple):
    """How the onion composite draws its older frames."""
    min_alpha: float
    tint: bool
    edges: bool


# --- units ---------------------------------------------------------------------------------------
def parse_time(text, what="--span"):
    """'900ms' / '1.5s' / '54' (frames) -> whole frames at 60 fps."""
    t = str(text).strip().lower()
    try:
        if t.endswith("ms"):
            return max(1, round(float(t[:-2]) * FPS / 1000))
        if t.endswith("s"):
            return max(1, round(float(t[:-1]) * FPS))
        return max(1, int(round(float(t))))
    except ValueError:
        raise SystemExit(f"{what}: cannot read {text!r} (use 900ms, 1.5s or a frame count)")


def spaced(total, count):
    if count < 2:
        return [total]
    return sorted({round(i * total / (count - 1)) for i in range(count)})


# --- the solo document ---------------------------------------------------------------------------
def solo_project(name, into):
    """Write a complete one-animation Rive project into `into`; return (dir, duration in frames)."""
    return temp_project(into, [], solo=name)


# --- what to capture -----------------------------------------------------------------------------
def identity_data(a):
    """The identity and extra view-model writes, in the order the CLI receives them."""
    data = [f"--data=shape={a.shape}"] if a.shape else []
    data += [f"--data=color={a.color}"] if a.color else []
    return data + [f"--data={kv}" for kv in a.data or []]


def motion_bits(a):
    """[(label, data args, gesture args)] for each motion the arguments ask for, in label order."""
    wanted = [
        (a.animation, a.animation, [], []),
        (a.state, a.state, [f"--data=state={a.state}"], []),
        (a.trigger, a.trigger, [], [f"--data={a.trigger}=true"]),
        (a.hover, "hover", [], ["--pointer=move@250,250"]),
        (a.drag, "drag", [], ["--pointer=down@250,250", "--pointer=move@300,300"]),
    ]
    return [(label, data, gestures) for asked, label, data, gestures in wanted if asked]


def build_spec(a):
    """-> (label, gesture args before the final --advance, needs_solo)."""
    bits = motion_bits(a)
    if not bits:
        raise SystemExit("pick a motion: --state, --animation, --trigger, --hover or --drag")
    data = identity_data(a) + [arg for _l, d, _g in bits for arg in d]
    gestures = [arg for _l, _d, g in bits for arg in g]
    return "+".join(label for label, _d, _g in bits), data + gestures, bool(a.animation)


def capture(shot, frames, refresh):
    """One CLI run per frame; cached PNGs come back untouched."""
    os.makedirs(shot.cache, exist_ok=True)
    paths = []
    for i, n in enumerate(frames):
        png = os.path.join(shot.cache, f"frame-{n}.png")
        paths.append(png)
        if not refresh and os.path.exists(png):
            print(f"  frame {n:>4}  cached")
            continue
        cmd = [rive_bin(), shot.project, f"--screenshot={png}", "--quiet"] + shot.args + [f"--advance={n}"]
        print(f"  frame {n:>4}  ({i + 1}/{len(frames)}) ...", end="", flush=True)
        p = subprocess.run(cmd, capture_output=True, text=True)
        if p.returncode or not os.path.exists(png):
            print()
            raise SystemExit("rive failed:\n  " + " ".join(cmd) + "\n" + (p.stderr or p.stdout))
        print(" ok")
    return paths


# --- compositing ---------------------------------------------------------------------------------
def flat(path):
    """The frame as the CLI wrote it, cropped to the character. The CLI's 'transparent'
    background arrives as a flat opaque ground, so the character has to be cut out of it."""
    return Image.open(path).convert("RGB").crop(CROP)


def load(path):
    """The character on a derived alpha: distance from the CLI's flat ground, with the interior
    (the dark glyph, which is close to the ground in value) filled in, and the feathered halo
    left soft so the arcs keep their glow."""
    im = Image.open(path).convert("RGB")
    ground = im.getpixel((0, 0))
    d = ImageChops.difference(im, Image.new("RGB", im.size, ground)).split()
    dist = ImageChops.lighter(ImageChops.lighter(d[0], d[1]), d[2])
    soft = dist.point(lambda v: min(255, round(v * 255 / 24)))
    solid = dist.point(lambda v: 255 if v > 6 else 0)
    # Holes (glyph ink, mouth) read as ground; flood the true outside, then keep what it missed.
    inv = ImageChops.invert(solid)
    ImageDraw.floodfill(inv, (0, 0), 128)
    holes = inv.point(lambda v: 255 if v > 200 else 0)
    out = im.convert("RGBA")
    out.putalpha(ImageChops.lighter(soft, ImageChops.lighter(solid, holes)))
    return out.crop(CROP)


def ramp(i, n, lo):
    return lo if n < 2 else lo + (1.0 - lo) * (i / (n - 1))


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def scale_alpha(im, factor):
    im = im.copy()
    im.putalpha(im.getchannel("A").point(lambda v: min(255, round(v * factor))))
    return im


def tinted(im, colour, strength):
    """Pull the frame's colour toward `colour`, keeping its own alpha."""
    flat = Image.new("RGBA", im.size, colour + (255,))
    rgb = Image.blend(im.convert("RGBA"), flat, strength)
    rgb.putalpha(im.getchannel("A"))
    return rgb


def outline(im, colour):
    """Silhouette only: the alpha mask minus its erosion, so spacing reads without mush."""
    mask = im.getchannel("A").point(lambda v: 255 if v > 64 else 0)
    inner = mask.filter(ImageFilter.MinFilter(5))
    edge = ImageChops.subtract(mask, inner)
    layer = Image.new("RGBA", im.size, colour + (0,))
    layer.putalpha(edge)
    return layer


def skin(im, i, n, look):
    """Frame i of n as the composite draws it: an outline, a tinted frame, or the frame itself."""
    age = 0 if n < 2 else i / (n - 1)
    colour = mix(COOL, WARM, age)
    if look.edges and i != n - 1:
        return outline(im, colour if look.tint else (200, 210, 225))
    if look.tint:
        # The newest frame is nearly untinted: it has to read as the real pose.
        return tinted(im, colour, 0.6 - 0.5 * age)
    return im


def onion(paths, frames, label, look):
    ims = [load(p) for p in paths]
    n = len(ims)
    w, h = ims[0].size
    base = Image.new("RGBA", (w, h), GROUND + (255,))
    for i, im in enumerate(ims):
        base.alpha_composite(scale_alpha(skin(im, i, n, look), ramp(i, n, look.min_alpha)))
    return label_image(base.convert("RGB"), label, frames)


def label_image(im, label, frames):
    w, h = im.size
    out = Image.new("RGB", (w, h + 26), GROUND)
    out.paste(im, (0, 0))
    d = ImageDraw.Draw(out)
    d.text((8, h + 8), label, fill=(235, 235, 235))
    text = "frames " + " ".join(str(f) for f in frames)
    if d.textlength(text) > w - 20 - d.textlength(label):
        text = f"frames {frames[0]}..{frames[-1]} ({len(frames)})"
    d.text((w - 8 - d.textlength(text), h + 8), text, fill=(150, 160, 175))
    return out


def strip(paths, frames, cell=200):
    """The raw frames side by side - sheet.py's contact sheet, one row."""
    ims = []
    for p, n in zip(paths, frames):
        im = flat(p).resize((cell, cell))
        ImageDraw.Draw(im).text((6, 6), str(n), fill=(220, 220, 220))
        ims.append(im)
    out = Image.new("RGB", (cell * len(ims), cell), GROUND)
    for i, im in enumerate(ims):
        out.paste(im, (i * cell, 0))
    return out


def heat(px):
    """0..255 motion energy -> black / red / yellow / white."""
    t = px / 255.0
    if t < 0.4:
        return mix((0, 0, 0), (190, 30, 30), t / 0.4)
    if t < 0.75:
        return mix((190, 30, 30), (240, 200, 40), (t - 0.4) / 0.35)
    return mix((240, 200, 40), (255, 255, 255), (t - 0.75) / 0.25)


def diff(paths, frames, cell=200, threshold=12):
    """Per-pixel difference of each consecutive pair: a heatmap strip and the % table."""
    flats = [flat(p) for p in paths]
    cells, rows = [], []
    for i in range(len(flats) - 1):
        d = ImageChops.difference(flats[i], flats[i + 1]).convert("L")
        data = list(d.getdata())
        pct = 100.0 * sum(1 for v in data if v > threshold) / len(data)
        energy = sum(data) / len(data)
        hot = Image.new("RGB", d.size)
        lut = [heat(v) for v in range(256)]
        hot.putdata([lut[v] for v in data])
        hot = hot.resize((cell, cell))
        ImageDraw.Draw(hot).text((6, 6), f"{frames[i]}->{frames[i + 1]}  {pct:.1f}%", fill=(235, 235, 235))
        cells.append(hot)
        rows.append((frames[i], frames[i + 1], pct, energy))
    out = Image.new("RGB", (cell * max(1, len(cells)), cell), GROUND)
    for i, c in enumerate(cells):
        out.paste(c, (i * cell, 0))
    return out, rows


def is_spike(pcts, i):
    """Whether pair i moves far more than the pairs on BOTH sides of it. An end pair has one
    neighbour, and one neighbour cannot tell a snap from a front- or back-loaded ease."""
    if not 0 < i < len(pcts) - 1:
        return False
    return pcts[i] > max(SPIKE_RATIO * max(pcts[i - 1], pcts[i + 1]), SPIKE_FLOOR)


def spikes_of(pcts):
    # A snap is an ISOLATED spike: one pair moving far more than the pairs either side of it.
    # A front-loaded ease is a smooth ramp and must not be flagged, so compare to neighbours,
    # not to the mean.
    return [i for i in range(len(pcts)) if is_spike(pcts, i)]


def print_diff(rows):
    if not rows:
        print("  (need at least two frames for a diff)")
        return
    pcts = [r[2] for r in rows]
    peak, mean = max(pcts), sum(pcts) / len(pcts)
    spikes = spikes_of(pcts)
    print("\n  frame pair        % pixels changed   energy")
    for i, (a, b, pct, energy) in enumerate(rows):
        bar = "#" * round(28 * (pct / peak if peak else 0))
        flag = "  <- spike" if i in spikes else ""
        print(f"  {a:>4} -> {b:<4}   {pct:6.2f}%  {bar:<28} {energy:5.1f}{flag}")
    verdict = ("no isolated spike: the spacing reads as continuous motion" if not spikes else
               "isolated spike(s) at " + ", ".join(f"{rows[i][0]}->{rows[i][1]}" for i in spikes)
               + " - a snap or a cut")
    print(f"  mean {mean:.2f}%, peak {peak:.2f}% ({verdict})")


# --- main ----------------------------------------------------------------------------------------
def parse_args(argv):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--state", help="sustained state to switch into (plays Enter_<from>_<state>)")
    p.add_argument("--animation", help="LinearAnimation name, played through a solo state machine")
    p.add_argument("--trigger", choices=("success", "error", "blink"), help="fire a contract trigger")
    p.add_argument("--hover", action="store_true", help="move the pointer onto the character")
    p.add_argument("--drag", action="store_true", help="press and drag the character")
    p.add_argument("--frames", type=int, default=8, help="how many onion frames (8)")
    p.add_argument("--span", help="window to spread them over: 900ms, 1.5s or a frame count")
    p.add_argument("--at", help="explicit frames instead: 0,4,8,12")
    p.add_argument("--shape", help="identity body (--data=shape=)")
    p.add_argument("--color", help="identity colour as argb (--data=color=)")
    p.add_argument("--data", action="append", help="extra view-model write, prop=value; repeatable")
    p.add_argument("--min-alpha", type=float, default=0.15, help="opacity of the oldest frame (0.15)")
    p.add_argument("--tint", action="store_true", help="colour the ramp cool blue -> warm orange")
    p.add_argument("--edges", action="store_true", help="older frames as silhouette outlines only")
    p.add_argument("--strip", action="store_true", help="also write a contact strip of the raw frames")
    p.add_argument("--diff", action="store_true", help="also write the motion-energy heatmap and table")
    p.add_argument("--refresh", action="store_true", help="recapture instead of reusing the cache")
    p.add_argument("--out", help="output png (default build/onion/<key>.png)")
    return p.parse_args(argv)


def frames_for(a, duration):
    """(span, frames): --at verbatim, else --frames spread over --span (or the solo duration, or 900 ms)."""
    span = parse_time(a.span, "--span") if a.span else (duration or parse_time("900ms"))
    if a.at:
        return span, [int(x) for x in a.at.replace(" ", "").split(",") if x != ""]
    return span, spaced(span, a.frames)


def capture_motion(a, label, args, cache):
    """Pick the project (a temp solo one for --animation), lay out the frames and capture them."""
    tmp = tempfile.mkdtemp(prefix="onion-solo-") if a.animation else None
    try:
        if tmp:
            project, duration = solo_project(a.animation, tmp)
            print(f"solo project for {a.animation} ({duration} frames) in {project}")
        else:
            project, duration = HERE, 0
        span, frames = frames_for(a, duration)
        print(f"{label}: {len(frames)} frames over {span} frames "
              f"({span / FPS * 1000:.0f} ms) -> {cache}")
        return capture(Shot(project, args, cache), frames, a.refresh), frames
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)


def write_extras(a, out, paths, frames):
    """--strip and --diff, next to the composite."""
    if a.strip:
        sp = os.path.splitext(out)[0] + "-strip.png"
        strip(paths, frames).save(sp)
        print("wrote", sp)
    if a.diff:
        dp = os.path.splitext(out)[0] + "-diff.png"
        image, rows = diff(paths, frames)
        image.save(dp)
        print("wrote", dp)
        print_diff(rows)


def main(argv=None):
    a = parse_args(argv)
    label, args, _needs_solo = build_spec(a)
    key = label.replace("/", "-") + "-" + hashlib.sha1(" ".join([label] + args).encode()).hexdigest()[:8]
    paths, frames = capture_motion(a, label, args, os.path.join(BUILD, key))

    os.makedirs(BUILD, exist_ok=True)
    out = a.out or os.path.join(BUILD, f"{key}.png")
    onion(paths, frames, label, Look(a.min_alpha, a.tint, a.edges)).save(out)
    print("wrote", out)
    write_extras(a, out, paths, frames)
    return 0


if __name__ == "__main__":
    sys.exit(main())
