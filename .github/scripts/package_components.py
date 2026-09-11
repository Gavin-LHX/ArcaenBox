"""Sign standalone protocol-core updates; the APK and updates contain identical bytes."""
import argparse
import json
import os
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import pkcs12
from package_builtins import ABIS, ROOT, check_elf, sha


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--keystore', required=True)
    args = p.parse_args()
    key, cert, _ = pkcs12.load_key_and_certificates(Path(args.keystore).read_bytes(), os.environ['KEYSTORE_PASS'].encode())
    public = cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    assert public == (ROOT / 'app/src/main/assets/cores/public-key.der').read_bytes()
    bundled = json.loads((ROOT / 'app/src/main/assets/builtins/manifest.json').read_text())
    for entry in bundled['components']:
        component, channel, revision = entry['id'], entry['channel'], entry['revision']
        destination = ROOT / 'core-build/component-updates' / component / channel
        destination.mkdir(parents=True, exist_ok=True)
        manifest = dict(schema=1, contract=1, component=component, channel=channel, version=entry['version'],
                        revision=revision, min_sdk=entry['min_sdk'], protocols=entry['protocols'],
                        source=entry['source'], license=entry['license'], assets={})
        for abi in ABIS:
            data = (ROOT / 'app/src/main/jniLibs' / abi / entry['library']).read_bytes()
            check_elf(data, abi)
            assert sha(data) == entry['sha256'][abi]
            name = f'ArcaenBox-{component}-{channel}-r{revision}-{abi}.so'
            (destination / name).write_bytes(data)
            manifest['assets'][abi] = dict(name=name, size=len(data), sha256=sha(data))
        metadata = json.dumps(manifest, sort_keys=True, separators=(',', ':')).encode()
        (destination / 'manifest.json').write_bytes(metadata)
        (destination / 'manifest.sig').write_bytes(key.sign(metadata, padding.PKCS1v15(), hashes.SHA256()))
        print(f'{component}/{channel}: {entry["version"]}, r{revision}')


if __name__ == '__main__':
    main()
