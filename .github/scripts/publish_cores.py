"""Publish already tested and signed core packages; never overwrite an existing release."""
import argparse, json, subprocess
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from hashlib import sha256

p=argparse.ArgumentParser()
p.add_argument('--target',required=True)
p.add_argument('--directory',default='core-build/dist')
a=p.parse_args()
public=serialization.load_der_public_key(Path('app/src/main/assets/cores/public-key.der').read_bytes())
for channel in ['stable','preview']:
    directory=Path(a.directory)/channel
    content=(directory/'manifest.json').read_bytes()
    public.verify((directory/'manifest.sig').read_bytes(),content,padding.PKCS1v15(),hashes.SHA256())
    manifest=json.loads(content)
    assert manifest['channel']==channel
    assets=[directory/'manifest.json',directory/'manifest.sig']
    for info in manifest['assets'].values():
        file=directory/info['name']
        assert file.parent==directory and file.stat().st_size==info['size'] and sha256(file.read_bytes()).hexdigest()==info['sha256']
        assets.append(file)
    tag=f"core-{channel}-{manifest['version']}-{manifest['revision']}"
    notes=directory/'release-notes.md'
    notes.write_text(f"ArcaenBox sing-box {manifest['version']} ({channel}), Android adaptation r{manifest['revision']}.\n\n"
                     "Install or switch from About → sing-box updates and channels in ArcaenBox. These native libraries are not standalone APKs.\n\n"
                     f"JNI bridge: `{manifest['bridge']}`. Manifest is signed by the ArcaenBox release key; the app verifies signature, ABI, size and SHA-256 before loading.\n\n"
                     "Source and pinned upstream commits: buildScript/lib/core/get_source_env.sh; compatibility patches: buildScript/lib/core/patches/.\n",encoding='utf-8')
    command=['gh','release','create',tag,*map(str,assets),'--target',a.target,'--title',f"sing-box {manifest['version']} · ArcaenBox r{manifest['revision']}",'--notes-file',str(notes),'--latest=false']
    if channel=='preview': command.append('--prerelease')
    subprocess.run(command,check=True)
