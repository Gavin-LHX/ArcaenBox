"""Restore pinned, signed component bytes for an app-only build."""
import argparse
import json
import shutil
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from package_builtins import ROOT, LOCK, ABIS, check_elf, sha


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text())
    public = serialization.load_der_public_key((ROOT/'app/src/main/assets/cores/public-key.der').read_bytes())
    identities = {'libnaive.so': ('naive', 'stable', 24, []),
                  'libtrojan-go.so': ('trojan-go', 'stable', 21, []),
                  'libmieru.so': ('mieru', 'stable', 21, []),
                  'libmihomo.so': ('snell', 'stable', 21, [4, 5]),
                  'libsnell.so': ('snell', 'preview', 21, [4, 5, 6])}
    manifest = {'components': []}
    for pinned in [*lock['components'], lock['mieru'], lock['snell_preview']]:
        component, channel, sdk, protocols = identities[pinned['library']]
        revision = lock['revisions'][f'{component}:{channel}']
        folder = args.directory/component/channel
        data = (folder/'manifest.json').read_bytes()
        public.verify((folder/'manifest.sig').read_bytes(), data, padding.PKCS1v15(), hashes.SHA256())
        signed = json.loads(data)
        expected = dict(schema=1, contract=1, component=component, channel=channel,
                        revision=revision, min_sdk=sdk, protocols=protocols,
                        version=pinned['version'], source=pinned['source'], license=pinned['license'])
        for key, value in expected.items():
            assert signed[key] == value, f'{component}/{channel}: pinned {key} mismatch'
        assert set(signed['assets']) == set(ABIS)
        entry = {key: pinned[key] for key in ('name', 'version', 'library', 'source', 'license')}
        entry.update(id=component, channel=channel, min_sdk=sdk, protocols=protocols, revision=revision, sha256={})
        for abi, asset in signed['assets'].items():
            assert Path(asset['name']).name == asset['name']
            binary = (folder/asset['name']).read_bytes()
            assert len(binary) == asset['size'] and sha(binary) == asset['sha256']
            check_elf(binary, abi)
            target = ROOT/'app/src/main/jniLibs'/abi/pinned['library']
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(binary)
            entry['sha256'][abi] = sha(binary)
        manifest['components'].append(entry)
        print(f'Restored signed {component}/{channel} {pinned["version"]}, r{revision}')
    output = ROOT/'app/src/main/assets/builtins/manifest.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2)+'\n')
    evidence = ROOT/'core-build/builtins'
    evidence.mkdir(parents=True, exist_ok=True)
    shutil.copy(LOCK, evidence/'versions.json')


if __name__ == '__main__':
    main()
