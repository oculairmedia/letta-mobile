"""Stand-in for GNU make, covering only what premake5_pls_renderer.lua asks for on Windows:
`make -C <shaders dir> -jN OUT=<dir> FLAGS="<minify flags>" d3d`.
Mirrors the minify + d3d rules in renderer/src/shaders/Makefile."""
import glob, os, shlex, subprocess, sys

def _newest_fxc():
    found = sorted(glob.glob(r"C:\Program Files (x86)\Windows Kits\10\bin\10.*\x64\fxc.exe"))
    if not found:
        sys.exit("make_shim: fxc.exe not found; install the Windows 10/11 SDK")
    return found[-1]

FXC = _newest_fxc()

args = sys.argv[1:]
cwd, out, flags, targets = os.getcwd(), None, "", []
i = 0
while i < len(args):
    a = args[i]
    if a == "-C":
        cwd = args[i + 1]; i += 2; continue
    if a.startswith("-j"):
        i += 1; continue
    if a.startswith("OUT="):
        out = a[4:]
    elif a.startswith("FLAGS="):
        flags = a[6:]
    else:
        targets.append(a)
    i += 1

os.chdir(cwd)
unknown = [t for t in targets if t not in ("d3d", "minify")]
if unknown:
    sys.exit(f"make_shim: unsupported targets {unknown}")

def run(cmd):
    print(" ".join(cmd), flush=True)
    subprocess.check_call(cmd)

os.makedirs(out, exist_ok=True)
inputs = sorted(glob.glob("*.glsl") + glob.glob("*.vert") + glob.glob("*.frag"))
run([sys.executable, "minify.py", *shlex.split(flags), "-o", out, *inputs])

if "d3d" in targets:
    d3d_out = os.path.join(out, "d3d")
    os.makedirs(d3d_out, exist_ok=True)
    run([FXC, "/nologo", "/I", out, "/T", "rootsig_1_1", "/E", "ROOT_SIG", "/Fh", os.path.join(d3d_out, "root.sig.h"), "d3d/root.sig"])
    for hlsl in sorted(glob.glob("d3d/*.hlsl")):
        stem = os.path.splitext(os.path.basename(hlsl))[0]
        run([FXC, "/nologo", "/D", "VERTEX", "/I", out, "/T", "vs_5_0", "/Fh", os.path.join(d3d_out, stem + ".vert.h"), hlsl])
        # The Makefile marks render_atlas.frag.h .PHONY, so the pattern rule never builds it;
        # only its stroke/fill variants below exist.
        if stem != "render_atlas":
            run([FXC, "/nologo", "/D", "FRAGMENT", "/I", out, "/T", "ps_5_0", "/Fh", os.path.join(d3d_out, stem + ".frag.h"), hlsl])
    for variant, define in (("stroke", "ATLAS_FEATHERED_STROKE"), ("fill", "ATLAS_FEATHERED_FILL")):
        run([FXC, "/nologo", "/D", "FRAGMENT", "/D", define, "/I", out, "/T", "ps_5_0", "/Fh",
             os.path.join(d3d_out, f"render_atlas_{variant}.frag.h"), "d3d/render_atlas.hlsl"])
