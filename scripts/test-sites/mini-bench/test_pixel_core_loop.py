"""Check the reused pixel oracle detects paint loss using independent images."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from PIL import Image


class PixelTest(unittest.TestCase):
    def test_stable_ink_and_one_frame_paint_loss(self):
        for missing in (False, True):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                video = root/'frames'
                video.mkdir()
                ink = Image.new('RGB',(64,64),'white')
                for x in range(8,56):
                    for y in range(8,56):
                        if x % 4 < 2 and y % 4 < 2:
                            ink.putpixel((x,y),(0,0,0))
                frames = []
                for index, seconds in enumerate((0,1,1.5,1.8,2,2.5,3)):
                    filename = f'{index}.png'
                    image = Image.new('RGB',(64,64),'white') if missing and seconds == 2.5 else ink
                    image.save(video/filename)
                    frames.append(dict(file=filename,seconds=seconds))
                (video/'frames.json').write_text(json.dumps(frames))
                result = subprocess.run(['python3', str(Path(__file__).with_name('analyze-settle.py')),
                    str(video),str(root/'analysis'),'--region','0,0,64,64','--settle-seconds','2',
                    '--post-seconds','1','--track-y','0'],capture_output=True,text=True)
                self.assertEqual(result.returncode,0,result.stderr)
                score = json.loads((root/'analysis'/'summary.json').read_text())
                self.assertAlmostEqual(score['minimum_template_correlation'],0 if missing else 1)
                self.assertAlmostEqual(score['dip_percent'],100 if missing else 0)
