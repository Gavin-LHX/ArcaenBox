"""Real proxy traffic through the installed APK, with isolated loopback-only servers."""
import http.server
import json
import os
import re
import shlex
import socket
import struct
import subprocess
import threading
import tempfile
import time
import traceback
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path
import ui_smoke as ui

OUT = Path('protocol-smoke'); OUT.mkdir(exist_ok=True)
BIN = Path('core-build/protocol-tests').resolve()
ui.OUT = OUT
ui.STRINGS.update({e.get('name'): ''.join(e.itertext()) for e in ET.parse('app/src/main/res/values/builtin_protocols.xml').getroot() if e.tag == 'string'})
P = ui.PACKAGE
PROCESSES = []
SOCKET_DIR = tempfile.TemporaryDirectory(prefix='arcaenbox-protocol-')
RESULTS = []
FAILURES = []
SECRET = 'arcaenbox-ci-only'
PAYLOAD = b'arcaenbox-built-in-protocol-proof'


def server(name, args, env=None):
    log = (OUT / (name+'.log')).open('wb')
    process = subprocess.Popen(args, env={**os.environ, **(env or {})}, stdout=log, stderr=subprocess.STDOUT)
    PROCESSES.append((process, log))
    time.sleep(.8)
    assert process.poll() is None, f'{name} exited: see server log'


def setup_servers():
    class Origin(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200); self.send_header('Content-Length',str(len(PAYLOAD))); self.end_headers(); self.wfile.write(PAYLOAD)
        def log_message(self,*args): pass
    origin=http.server.ThreadingHTTPServer(('127.0.0.1',18888),Origin)
    threading.Thread(target=origin.serve_forever,daemon=True).start()
    subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(OUT/'key.pem'),
                    '-out',str(OUT/'cert.pem'),'-days','2','-subj','/CN=localhost',
                    '-addext','subjectAltName=DNS:localhost'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    cert = str((OUT/'cert.pem').resolve()); key = str((OUT/'key.pem').resolve())
    for version, port in [(4,18084),(5,18085)]:
        config=OUT/f'snell{version}.conf'
        config.write_text(f'[snell-server]\nlisten = 127.0.0.1:{port}\npsk = {SECRET}\nipv6 = false\n')
        server(f'snell{version}',[str(BIN/f'snell{version}'),'-c',str(config)])
    for mode,port in [('default',18091),('unshaped',18092),('unsafe-raw',18093)]:
        config=OUT/f'snell6-{mode}.conf'
        config.write_text(f'[snell-server]\nlisten = 127.0.0.1:{port}\npsk = {SECRET}\nmode = {mode}\n')
        server(f'snell6-{mode}',[str(BIN/'snell6'),'-c',str(config)])
    for name, port, extra in [('trojan',18086,{}),('trojan-ws-ss',18087,{
        'websocket':{'enabled':True,'path':'/test','host':'localhost'},
        'shadowsocks':{'enabled':True,'method':'AES-128-GCM','password':SECRET}})]:
        config=OUT/(name+'.json')
        config.write_text(json.dumps({'run_type':'server','local_addr':'127.0.0.1','local_port':port,
            'remote_addr':'127.0.0.1','remote_port':18888,'password':[SECRET],
            'ssl':{'cert':cert,'key':key},**extra}))
        server(name,[str(BIN/'trojan-go'),'-config',str(config)])
    config=OUT/'mita.json'
    config.write_text(json.dumps({'portBindings':[{'port':18088,'protocol':'TCP'},{'port':18089,'protocol':'UDP'}],
                                 # The test origin is loopback-only. Mita rejects loopback
                                 # destinations unless this isolated test user permits them.
                                 'users':[{'name':'test','password':SECRET,'allowLoopbackIP':True}],'loggingLevel':'INFO'}))
    server('mieru',[str(BIN/'mita'),'run'],{'MITA_CONFIG_JSON_FILE':str(config.resolve()),'MITA_UDS_PATH':str(Path(SOCKET_DIR.name)/'mita.sock'),'MITA_INSECURE_UDS':'1'})
    config=OUT/'Caddyfile'
    site=OUT/'site'; site.mkdir(exist_ok=True)
    (site/'index.html').write_text('<html><body>ArcaenBox integration test</body></html>')
    config.write_text('''{
    admin off
    persist_config off
    auto_https off
    order forward_proxy before file_server
}
:18090 {
    bind 127.0.0.1
    tls CERT KEY
    forward_proxy {
        basic_auth test SECRET
        hide_ip
        hide_via
        acl {
            allow 127.0.0.1/32
            deny all
        }
    }
    file_server {
        root SITE
    }
}
'''.replace('CERT',cert).replace('KEY',key).replace('SECRET',SECRET).replace('SITE',str(site.resolve())))
    server('naive',[str(BIN/'caddy'),'run','--config',str(config),'--adapter','caddyfile'],
           {'XDG_CONFIG_HOME':SOCKET_DIR.name+'/config','XDG_DATA_HOME':SOCKET_DIR.name+'/data'})
    def echo():
        sock=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); sock.bind(('127.0.0.1',18889))
        while True:
            data,addr=sock.recvfrom(65535); sock.sendto(data,addr)
    threading.Thread(target=echo,daemon=True).start()


def edit_value(label, value):
    ui.tap(ui.scroll_for(text=ui.STRINGS.get(label,label)))
    custom=ui.find(ui.tree(),text=ui.STRINGS['test_preset_custom'])
    if custom is None and ui.find(ui.tree(),resource_id=P+':id/select_dialog_listview') is not None:
        custom=ui.scroll_for(text=ui.STRINGS['test_preset_custom'])
    if custom is not None: ui.tap(custom)
    field=ui.wait_for(resource_id='android:id/edit')
    ui.adb('shell','input','keyevent','KEYCODE_MOVE_END')
    for _ in field.get('text',''): ui.adb('shell','input','keyevent','KEYCODE_DEL')
    ui.adb('shell','input','text',shlex.quote(value))
    ui.tap(ui.wait_for(resource_id='android:id/button1'))


def save():
    ui.adb('shell','input','keyevent','BACK') if ui.find(ui.tree(),resource_id='android:id/edit') is not None else None
    ui.tap(ui.wait_for(resource_id=P+':id/action_apply'))
    ui.wait_for(resource_id=P+':id/profile_name')


def create_mieru(name, protocol, port):
    ui.launch()
    ui.tap(ui.wait_for(resource_id=P+':id/action_add'))
    ui.tap(ui.scroll_for(text=ui.STRINGS['add_profile_methods_manual_settings']))
    ui.tap(ui.scroll_for(text='Mieru'))
    ui.wait_for(text=ui.STRINGS['server_address'])
    for label,value in [('profile_name',name),('server_address','10.0.2.2'),('server_port',str(port)),
                        ('username','test'),('password',SECRET)]:
        edit_value(label,value)
    if protocol=='UDP':
        ui.tap(ui.scroll_for(text=ui.STRINGS['protocol']))
        ui.tap(ui.wait_for(text='UDP'))
    save()


def import_uri(uri, name):
    ui.launch()
    ui.adb('shell','am','start','-W','-a','android.intent.action.VIEW','-d',shlex.quote(uri+'#'+name),'-p',P)
    ui.tap(ui.wait_for(resource_id='android:id/button1'))
    ui.wait_for(text=name)


def edit_profile(name):
    node=ui.scroll_for(text=name)
    doc=ui.tree(); parents={child:parent for parent in doc.iter() for child in parent}
    row=ui.find(doc,text=name)
    while row in parents:
        edit=ui.find(row,resource_id=P+':id/node_actions')
        if edit is not None:
            ui.tap(edit); ui.tap(ui.wait_for(text=ui.STRINGS['edit'])); return
        row=parents[row]
    raise AssertionError('Profile edit button missing: '+name)


def start_profile(name):
    ui.launch()
    ui.adb('shell','appops','set',P,'ACTIVATE_VPN','allow')
    ui.tap(ui.scroll_for(text=name))
    button=ui.wait_for(resource_id=P+':id/fab',enabled='true')
    ui.assert_connect_button_visible(button)
    ui.tap(button)
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        if re.search(r'\btun\d+:',ui.adb('shell','ip','-o','link','show')):
            time.sleep(2); return button
        time.sleep(.5)
    raise AssertionError(name+': no TUN')


def read_exact(sock, count):
    result=b''
    while len(result)<count:
        data=sock.recv(count-len(result)); assert data, 'Unexpected EOF from SOCKS'
        result+=data
    return result


def socks(command, port):
    sock=socket.create_connection(('127.0.0.1',12080),timeout=15)
    sock.sendall(b'\x05\x01\x00'); assert read_exact(sock,2)==b'\x05\x00'
    sock.sendall(bytes([5,command,0,1])+socket.inet_aton('127.0.0.1' if command==1 else '0.0.0.0')+struct.pack('!H',port))
    reply=read_exact(sock,4); assert reply[1]==0, f'SOCKS command {command} failed: {reply.hex()}'
    size={1:4,4:16}.get(reply[3])
    if size is None: size=read_exact(sock,1)[0]
    read_exact(sock,size)
    bound=struct.unpack('!H',read_exact(sock,2))[0]
    return sock,bound


def traffic(name, library, udp=False, downloaded=False):
    button=start_profile(name)
    ui.adb('forward','tcp:12080','tcp:2080')
    try:
        last=None
        for _ in range(3):
            try:
                client,_=socks(1,18888)
                with client:
                    client.sendall(b'GET / HTTP/1.0\r\nHost: localhost\r\n\r\n')
                    body=b''
                    while True:
                        data=client.recv(65536)
                        if not data: break
                        body+=data
                assert PAYLOAD in body, body
                last=None; break
            except Exception as error: last=error; time.sleep(2)
        if last: raise last
        processes=ui.adb('shell','ps','-A','-o','PID,ARGS')
        rows=[line for line in processes.splitlines() if library in line]
        assert rows, 'Built-in process missing: '+library
        # Android ps can display just the process name for ARGS. Inspect the
        # executable symlink instead of assuming argv[0] contains a full path.
        executables=[ui.adb('shell','readlink','/proc/'+line.split()[0]+'/exe').strip() for line in rows]
        (OUT/(name+'-processes.txt')).write_text('\n'.join(rows+executables))
        if downloaded:
            loaders={ui.adb('shell','readlink','-f',p).strip() for p in ['/system/bin/linker','/system/bin/linker64']}
            assert all(exe in loaders for exe in executables), executables
            for row in rows:
                maps=ui.adb('shell','cat','/proc/'+row.split()[0]+'/maps')
                assert '/no_backup/components/packages/' in maps and '/libcomponent.so' in maps, maps
                (OUT/(name+'-maps.txt')).write_text(maps)
        else:
            assert all(exe.startswith('/data/app/') and exe.endswith('/'+library) for exe in executables), executables
        if udp:
            control,port=socks(3,0)
            with control:
                datagram=b'\0\0\0\x01'+socket.inet_aton('127.0.0.1')+struct.pack('!H',18889)+PAYLOAD
                packet=OUT/'udp.bin'; packet.write_bytes(datagram)
                ui.adb('push',str(packet),'/data/local/tmp/arcaenbox-udp.bin')
                response=ui.adb('exec-out','sh','-c',f'toybox nc -u -w 5 -W 5 -q 5 127.0.0.1 {port} < /data/local/tmp/arcaenbox-udp.bin',binary=True)
                assert response.endswith(PAYLOAD), 'UDP relay returned no echo: '+response.hex()
        (OUT/(name+'.png')).write_bytes(ui.adb('exec-out','screencap','-p',binary=True))
        RESULTS.append({'profile':name,'tcp':True,'udp':udp,'library':library})
        print('PASS traffic:',name,flush=True)
    finally:
        ui.tap(button)
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            interfaces=ui.adb('shell','ip','-o','link','show')
            processes=ui.adb('shell','ps','-A','-o','PID,ARGS')
            if not re.search(r'\btun\d+:',interfaces) and library not in processes: break
            time.sleep(.5)
        else: raise AssertionError(name+': component or TUN leaked after stop')


def check_traffic(name,lib,udp):
    try:
        traffic(name,lib,udp)
    except Exception:
        FAILURES.append(name)
        (OUT/(name+'-failure.txt')).write_text(traceback.format_exc())
        (OUT/(name+'-failure-logcat.txt')).write_text(ui.adb('shell','logcat','-d',check=False))
        (OUT/(name+'-core.log')).write_text(ui.adb('shell','cat','/data/user/0/'+P+'/cache/neko.log',check=False))
        try: ui.capture(name+'-failure')
        except Exception: pass
        print('FAIL traffic:',name,traceback.format_exc(),flush=True)
        ui.adb('shell','am','force-stop',P)


def main():
    try:
        setup_servers()
        ui.root_emulator()
        # A fresh emulator may still show its boot lock screen. Keep this isolated
        # test device awake while exercising long-running protocol transfers.
        ui.adb('shell','svc','power','stayon','true')
        ui.adb('shell','input','keyevent','KEYCODE_WAKEUP')
        ui.adb('shell','wm','dismiss-keyguard')
        if os.environ.get('PROTOCOL_SMOKE_SCOPE') in ('naive','preview'):
            ui.adb('install','-r','-g',str(next(Path('dist').glob('*x86_64*.apk'))))
            ui.launch(); ui.navigate('nav_settings')
            ui.tap(ui.scroll_for(text=ui.STRINGS['log_level'])); ui.tap(ui.wait_for(text='debug'))
            if os.environ['PROTOCOL_SMOKE_SCOPE']=='naive':
                cert=urllib.parse.quote((OUT/'cert.pem').read_text(),safe='')
                import_uri(f'naive+https://test:{SECRET}@10.0.2.2:18090?sni=localhost&cert={cert}','NaiveProxy')
                check_traffic('NaiveProxy','libnaive.so',False)
            else:
                import_uri(f'snell://{SECRET}@10.0.2.2:18085?version=5&udp=true','Snell-v5')
                ui.open_core(); ui.tap(ui.scroll_for(resource_id=P+':id/core_preview'))
                ui.tap(ui.scroll_for(resource_id=P+':id/core_apply',enabled='true'))
                ui.tap(ui.wait_for(resource_id='android:id/button1')); time.sleep(4)
                state=json.loads(ui.adb('shell','cat','/data/user/0/'+P+'/no_backup/cores/state.json'))
                assert state['channel']=='preview',state
                (OUT/'preview-core-state.json').write_text(json.dumps(state))
                check_traffic('Snell-v5','libmihomo.so',True)
            assert not FAILURES,FAILURES
            return
        ui.adb('install','-r','-g',str(BIN/'previous.apk'))
        ui.adb('shell','logcat','-c')
        ui.adb('shell','cmd','uimode','night','no')
        packages=ui.adb('shell','pm','list','packages')
        (OUT/'installed-packages.txt').write_text(packages)
        assert not any(prefix in packages for prefix in ('io.nekohasekai.sagernet.plugin.', 'moe.matsuri.exe.')), 'Emulator unexpectedly has proxy plugins'
        # Save an existing Mieru node in the released database schema before upgrading.
        create_mieru('Mieru-TCP','TCP',18088)
        ui.adb('shell','am','force-stop',P)
        apk=next(Path('dist').glob('*x86_64*.apk'))
        ui.adb('install','-r','-g',str(apk))
        ui.launch(); ui.wait_for(text='Mieru-TCP'); ui.capture('upgrade-preserved-mieru')
        create_mieru('Mieru-UDP','UDP',18089)
        if os.environ.get('PROTOCOL_SMOKE_SCOPE')=='mieru':
            for name in ['Mieru-TCP','Mieru-UDP']:
                check_traffic(name,'libmieru.so',True)
            assert not FAILURES, FAILURES
            print('MIERU SMOKE PASSED',flush=True)
            return
        for version in [4,5]:
            import_uri(f'snell://{SECRET}@10.0.2.2:{18080+version}?version={version}&udp=true',f'Snell-v{version}')
        edit_profile('Snell-v5')
        ui.tap(ui.scroll_for(text=ui.STRINGS['snell_version']))
        ui.tap(ui.wait_for(text='v4')); save()
        edit_profile('Snell-v5')
        assert ui.find(ui.tree(),text='v4') is not None
        ui.tap(ui.scroll_for(text=ui.STRINGS['snell_version']))
        ui.tap(ui.wait_for(text='v5')); ui.capture('snell-v5-selector'); save()
        for name,port,extra in [('Trojan-Go',18086,''),('Trojan-Go-WS-SS',18087,'&type=ws&host=localhost&path=%2Ftest&encryption=ss%3BAES-128-GCM%3A'+SECRET)]:
            import_uri(f'trojan-go://{SECRET}@10.0.2.2:{port}?sni=localhost'+extra,name)
            edit_profile(name); ui.tap(ui.scroll_for(text=ui.STRINGS['allow_insecure'])); save()
        cert=urllib.parse.quote((OUT/'cert.pem').read_text(),safe='')
        import_uri(f'naive+https://test:{SECRET}@10.0.2.2:18090?sni=localhost&cert={cert}','NaiveProxy')
        for name,lib,udp in [('Snell-v4','libmihomo.so',True),('Snell-v5','libmihomo.so',True),
                             ('Trojan-Go','libtrojan-go.so',True),('Trojan-Go-WS-SS','libtrojan-go.so',False),
                             ('Mieru-TCP','libmieru.so',True),('Mieru-UDP','libmieru.so',True),
                             ('NaiveProxy','libnaive.so',False)]:
            check_traffic(name,lib,udp)
        ui.launch(); ui.navigate('nav_kernels')
        ui.tap(ui.scroll_for(text_contains='Snell'))
        ui.tap(ui.scroll_for(resource_id=P+':id/core_preview'))
        ui.tap(ui.scroll_for(resource_id=P+':id/core_apply'))
        ui.tap(ui.wait_for(resource_id='android:id/button1'))
        ui.wait_for(text=ui.STRINGS['kernel_applied'])
        for mode,port in [('default',18091),('unshaped',18092),('unsafe-raw',18093)]:
            name='Snell-v6-'+mode
            import_uri(f'snell://{SECRET}@10.0.2.2:{port}?version=6&udp=true&reuse=true&mode={mode}',name)
            check_traffic(name,'libsnell.so',True)
        ui.launch(); ui.navigate('nav_kernels')
        ui.tap(ui.scroll_for(text_contains='Snell'))
        ui.tap(ui.scroll_for(resource_id=P+':id/core_restore',enabled='true'))
        ui.tap(ui.wait_for(resource_id='android:id/button1'))
        ui.wait_for(text=ui.STRINGS['kernel_applied'])
        ui.launch(); ui.open_core()
        ui.tap(ui.scroll_for(resource_id=P+':id/core_preview'))
        ui.tap(ui.scroll_for(resource_id=P+':id/core_apply'))
        ui.tap(ui.wait_for(resource_id='android:id/button1')); time.sleep(4)
        state=json.loads(ui.adb('shell','cat','/data/user/0/'+P+'/no_backup/cores/state.json'))
        assert state['channel']=='preview', state
        (OUT/'preview-core-state.json').write_text(json.dumps(state))
        check_traffic('Snell-v5','libmihomo.so',True)
        assert not FAILURES, 'Failed protocols: '+', '.join(FAILURES)
        print('PROTOCOL SMOKE PASSED',flush=True)
    except Exception as error:
        (OUT/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        (OUT/'results.json').write_text(json.dumps(RESULTS,indent=2))
        (OUT/'logcat.txt').write_text(ui.adb('shell','logcat','-d',check=False))
        try:
            (OUT/'last-screen.png').write_bytes(ui.adb('exec-out','screencap','-p',binary=True,check=False))
            (OUT/'last-screen.xml').write_text(ET.tostring(ui.tree(),encoding='unicode'))
            (OUT/'activities.txt').write_text(ui.adb('shell','dumpsys','activity','activities',check=False))
        except Exception: pass
        for process,log in PROCESSES:
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill()
            log.close()


if __name__=='__main__': main()
