#!/usr/bin/env python3
"""Static art proof, not a Rive renderer. Run with --render to refresh PNGs.

Requires Python 3, numpy, Pillow, CairoSVG. Reads svgpath.py in memory; never
opens a Rive scene, invokes Rive, or writes RML. Font falls back to Pillow's.
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
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).resolve().parent
ART = HERE.parent
PROJECT = ART.parent
REPO = next(p for p in PROJECT.parents if (p / '.git').exists())
BASE = 'c6bea8dd780169cf2e57d670da50ea2987b78234'
REL = PROJECT.relative_to(REPO).as_posix()
STATES = ['idle','listening','thinking','waitingInput','speaking','error',
          'sleeping','loading','failed','degraded','success','error-flash','dragged']
SHAPES = ['circle','blob','squircle','pill','triangle','hexagon','cloud','drop']
MOUTHS = ['closed','half','open','o','frown']
EXPECTED = {f'body-{s}.svg' for s in SHAPES} | {
    f'glyph-{s}{tail}.svg' for s in STATES + ['mouth-'+m for m in MOUTHS]
    for tail in ('','-small')}
SPEC = (PROJECT / 'SPEC.md').read_text()
ROWS = [[c.strip() for c in line.strip('|').split('|')]
        for line in SPEC.splitlines() if line.startswith('| ')]
loader = importlib.util.spec_from_file_location('svgpath', PROJECT / 'svgpath.py')
svgpath = importlib.util.module_from_spec(loader)
loader.loader.exec_module(svgpath)


def old_file(name):
    return subprocess.check_output(['git','show',f'{BASE}:{REL}/{name}'], cwd=REPO)


def node(name, old=False):
    raw = old_file('art/'+name) if old else (ART / name).read_bytes()
    return ET.fromstring(raw)[0]


def content(name, old=False):
    return ET.tostring(node(name,old), encoding='unicode').replace('ns0:','').replace(':ns0','')


def number(text):
    return float(re.search(r'[-+]?\d+(?:\.\d+)?', text.replace('−','-'))[0])


AMP = {r[0]: (float(r[2]),float(r[3])) for r in ROWS
       if len(r)==7 and r[0] in ('Breath root y','Gaze lookX max','Gaze lookY max',
          'Success hop peak','Waiting bounce','Error shake','Error settle','Listening lean')}


def samples(d, steps=101):
    """Cubic boundary samples, including endpoints, suitable for swept bounds."""
    output = []
    for sp in svgpath.parse_path(d):
        verts = sp['verts']
        count = len(verts) if sp['closed'] else len(verts)-1
        part = []
        for i in range(count):
            a, b = verts[i], verts[(i+1) % len(verts)]
            p0,p3 = np.array(a[:2]),np.array(b[:2])
            p1 = np.array(a[3]) if a[3] else p0
            p2 = np.array(b[2]) if b[2] else p3
            t = np.linspace(0,1,steps)[:,None]
            part.extend((1-t)**3*p0 + 3*(1-t)**2*t*p1 + 3*(1-t)*t*t*p2 + t**3*p3)
        output.append(np.array(part))
    return output


def signature(name):
    keys = []
    for row in ROWS:
        if len(row)==6 and row[0]==name and row[1].isdigit():
            target = row[4].split('→')[-1].split('/')
            keys.append((int(row[1]),number(target[0]),number(target[1]),
                         tuple(float(x) for x in row[5].split())))
            assert abs(float(row[2]) - int(row[1])/(300 if name=='listening' else 800)*100)<.0001
    assert len(keys)==(4 if name=='listening' else 6)
    return keys


def gate():
    # Two Max assets are proposals, not additions to the active state set.
    proposals = {'glyph-working.svg', 'glyph-working-small.svg'}
    supplied = {p.name for p in ART.glob('*.svg')}
    assert supplied == EXPECTED or supplied == EXPECTED | proposals
    baseline_names = set(subprocess.check_output(
        ['git','ls-tree','--name-only',BASE,f'{REL}/art/'],cwd=REPO,text=True).splitlines())
    assert {f'{REL}/art/{n}' for n in EXPECTED} <= baseline_names
    report = {'baseline':BASE, 'svg_count':len(supplied),
              'proposal_only_svg_count':len(supplied - EXPECTED),
              'max_appendix_gate':'art/validation/max/validate_max.py'}
    for file in sorted(ART.glob('*.svg')):
        root = ET.parse(file).getroot()
        body = file.name.startswith('body-')
        assert root.tag=='{http://www.w3.org/2000/svg}svg'
        assert root.attrib=={'viewBox':'-180 -180 360 360' if body else '-50 -50 100 100'}
        assert len(root)==1 and root[0].tag=='{http://www.w3.org/2000/svg}path'
        a = root[0].attrib
        assert set(a) <= {'d','fill','stroke','stroke-width','stroke-linecap','stroke-linejoin','fill-rule'}
        assert {c for c in re.findall('[A-Za-z]',a['d'])} <= set('MLCZ')
        assert len({a[k] for k in ('fill','stroke') if k in a and a[k]!='none'})==1
        assert all(a[k] in ('#111111','#000000','none') for k in ('fill','stroke') if k in a)
        points = np.concatenate(samples(a['d']))
        pad = float(a.get('stroke-width',0))/2
        assert np.min(points-pad)>=(-180 if body else -50)
        assert np.max(points+pad)<=(180 if body else 50)
        if body:
            assert re.findall('[A-Z]',a['d'])==['M']+['C']*8+['Z']
            assert len(svgpath.body_vertices(file))==8
            assert file.read_bytes()==old_file('art/'+file.name)
        else:
            # Fable's actual path converter must accept every supplied file.
            # Parse its returned XML in memory; do not serialize an .rml artifact.
            ET.fromstring(svgpath.path_rml(file,'ValidationOnly','999:1'))
        raster = Image.open(BytesIO(cairosvg.svg2png(url=str(file),output_width=100,output_height=100)))
        assert (raster.getchannel('A').getbbox() is None)==('mouth-closed' in file.name)
    for tail in ('','-small'):
        assert node(f'glyph-thinking{tail}.svg').get('d') != node(f'glyph-dragged{tail}.svg').get('d')
        for key in ('thinking','dragged'):
            d=node(f'glyph-{key}{tail}.svg').get('d')
            assert re.findall('[A-Z]',d)==['M']+['C']*6+['Z']
            assert len(svgpath.parse_path(d)[0]['verts'])==6
        assert node(f'glyph-idle{tail}.svg').get('d') != node(f'glyph-speaking{tail}.svg').get('d')
        for key in MOUTHS:
            p = ART / f'glyph-mouth-{key}{tail}.svg'
            assert p.read_bytes()==old_file('art/'+p.name)
        poses=[]
        for key in ('closed','half','open'):
            p = ART / f'glyph-mouth-{key}{tail}.svg'
            assert re.findall('[A-Z]',node(p.name).get('d'))==['M']+['C']*4+['Z']
            assert len(svgpath.mouth_vertices(p))==4
            verts=svgpath.parse_path(node(p.name).get('d'))[0]['verts']
            points=np.array([v[:2] for v in verts])
            assert points[0,0]>0 and points[2,0]<0
            assert points[1,1]>=0 and points[3,1]<=0
            poses.append(points)
        for a in np.linspace(0,1,41):
            i=0 if a<=.5 else 1
            q=2*a-i
            verts=(1-q)*poses[i]+q*poses[i+1]
            assert verts[0,0]>0 and verts[2,0]<0
            assert verts[1,1]>=0 and verts[3,1]<=0
    section8=SPEC.split('## 8.',1)[1].split('## 9.',1)[0].rstrip()+'\n'
    # Fable added the evolved blink row after the original art baseline.
    # Max preserves that implementation record as well as the rest of §§1–9.
    section8_base = '2e11692c21ddcc971e2acb1609c33794ed892118' if '## 10. Astra Max' in SPEC else BASE
    old8 = subprocess.check_output(['git','show',f'{section8_base}:{REL}/SPEC.md'],cwd=REPO).decode().split('## 8.',1)[1].split('## 9.',1)[0].rstrip()+'\n'
    assert section8==old8
    report['section8_baseline']=section8_base
    report['section8_sha256']=hashlib.sha256(section8.encode()).hexdigest()
    targets={'Breath root y':(1.2,1.5),'Gaze lookX max':(2.2,2.8),
       'Gaze lookY max':(1.6,2.2),'Success hop peak':(5,7),'Waiting bounce':(2,2.5),
       'Error shake':(2.5,3.5),'Error settle':(2.5,3.5),'Listening lean':(1.5,float('inf'))}
    assert AMP.keys()==targets.keys()
    report['amplitudes_44dp']={}
    for key,(old,new) in AMP.items():
        lo,hi=targets[key]
        assert old*.11<lo and lo<=new*.11<=hi
        row=next(r for r in ROWS if len(r)==7 and r[0]==key)
        assert abs(number(row[4])-new*.11)<1e-8
        report['amplitudes_44dp'][key]=round(new*.11,2)
    for key in ('listening','success'): signature(key)
    assert '| gaze max x/y | 0.22 / 0.165 dp | 2.53 / 1.87 dp | 4.14 / 3.06 dp |' in SPEC
    assert '| root breath peak from rest | 0 dp (pruned) | 1.21 dp | 1.98 dp |' in SPEC
    def size_values(key, column):
        row=next(r for r in ROWS if len(r)==4 and r[0]==key)
        return [float(x) for x in re.findall(r'\d+(?:\.\d+)?',row[column])]
    for col,mult,tail in ((1,.055,'-small'),(2,.11,''),(3,.18,'')):
        pieces=samples(node(f'glyph-waitingInput{tail}.svg').get('d'))
        diameters=[float(np.ptp(p[:,0]))*mult for p in pieces]
        assert np.allclose(size_values('waitingInput ring outer / hole diameter',col),diameters,atol=.005)
        points=np.concatenate(samples(node(f'glyph-speaking{tail}.svg').get('d')))
        assert np.allclose(size_values('speaking eye width×height',col),np.ptp(points,axis=0)*mult,atol=.005)
        stroke=float(node(f'glyph-failed{tail}.svg').get('stroke-width'))
        assert abs(size_values('failed X stroke',col)[0]-stroke*mult)<.005
        outer,inner=(36,16) if tail else (29,14)
        conservative=math.floor((outer-inner-math.sqrt(2)) * mult * 100)/100
        assert abs(size_values('waitingInput eye minimum radial ink width (off-centre hole; conservative bound)',col)[0]-conservative)<1e-8
    # State selection, all unchanged identity names, and exact external state keys.
    states=[r for r in ROWS if len(r)==8 and r[0] in STATES and r[1].startswith('glyph-')]
    assert len(states)==13 and all(r[2]=='body-blob.svg' for r in states)
    contract=(PROJECT.parents[1]/'src/commonMain/kotlin/com/letta/mobile/avatar/rive/RiveAvatarContract.kt').read_text()
    assert set(re.findall(r'AvatarState\.\w+ -> "(\w+)"',contract))==set(STATES[:10])
    # Rounded Card signed-distance test. Expand stroked centerlines by half-width.
    minimum=float('inf')
    for state in STATES:
        a=node(f'glyph-{state}.svg').attrib
        points=np.concatenate(samples(a['d']))
        scale={'listening':1.04,'waitingInput':1.06}.get(state,1)
        h,r=60*scale,27*scale
        pad=float(a.get('stroke-width',0))/2
        for x in (-AMP['Gaze lookX max'][1],0,AMP['Gaze lookX max'][1]):
            for y in (-AMP['Gaze lookY max'][1],0,AMP['Gaze lookY max'][1]):
                q=np.abs(points+[x,y])-(h-r)
                distance=np.linalg.norm(np.maximum(q,0),axis=1)+np.minimum(np.maximum(q[:,0],q[:,1]),0)-r
                minimum=min(minimum,float(np.min(-distance-pad)))
    assert minimum>=2, minimum
    report['minimum_gaze_ink_clearance_px']=round(minimum,3)
    minimum=500
    for shape in SHAPES:
        pts=np.concatenate(samples(node(f'body-{shape}.svg').get('d'),301))
        for angle in np.linspace(-11,11,221):
            c,s=math.cos(math.radians(angle)),math.sin(math.radians(angle))
            p=pts@np.array([[c,s],[-s,c]])
            for x in (-24,24):
                for y in (-48,24):
                    out=np.array([250,270])+1.25*(p+[x,y])
                    minimum=min(minimum,float(out.min()),float((500-out).min()))
    assert minimum>=2,minimum
    report['minimum_swept_body_fill_clearance_px']=round(minimum,3)
    report.update(purity='PASS',native_svg_converter='PASS',mouth_topology='PASS',
        body_identity='PASS',spec_numeric_floors='PASS',runtime='NOT RUN',
        blind_human_recognition='NOT RUN')
    return report


def svg_raster(body, size):
    svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 500 500">{body}</svg>'
    return Image.open(BytesIO(cairosvg.svg2png(bytestring=svg.encode(),output_width=size,output_height=size))).convert('RGBA')


def avatar(state='idle', size=72, bg='#15191F', color='#79B7DF', old=False,
           small=False, root=(0,0), face_y=None, gaze=(0,0), mouth=.5, angle=None,
           old_paint=None):
    tail='-small' if small else ''
    pscale={'listening':1.04,'waitingInput':1.06}.get(state,1)
    ps=(152 if small else 120)*pscale
    rad=(34 if small else 27)*pscale
    offset={'listening':-(3 if old else AMP['Listening lean'][1]),'waitingInput':-2,
            'error':4,'error-flash':4,'sleeping':4}.get(state,0) if face_y is None else face_y
    rot={'listening':-2,'thinking':-6,'error':5,'error-flash':5,'sleeping':3,'degraded':4}.get(state,0) if angle is None else angle
    d=node('body-blob.svg').get('d')
    xf=f'translate(250 270) scale(1.25) translate({root[0]} {root[1]})'
    # Paint illustration: SVG gradients exactly follow SPEC stops; feathered
    # strokes use Pillow Gaussian blur, explicitly not the Rive feather kernel.
    size_hi=size*4
    output=Image.new('RGBA',(size_hi,size_hi),bg)
    muted = old if old_paint is None else old_paint
    halo=(.02 if muted else .06) if state=='sleeping' else (0 if state=='failed' else (.04 if muted else .10))
    edge=.08 if muted else .14
    if not small:
        for width,feather,alpha,scale in [(12,10,halo,1.02),(8,4,edge,1)]:
            stroke=svg_raster(f'<g transform="{xf}"><path transform="scale({scale})" d="{d}" fill="none" stroke="{color}" stroke-width="{width}" opacity="{alpha}"/></g>',size_hi)
            output=Image.alpha_composite(output,stroke.filter(ImageFilter.GaussianBlur(feather*size_hi/500*1.25)))
    defs='''<defs><radialGradient id="shade" gradientUnits="userSpaceOnUse" cx="-40" cy="-65" r="251.2469"><stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset=".55" stop-color="#000" stop-opacity=".03137255"/><stop offset="1" stop-color="#000" stop-opacity=".2509804"/></radialGradient><radialGradient id="gloss" gradientUnits="userSpaceOnUse" cx="-65" cy="-85" r="183.8478"><stop offset="0" stop-color="#fff" stop-opacity=".3215686"/><stop offset=".45" stop-color="#fff" stop-opacity=".1411765"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>'''
    tint,opacity={'sleeping':('#000000',56/255),'failed':('#808080',102/255),
      'loading':('#000000',16/255),'error':('#000000',20/255),'error-flash':('#000000',20/255)}.get(state,('#000000',0))
    ink=content(f'glyph-{state}{tail}.svg',old)
    mouth_svg=''
    if state in ('speaking','dragged','waitingInput','error','error-flash'):
        key='o' if state=='waitingInput' else 'frown' if state in ('error','error-flash') else 'closed' if mouth==0 and state!='dragged' else 'half' if mouth<=.5 else 'open'
        mouth_svg=content(f'glyph-mouth-{key}{tail}.svg')
        rgb=np.array([int(color[i:i+2],16)/255 for i in (1,3,5)])
        linear=np.where(rgb<=.04045,rgb/12.92,((rgb+.055)/1.055)**2.4)
        if linear@np.array([.2126,.7152,.0722])<.18: mouth_svg=mouth_svg.replace('#111111','#F7F7F7')
        mouth_svg=f'<g transform="translate(0 {114 if small else 82})">{mouth_svg}</g>'
    art=f'''{defs}<g transform="{xf}"><path d="{d}" fill="{color}"/><path d="{d}" fill="url(#shade)"/><path d="{d}" fill="url(#gloss)"/><path d="{d}" fill="{tint}" opacity="{opacity}"/><g transform="translate(0 {-16+offset}) rotate({rot})"><rect x="{-ps/2}" y="{-ps/2}" width="{ps}" height="{ps}" rx="{rad}" fill="#F7F7F7"/><g transform="translate({gaze[0]} {gaze[1]})">{ink}</g>{mouth_svg}</g></g>'''
    output=Image.alpha_composite(output,svg_raster(art,size_hi))
    return output.resize((size,size),Image.Resampling.LANCZOS).convert('RGB')


def font(size=14):
    try: return ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',size)
    except OSError: return ImageFont.load_default()


def label(im, xy, text, fill='#CBD1D8', size=14):
    ImageDraw.Draw(im).text(xy,text,fill=fill,font=font(size))


def sheet_sizes():
    im=Image.new('RGB',(1510,1030),'#20252D')
    label(im,(20,15),'V1 | Shipped standard profile: exact 22 / 44 / 72 px; 120 px Card',size=20)
    label(im,(20,46),'Each cell: actual surface above; 4x nearest-neighbour eye crop below. View this PNG at 100% for the size test.')
    for col,state in enumerate(STATES): label(im,(82+col*109,80),state,size=12)
    y=108
    for row,(size,bg) in enumerate((n,b) for n in (22,44,72) for b in ('#15191F','#F2F3F5')):
        label(im,(8,y),f'{size}px',size=13)
        label(im,(8,y+18),'dark' if row%2==0 else 'light',size=11)
        for col,state in enumerate(STATES):
            x=80+col*109
            a=avatar(state,size,bg)
            im.paste(a,(x+(100-size)//2,y))
            off={'listening':-14,'waitingInput':-2,'error':4,'error-flash':4,'sleeping':4}.get(state,0)
            cy=(270+1.25*(-16+off))*size/500
            side=math.ceil(128*size/500*1.25)
            left=round(size/2-side/2); top=round(cy-side/2)
            crop=a.crop((left,top,left+side,top+side)).resize((side*4,side*4),Image.Resampling.NEAREST)
            im.paste(crop,(x+(100-side*4)//2,y+size+7))
        y += {22:95,44:140,72:200}[size]
    label(im,(20,1000),'100 px calibration:')
    ImageDraw.Draw(im).line((165,1009,265,1009),fill='white',width=2)
    im.save(HERE/'sizes-standard.png')
    im=Image.new('RGB',(1510,475),'#20252D')
    label(im,(20,15),'V1 | Pending small profile: exact 22 px + 4x full surface',size=20)
    label(im,(20,45),'152 px Card and -small paths. Requires Fable/app size signal; this is not the currently shipped profile.')
    for row,bg in enumerate(('#15191F','#F2F3F5')):
        for col,state in enumerate(STATES):
            x=80+col*109;y=100+row*175
            label(im,(x,y-22),state,size=12)
            a=avatar(state,22,bg,small=True)
            im.paste(a,(x+38,y));im.paste(a.resize((88,88),Image.Resampling.NEAREST),(x+5,y+34))
    im.save(HERE/'sizes-small-proposal.png')


def arrow(draw,a,b,color,width=3):
    draw.line((a,b),fill=color,width=width)
    dx,dy=b[0]-a[0],b[1]-a[1];length=math.hypot(dx,dy)
    if length<1:return
    ux,uy=dx/length,dy/length
    tip=min(9,length*.6)
    draw.polygon([b,(b[0]-tip*ux+tip*.5*uy,b[1]-tip*uy-tip*.5*ux),
                  (b[0]-tip*ux-tip*.5*uy,b[1]-tip*uy+tip*.5*ux)],fill=color)


def sheet_motion():
    im=Image.new('RGB',(1080,2570),'#20252D')
    label(im,(20,12),'V2 | 500 x 500 frames; old grey / new blue travel',size=20)
    label(im,(20,44),'Facing neutral. Arrows start at rest. Arrow length = authored px x 1.25 display scale.')
    mapping=[('Breath root y','idle',(0,-1),'root'),('Gaze lookX max','idle',(1,0),'gaze'),
      ('Gaze lookY max','idle',(0,-1),'gaze'),('Success hop peak','success',(0,-1),'root'),
      ('Waiting bounce','waitingInput',(0,-1),'root'),('Error shake','error',(-1,0),'root'),
      ('Error settle','error',(0,1),'root'),('Listening lean','listening',(0,-1),'face')]
    for i,(key,state,direction,channel) in enumerate(mapping):
        x=20+(i%2)*540;y=95+(i//2)*615
        old,new=AMP[key];kwargs={}
        vector=(direction[0]*new,direction[1]*new)
        if channel=='root':kwargs['root']=vector
        elif channel=='gaze':kwargs['gaze']=vector
        else:kwargs['face_y']=-new
        frame=avatar(state,500,'#F2F3F5',**kwargs)
        draw=ImageDraw.Draw(frame)
        draw.rectangle((0,0,499,499),outline='#88929D',width=1)
        if channel=='gaze':origin=(250,250)
        elif channel=='face':origin=(360,254)
        else:origin=(70,270)
        for k,(value,c) in enumerate(((old,'#737D88'),(new,'#005EA8'))):
            start=(origin[0]+(20*k if direction[1] else 0),origin[1]+(20*k if direction[0] else 0))
            end=(start[0]+direction[0]*value*1.25,start[1]+direction[1]*value*1.25)
            arrow(draw,start,end,c,3)
        draw.line((45,270,115,270),fill='#939CA5',width=1)
        im.paste(frame,(x,y))
        label(im,(x,y+511),key,size=18)
        label(im,(x,y+539),f'OLD {old:g} px = {old*.11:.2f} dp    NEW {new:g} px = {new*.11:.2f} dp')
        label(im,(x,y+562),f'500 px frame | {channel} | peak from rest',size=12)
    im.save(HERE/'motion-amplitudes.png')


def sheet_materials():
    im=Image.new('RGB',(1080,660),'#20252D')
    label(im,(20,14),'P0 | Same paths / colour / stops. Halo 0.04 > 0.10; edge 0.08 > 0.14',size=19)
    label(im,(20,45),'Gaussian feather illustration; 44 / 72 px exact + 240 px detail. Ring stays off. Sleep halo 0.02 > 0.06.')
    for row,bg in enumerate(('#15191F','#F2F3F5')):
        for col,old in enumerate((True,False)):
            x=20+col*530;y=85+row*280
            label(im,(x,y),'BEFORE' if old else 'PATCH',size=16)
            for size,dx in ((44,0),(72,74),(240,182)):
                im.paste(avatar('idle',size,bg,old_paint=old),(x+dx,y+30))
    im.save(HERE/'materials.png')


def sheet_character():
    im=Image.new('RGB',(1120,485),'#20252D')
    label(im,(20,14),'V5 | Ten sustained states; same blob; new paths at exact 72 px',size=20)
    label(im,(20,46),'White plate, single dark eye. Mouth only on waitingInput / speaking / error. Bottom: path detail.')
    for col,state in enumerate(STATES[:10]):
        x=15+col*110
        label(im,(x,86),state,size=12)
        a=avatar(state,72)
        im.paste(a,(x+15,115))
        # Geometry alone exposes corner / arc craft without changing the size proof.
        ink=svg_raster(f'<rect width="500" height="500" fill="#F7F7F7"/><g transform="translate(250 250) scale(4)">{content(f"glyph-{state}.svg")}</g>',96).convert('RGB')
        im.paste(ink,(x+2,222))
    label(im,(20,351),'Held / flash distinction at 44 px: thinking     dragged      idle       speaking     success       sleeping')
    for i,state in enumerate(('thinking','dragged','idle','speaking','success','sleeping')):
        im.paste(avatar(state,44),(335+i*115,386))
    im.save(HERE/'character-freeze.png')


def sheet_signatures():
    im=Image.new('RGB',(1200,670),'#20252D')
    label(im,(20,14),'P2 | Two signatures: authored pose endpoints; 160 px proof thumbnails',size=20)
    for row,key in enumerate(('listening','success')):
        keys=signature(key)
        y=90+row*280
        label(im,(20,y-28),f'{key}: '+('300 ms' if key=='listening' else '800 ms + existing 120 ms live return'),size=18)
        for i,(t,value,rot,curve) in enumerate(keys):
            x=20+i*195
            kw={'face_y':value} if key=='listening' else {'root':(0,value)}
            im.paste(avatar(key,160,angle=rot,**kw),(x,y))
            label(im,(x,y+168),f'{t} ms | y={value:g} px',size=13)
            label(im,(x,y+190),' '.join(f'{n:g}' for n in curve),size=11)
    label(im,(20,638),'Static assembly omits Fable-owned facing, trails and blink shutter. Pose numbers and easing are in SPEC 9.4.')
    im.save(HERE/'signatures.png')


def sheet_mouths():
    im=Image.new('RGB',(1130,440),'#20252D')
    label(im,(20,14),'Mouth | Existing four-vertex morph; primary speech cue remains under plate',size=20)
    for row,color in enumerate(('#79B7DF','#192B40')):
        for col,(size,small,a) in enumerate([(22,False,0),(22,False,.5),(22,False,1),
                    (22,True,.5),(22,True,1),(44,False,.5),(44,False,1),(72,False,.5),(72,False,1)]):
            x=15+col*122;y=72+row*175
            m=avatar('speaking',size,color=color,small=small,mouth=a)
            im.paste(m,(x,y))
            if size==22:im.paste(m.resize((88,88),Image.Resampling.NEAREST),(x,y+28))
            label(im,(x,y+127),f'{size}px a={a:g}'+(' small' if small else ''),size=11)
    im.save(HERE/'mouths.png')


def sheet_recognition():
    im=Image.new('RGB',(720,360),'#20252D')
    label(im,(20,14),'V1 | Recognition card: 44 px, no enlargement',size=20)
    label(im,(20,45),'Name each expression: idle / thinking / success / error / sleeping.')
    label(im,(20,70),'No answer labels. Use 100% image size; record answers before reading VALIDATION.md.')
    orders=[['sleeping','error','idle','success','thinking'],
            ['thinking','idle','sleeping','error','success']]
    for row,order in enumerate(orders):
        bg='#15191F' if row==0 else '#F2F3F5'
        for col,state in enumerate(order):
            x=50+col*132;y=126+row*108
            im.paste(avatar(state,44,bg),(x,y))
            label(im,(x,y+53),f'{row*5+col+1}',size=13)
    im.save(HERE/'recognition-44.png')


if __name__=='__main__':
    arg=argparse.ArgumentParser()
    arg.add_argument('--render',action='store_true')
    args=arg.parse_args()
    result=gate()
    if args.render:
        for render in (sheet_sizes,sheet_motion,sheet_materials,sheet_character,sheet_signatures,sheet_mouths,sheet_recognition):
            render()
            print(f'Wrote and decoded {render.__name__}',flush=True)
        for p in HERE.glob('*.png'): Image.open(p).verify()
    print(json.dumps(result,indent=2))
