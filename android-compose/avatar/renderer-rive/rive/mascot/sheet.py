"""Contact sheet from CLI screenshots, for reviewing motion frame by frame.

    python sheet.py out.png <cols> <dir> name1 name2 ...   # reads <dir>/<name>.png

Each cell is the artboard's centre crop (50,30)-(450,430) on a dark ground with its name in
the corner. Review the sheet, not the individual frames: a fade, a pop or a collision shows
up as a difference between neighbours. Needs Pillow.
"""
import sys

from PIL import Image, ImageDraw

out, cols, src, names = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4:]
ims = []
for n in names:
    im = Image.open(f"{src}/{n}.png").convert("RGBA")
    bg = Image.new("RGBA", im.size, (12, 12, 12, 255))
    bg.alpha_composite(im)
    im = bg.convert("RGB").crop((50, 30, 450, 430))
    ImageDraw.Draw(im).text((8, 8), n, fill=(220, 220, 220))
    ims.append(im)
w, h = ims[0].size
rows = (len(ims) + cols - 1) // cols
sheet = Image.new("RGB", (w * cols, h * rows), (12, 12, 12))
for i, im in enumerate(ims):
    sheet.paste(im, ((i % cols) * w, (i // cols) * h))
sheet.resize((300 * cols, 300 * rows)).save(out)
print("wrote", out)
