"""Promote exact signed build artifacts into an existing draft, without replacing assets."""
import hashlib
import json
import os
import subprocess
from pathlib import Path

repo = os.environ['GITHUB_REPOSITORY']
run_id, release_id = int(os.environ['SOURCE_RUN']), int(os.environ['RELEASE_ID'])

def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{repo}/{path}']))

run = api(f'actions/runs/{run_id}')
release = api(f'releases/{release_id}')
assert run['conclusion'] == 'success' and run['path'] == '.github/workflows/apk.yml'
assert release['draft'] and release['target_commitish'] == run['head_sha']
folder = Path('dist')
checksums = {}
for line in (folder/'SHA256SUMS.txt').read_text().splitlines():
    digest, name = line.split()
    assert name == Path(name).name and name.endswith('.apk') and name not in checksums
    checksums[name] = digest
assert len(checksums) == 4 and set(checksums) == {p.name for p in folder.glob('*.apk')}
for name, digest in checksums.items():
    assert hashlib.sha256((folder/name).read_bytes()).hexdigest() == digest
checksums['SHA256SUMS.txt'] = hashlib.sha256((folder/'SHA256SUMS.txt').read_bytes()).hexdigest()
for name, digest in checksums.items():
    current = api(f'releases/{release_id}')
    assert current['draft']
    existing = next((a for a in current['assets'] if a['name'] == name), None)
    if existing is None:
        subprocess.run(['gh', 'release', 'upload', release['tag_name'], str(folder/name)], check=True)
    else:
        assert existing['state'] == 'uploaded' and existing['digest'] == 'sha256:'+digest
        assert existing['size'] == (folder/name).stat().st_size
final = api(f'releases/{release_id}')
assert final['draft'] and {a['name'] for a in final['assets']} == set(checksums)
for asset in final['assets']:
    assert asset['state'] == 'uploaded' and asset['digest'] == 'sha256:'+checksums[asset['name']]
    assert asset['size'] == (folder/asset['name']).stat().st_size
print('Five exact assets verified; release remains a draft.')
