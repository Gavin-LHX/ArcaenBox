"""Verify both bundled Android cores against Xray's current REALITY handshake."""
import hashlib
import http.server
import json
import re
import subprocess
import threading
import time
import urllib.parse
import uuid
import zipfile
from base64 import urlsafe_b64encode
from contextlib import ExitStack
from tempfile import TemporaryDirectory
from pathlib import Path

def encode(data):
    return urlsafe_b64encode(data).decode().rstrip('=')


def check(ui):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

    assert ui.adb('get-serialno').strip().startswith('emulator-')
    original_out = ui.OUT
    import protocol_smoke as p
    ui.OUT = original_out
    p.ui = ui
    out = (ui.OUT / 'reality').resolve()
    out.mkdir(exist_ok=True)
    results = []

    class Origin(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header('Content-Length', str(len(p.PAYLOAD)))
            self.end_headers()
            self.wfile.write(p.PAYLOAD)

        def log_message(self, *_):
            pass

    with TemporaryDirectory(prefix='arcaenbox-reality-') as tmp, ExitStack() as cleanup:
        root = Path(tmp)
        archive = root / 'xray.zip'
        subprocess.run(['curl', '-fL', '--retry', '3', '--max-time', '180', '-o', str(archive),
                        'https://github.com/XTLS/Xray-core/releases/download/v26.10.21/Xray-linux-64.zip'], check=True)
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == '0eec3ed4163c273d8c8bf821f7d219e257921cf584aa3fb970715e458e631978'
        with zipfile.ZipFile(archive) as z:
            (root / 'xray').write_bytes(z.read('xray'))
        (root / 'xray').chmod(0o700)
        # Use a local TLS 1.3 fallback so authentication never depends on the internet.
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-subj', '/CN=reality.test', '-keyout', str(root / 'tls.key'),
                        '-out', str(root / 'tls.crt')], check=True, capture_output=True)
        key = X25519PrivateKey.generate()
        private_key = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw,
                                       serialization.NoEncryption())
        public_key = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        user, sid, flow = str(uuid.uuid4()), '0123456789abcdef', 'xtls-rprx-vision'
        origin = cleanup.enter_context(http.server.ThreadingHTTPServer(('127.0.0.1', 18888), Origin))
        threading.Thread(target=origin.serve_forever, daemon=True).start()
        cleanup.callback(origin.shutdown)
        target_port, reality_port = 18444, 18443
        config = {'log': {'loglevel': 'info'}, 'inbounds': [{
            'listen': '127.0.0.1', 'port': reality_port, 'protocol': 'vless',
            'settings': {'clients': [{'id': user, 'flow': flow}], 'decryption': 'none'},
            'streamSettings': {'network': 'raw', 'security': 'reality', 'realitySettings': {
                'target': f'127.0.0.1:{target_port}', 'serverNames': ['reality.test'],
                'privateKey': encode(private_key), 'shortIds': [sid]}}}],
            'outbounds': [{'protocol': 'freedom', 'settings': {'finalRules': [
                {'action': 'allow', 'ip': ['127.0.0.1/32'], 'port': '18888'}]}}]}
        (root / 'xray.json').write_text(json.dumps(config))

        def start(args, log):
            stream = cleanup.enter_context(log.open('wb'))
            proc = subprocess.Popen(args, stdout=stream, stderr=subprocess.STDOUT)

            def stop():
                if proc.poll() is None:
                    proc.terminate()
                    try: proc.wait(timeout=5)
                    except subprocess.TimeoutExpired: proc.kill(); proc.wait()
            cleanup.callback(stop)
            return proc

        fallback = start(['openssl', 's_server', '-accept', f'127.0.0.1:{target_port}', '-tls1_3',
                          '-cert', str(root / 'tls.crt'), '-key', str(root / 'tls.key'), '-www'], out / 'tls.log')
        log = out / 'xray.log'
        server = start([str(root / 'xray'), 'run', '-c', str(root / 'xray.json')], log)
        time.sleep(1)
        assert fallback.poll() is None and server.poll() is None, 'REALITY fixtures failed to start'
        cleanup.callback(ui.adb, 'forward', '--remove', 'tcp:12080', check=False)
        uri = f'vless://{user}@10.0.2.2:{reality_port}?' + urllib.parse.urlencode({
            'type': 'tcp', 'security': 'reality', 'encryption': 'none', 'flow': flow,
            'sni': 'reality.test', 'fp': 'chrome', 'pbk': encode(public_key), 'sid': sid})
        name = 'REALITY-MLKEM'
        p.import_uri(uri, name)
        for channel, version in [('stable', '1.14.0'), ('preview', '1.15.0-alpha.2')]:
            ui.launch()
            ui.open_core()
            if channel == 'preview':
                ui.tap(ui.scroll_for(resource_id=ui.PACKAGE + ':id/core_preview'))
                ui.tap(ui.scroll_for(resource_id=ui.PACKAGE + ':id/core_apply', enabled='true'))
                ui.tap(ui.wait_for(resource_id='android:id/button1'))
                time.sleep(4)
                ui.launch()
                ui.open_core()
            details = ui.wait_for(resource_id=ui.PACKAGE + ':id/core_details').get('text', '')
            assert f'{version} / r4' in details, details
            offset = log.stat().st_size
            button = p.start_profile(name)
            try:
                ui.adb('forward', 'tcp:12080', 'tcp:2080')
                with p.socks(1, 18888)[0] as client:
                    client.sendall(b'GET / HTTP/1.0\r\nHost: localhost\r\n\r\n')
                    with client.makefile('rb') as stream:
                        response = stream.read()
                assert response.startswith(b'HTTP/1.0 200') and p.PAYLOAD in response
                with log.open('rb') as stream:
                    stream.seek(offset)
                    assert b'accepted tcp:127.0.0.1:18888' in stream.read(), 'Request did not traverse Xray'
                results.append({'channel': channel, 'core': version, 'server': '26.10.21', 'http_status': 200})
            finally:
                ui.tap(button)
                for _ in range(30):
                    if not re.search(r'\btun\d+:', ui.adb('shell', 'ip', '-o', 'link', 'show')):
                        break
                    time.sleep(.5)
                else:
                    raise AssertionError('TUN still present after disconnect')
        ui.open_core()
        ui.tap(ui.scroll_for(resource_id=ui.PACKAGE + ':id/core_stable'))
        ui.tap(ui.scroll_for(resource_id=ui.PACKAGE + ':id/core_apply', enabled='true'))
        ui.tap(ui.wait_for(resource_id='android:id/button1'))
        time.sleep(4)
    (out / 'results.json').write_text(json.dumps(results, indent=2))
