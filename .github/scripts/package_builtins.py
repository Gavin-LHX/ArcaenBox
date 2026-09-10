"""Package pinned protocol executables into every APK ABI; never install plugin APKs."""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LOCK = ROOT / 'buildScript/builtins/versions.json'
ABIS = {'arm64-v8a': ('arm64', 183, 'aarch64-linux-android'),
        'armeabi-v7a': ('arm', 40, 'armv7a-linux-androideabi'),
        'x86': ('386', 3, 'i686-linux-android'),
        'x86_64': ('amd64', 62, 'x86_64-linux-android')}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fetch(item, cache):
    path = cache / item['sha256']
    if not path.is_file() or sha(path.read_bytes()) != item['sha256']:
        part = path.with_suffix('.part')
        subprocess.run(['curl', '--fail', '--location', '--retry', '3', '--connect-timeout', '15',
                        '--max-time', '180', '--output', str(part), item['url']], check=True)
        if sha(part.read_bytes()) != item['sha256']:
            raise ValueError(f"Checksum mismatch: {item['url']}")
        part.replace(path)
    return path.read_bytes()


def check_elf(data, abi):
    assert data[:4] == b'\x7fELF', f'{abi}: not ELF'
    assert data[5] == 1 and struct.unpack_from('<H', data, 18)[0] == ABIS[abi][1], f'{abi}: wrong architecture'
    assert struct.unpack_from('<H', data, 16)[0] == 3, f'{abi}: executable must be PIE'


def verify_apk(apk):
    with zipfile.ZipFile(apk) as z:
        manifest = json.loads(z.read('assets/builtins/manifest.json'))
        abis = {p.split('/')[1] for p in z.namelist() if p.startswith('lib/')}
        assert len(abis) == 1, f'Unexpected ABI split: {abis}'
        abi = abis.pop()
        for component in manifest['components']:
            name = component['library']
            data = z.read(f'lib/{abi}/{name}')
            check_elf(data, abi)
            assert sha(data) == component['sha256'][abi], f'{name}: packaged bytes differ'
        print(f'{apk}: all four built-in executables verified ({abi})')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify-apk', type=Path)
    parser.add_argument('--prebuilt-only', action='store_true')
    args = parser.parse_args()
    if args.verify_apk:
        return verify_apk(args.verify_apk)
    lock = json.loads(LOCK.read_text())
    cache = ROOT / 'core-build/builtins/cache'
    cache.mkdir(parents=True, exist_ok=True)
    target = ROOT / 'app/src/main/jniLibs'
    manifest = {'components': []}
    for component in lock['components']:
        entry = {k: component[k] for k in ('name', 'version', 'library', 'source', 'license')}
        entry['sha256'] = {}
        for abi, asset in component['assets'].items():
            data = fetch(asset, cache)
            if asset['url'].endswith('.apk'):
                with zipfile.ZipFile(io.BytesIO(data)) as z:
                    data = z.read(f"lib/{abi}/{component['library']}")
            else:
                data = gzip.decompress(data)
            check_elf(data, abi)
            output = target / abi / component['library']
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(data)
            entry['sha256'][abi] = sha(data)
        manifest['components'].append(entry)
    if args.prebuilt_only:
        return
    mieru = lock['mieru']
    source = ROOT / 'core-build/builtins/source'
    source.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(fetch(mieru, cache)), mode='r:gz') as tar:
        tar.extractall(source, filter='data')
    checkout = source / ('mieru-' + mieru['commit'])
    ndk = Path(os.environ['ANDROID_NDK_HOME']) / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    entry = {k: mieru[k] for k in ('name', 'version', 'library', 'source', 'license')}
    entry['sha256'] = {}
    for abi, (arch, _, triple) in ABIS.items():
        output = target / abi / 'libmieru.so'
        env = {**os.environ, 'GOOS': 'android', 'GOARCH': arch, 'GOARM': '7', 'CGO_ENABLED': '1',
               'CC': str(ndk / (triple + '21-clang'))}
        subprocess.run(['go', 'build', '-trimpath', '-buildmode=pie', '-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384',
                        '-o', str(output), './cmd/mieru'], cwd=checkout, env=env, check=True)
        data = output.read_bytes()
        check_elf(data, abi)
        entry['sha256'][abi] = sha(data)
    manifest['components'].append(entry)
    output = ROOT / 'app/src/main/assets/builtins/manifest.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    # Keep exact upstream source and module dependency identities with release evidence.
    shutil.copy(LOCK, ROOT / 'core-build/builtins/versions.json')
    print('Packaged Trojan-Go, NaiveProxy, Mieru and Snell for all four ABIs')


if __name__ == '__main__':
    main()
