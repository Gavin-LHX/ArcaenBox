"""Exercise the APK's actual protected Xray path against a loopback Sudoku server."""
import hashlib
import http.server
import json
import socket
import subprocess
import threading
import time
import urllib.parse
import zipfile
from pathlib import Path


def check(ui):
    assert ui.adb('get-serialno').strip().startswith('emulator-'), 'Requires an isolated emulator'
    evidence_dir = ui.OUT
    import protocol_smoke as p
    ui.OUT = evidence_dir
    p.ui = ui
    p.OUT = ui.OUT / 'finalmask'
    p.OUT.mkdir(exist_ok=True)
    p.RESULTS = []
    out = p.OUT.resolve()
    archive = out / 'xray.zip'
    subprocess.run(['curl', '-fL', '--retry', '3', '--max-time', '180', '-o', str(archive),
                    'https://github.com/XTLS/Xray-core/releases/download/v26.3.27/Xray-linux-64.zip'], check=True)
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == '23cd9af937744d97776ee35ecad4972cf4b2109d1e0fe6be9930467608f7c8ae'
    with zipfile.ZipFile(archive) as z:
        (out / 'xray').write_bytes(z.read('xray'))
    (out / 'xray').chmod(0o700)
    mask = {'tcp': [{'type': 'sudoku', 'settings': {
        'password': 'ci-only-Sudoku-secret', 'ascii': 'prefer_ascii', 'paddingMin': 0, 'paddingMax': 3}}]}
    uuid = '00000000-0000-4000-8000-000000000001'
    config = {'log': {'loglevel': 'warning'}, 'inbounds': [{
        'listen': '127.0.0.1', 'port': 18095, 'protocol': 'vless',
        'settings': {'clients': [{'id': uuid}], 'decryption': 'none'},
        'streamSettings': {'network': 'tcp', 'security': 'none', 'finalmask': mask}
    }], 'outbounds': [{'protocol': 'freedom'}]}
    (out / 'server.json').write_text(json.dumps(config))

    class Origin(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header('Content-Length', str(len(p.PAYLOAD)))
            self.end_headers()
            self.wfile.write(p.PAYLOAD)
        def log_message(self, *args): pass

    origin = http.server.ThreadingHTTPServer(('127.0.0.1', 18888), Origin)
    threading.Thread(target=origin.serve_forever, daemon=True).start()
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(('127.0.0.1', 18889))
    udp.settimeout(1)
    stopped = threading.Event()
    def echo():
        while not stopped.is_set():
            try:
                data, address = udp.recvfrom(65535)
                udp.sendto(data, address)
            except socket.timeout: pass
    threading.Thread(target=echo, daemon=True).start()
    log = (out / 'server.log').open('wb')
    server = subprocess.Popen([str(out / 'xray'), 'run', '-c', str(out / 'server.json')], stdout=log, stderr=subprocess.STDOUT)
    name = 'FinalMask-Sudoku-TCP'
    try:
        time.sleep(1)
        assert server.poll() is None, 'Sudoku test server exited'
        ui.launch(); ui.navigate('nav_configuration')
        uri = f'vless://{uuid}@10.0.2.2:18095?' + urllib.parse.urlencode({
            'type': 'tcp', 'security': 'none', 'encryption': 'none', 'fm': json.dumps(mask, separators=(',', ':'))})
        p.import_uri(uri, name)
        ui.adb('shell', 'am', 'force-stop', ui.PACKAGE)
        ui.launch(); ui.navigate('nav_configuration')
        p.edit_profile(name)
        ui.tap(ui.scroll_for(text='FinalMask · Sudoku (TCP)', expand_categories=False))
        editor = ui.wait_for(resource_id='android:id/edit')
        assert json.loads(editor.get('text')) == mask, 'Database/editor lost FinalMask settings'
        ui.tap(ui.wait_for(resource_id='android:id/button2'))
        p.save()
        p.traffic(name, 'libxray.so', True)
        # Restart validates process cleanup and reload from stored profile data.
        p.traffic(name, 'libxray.so', False)
        (out / 'results.json').write_text(json.dumps(p.RESULTS, indent=2))
    finally:
        ui.adb('shell', 'am', 'force-stop', ui.PACKAGE)
        server.terminate()
        try: server.wait(timeout=5)
        except subprocess.TimeoutExpired: server.kill(); server.wait()
        log.close(); origin.shutdown(); origin.server_close()
        stopped.set(); time.sleep(1.1); udp.close()
