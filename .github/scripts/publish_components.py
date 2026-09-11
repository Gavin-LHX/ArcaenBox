"""Publish immutable signed component channels without replacing the latest app release."""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from package_builtins import ROOT, check_elf, sha


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--directory', default='core-build/component-updates')
    p.add_argument('--target', required=True)
    args = p.parse_args()
    key = serialization.load_der_public_key((ROOT / 'app/src/main/assets/cores/public-key.der').read_bytes())
    for path in sorted(Path(args.directory).glob('*/*/manifest.json')):
        metadata = path.read_bytes()
        key.verify((path.parent / 'manifest.sig').read_bytes(), metadata, padding.PKCS1v15(), hashes.SHA256())
        m = json.loads(metadata)
        tag = f'component-{m["component"]}-{m["channel"]}-r{m["revision"]}'
        for abi, asset in m['assets'].items():
            data = (path.parent / asset['name']).read_bytes()
            check_elf(data, abi)
            assert len(data) == asset['size'] and sha(data) == asset['sha256']
        existing = subprocess.run(['gh', 'release', 'view', tag, '--json', 'tagName'], capture_output=True)
        if existing.returncode == 0:
            # A revision is immutable, even if rebuilding the same source changes compiler output.
            with tempfile.TemporaryDirectory() as temp:
                subprocess.run(['gh', 'release', 'download', tag, '-p', 'manifest.json', '-D', temp], check=True)
                assert Path(temp, 'manifest.json').read_bytes() == metadata, f'{tag}: bump revision before replacing bytes'
            print(f'{tag}: already published'); continue
        notes = path.parent / 'release-notes.md'
        notes.write_text(f'{m["component"]}: {m["version"]}\n\nChannel: {m["channel"]}. Update in ArcaenBox → Kernel manager.\n\n'
                         f'Source: {m["source"]}\n\nSigned manifest and four Android architectures. Contract 1.\n', encoding='utf-8')
        files = [str(path), str(path.parent / 'manifest.sig')] + [str(path.parent / a['name']) for a in m['assets'].values()]
        cmd = ['gh', 'release', 'create', tag, *files, '--target', args.target, '--latest=false',
               '--title', f'{m["component"]} {m["version"]} ({m["channel"]})', '--notes-file', str(notes)]
        if m['channel'] == 'preview': cmd.append('--prerelease')
        subprocess.run(cmd, check=True)


if __name__ == '__main__':
    main()
