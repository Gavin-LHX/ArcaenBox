"""Build the same updater at an older version, solely for isolated upgrade tests.

Production APKs have already been copied to dist/. Never copy this fixture there
or publish it as a release. No update endpoint or application behavior is changed.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path


metadata = Path('nb4a.properties')
original = metadata.read_bytes()
destination = Path('updater-fixture')
destination.mkdir(exist_ok=True)
try:
    content = original.decode('utf-8')
    content, names = re.subn(r'^VERSION_NAME=.*$', 'VERSION_NAME=0.0.0-arcaenbox.1', content, flags=re.M)
    content, codes = re.subn(r'^VERSION_CODE=.*$', 'VERSION_CODE=1', content, flags=re.M)
    assert names == 1 and codes == 1
    metadata.write_text(content, encoding='utf-8')
    subprocess.run(['./gradlew', '--no-daemon', 'app:assembleOssRelease'], check=True)
    candidates = list(Path('app/build/outputs/apk/oss/release').glob('*0.0.0-arcaenbox.1*x86_64*.apk'))
    assert len(candidates) == 1, 'Expected one signed QA APK'
    target = destination / 'ArcaenBox-updater-QA-base-x86_64.apk'
    shutil.copy2(candidates[0], target)
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.1'
    subprocess.run([str(sdk / 'apksigner'), 'verify', '--verbose', str(target)], check=True)
    badging = subprocess.check_output([str(sdk / 'aapt'), 'dump', 'badging', str(target)], text=True)
    assert "package: name='com.arcaenbox.android' versionCode='5' versionName='0.0.0-arcaenbox.1'" in badging
    (destination / 'provenance.json').write_text(json.dumps({
        'source_commit': os.environ['GITHUB_SHA'],
        'purpose': 'Isolated in-app download and system-installer verification; never publish',
        'only_source_differences': {'VERSION_NAME': '0.0.0-arcaenbox.1', 'VERSION_CODE': 1},
        'apk': target.name, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
    }, indent=2) + '\n')
finally:
    metadata.write_bytes(original)
