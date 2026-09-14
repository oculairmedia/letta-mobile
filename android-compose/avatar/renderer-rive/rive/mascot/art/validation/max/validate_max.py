#!/usr/bin/env python3
"""Static SVG assembly and numeric gates; never invokes Rive or emits RML.

Run normally for checks/JSON, --render to refresh this folder's proof PNGs.
Dependencies: numpy, Pillow, CairoSVG, existing pure svgpath.py converter.
"""
from pathlib import Path
from io import BytesIO
import argparse
import hashlib
import importlib.util
import json
import math
import re
import subprocess
import xml.etree.ElementTree as ET

import cairosvg
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
ART = HERE.parents[1]
PROJECT = ART.parent
BASE = '2e11692c21ddcc971e2acb1609c33794ed892118'
loader = importlib.util.spec_from_file_location('baseline_validation', HERE.parent / 'validate.py')
v = importlib.util.module_from_spec(loader)
loader.loader.exec_module(v)
STATES = v.STATES + ['working']
HOSTED = {'idle', 'listening', 'speaking'}
PUPILS = {'pupil-core.svg', 'catchlight.svg', 'iris-field.svg'} | {f'squiggle-{i}.svg' for i in range(4)}


def baseline(path):
    return subprocess.check_output(['git', 'show', f'{BASE}:{v.REL}/{path}'], cwd=v.REPO)


def wave(phase, amplitude):
    phi = 2 * math.pi * phase
    def y(x): return amplitude * math.sin(phi + math.pi * (x + 10) / 10)
    def dy(x): return amplitude * math.pi / 10 * math.cos(phi + math.pi * (x + 10) / 10)
    d = f'M -10 {y(-10):.6f}'
    for x in (-10, -5, 0, 5):
        b = x + 5
        d += f' C {x+5/3:.6f} {y(x)+5/3*dy(x):.6f} {b-5/3:.6f} {y(b)-5/3*dy(b):.6f} {b} {y(b):.6f}'
    return d


def sdf(points, half, radius):
    q = np.abs(points) - (np.asarray(half) - radius)
    return np.linalg.norm(np.maximum(q, 0), axis=-1) + np.minimum(np.max(q, axis=-1), 0) - radius


def ink(state, tail=''):
    a = v.node(f'glyph-{state}{tail}.svg').attrib
    return np.concatenate(v.samples(a['d'], 61)), float(a.get('stroke-width', 0)) / 2


def clearance(state, offset, tail=''):
    points, pad = ink(state, tail)
    scale = {'listening': 1.04, 'waitingInput': 1.06}.get(state, 1)
    half, radius = (76, 34) if tail else (60, 27)
    return float(np.min(-sdf(points + offset, half * scale, radius * scale) - pad))


def compose_gaze(state, host, noise, tail=''):
    """Noise-first clamp, requiring two local px of actual outer-ink margin."""
    points, pad = ink(state, tail)
    scale = {'listening': 1.04, 'waitingInput': 1.06}.get(state, 1)
    half, radius = (76, 34) if tail else (60, 27)
    host, noise = np.array(host, float), np.array(noise, float)
    def safe(offset):
        return np.max(sdf(points + offset, half * scale, radius * scale) + pad) <= -2
    def fraction(base, delta):
        if safe(base + delta): return 1.0
        lo, hi = 0., 1.
        for _ in range(32):
            mid = (lo + hi) / 2
            if safe(base + mid * delta): lo = mid
            else: hi = mid
        return lo
    kappa = fraction(np.zeros(2), host)
    host *= kappa
    lam = fraction(host, noise) if kappa == 1 else 0.
    return host + lam * noise, lam, kappa


def pupil_svg(*args, **opts):
    state, size, phase = args[0], args[1], args[2]
    amplitude = args[3] if len(args) > 3 else opts.get('amplitude')
    mouth = args[4] if len(args) > 4 else opts.get('mouth', .5)
    gaze = args[5] if len(args) > 5 else opts.get('gaze', (0, 0))
    reduced = args[6] if len(args) > 6 else opts.get('reduced', False)
    return _pupil_svg(state, size, phase, amplitude, mouth, gaze, reduced)


def _pupil_svg(state, size, phase, amplitude, mouth, gaze, reduced):
    if state not in HOSTED or size <= 28: return ''
    amplitude = {'idle': 1.5, 'listening': 4., 'speaking': 1 + 3 * mouth}[state] if amplitude is None else amplitude
    delta = np.zeros(2) if reduced else np.clip(.15 * np.array(gaze), [-1.5, -1], [1.5, 1])
    iris = v.content('pupil/iris-field.svg')
    wave_svg = '' if reduced else f'<path d="{wave(phase, amplitude)}" fill="none" stroke="#111111" stroke-width="8" stroke-linecap="round" stroke-linejoin="round"/>'
    glint = f'<g opacity=".9">{v.content("pupil/catchlight.svg")}</g>' if size >= 72 and not reduced else ''
    return f'<defs><clipPath id="irisClip">{iris}</clipPath></defs>{iris}<g clip-path="url(#irisClip)"><g transform="translate({delta[0]} {delta[1]})">{wave_svg}{v.content("pupil/pupil-core.svg")}{glint}</g></g>'


def avatar(*args, **opts):
    state = args[0] if args else opts.get('state', 'idle')
    size = args[1] if len(args) > 1 else opts.get('size', 72)
    bg = args[2] if len(args) > 2 else opts.get('bg', '#15191F')
    display = opts.get('display', 1.25)
    pupil = opts.get('pupil', True)
    phase = opts.get('phase', 0)
    amplitude = opts.get('amplitude')
    small = opts.get('small', False)
    gaze = opts.get('gaze', (0, 0))
    mouth = opts.get('mouth', .5)
    root = opts.get('root', (0, 0))
    neutral = opts.get('neutral', False)
    reduced = opts.get('reduced', False)
    return _draw_avatar(state, size, bg, display, pupil, phase, amplitude, small, gaze, mouth, root, neutral, reduced)


def _draw_avatar(state, size, bg, display, pupil, phase, amplitude, small, gaze, mouth, root, neutral, reduced):
    """Rest-state assembly. Gaussian edge approximates paint, not Rive feather."""
    tail = '-small' if small else ''
    ps = {'listening': 1.04, 'waitingInput': 1.06}.get(state, 1)
    half, radius = ((76, 34) if small else (60, 27))
    half *= ps
    radius *= ps
    offset = 0 if neutral else {'listening': -14, 'waitingInput': -2, 'error': 4, 'error-flash': 4, 'sleeping': 4}.get(state, 0)
    rot = 0 if neutral else {'listening': -2, 'thinking': -6, 'error': 5, 'error-flash': 5, 'sleeping': 3, 'degraded': 4}.get(state, 0)
    body = v.node('body-blob.svg').get('d')
    xf = f'translate(250 270) scale({display}) translate({root[0]} {root[1]})'
    hi = size * 4
    output = Image.new('RGBA', (hi, hi), bg)
    halo = .06 if state == 'sleeping' else 0 if state == 'failed' else .10
    if size > 28:
        for width, feather, alpha, scale in [(12, 10, halo, 1.02), (8, 4, .14, 1)]:
            stroke = v.svg_raster(f'<g transform="{xf}"><path transform="scale({scale})" d="{body}" fill="none" stroke="#79B7DF" stroke-width="{width}" opacity="{alpha}"/></g>', hi)
            output = Image.alpha_composite(output, stroke.filter(ImageFilter.GaussianBlur(feather * hi / 500 * display)))
    defs = '<defs><radialGradient id="shade" gradientUnits="userSpaceOnUse" cx="-40" cy="-65" r="251.2469"><stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset=".55" stop-color="#000" stop-opacity=".03137255"/><stop offset="1" stop-color="#000" stop-opacity=".2509804"/></radialGradient><radialGradient id="gloss" gradientUnits="userSpaceOnUse" cx="-65" cy="-85" r="183.8478"><stop offset="0" stop-color="#fff" stop-opacity=".3215686"/><stop offset=".45" stop-color="#fff" stop-opacity=".1411765"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>'
    tint, opacity = {'sleeping': ('#000000', 56/255), 'failed': ('#808080', 102/255), 'loading': ('#000000', 16/255), 'error': ('#000000', 20/255), 'error-flash': ('#000000', 20/255)}.get(state, ('#000000', 0))
    eye = v.content(f'glyph-{state}{tail}.svg')
    if pupil: eye += pupil_svg(state, size, phase, amplitude, mouth, gaze, reduced)
    mouth_svg = ''
    if state in ('speaking', 'dragged', 'waitingInput', 'error', 'error-flash'):
        key = 'o' if state == 'waitingInput' else 'frown' if state in ('error', 'error-flash') else 'closed' if mouth == 0 and state != 'dragged' else 'half' if mouth <= .5 else 'open'
        mouth_svg = f'<g transform="translate(0 {114 if small else 82})">{v.content(f"glyph-mouth-{key}{tail}.svg")}</g>'
    graphic = f'{defs}<g transform="{xf}"><path d="{body}" fill="#79B7DF"/><path d="{body}" fill="url(#shade)"/><path d="{body}" fill="url(#gloss)"/><path d="{body}" fill="{tint}" opacity="{opacity}"/><g transform="translate(0 {-16+offset}) rotate({rot})"><rect x="{-half}" y="{-half}" width="{2*half}" height="{2*half}" rx="{radius}" fill="#F7F7F7"/><g transform="translate({gaze[0]} {gaze[1]})">{eye}</g>{mouth_svg}</g></g>'
    output = Image.alpha_composite(output, v.svg_raster(graphic, hi))
    return output.resize((size, size), Image.Resampling.LANCZOS).convert('RGB')


def _assert_max_inventory():
    assert {p.name for p in ART.glob('*.svg')} == v.EXPECTED | {'glyph-working.svg', 'glyph-working-small.svg'}
    assert {p.name for p in (ART / 'pupil').glob('*.svg')} == PUPILS
    for name in v.EXPECTED:
        assert (ART / name).read_bytes() == baseline('art/' + name), name
    previous_spec = baseline('SPEC.md')
    assert (PROJECT / 'SPEC.md').read_bytes().startswith(previous_spec)
    changed = subprocess.check_output(['git', 'diff', BASE, '--name-only'], cwd=v.REPO, text=True).splitlines()
    assert all(p.endswith('SPEC.md') or '/art/' in p for p in changed), changed


def _assert_new_svg(path):
    root = ET.parse(path).getroot()
    assert root.tag == '{http://www.w3.org/2000/svg}svg'
    assert root.attrib == {'viewBox': '-50 -50 100 100'} and len(root) == 1
    a = root[0].attrib
    assert root[0].tag == '{http://www.w3.org/2000/svg}path'
    assert set(a) <= {'d', 'fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin', 'fill-rule'}
    assert set(re.findall('[A-Za-z]', a['d'])) <= set('MLCZ')
    paints = {a[k] for k in ('fill', 'stroke') if k in a and a[k] != 'none'}
    assert len(paints) == 1 and paints <= {'#111111', '#F7F7F7', '#FFFFFF'}
    p = np.concatenate(v.samples(a['d']))
    pad = float(a.get('stroke-width', 0)) / 2
    assert np.max(np.abs(p)) + pad <= 50
    ET.fromstring(v.svgpath.path_rml(path, 'ValidationOnly', '999:1'))


def _pupil_clearance():
    minimum = float('inf')
    for phase in np.linspace(0, 1, 257):
        p = np.concatenate(v.samples(wave(phase, 4), 41))
        for x in (-1.5, 1.5):
            for y in (-1, 1):
                minimum = min(minimum, float(np.min(-sdf(p + [x, y], (17, 15), 7) - 4)))
    core = np.concatenate(v.samples(v.node('pupil/pupil-core.svg').get('d')))
    for x in (-1.5, 1.5):
        for y in (-1, 1):
            assert np.max(sdf(core + [x, y], (17, 15), 7)) < 0
    assert math.hypot(2, -2) + 3 < 7
    assert minimum >= 1, minimum
    return round(minimum, 6)


def _outer_gaze():
    before, after, kappas = [], [], []
    for state in STATES:
        for x in (-23, 0, 23):
            for y in (-17, 0, 17):
                for nx, ny in ((-12, -9), (-12, 9), (12, -9), (12, 9), (0, 0)):
                    host, noise = np.array([x, y]), np.array([nx, ny])
                    before.append(clearance(state, host + noise))
                    out, lam, kappa = compose_gaze(state, host, noise)
                    after.append(clearance(state, out))
                    kappas.append(kappa)
    assert min(after) >= 2 - 1e-8 and min(kappas) == 1
    raw = clearance('failed', np.array([29, 17]))
    corrected, lam, _ = compose_gaze('failed', (23, 17), (6, 0))
    return {
        'sample_cases': len(after),
        'minimum_raw_clearance_px': round(min(before), 6),
        'minimum_clamped_clearance_px': round(min(after), 6),
        'host_fraction_min': min(kappas),
        'failed_example_overflow_px': round(-raw, 6),
        'failed_example_noise_fraction': round(lam, 9),
        'failed_example_corrected_xy': corrected.tolist(),
    }


def _phase_rasters():
    report = {}
    for state in ('idle', 'listening', 'speaking', 'thinking', 'sleeping'):
        for size in (22, 44, 72):
            a = np.array(avatar(state, size, phase=0, small=size == 22), dtype=int)
            b = np.array(avatar(state, size, phase=.5, small=size == 22), dtype=int)
            delta = np.max(np.abs(a - b), axis=2)
            count = int(np.sum(delta >= 8))
            if size == 22 or state not in HOSTED:
                assert count == 0
            if size > 22 and state in ('listening', 'speaking'):
                assert count > 0
            report[f'{state}_{size}'] = {'pixels_delta_ge_8': count, 'max_channel_delta': int(delta.max())}
    return report


def gate():
    report = {'baseline': BASE}
    _assert_max_inventory()
    new = [ART / f'glyph-working{t}.svg' for t in ('', '-small')] + sorted((ART / 'pupil').glob('*.svg'))
    for path in new:
        _assert_new_svg(path)
    for i in range(4):
        a = v.node(f'pupil/squiggle-{i}.svg').attrib
        assert a['d'] == wave(i/4, 1)
        assert re.findall('[A-Z]', a['d']) == ['M'] + ['C'] * 4
        assert a['stroke-width'] == '8' and a['stroke-linecap'] == 'round'
    report['pupil_iris_minimum_clearance_px'] = _pupil_clearance()
    report['outer_gaze'] = _outer_gaze()
    report['phase_raster_changes'] = _phase_rasters()
    for a in (0, .5, 1):
        for size in (22, 44, 72):
            assert avatar('speaking', size, mouth=a, small=size == 22).size == (size, size)
    report.update(new_pure_svg_count=len(new), prior_root_svgs_unchanged=len(v.EXPECTED), prior_spec_prefix='PASS', native_path_parser='PASS', mouth_topology='PASS (unchanged bytes)', runtime='NOT RUN', human_recognition='PENDING')
    report['new_svg_sha256'] = {p.relative_to(ART).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in new}
    return report


def eye_crop(a, state, display=1.25, factor=4):
    size = a.width
    offset = {'listening': -14, 'waitingInput': -2, 'error': 4, 'error-flash': 4, 'sleeping': 4}.get(state, 0)
    side = math.ceil((170 if size <= 28 else 140) * size / 500 * display)
    cx, cy = size/2, (270 + display * (-16 + offset)) * size/500
    left, top = round(cx-side/2), round(cy-side/2)
    return a.crop((left,top,left+side,top+side)).resize((side*factor,side*factor),Image.Resampling.NEAREST)


def sheet_sizes():
    im = Image.new('RGB',(1620,1130),'#20252D')
    v.label(im,(20,14),'ASTRA MAX | Proposed scale 1.25; 22 / 44 / 72 px',size=21)
    v.label(im,(20,45),'Native pixels above 4x plate crops. 22: pending small profile, pupil OFF. 44/72: pupil only idle/listening/speaking.')
    for i,state in enumerate(STATES): v.label(im,(82+i*109,77),state,size=12)
    y = 105
    for size in (22,44,72):
        for bg in ('#15191F','#F2F3F5'):
            v.label(im,(10,y),f'{size}px',size=13)
            v.label(im,(10,y+20),'dark' if bg=='#15191F' else 'light',size=11)
            for i,state in enumerate(STATES):
                a=avatar(state,size,bg,small=size==22)
                x=82+i*109
                im.paste(a,(x+(100-size)//2,y))
                crop=eye_crop(a,state)
                im.paste(crop,(x+(100-crop.width)//2,y+size+6))
            y += {22:83,44:134,72:208}[size]
    v.label(im,(20,980),'Current framing | 44 px, scale 1.0, standard symbols without proposed pupil',size=17)
    for i,state in enumerate(STATES):
        a=avatar(state,44,display=1,pupil=False)
        im.paste(a,(104+i*109,1018))
    v.label(im,(20,1085),'Static SVG assemblies, not live Rive captures. Working is a proposed asset, not an implemented state. Human recognition remains pending.')
    im.save(HERE/'sizes.png')


def sheet_phase():
    im=Image.new('RGB',(1170,1330),'#20252D')
    v.label(im,(20,14),'Living pupil | one core + one connected wave',size=22)
    v.label(im,(20,47),'Proposed scale 1.25; native 44/72 px and 4x plate crops. Analytic geometry sampled at four phases. Mouth held at e=0.5.')
    for col,p in enumerate((0,.25,.5,.75)):
        v.label(im,(260+col*220,80),f'phase {p:.2f}',size=16)
    y=115
    for state in ('idle','listening','speaking','thinking'):
        for size in (44,72):
            v.label(im,(20,y),f'{state} / {size}px',size=16)
            v.label(im,(20,y+25),{'idle':'.4 Hz / A1.5','listening':'.8 Hz / A4','speaking':'2 Hz / A2.5','thinking':'pure symbol; OFF'}[state],size=13)
            for col,p in enumerate((0,.25,.5,.75)):
                a=avatar(state,size,phase=p)
                x=230+col*220
                im.paste(a,(x,y))
                c=eye_crop(a,state)
                im.paste(c,(x+80,y))
            y += 142
    v.label(im,(20,1270),'Catchlight: 72 only. Wave stroke 8 px = 0.88 dp at 44; amplitude is deliberately small. No duplicate saccade or tremor clock.')
    im.save(HERE/'squiggle-strip.png')


def sheet_containment():
    im=Image.new('RGB',(1120,910),'#20252D')
    v.label(im,(20,15),'Containment | noise yields to host gaze',size=22)
    v.label(im,(20,50),'Failed X at look(1,1) plus saccade(+6,0): current composition breaches the rounded Card. Proposed clamp reserves 2 px.')
    out,lam,_=compose_gaze('failed',(23,17),(6,0))
    for x,gaze,title in ((20,(29,17),'Current sum: 2.541 px outside'),(570,out,f'Clamp: noise x6 × {lam:.4f}; 2 px margin')):
        v.label(im,(x,90),title,size=17)
        a=avatar('failed',480,gaze=gaze,neutral=True)
        im.paste(a,(x,120))
    v.label(im,(20,620),'Pupil at all extreme host looks; internal parallax capped to ±1.5 / ±1 px. A4; phase 0.25.',size=17)
    for i,gaze in enumerate(((-23,-17),(23,-17),(-23,17),(23,17))):
        x=45+i*265
        a=avatar('listening',72,gaze=gaze,phase=.25,neutral=True)
        im.paste(a,(x,675))
        # Full-surface enlargement preserves the extreme position, including plate edge.
        im.paste(a.resize((144,144),Image.Resampling.NEAREST),(x+90,675))
        v.label(im,(x,835),str(gaze),size=14)
    v.label(im,(20,880),'Local geometry proof only: not combined facing/lean/foreshortening, scaling, or runtime constraints.',size=13)
    im.save(HERE/'containment.png')


def sheet_recognition():
    im=Image.new('RGB',(680,390),'#20252D')
    v.label(im,(20,15),'44 px recognition card | answer before reading the key',size=19)
    v.label(im,(20,46),'Proposed 1.25 framing. Static shape recognition; motion comprehension needs runtime.',size=13)
    order=['sleeping','idle','success','thinking','error']
    for row,bg in enumerate(('#15191F','#F2F3F5')):
        for i,state in enumerate(order):
            x=60+i*125;y=110+row*110
            im.paste(avatar(state,44,bg),(x,y))
            v.label(im,(x+18,y+55),str(i+1))
    v.label(im,(20,345),'Key and five-person test protocol: VALIDATION.md. No participant results claimed.',size=13)
    im.save(HERE/'recognition-44.png')


def sheet_motion():
    im=Image.new('RGB',(1120,840),'#20252D')
    v.label(im,(20,15),'Motion floors | current default 1.0 → proposed 1.25',size=22)
    v.label(im,(20,50),'44 px surface. Arrow lengths are exact pixel travel enlarged 20x; labels report native dp. Authored amplitudes unchanged.')
    rows=[('Breath root',11),('Host gaze X',23),('Host gaze Y',17),('Success hop',48),('Waiting bounce',19),('Error shake / settle',24),('Listening lean',14)]
    draw=ImageDraw.Draw(im)
    for i,(name,amp) in enumerate(rows):
        y=115+i*86
        v.label(im,(20,y),name,size=17)
        v.arrow(draw,(290,y+12),(290+20*amp*.088,y+12),'#A0A6AF')
        v.arrow(draw,(575,y+12),(575+20*amp*.11,y+12),'#79B7DF')
        v.label(im,(850,y),f'{amp*.088:.3f} → {amp*.11:.2f} dp',size=17)
    v.label(im,(290,85),'Current 1.0',size=15)
    v.label(im,(575,85),'Proposed 1.25',size=15)
    v.label(im,(20,740),'Restoring the framing meets the brief’s floors; it requires a runtime default change. This art PR does not change that default.')
    v.label(im,(20,773),'Breath period 6500 → 4600 ms; sleep 9000 → 6800 ms. Body inflate 1.06 → 1.03. See SPEC §10 and mesh recipe.')
    im.save(HERE/'motion-floors.png')


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--render',action='store_true')
    args=parser.parse_args()
    report=gate()
    if args.render:
        for render in (sheet_sizes,sheet_phase,sheet_containment,sheet_recognition,sheet_motion): render()
        (HERE/'metrics.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
