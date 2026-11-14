"""Verify both bundled Android cores against Xray's current REALITY handshake."""
import hashlib
import http.server
import json
import re
import ssl
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
                        '-subj', '/CN=reality.test', '-addext', 'subjectAltName=DNS:reality.test',
                        '-keyout', str(root / 'tls.key'),
                        '-out', str(root / 'tls.crt')], check=True, capture_output=True)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_3
        tls.load_cert_chain(root / 'tls.crt', root / 'tls.key')
        tls.set_alpn_protocols(['http/1.1'])

        class TLSOrigin(http.server.ThreadingHTTPServer):
            def process_request_thread(self, request, address):
                try:
                    request.settimeout(5)
                    request = tls.wrap_socket(request, server_side=True)
                except OSError:
                    request.close()
                    return
                super().process_request_thread(request, address)

        fallback = cleanup.enter_context(TLSOrigin(('127.0.0.1', 0), Origin))
        threading.Thread(target=fallback.serve_forever, daemon=True).start()
        cleanup.callback(fallback.shutdown)
        key = X25519PrivateKey.generate()
        private_key = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw,
                                       serialization.NoEncryption())
        public_key = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        user, sid, flow = str(uuid.uuid4()), '0123456789abcdef', 'xtls-rprx-vision'
        origin = cleanup.enter_context(http.server.ThreadingHTTPServer(('127.0.0.1', 18888), Origin))
        threading.Thread(target=origin.serve_forever, daemon=True).start()
        cleanup.callback(origin.shutdown)
        target_port, reality_port = fallback.server_port, 18443
        config = {'log': {'loglevel': 'debug'}, 'inbounds': [{
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

        log = out / 'xray.log'
        server = start([str(root / 'xray'), 'run', '-c', str(root / 'xray.json')], log)
        time.sleep(1)
        assert server.poll() is None, 'REALITY fixture failed to start'
        cleanup.callback(ui.adb, 'forward', '--remove', 'tcp:12080', check=False)
        uri = f'vless://{user}@10.0.2.2:{reality_port}?' + urllib.parse.urlencode({
            'type': 'tcp', 'security': 'reality', 'encryption': 'none', 'flow': flow,
            'sni': 'reality.test', 'fp': 'chrome', 'pbk': encode(public_key), 'sid': sid})
        name = 'REALITY-MLKEM'
        ui.enable_debug_logs()
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
                assert b' 200 ' in response.split(b'\r\n', 1)[0] and p.PAYLOAD in response, (
                    f'{channel}: unexpected HTTP response {response[:140]!r}')
                with log.open('rb') as stream:
                    stream.seek(offset)
                    assert b'accepted tcp:127.0.0.1:18888' in stream.read(), 'Request did not traverse Xray'
                results.append({'channel': channel, 'core': version, 'server': '26.10.21', 'http_status': 200})
            finally:
                data = ui.adb('shell', 'cat', '/data/user/0/' + ui.PACKAGE + '/cache/neko.log', check=False)
                # Keep only the failure classification; never attach temporary profile credentials.
                (out / (channel + '-errors.txt')).write_text('\n'.join(
                    line for line in data.splitlines() if ('reality verification failed' in line or 'x509:' in line)))
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
