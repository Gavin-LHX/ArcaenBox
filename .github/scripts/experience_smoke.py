"""Real Android authorization, exit IP, resource UI and independently loaded core checks."""
import hashlib
import json
import os
import re
import shlex
import subprocess
import time
import traceback
import urllib.parse
from pathlib import Path
import protocol_smoke as protocol
import ui_smoke as ui

P = ui.PACKAGE
OUT = Path('experience-smoke'); OUT.mkdir(exist_ok=True)
ui.OUT = OUT
protocol.OUT = OUT
CHECKS = set(os.environ.get('EXPERIENCE_CHECKS', '').split(',')) - {''}
RESULTS = []
FAILURES = []
FIXTURE = 'com.arcaenbox.vpnfixture'


def wait_tun(expected=True):
    end = time.monotonic() + 30
    while time.monotonic() < end:
        active = bool(re.search(r'\btun\d+:', ui.adb('shell', 'ip', '-o', 'link', 'show')))
        if active == expected:
            return
        time.sleep(.5)
    raise AssertionError('Unexpected TUN state: ' + str(expected))


def ensure_snell(name='Snell-exit'):
    protocol.import_uri(f'snell://{protocol.SECRET}@10.0.2.2:18085?version=5&udp=true', name)


def apply_native(component, channel='stable', restore=False):
    ui.launch(); ui.navigate('nav_kernels')
    ui.tap(ui.scroll_for(text_contains={'naive': 'NaïveProxy', 'mieru': 'Mieru', 'trojan-go': 'Trojan-Go', 'snell': 'Snell'}[component]))
    ui.tap(ui.scroll_for(resource_id=P+':id/core_'+channel))
    ui.tap(ui.scroll_for(resource_id=P+':id/core_restore' if restore else P+':id/core_apply', enabled='true'))
    ui.tap(ui.wait_for(resource_id='android:id/button1'))
    ui.wait_for(text=ui.STRINGS['kernel_applied'])


def permissions():
    ensure_snell()
    ui.adb('install', '-t', '-r', 'core-build/vpn-fixture/fixture.apk')
    result = ui.adb('shell', 'dpm', 'set-device-owner', shlex.quote(FIXTURE+'/.Main$Admin'))
    assert 'Success' in result, result
    try:
        ui.adb('shell', 'am', 'start', '-W', '-n', FIXTURE+'/.Main', '--ez', 'block', 'true')
        time.sleep(1)
        ui.launch()
        ui.tap(ui.scroll_for(text='Snell-exit'))
        ui.tap(ui.wait_for(resource_id=P+':id/fab'))
        ui.wait_for(text=ui.STRINGS['vpn_permission_help'])
        wait_tun(False)
        ui.capture('vpn-always-on-conflict-help')
        ui.tap(ui.wait_for(resource_id='android:id/button1'))
        assert any(n.get('package') == 'com.android.settings' for n in ui.tree().iter('node'))
        (OUT/'system-vpn-settings.png').write_bytes(ui.adb('exec-out', 'screencap', '-p', binary=True))
    finally:
        ui.adb('shell', 'am', 'start', '-W', '-n', FIXTURE+'/.Main', '--ez', 'block', 'false')
        ui.adb('shell', 'dpm', 'remove-active-admin', shlex.quote(FIXTURE+'/.Main$Admin'))
        ui.adb('uninstall', FIXTURE)
    ui.adb('shell', 'appops', 'set', P, 'ACTIVATE_VPN', 'default')
    ui.launch()
    ui.tap(ui.wait_for(resource_id=P+':id/fab'))
    confirm = ui.wait_for(resource_id='android:id/button1')
    assert any(n.get('package') == 'com.android.vpndialogs' for n in ui.tree().iter('node')), 'System VPN consent did not appear'
    (OUT/'vpn-system-consent.png').write_bytes(ui.adb('exec-out', 'screencap', '-p', binary=True))
    ui.tap(ui.wait_for(resource_id='android:id/button2'))
    ui.wait_for(text=ui.STRINGS['vpn_permission_help'])
    wait_tun(False)
    ui.tap(ui.wait_for(resource_id='android:id/button2'))
    ui.tap(ui.wait_for(resource_id=P+':id/fab'))
    ui.tap(ui.wait_for(resource_id='android:id/button1'))
    wait_tun()
    (OUT/'vpn-authorized-service.txt').write_text(ui.adb('shell', 'dumpsys', 'activity', 'services', P))
    ui.adb('shell', 'am', 'force-stop', P)
    wait_tun(False)


def exit_ip():
    ensure_snell('Snell-public-exit')
    ui.navigate('nav_settings')
    ui.tap(ui.scroll_for(text=ui.STRINGS['log_level']))
    ui.tap(ui.wait_for(text='debug'))
    button = protocol.start_profile('Snell-public-exit')
    try:
        end = time.monotonic() + 40
        ip = None
        while time.monotonic() < end:
            node = ui.find(ui.tree(), resource_id=P+':id/exit_ip')
            text = node.get('text', '') if node is not None else ''
            match = re.search(r'Exit IP: (\d+\.\d+\.\d+\.\d+|[0-9a-fA-F:]+)$', text)
            if match:
                ip = match.group(1); break
            time.sleep(1)
        if not ip:
            (OUT/'exit-core.log').write_text(ui.adb('shell','cat','/data/user/0/'+P+'/cache/neko.log',check=False))
            sockets=ui.adb('shell','ss','-ltnp',check=False)
            (OUT/'exit-listeners.txt').write_text(sockets)
            (OUT/'exit-processes.txt').write_text(ui.adb('shell','ps','-A','-o','PID,ARGS'))
            baseline=subprocess.run(['curl','-i','--max-time','20','https://api.ipify.org'],capture_output=True,text=True)
            (OUT/'exit-host-baseline.txt').write_text(baseline.stdout+'\n'+baseline.stderr)
        assert ip, 'No public proxy exit IP: '+text
        assert ip not in ('10.0.2.2', '127.0.0.1')
        (OUT/'public-exit.json').write_text(json.dumps({'ip': ip, 'proxy': 'Snell 5', 'endpoint': 'https://api.ipify.org'}))
        ui.capture('connected-public-exit')
    finally:
        ui.tap(button); wait_tun(False)
    assert ui.find(ui.tree(), resource_id=P+':id/exit_ip') is None, 'Stale exit after disconnect'
    protocol.import_uri(f'snell://{protocol.SECRET}@10.0.2.2:9?version=5', 'Snell-unreachable-exit')
    button = protocol.start_profile('Snell-unreachable-exit')
    try:
        ui.wait_for(text=ui.STRINGS['exit_ip_failed'])
        ui.capture('failed-proxy-no-direct-ip-fallback')
    finally:
        ui.tap(button); wait_tun(False)


def resources():
    ui.launch(); ui.navigate('nav_resources')
    ui.wait_for(text='geoip.db'); ui.wait_for(text='geosite.db')
    ui.capture('resource-files-overview')
    ui.tap(ui.wait_for(resource_id=P+':id/resource_source'))
    ui.wait_for(resource_id=P+':id/select_dialog_listview')
    ui.capture('resource-source-picker')
    ui.adb('shell','input','keyevent','BACK')
    ui.tap(ui.wait_for(resource_id=P+':id/action_update_resources'))
    end = time.monotonic()+180
    while time.monotonic()<end:
        doc=ui.tree()
        if ui.find(doc,text=ui.STRINGS['error_title']) is not None:
            raise AssertionError('Resource update error: '+str([n.get('text') for n in doc.iter('node')]))
        button=ui.find(doc,resource_id=P+':id/action_update_resources')
        if button is not None and button.get('enabled')=='true': break
        time.sleep(2)
    else: raise AssertionError('Resource update timeout')
    listing=ui.adb('shell','ls','-l','/sdcard/Android/data/'+P+'/files')
    assert 'geoip.db' in listing and 'geosite.db' in listing
    (OUT/'resource-files.txt').write_text(listing)
    ui.capture('resource-files-updated')
    rule=OUT/'arcaenbox-fixture.json'
    rule.write_text('{"version":3,"rules":[{"domain_suffix":["example.com"]}]}')
    ui.adb('push',str(rule),'/sdcard/Download/arcaenbox-fixture.json')
    ui.tap(ui.wait_for(resource_id=P+':id/action_import_file'))
    # DocumentsUI exposes Downloads through its navigation drawer.
    doc=ui.tree()
    node=ui.find(doc,text='arcaenbox-fixture.json')
    if node is None:
        drawer=ui.find(doc,content_desc='Show roots')
        if drawer is not None: ui.tap(drawer)
        ui.tap(ui.wait_for(text='Downloads'))
        node=ui.scroll_for(text='arcaenbox-fixture.json')
    ui.tap(node)
    ui.wait_for(text='arcaenbox-fixture.json')
    actual=ui.adb('shell','cat','/sdcard/Android/data/'+P+'/files/arcaenbox-fixture.json')
    assert json.loads(actual)==json.loads(rule.read_text())
    ui.capture('resource-imported-rule-set')
    doc=ui.tree(); parents={child:parent for parent in doc.iter() for child in parent}
    row=ui.find(doc,text='arcaenbox-fixture.json')
    while row in parents:
        button=ui.find(row,resource_id=P+':id/delete_resource')
        if button is not None: break
        row=parents[row]
    ui.tap(button); ui.tap(ui.wait_for(resource_id='android:id/button1'))
    time.sleep(1)
    assert 'arcaenbox-fixture.json' not in ui.adb('shell','ls','/sdcard/Android/data/'+P+'/files')


def settings_and_routes():
    ui.launch(); ui.navigate('nav_route')
    ui.tap(ui.wait_for(resource_id=P+':id/route_domain_strategy'))
    ui.tap(ui.wait_for(text='IPv4 first'))
    ui.wait_for(text=ui.STRINGS['route_strategy_value'].replace('%1$s','IPv4 first'))
    ui.capture('route-strategy-and-controls')
    ui.tap(ui.wait_for(resource_id=P+':id/route_domain_strategy'))
    ui.tap(ui.wait_for(text='AsIs'))
    ui.navigate('nav_settings')
    for title,value in [('udp_timeout_title','60'),('globalMuxStreams','8'),('connection_concurrency','4')]:
        if title=='globalMuxStreams': continue
        protocol.edit_value(title,value)
    ui.tap(ui.scroll_for(text=ui.STRINGS['fragment_title']))
    ui.tap(ui.wait_for(text='TLS record'))
    ui.capture('singbox-fragment-setting')
    ui.tap(ui.scroll_for(text=ui.STRINGS['fragment_title']))
    ui.tap(ui.wait_for(text=ui.STRINGS['off']))
    ui.launch(); ui.tap(ui.wait_for(resource_id=P+':id/action_misc'))
    for key in ['node_restart','node_delete_group','node_export_group','node_locate','node_sort_delay']:
        ui.scroll_for(text=ui.STRINGS[key])
    ui.capture('node-actions-menu')
    ui.adb('shell','input','keyevent','BACK')


def native_cache():
    """Verify signed downloaded-package loading under the ordinary app UID, including SELinux."""
    ui.launch(); ui.adb('shell','am','force-stop',P)
    root='/data/user/0/'+P+'/no_backup/components'
    owner=ui.adb('shell','stat','-c','%u:%g','/data/user/0/'+P).strip()
    state={'channels':{},'installed':{},'enabled':{}}
    for file in Path('core-build/component-updates').glob('*/*/manifest.json'):
        raw=file.read_bytes(); manifest=json.loads(raw); digest=hashlib.sha256(raw).hexdigest()
        folder=root+'/packages/'+digest
        ui.adb('shell','mkdir','-p',folder)
        for source,target in [(file,'manifest.json'),(file.parent/'manifest.sig','manifest.sig'),(file.parent/manifest['assets']['x86_64']['name'],'libcomponent.so')]:
            ui.adb('push',str(source),folder+'/'+target)
        ui.adb('shell','chmod','444',folder+'/libcomponent.so')
        state['installed'][manifest['component']+':'+manifest['channel']]=digest
    file=OUT/'native-state.json'; file.write_text(json.dumps(state))
    ui.adb('push',str(file),root+'/state.json')
    ui.adb('shell','chown','-R',owner,root)
    ui.adb('shell','restorecon','-R',root)
    assert ui.adb('shell','getenforce').strip()=='Enforcing'
    ensure_snell('Snell-downloaded')
    protocol.import_uri(f'trojan-go://{protocol.SECRET}@10.0.2.2:18086?sni=localhost','Trojan-downloaded')
    protocol.edit_profile('Trojan-downloaded'); ui.tap(ui.scroll_for(text=ui.STRINGS['allow_insecure'])); protocol.save()
    protocol.create_mieru('Mieru-downloaded','TCP',18088)
    cert=urllib.parse.quote((OUT/'cert.pem').read_text(),safe='')
    protocol.import_uri(f'naive+https://test:{protocol.SECRET}@10.0.2.2:18090?sni=localhost&cert={cert}','Naive-downloaded')
    for component,name,udp in [('snell','Snell-downloaded',True),('trojan-go','Trojan-downloaded',True),('mieru','Mieru-downloaded',True),('naive','Naive-downloaded',False)]:
        apply_native(component)
        assert ui.STRINGS['core_downloaded'] in ui.find(ui.tree(),resource_id=P+':id/core_running').get('text','')
        protocol.traffic(name,'libcomponent.so',udp,downloaded=True)
        apply_native(component,restore=True)
    protocol.import_uri(f'snell://{protocol.SECRET}@10.0.2.2:18091?version=6&mode=default&udp=true&reuse=true','Snell6-downloaded')
    apply_native('snell','preview')
    protocol.traffic('Snell6-downloaded','libcomponent.so',True,downloaded=True)
    apply_native('snell',restore=True)
    (OUT/'native-package-runtime.json').write_text(json.dumps({'selinux':'Enforcing','origin':'signed CI packages staged for loader verification','protocols':protocol.RESULTS},indent=2))


def native_downloads():
    """Run only after the candidate's signed component releases have been published."""
    root='/data/user/0/'+P+'/no_backup/components'
    evidence=[]
    for component,channel in [('trojan-go','stable'),('naive','stable'),('mieru','stable'),('snell','stable'),('snell','preview')]:
        ui.launch(); ui.navigate('nav_kernels')
        ui.tap(ui.scroll_for(text_contains={'naive':'NaïveProxy','mieru':'Mieru','trojan-go':'Trojan-Go','snell':'Snell'}[component]))
        ui.tap(ui.scroll_for(resource_id=P+':id/core_'+channel))
        ui.tap(ui.scroll_for(resource_id=P+':id/core_check',enabled='true'))
        ui.tap(ui.wait_for(resource_id=P+':id/core_download',enabled='true'))
        end=time.monotonic()+180
        while time.monotonic()<end:
            status=ui.find(ui.tree(),resource_id=P+':id/core_status')
            value=status.get('text','') if status is not None else ''
            if value==ui.STRINGS['kernel_ready']: break
            if status is not None and any(term in value.lower() for term in ['failed','invalid','error']):
                raise AssertionError(value)
            time.sleep(1)
        else: raise AssertionError('Component download timeout: '+value)
        expected=(Path('core-build/component-updates')/component/channel/'manifest.json').read_bytes()
        digest=hashlib.sha256(expected).hexdigest()
        state=json.loads(ui.adb('shell','cat',root+'/state.json'))
        assert state['installed'][component+':'+channel]==digest
        apply_native(component,channel)
        state=json.loads(ui.adb('shell','cat',root+'/state.json'))
        assert state['enabled'][component]==digest
        manifest=json.loads(expected)
        binary=root+'/packages/'+digest+'/libcomponent.so'
        actual=ui.adb('shell','sha256sum',binary).split()[0]
        assert actual==manifest['assets']['x86_64']['sha256']
        ui.capture('downloaded-'+component+'-'+channel)
        evidence.append({'component':component,'channel':channel,'manifest_sha256':digest,'binary_sha256':actual})
        apply_native(component,restore=True)
    # No preview upstream/package exists for these three components. The UI must say so.
    for component,title in [('trojan-go','Trojan-Go'),('naive','NaïveProxy'),('mieru','Mieru')]:
        ui.launch(); ui.navigate('nav_kernels'); ui.tap(ui.scroll_for(text_contains=title))
        ui.tap(ui.scroll_for(resource_id=P+':id/core_preview'))
        ui.tap(ui.scroll_for(resource_id=P+':id/core_check',enabled='true'))
        ui.wait_for(text=ui.STRINGS['kernel_unavailable'])
    (OUT/'native-network-downloads.json').write_text(json.dumps(evidence,indent=2))


def run(name, function):
    if CHECKS and name not in CHECKS: return
    try:
        function(); RESULTS.append(name); print('PASS:',name,flush=True)
    except Exception:
        FAILURES.append(name)
        (OUT/(name+'-failure.txt')).write_text(traceback.format_exc())
        (OUT/(name+'-failure-logcat.txt')).write_text(ui.adb('shell','logcat','-d',check=False))
        (OUT/(name+'-core.log')).write_text(ui.adb('shell','cat','/data/user/0/'+P+'/cache/neko.log',check=False))
        print('FAIL:',name,traceback.format_exc(),flush=True)
        try: ui.capture(name+'-failure')
        except Exception: pass
    finally:
        ui.adb('shell','am','force-stop',P)


def main():
    try:
        protocol.setup_servers()
        ui.adb('root'); ui.adb('wait-for-device')
        ui.adb('shell','svc','power','stayon','true')
        ui.adb('shell','wm','dismiss-keyguard')
        ui.adb('install','-r','-g',str(next(Path('dist').glob('*x86_64*.apk'))))
        ui.adb('shell','cmd','uimode','night','no')
        ui.adb('shell','logcat','-c')
        for name,function in [('permissions',permissions),('exit-ip',exit_ip),('resources',resources),('settings-routes',settings_and_routes),('native-cache',native_cache)]:
            run(name,function)
        if 'native-downloads' in CHECKS: run('native-downloads',native_downloads)
        assert not FAILURES, FAILURES
    finally:
        (OUT/'results.json').write_text(json.dumps({'passed':RESULTS,'failed':FAILURES},indent=2))
        (OUT/'logcat.txt').write_text(ui.adb('shell','logcat','-d',check=False))
        for process,log in protocol.PROCESSES:
            process.terminate()
            try: process.wait(timeout=5)
            except Exception: process.kill()
            log.close()


if __name__=='__main__': main()
