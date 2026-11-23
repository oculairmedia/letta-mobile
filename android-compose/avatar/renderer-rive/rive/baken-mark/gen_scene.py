"""Generator for rive/baken-mark/scene.rml - the animated Baken logomark (03 Basalt Planes).

Two stones on a flat ground: the anchor (left, tall) and the pebble (the beacon, right). They
pivot on their ground contacts and never leave the ground for long; the only lights are painted
on the stones or glow inside the seam between them. The mark owns app and connection states only
(`BakenMarkState`); every turn activity belongs to the mascots (`mascot.riv`).

Structure:
  BakenMark (artboard 500x500, view model `BrandMark`, state machine `BrandMark`)
    rig/__init__.py the path bootstrap onto the mascot rig's shared tools (rig.layers, rig.seams)
    rig/ids.py      every named Rive object id, frozen
    rig/stones.py   the drawing: Base > Lean > Event > Pose > Tone per stone, the seam light
    rig/motion.py   every animation, from the tables in rig/constants.py
    rig/machine.py  the five layers: Theme, Pose, Light, Event, Hover

The checks, run from this directory (README.md, "The loop"):
    test_rig.py       python -m unittest test_rig       no CLI: parity, references, reduceMotion
    test_contract.py  python -m unittest test_contract  no CLI: the shipped .riv is rig/contract.py
    test_probe.py     python -m unittest test_probe     Rive CLI: goldens, reduceMotion telemetry

This file only assembles: the artboard shell, the data section (enums, view model) and the
document, plus the two throwaway variants (`--solo`, `--probe`). See README.md for the loop.

    python gen_scene.py                              # writes scene.rml
    python gen_scene.py out.rml                      # writes elsewhere (the parity check)
    python gen_scene.py out.rml --solo Success       # a Solo machine playing one animation
    python gen_scene.py out.rml --probe              # telemetry binds (probe.py)
    python gen_scene.py out.rml --backdrop FFEFF3F6  # a painted ground (review.py)
"""
import os
import re
import sys
from textwrap import indent

import rig  # noqa: F401 - path bootstrap: the mascot's shared tools
from rml import VM, anim_state, layer_frame

from rig.constants import DEFAULT_STATE, DEFAULT_THEME
from rig.contract import ARTBOARD, BRAND_MARK, VIEW_MODEL
from rig.ids import (ENUM_STATE, ENUM_THEME, REVIEW_BACKDROP, ROOT, SM, SOLO_LAYER, SOLO_SM, SOLO_STATE, STATES, STYLE,
                     THEMES, VM_BOOT, VM_HOVER, VM_INSTANCE, VM_REDUCE, VM_STATE, VM_SUCCESS, VM_THEME,
                     state_enum_ids, theme_enum_ids)
from rig.layers import set_animation_index
from rig.machine import root_machine
from rig.motion import all_animations
from rig.seams import animation_index
from rig.stones import drawing

HERE = os.path.dirname(os.path.abspath(__file__))

# Contract name -> view-model property id. Order and types come from rig/contract.py.
VM_IDS = {"state": VM_STATE, "theme": VM_THEME, "boot": VM_BOOT, "success": VM_SUCCESS,
          "reduceMotion": VM_REDUCE, "hovered": VM_HOVER}
ENUM_OF = {"BakenMarkState": ENUM_STATE, "BakenMarkTheme": ENUM_THEME}

_VM_TYPE = {"boolean": "ViewModelPropertyBoolean", "trigger": "ViewModelPropertyTrigger"}


def _animation_id(document, name):
    m = re.search(r'<LinearAnimation[^>]*\bname="%s" id="([^"]+)"' % re.escape(name), document)
    if not m:
        raise SystemExit(f"no animation named {name!r} in the generated document")
    return m.group(1)


def _solo_machine(anim_id):
    layer = layer_frame("Solo", SOLO_LAYER, SOLO_STATE, "", anim_state(anim_id, SOLO_STATE, 0))
    return f'<StateMachine name="Solo" id="{SOLO_SM}">\n{indent(layer, "    ")}\n</StateMachine>'


def _backdrop(argb):
    """A review-only ground (review.py): the theme's background painted behind everything, so the
    CLI composites the translucent lights on it the way the app will. Never in scene.rml."""
    return (f'<Shape x="250" y="250" name="ReviewBackdrop" id="{REVIEW_BACKDROP}">\n'
            f'    <Rectangle width="500" height="500" name="Rect"/>\n'
            f'    <Fill name="Fill"><SolidColor colorValue="{argb}" name="Color"/></Fill>\n</Shape>')


def artboard(solo=None, backdrop=None):
    anims = all_animations()
    # The blend policy: every layer below validates against what these animations really key.
    set_animation_index(animation_index("\n".join(anims)))
    board = f'''<Artboard defaultStateMachineId="{SM}" viewModelId="{VM}" viewModelInstanceId="{VM_INSTANCE}"
          x="0" y="0" styleId="{STYLE}" clip="false" width="500" height="500" name="{ARTBOARD}" id="{ROOT}">
    <LayoutComponentStyle name="Style" id="{STYLE}"/>
{indent(drawing() + (chr(10) + _backdrop(backdrop) if backdrop else ""), "    ")}

{indent(chr(10).join(anims), "    ")}

{indent(root_machine(), "    ")}
</Artboard>'''
    if not solo:
        return board
    machine = indent(_solo_machine(_animation_id(board, solo)), "    ")
    return board.replace(f'defaultStateMachineId="{SM}"', f'defaultStateMachineId="{SOLO_SM}"', 1) \
                .replace("\n</Artboard>", f"\n\n{machine}\n</Artboard>")


def _property(p):
    pid = VM_IDS[p.name]
    if p.type == "enum":
        return f'<ViewModelPropertyEnumCustom enumId="{ENUM_OF[p.range]}" name="{p.name}" id="{pid}"/>'
    return f'<{_VM_TYPE[p.type]} name="{p.name}" id="{pid}"/>'


_DEFAULT_ENUM = {"state": state_enum_ids[DEFAULT_STATE], "theme": theme_enum_ids[DEFAULT_THEME]}


def _instance_value(p):
    pid = VM_IDS[p.name]
    if p.type == "enum":
        return f'<ViewModelInstanceEnum propertyValue="{_DEFAULT_ENUM[p.name]}" viewModelPropertyId="{pid}"/>'
    if p.type == "boolean":
        return f'<ViewModelInstanceBoolean propertyValue="false" viewModelPropertyId="{pid}"/>'
    return f'<ViewModelInstanceTrigger viewModelPropertyId="{pid}"/>'


def _enum(name, eid, keys, ids):
    values = "\n".join(f'    <DataEnumValue key="{k}" value="{k[0].upper() + k[1:]}" id="{ids[k]}"/>' for k in keys)
    return f'<DataEnumCustom name="{name}" id="{eid}">\n{values}\n</DataEnumCustom>'


def data():
    """The two enums and the BrandMark view model (with its default instance)."""
    props = "\n".join(_property(p) for p in BRAND_MARK)
    values = "\n".join(_instance_value(p) for p in BRAND_MARK)
    return f'''{_enum("BakenMarkState", ENUM_STATE, STATES, state_enum_ids)}
{_enum("BakenMarkTheme", ENUM_THEME, THEMES, theme_enum_ids)}

<ViewModel defaultInstanceId="{VM_INSTANCE}" name="{VIEW_MODEL}" id="{VM}">
{indent(props, "    ")}

    <ViewModelInstance exports="true" name="Default" id="{VM_INSTANCE}">
{indent(values, "        ")}
    </ViewModelInstance>
</ViewModel>'''


def scene_document(solo=None, backdrop=None):
    return f'''<Rive version="1" kind="fragment">
    <!--
        The Baken mark, 03 Basalt Planes, from BAKEN_MARK_RIVE_SPEC.md and the brand kit's art
        through gen_scene.py. Generated: never hand-edit; change rig/ and regenerate.

        The host writes, on the BrandMark view model (rig/contract.py is the full list):
          state        - BakenMarkState: idle loading connecting degraded ambient
          theme        - BakenMarkTheme: dark light monoDark monoLight
          boot         - trigger: the splash entrance (fire before the first advance)
          success      - trigger: the peer-lock settle
          reduceMotion - boolean: static partings and brightness only
          hovered      - boolean: the lean perk
    -->
{indent(artboard(solo, backdrop), "    ")}

{indent(data(), "    ")}
</Rive>
'''


def main(argv):
    argv, solo = list(argv), None
    if "--solo" in argv:
        i = argv.index("--solo")
        if i + 1 >= len(argv):
            raise SystemExit("--solo needs an animation name")
        solo = argv[i + 1]
        del argv[i:i + 2]
    backdrop = None
    if "--backdrop" in argv:
        i = argv.index("--backdrop")
        backdrop = argv[i + 1]
        del argv[i:i + 2]
    probe = "--probe" in argv
    if probe:
        argv.remove("--probe")
    if (solo or probe or backdrop) and not argv:
        raise SystemExit("--solo, --probe and --backdrop write a throwaway document: pass its output path")
    doc = scene_document(solo, backdrop)
    if probe:
        from rig.probe import inject
        doc = inject(doc)
    out = argv[0] if argv else os.path.join(HERE, "scene.rml")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(doc)
    print(f"wrote {out} ({doc.count(chr(10))} lines)")


if __name__ == "__main__":
    main(sys.argv[1:])
