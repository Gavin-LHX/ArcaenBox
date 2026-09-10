"""Fetch checksum-pinned, isolated integration-test servers. Never shipped in APKs."""
import io
import tarfile
import zipfile
from pathlib import Path
from package_builtins import fetch

OUT = Path('core-build/protocol-tests')
OUT.mkdir(parents=True, exist_ok=True)
SERVERS = [
    ('snell4', 'https://dl.nssurge.com/snell/snell-server-v4.1.1-linux-amd64.zip',
     'cc2271b79c7506888b34e651e8741b3aa7fc7d5f60aa65ef8bb096f3313a193b', 'snell-server'),
    ('snell5', 'https://dl.nssurge.com/snell/snell-server-v5.0.1-linux-amd64.zip',
     '9bea1c2b9e35b73b31634856c04d18c393072b9e5dcde6a32781d8b8f908c539', 'snell-server'),
    ('trojan-go', 'https://github.com/p4gefau1t/trojan-go/releases/download/v0.10.6/trojan-go-linux-amd64.zip',
     '764480722783a6d76ed8401f6d2f1d87d8df7e60bf261f69c67eb94b77e732af', 'trojan-go'),
    ('mita', 'https://github.com/enfein/mieru/releases/download/v3.36.1/mita_3.36.1_linux_amd64.tar.gz',
     '8e6ae525bbcaa688a8446aebf3c2ecbcb4ce606838d23edfa3f7f2fad506a8f7', 'mita'),
    ('caddy', 'https://github.com/klzgrad/forwardproxy/releases/download/v2.11.2-naive/caddy-forwardproxy-naive.tar.xz',
     '19eccb7321dd877a5fb4a3dba6ef1b745185188b616c96cc6201f1a1fc0380a8', 'caddy-forwardproxy-naive/caddy'),
]

for name, url, digest, member in SERVERS:
    data = fetch({'url': url, 'sha256': digest}, OUT)
    if url.endswith('.zip'):
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            binary = z.read(member)
    else:
        with tarfile.open(fileobj=io.BytesIO(data)) as tar:
            binary = tar.extractfile(member).read()
    output = OUT / name
    output.write_bytes(binary)
    output.chmod(0o755)
    print('Prepared', name)

previous = fetch({
    'url': 'https://github.com/Gavin-LHX/ArcaenBox/releases/download/v1.4.2-arcaenbox.6/ArcaenBox-1.4.2-arcaenbox.6-x86_64.apk',
    'sha256': '29f356f7245df56d04c6501f1d7a966c9f28a213613e99850e543cd9787e8223'
}, OUT)
(OUT / 'previous.apk').write_bytes(previous)
