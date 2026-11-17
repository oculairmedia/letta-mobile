"""Check the reused pixel oracle detects paint loss using independent images."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from PIL import Image


def make_frames(video, missing, contrast, partial):
    ink = Image.new('RGB',(64,64),'white')
    for x in range(8,56):
        for y in range(8,56):
            if x % 4 < 2 and y % 4 < 2:
                ink.putpixel((x,y),(0,0,0))
    frames = []
    for index, seconds in enumerate((0,1,1.5,1.8,2,2.5,3)):
        filename = f'{index}.png'
        image = Image.new('RGB',(64,64),'white') if missing and seconds == 2.5 else ink
        if not missing and seconds < 2:
            image = image.point(lambda value: 255 - contrast if value == 0 else value)
        if partial and seconds >= 2:
            image = image.copy()
            for x in range(32):
                for y in range(64):
                    image.putpixel((x,y),(255,255,255))
        image.save(video/filename)
        frames.append(dict(file=filename,seconds=seconds))
    return frames


class PixelTest(unittest.TestCase):
    def test_stable_ink_and_one_frame_paint_loss(self):
        for missing, contrast, partial in ((False, 255, False), (True, 255, False),
                                           (False, 100, False), (False, 100, True)):
            with self.subTest(missing=missing, contrast=contrast, partial=partial), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                video = root/'frames'
                video.mkdir()
                frames = make_frames(video, missing, contrast, partial)
                (video/'frames.json').write_text(json.dumps(frames))
                result = subprocess.run(['python3', str(Path(__file__).with_name('analyze-settle.py')),
                    str(video),str(root/'analysis'),'--region','0,0,64,64','--settle-seconds','2',
                    '--post-seconds','1','--track-y','0'],capture_output=True,text=True)
                self.assertEqual(result.returncode,0,result.stderr)
                score = json.loads((root/'analysis'/'summary.json').read_text())
                self.assertAlmostEqual(score['minimum_template_correlation'],0 if missing else 0.5 ** 0.5 if partial else 1)
                self.assertAlmostEqual(score['dip_percent'],100 if missing else 100 * (1 - 255 / contrast * (0.5 if partial else 1)))
