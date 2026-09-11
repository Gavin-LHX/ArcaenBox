"""Exercise node selection, actual measurements, cancellation and advanced settings on Android."""
import json
import re
import socket
import sqlite3
import struct
import threading
import time
from pathlib import Path
import ui_smoke as ui
import protocol_smoke as protocol

P = ui.PACKAGE


def results():
    path = ui.OUT / 'node-tests-snapshot.db'
    path.write_bytes(ui.adb('exec-out', 'cat', '/data/user/0/'+P+'/databases/sager_net.db', binary=True))
    with sqlite3.connect(path) as db:
        rows = db.execute('SELECT profileId,kind,value,testedAt,error,transferred FROM node_test_results').fetchall()
    return {(row[0], row[1]): dict(zip(['profileId','kind','value','testedAt','error','transferred'],row)) for row in rows}


def start_test(key, targets, speed=False):
    ui.launch()
    ui.tap(ui.wait_for(resource_id=P+':id/action_misc'))
    ui.tap(ui.scroll_for(text=ui.STRINGS[key]))
    ui.wait_for(text=ui.STRINGS['node_test_toggle_all'])
    ui.tap(ui.wait_for(resource_id='android:id/button3'))
    for name in targets: ui.tap(ui.scroll_for(text_contains=name, checked='false'))
    ui.capture('node-selection-'+key)
    ui.tap(ui.wait_for(resource_id='android:id/button1'))
    if speed:
        ui.wait_for(text_contains='The total download limit')
        ui.capture('speed-test-limit-confirmation')
        ui.tap(ui.wait_for(resource_id='android:id/button1'))


def wait_results(kind, count, since, timeout=65):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            records = [r for r in results().values() if r['kind'] == kind and r['testedAt'] >= since]
            if len(records) >= count: return records
        except sqlite3.DatabaseError: pass
        time.sleep(.6)
    raise AssertionError('Node tests did not finish: '+kind)


def node_tests():
    stop = threading.Event()
    ntp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    ntp.bind(('127.0.0.1',18892)); ntp.settimeout(.3)
    ntp_requests = []
    def serve():
        while not stop.is_set():
            try: data, source = ntp.recvfrom(2048)
            except socket.timeout: continue
            except OSError: break
            if len(data) == 48:
                ntp_requests.append({'source':source[0],'bytes':len(data)})
                reply = bytearray(48); reply[0] = 0x24; reply[1] = 1
                reply[24:32] = data[40:48]; reply[40:48] = b'\x01\x02\x03\x04\x05\x06\x07\x08'
                ntp.sendto(reply,source)
    threading.Thread(target=serve,daemon=True).start()
    try:
        for name,port in [('Measure-OK',18085),('Measure-Failed',9),('Measure-Unselected',18084)]:
            protocol.import_uri(f'snell://{protocol.SECRET}@10.0.2.2:{port}?version=5&udp=true',name)
        ui.enable_debug_logs()
        ui.navigate('nav_settings')
        for key,value in [('node_test_timeout','10'),('connection_concurrency','2'),('node_test_udp_host','127.0.0.1'),
                          ('node_test_udp_port','18892'),('node_test_limit','1'),('connection_test_url','http://127.0.0.1:18888/')]:
            protocol.edit_value(key,value)
        ui.capture('node-test-settings')
        collected = []
        for key,kind in [('node_test_tcp','TCP'),('node_test_url','URL'),('node_test_udp','UDP'),('node_test_speed','SPEED')]:
            before = int(time.time()*1000)
            start_test(key,['Measure-OK','Measure-Failed'],speed=kind=='SPEED')
            records = wait_results(kind,2,before)
            assert len([r for r in records if r['value'] >= 0]) == 1, records
            assert len([r for r in records if r['value'] < 0]) == 1, records
            if kind=='SPEED':
                measured = next(r for r in records if r['value'] >= 0)
                assert 32768 <= measured['transferred'] <= 1048576, measured
            collected.extend(records)
            ui.wait_for(resource_id=P+':id/action_misc')
            ui.capture('node-results-'+kind)
            assert not re.search(r'\btun\d+:',ui.adb('shell','ip','-o','link','show')), 'Node tests unexpectedly created a VPN'
        assert len(results()) == 8, 'An unselected node was modified'
        assert ntp_requests and all(r['source']=='127.0.0.1' for r in ntp_requests)
        # Cancellation must close all test cores; a cancelled measurement preserves its previous result.
        ui.launch(); ui.navigate('nav_settings'); protocol.edit_value('node_test_timeout','60')
        before = results()
        start_test('node_test_udp',['Measure-Failed'])
        ui.tap(ui.wait_for(resource_id='android:id/button2'))
        ui.wait_for(resource_id=P+':id/action_misc')
        time.sleep(2)
        processes = ui.adb('shell','ps','-A','-o','PID,ARGS')
        assert not any('/libmihomo.so' in line or '/libsnell_preview.so' in line for line in processes.splitlines()), processes
        assert results() == before, 'Cancellation overwrote completed results'
        ui.launch(); ui.tap(ui.wait_for(resource_id=P+':id/action_misc'))
        ui.tap(ui.scroll_for(text=ui.STRINGS['node_sort_results']))
        ui.tap(ui.wait_for(text=ui.STRINGS['node_test_speed']))
        ui.wait_for(resource_id=P+':id/node_test_results'); ui.capture('nodes-sorted-by-speed')
        (ui.OUT/'node-measurements.json').write_text(json.dumps({'results':collected,'ntp_requests':ntp_requests,'cancelled':True,'unselected_unchanged':True},indent=2))
    finally:
        stop.set(); ntp.close()


def advanced_settings():
    protocol.import_uri(f'snell://{protocol.SECRET}@10.0.2.2:18085?version=5&udp=true','Advanced-Snell')
    ui.enable_debug_logs(); ui.navigate('nav_settings')
    for title,value in [('advanced_bootstrap','9.9.9.9'),('advanced_dns_timeout','8'),('advanced_dns_capacity','2048'),
                        ('advanced_hosts','example.test 192.0.2.123')]:
        protocol.edit_value(title,value)
    for key in ['advanced_dns_aaaa','advanced_dns_https','advanced_dns_optimistic','advanced_core_cache']:
        ui.tap(ui.scroll_for(text=ui.STRINGS[key]))
    ui.tap(ui.scroll_for(text=ui.STRINGS['advanced_sniffers']))
    ui.tap(ui.wait_for(text='http')); ui.tap(ui.wait_for(text='tls')); ui.tap(ui.wait_for(resource_id='android:id/button1'))
    ui.capture('advanced-dns-controls')
    button = protocol.start_profile('Advanced-Snell')
    try:
        log = ui.adb('shell','cat','/data/user/0/'+P+'/cache/neko.log')
        lines = [line.split('[ProxyInstance] ',1)[1] for line in log.splitlines() if '[ProxyInstance] {' in line]
        config = json.loads(lines[-1])
        assert config['dns']['timeout']=='8s' and config['dns']['optimistic'] is True
        assert config['dns']['cache_capacity']==2048
        assert any(s.get('predefined',{}).get('example.test')==['192.0.2.123'] for s in config['dns']['servers'])
        assert any(set(r.get('query_type',[]))=={'AAAA','SVCB','HTTPS'} for r in config['dns']['rules'])
        assert any(r.get('action')=='sniff' and set(r.get('sniffer',[]))=={'http','tls'} for r in config['route']['rules'])
        assert config['experimental']['cache_file']['enabled'] is True
        ui.adb('forward','tcp:12080','tcp:2080')
        control,_=protocol.socks(1,18888)
        with control:
            control.sendall(b'GET / HTTP/1.0\r\nHost: localhost\r\n\r\n')
            response=b''
            while True:
                block=control.recv(8192)
                if not block: break
                response+=block
            assert protocol.PAYLOAD in response
        (ui.OUT/'advanced-generated-config.json').write_text(json.dumps(config,indent=2))
        # Query DNS over the app's real SOCKS TCP listener. Port 53 is handled by
        # its DNS rules, so these assertions verify answers rather than JSON alone.
        answers = []
        for qtype in [1, 28, 64, 65]:
            query = struct.pack('!HHHHHH', 0x1234, 0x100, 1, 0, 0, 0)
            query += b'\x07example\x04test\x00' + struct.pack('!HH', qtype, 1)
            control, _ = protocol.socks(1, 53)
            with control:
                control.sendall(struct.pack('!H', len(query)) + query)
                size = struct.unpack('!H', protocol.read_exact(control, 2))[0]
                response = protocol.read_exact(control, size)
            txid, flags, questions, count, _, _ = struct.unpack('!HHHHHH', response[:12])
            assert txid == 0x1234 and flags & 15 == 0, response.hex()
            if qtype == 1:
                assert count == 1 and socket.inet_aton('192.0.2.123') in response, response.hex()
            else: assert count == 0, response.hex()
            answers.append({'type': qtype, 'answer_count': count, 'wire': response.hex()})
        (ui.OUT/'advanced-dns-answers.json').write_text(json.dumps(answers, indent=2))
    finally:
        ui.tap(button)
    # Presets show their actual active rules and retain custom rules for switching back.
    ui.launch(); ui.navigate('nav_route')
    for label in ['Bypass mainland China · Whitelist','Proxy listed domains · Blacklist','Global proxy','Custom rules']:
        ui.tap(ui.wait_for(resource_id=P+':id/route_preset'))
        ui.tap(ui.wait_for(text=label)); ui.wait_for(text='Routing: '+label)
        ui.capture('route-preset-'+label.split()[0])
    # Restore the options so the other protocol checks retain their expected defaults.
    ui.navigate('nav_settings')
    for key in ['advanced_dns_aaaa','advanced_dns_https','advanced_dns_optimistic','advanced_core_cache']:
        ui.tap(ui.scroll_for(text=ui.STRINGS[key]))


def route_import():
    def rule_rows():
        path = ui.OUT/'routing-snapshot.db'
        path.write_bytes(ui.adb('exec-out','cat','/data/user/0/'+P+'/databases/sager_net.db',binary=True))
        with sqlite3.connect(path) as db:
            return db.execute('SELECT name,domains,ip,port,network,outbound FROM rules ORDER BY userOrder').fetchall()
    def choose_file(path):
        ui.adb('push',str(path),'/sdcard/Download/'+path.name)
        ui.tap(ui.wait_for(content_desc='More options'))
        ui.tap(ui.wait_for(text=ui.STRINGS['route_import']))
        ui.tap(ui.wait_for(text='From file'))
        node=ui.find(ui.tree(),text=path.name)
        if node is None:
            drawer=ui.find(ui.tree(),content_desc='Show roots')
            if drawer is not None: ui.tap(drawer)
            ui.tap(ui.wait_for(text='Downloads'))
            node=ui.scroll_for(text=path.name)
        ui.tap(node)
    ui.launch(); ui.navigate('nav_route')
    before=rule_rows()
    fixture=ui.OUT/'routing-import.json'
    fixture.write_text(json.dumps({'route':{'rules':[
        {'name':'Imported DNS host','domain':['example.test'],'outbound':'direct'},
        {'name':'Imported UDP block','network':'udp','port':[8443],'action':'reject'},
        {'name':'Imported subnet','ip_cidr':['192.0.2.0/24'],'outbound':'proxy'}]}}))
    choose_file(fixture)
    ui.wait_for(text='Import 3 rules'); ui.capture('routing-import-preview')
    # Cancelling the preview leaves every existing rule intact.
    ui.tap(ui.wait_for(resource_id='android:id/button2')); assert rule_rows()==before
    choose_file(fixture); ui.tap(ui.wait_for(resource_id='android:id/button1'))
    ui.wait_for(text='Routing: Custom rules')
    after=rule_rows(); assert after[:len(before)]==before and len(after)==len(before)+3, after
    assert after[-3][1]=='full:example.test' and after[-2][3:] == ('8443','udp',-2), after
    bad=ui.OUT/'unsupported-routing.json'
    bad.write_text(json.dumps({'rules':[{'domain':['valid.test'],'outbound':'direct'},
                                     {'process_name':['desktop.exe'],'outbound':'proxy'}]}))
    choose_file(bad); ui.wait_for(text=ui.STRINGS['error_title'])
    ui.capture('routing-import-rejects-unsupported-fields')
    ui.tap(ui.wait_for(resource_id='android:id/button1')); assert rule_rows()==after
    ui.tap(ui.wait_for(content_desc='More options'))
    ui.tap(ui.wait_for(text=ui.STRINGS['route_export']))
    ui.tap(ui.wait_for(resource_id='android:id/button1'))
    ui.wait_for(resource_id=P+':id/route_preset')
    exported=json.loads(ui.adb('shell','cat','/sdcard/Download/ArcaenBox-routes.json'))
    assert exported['format']=='arcaenbox-route-rules' and len(exported['rules'])==len(after)
    assert exported['rules'][-3]['outbound']=='direct' and exported['rules'][-2]['outbound']=='block'
    (ui.OUT/'routing-import-result.json').write_text(json.dumps({'before':before,'after':after,'export':exported},indent=2))
