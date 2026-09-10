"""Check the alpha and decoded artwork in release APKs, including AAPT PNG optimization."""
import sys
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile
from PIL import Image

source = Image.open('app/src/main/res/drawable-nodpi/arcaenbox_artwork.png').convert('RGBA')
assert source.getchannel('A').getextrema() == (0, 255), 'Icon must contain real transparency'
assert source.getpixel((0, 0))[3] == 0, 'Opaque icon background'
with ZipFile(sys.argv[1]) as apk:
    for name in apk.namelist():
        if not name.endswith('.png'):
            continue
        candidate = Image.open(BytesIO(apk.read(name))).convert('RGBA')
        if candidate.size == source.size and candidate.tobytes() == source.tobytes():
            print('Verified transparent artwork:', name)
            break
    else:
        raise AssertionError('Transparent ArcaenBox artwork missing from APK')
