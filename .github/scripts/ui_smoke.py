"""Exercise the signed release on an isolated emulator and save UI evidence."""
import json
import os
import re
import subprocess
import time
import traceback
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = 'com.arcaenbox.android'
OUT = Path('ui-smoke')
OUT.mkdir(exist_ok=True)
STRINGS = {e.get('name'): ''.join(e.itertext()) for e in ET.parse('app/src/main/res/values/strings.xml').getroot() if e.tag == 'string'}
for resource in Path('app/src/main/res/values').glob('*.xml'):
    STRINGS.update({e.get('name'): ''.join(e.itertext()) for e in ET.parse(resource).getroot() if e.tag == 'string'})
ANDROID = '{http://schemas.android.com/apk/res/android}'
MENU = {e.get(ANDROID + 'id').split('/')[-1]: STRINGS[e.get(ANDROID + 'title').split('/')[-1]] for e in ET.parse('app/src/main/res/menu/main_drawer_menu.xml').iter('item')}
RESULTS = []
FAILURES = []
ONLY_CHECKS = set(os.environ.get('UI_SMOKE_CHECKS', '').split(',')) - {''}


def adb(*args, binary=False, check=True):
    result = subprocess.run(['adb', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    if check and result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout if binary else result.stdout.decode(errors='replace')


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))


def root_emulator():
    """adbd may close its transport while the emulator runner restarts it."""
    for _ in range(4):
        adb('root', check=False)
        adb('wait-for-device')
        if adb('shell', 'id', '-u', check=False).strip() == '0':
            adb('shell', 'svc', 'power', 'stayon', 'true')
            adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
            adb('shell', 'wm', 'dismiss-keyguard')
            return
        time.sleep(1)
    raise AssertionError('Isolated test emulator adbd did not become available')


def tree():
    # Counters can briefly prevent UIAutomator from reaching idle. Never read a
    # stale hierarchy, but retry a missing/partial dump before failing the test.
    for attempt in range(3):
        adb('shell', 'rm', '-f', '/sdcard/arcaenbox-ui.xml')
        diagnostic = adb('shell', 'uiautomator', 'dump', '/sdcard/arcaenbox-ui.xml', check=False)
        content = adb('shell', 'cat', '/sdcard/arcaenbox-ui.xml', check=False)
        try:
            return ET.fromstring(content)
        except ET.ParseError:
            (OUT/'uiautomator-dump-error.txt').write_text(diagnostic+'\n'+content)
            if attempt == 2: raise
            time.sleep(.5)


def find(doc, **attrs):
    for node in doc.iter('node'):
        if all((v in node.get('text', '') if k == 'text_contains' else node.get(k.replace('_', '-')) == v) for k, v in attrs.items()):
            if len(bounds(node)) == 4 and bounds(node)[2] > bounds(node)[0]:
                return node
    return None


def tap(node):
    assert node is not None, 'Required control not found'
    x1, y1, x2, y2 = bounds(node)
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.4)


def assert_connect_button_visible(button):
    """Catch a FAB covered by the bar even when accessibility says it is visible."""
    import io
    from PIL import Image
    data = adb('exec-out', 'screencap', '-p', binary=True)
    pixels = Image.open(io.BytesIO(data)).convert('RGB').crop(bounds(button))
    # The high-contrast service icon must be rendered. A covered FAB leaves only
    # the uniform status-bar background in this reserved area of the layout.
    if max(high-low for low,high in pixels.getextrema()) < 40:
        (OUT/'connect-button-covered.png').write_bytes(data)
        raise AssertionError('Connect button is covered despite accessible bounds')


def wait_for(timeout=25, **attrs):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        doc = tree()
        # A Google APIs emulator can leave the launcher ANR dialog in front of
        # a healthy foreground app. Dismiss only this identified fixture failure;
        # ArcaenBox crashes/ANRs must still fail the test and retain their evidence.
        launcher_anr = find(doc, text="Pixel Launcher isn't responding", package='android')
        if launcher_anr is not None:
            close = find(doc, resource_id='android:id/aerr_close')
            if close is not None:
                (OUT/'emulator-launcher-anr.xml').write_text(ET.tostring(doc,encoding='unicode'),encoding='utf-8')
                tap(close)
                continue
        node = find(doc, **attrs)
        if node is not None:
            return node
        time.sleep(.4)
    raise AssertionError(f'Control did not appear: {attrs}')


def scroll_for(**attrs):
    """Search long preference lists and forms, stopping at either scroll boundary."""
    doc=tree()
    def exposed(doc):
        node=find(doc,**attrs)
        if node is None: return None
        # UIAutomator includes preference rows behind the fixed status bar.
        # Scroll those rows into view before tapping their reported bounds.
        stats=find(doc, resource_id=PACKAGE+':id/stats')
        if stats is not None and node not in stats.iter('node'):
            if bounds(node)[3] > bounds(stats)[1]: return None
        return node
    node=exposed(doc)
    if node is not None: return node
    width,height=map(int,re.findall(r'(\d+)x(\d+)',adb('shell','wm','size'))[-1])
    def signature(doc):
        return [(n.get('resource-id'),n.get('text'),n.get('bounds')) for n in doc.iter('node')
                if n.get('package') != 'com.android.systemui']
    for direction in ['bottom','top']:
        for _ in range(20):
            before=signature(doc)
            start,end=(height//3,3*height//4) if direction == 'top' else (3*height//4,height//3)
            adb('shell','input','swipe',str(width//2),str(start),str(width//2),str(end),'350')
            doc=tree()
            node=exposed(doc)
            if node is not None: return node
            if before==signature(doc): break
    raise AssertionError(f'Scrollable control did not appear: {attrs}')


def launch():
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/io.nekohasekai.sagernet.ui.MainActivity')
    wait_for(resource_id=PACKAGE + ':id/toolbar')


def capture(name):
    doc = tree()
    assert any(n.get('package') == PACKAGE for n in doc.iter('node')), 'Application screen not visible'
    assert adb('shell', 'pidof', PACKAGE).strip(), 'Application process exited'
    (OUT / (name + '.xml')).write_text(ET.tostring(doc, encoding='unicode'), encoding='utf-8')
    (OUT / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    RESULTS.append(name)


def open_drawer():
    toolbar = wait_for(resource_id=PACKAGE + ':id/toolbar')
    icon = next((n for n in toolbar.iter('node') if n.get('class') == 'android.widget.ImageButton'), None)
    tap(icon)
    wait_for(resource_id=PACKAGE + ':id/nav_view')


def navigate(item):
    open_drawer()
    label = MENU[item]
    for direction in ['up', 'down', 'down', 'up']:
        doc = tree()
        node = find(doc, text=label)
        if node is not None:
            tap(node)
            wait_for(resource_id=PACKAGE + ':id/toolbar')
            return
        drawer = find(doc, resource_id=PACKAGE + ':id/nav_view')
        x1,y1,x2,y2 = bounds(drawer)
        x=(x1+x2)//2; top=y1+(y2-y1)//4; bottom=y1+3*(y2-y1)//4
        start,end=(top,bottom) if direction == 'up' else (bottom,top)
        adb('shell','input','swipe',str(x),str(start),str(x),str(end),'350')
    raise AssertionError(f'Navigation item missing: {label}')


def auto_connect_switch():
    scroll_for(text=STRINGS['auto_connect'])
    doc=tree()
    title=find(doc,text=STRINGS['auto_connect'])
    assert title is not None
    parents={child:parent for parent in doc.iter() for child in parent}
    row=title
    while row in parents:
        switch=find(row,resource_id=PACKAGE + ':id/material_switch')
        if switch is not None:
            return switch
        row=parents[row]
    raise AssertionError('MD3 switch missing from preference row')


def startup():
    launch()
    capture('01-light-main')
    open_drawer()
    doc = tree()
    assert find(doc, text='Promote') is None and find(doc, text='Ads') is None
    capture('02-light-drawer')
    adb('shell','input','keyevent','BACK')
    wait_for(resource_id=PACKAGE + ':id/toolbar')


def whitelist():
    launch()
    navigate('nav_po0')
    capture('15-po0-empty')
    tap(scroll_for(resource_id=PACKAGE + ':id/po0_add'))
    scroll_for(text=STRINGS['po0_tokens_required'])
    field=scroll_for(resource_id=PACKAGE + ':id/po0_tokens')
    tap(field)
    adb('shell','input','text','invalid_token')
    adb('shell','input','keyevent','BACK')
    tap(scroll_for(resource_id=PACKAGE + ':id/po0_save'))
    scroll_for(text=STRINGS['po0_invalid_tokens'])
    capture('16-po0-validation')
    # Save with automatic updates OFF: this fixture must never reach the remote API.
    tap(scroll_for(resource_id=PACKAGE + ':id/po0_tokens'))
    adb('shell','input','keyevent','KEYCODE_MOVE_END')
    for _ in 'invalid_token': adb('shell','input','keyevent','KEYCODE_DEL')
    fixture='pgnfw_emulator_fixture@0,pgnfw_second_fixture'
    adb('shell','input','text',fixture)
    adb('shell','input','keyevent','BACK')
    assert scroll_for(resource_id=PACKAGE + ':id/po0_automatic').get('checked') == 'false'
    tap(scroll_for(resource_id=PACKAGE + ':id/po0_save'))
    wait_for(text=STRINGS['po0_saved'])
    # The isolated Google APIs emulator permits root inspection of this test
    # app's private files. Persist only a boolean result, never the ciphertext.
    adb('root')
    adb('wait-for-device')
    protected=adb('shell','cat','/data/user/0/' + PACKAGE + '/no_backup/po0/state.json')
    state=json.loads(protected)
    assert fixture not in protected and 'pgnfw_' not in protected, 'Credentials persisted in plaintext'
    assert state['encryptedTokens'] and state['automatic'] is False
    assert state['checkedAt'] == 0, 'Save with automatic updates off sent a request'
    (OUT/'po0-storage-check.json').write_text(json.dumps({'encrypted':True,'automatic':False,'no_request':True}),encoding='utf-8')
    launch()
    navigate('nav_po0')
    field=scroll_for(resource_id=PACKAGE + ':id/po0_tokens')
    assert field.get('password') == 'true', 'Token must be masked'
    # Android exposes password text to privileged UI automation; password=true
    # and the captured pixels establish that normal screen rendering is masked.
    capture('17-po0-saved-masked')
    tap(scroll_for(content_desc='Show password'))
    assert scroll_for(resource_id=PACKAGE + ':id/po0_tokens').get('text') == fixture, 'Encrypted token failed to round trip after process restart'
    # Clear fixtures before other checks and leave no configured machines behind.
    tap(scroll_for(resource_id=PACKAGE + ':id/po0_tokens'))
    adb('shell','input','keyevent','KEYCODE_MOVE_END')
    for _ in fixture: adb('shell','input','keyevent','KEYCODE_DEL')
    adb('shell','input','keyevent','BACK')
    tap(scroll_for(resource_id=PACKAGE + ':id/po0_save'))
    time.sleep(1)
    launch()
    navigate('nav_po0')
    assert scroll_for(resource_id=PACKAGE + ':id/po0_tokens').get('text') in ('', STRINGS['po0_tokens'])


def launcher_icon():
    adb('shell','input','keyevent','HOME')
    time.sleep(1)
    dimensions=adb('shell','wm','size')
    width,height=map(int,re.findall(r'(\d+)x(\d+)',dimensions)[-1])
    adb('shell','input','swipe',str(width//2),str(3*height//4),str(width//2),str(height//4),'500')
    time.sleep(1)
    wait_for(text='ArcaenBox')
    (OUT/'18-launcher-icon.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
    (OUT/'18-launcher-icon.xml').write_text(ET.tostring(tree(),encoding='unicode'),encoding='utf-8')
    RESULTS.append('18-launcher-icon')


def destination(name, prefix):
    launch()
    navigate(name)
    capture(prefix + name)


def settings():
    launch()
    navigate('nav_settings')
    switch=auto_connect_switch()
    original=switch.get('checked')
    tap(switch)
    assert auto_connect_switch().get('checked') != original, 'Switch did not toggle'
    navigate('nav_configuration')
    navigate('nav_settings')
    assert auto_connect_switch().get('checked') != original, 'Switch value was not persisted'
    tap(auto_connect_switch())
    assert auto_connect_switch().get('checked') == original, 'Switch did not restore'
    capture('04-light-settings-switch')
    tap(wait_for(text=STRINGS['service_mode']))
    wait_for(resource_id=PACKAGE + ':id/select_dialog_listview')
    capture('04-light-single-choice-dialog')
    adb('shell','input','keyevent','BACK')


def profile():
    launch()
    # A local-only profile exercises the editor without starting a VPN or using an external server.
    adb('shell','am','start','-W','-a','android.intent.action.VIEW','-d','socks://127.0.0.1:1080','-p',PACKAGE)
    wait_for(text=STRINGS['profile_import'])
    capture('05-light-profile-import-dialog')
    tap(wait_for(resource_id='android:id/button1'))
    wait_for(resource_id=PACKAGE + ':id/edit')
    tap(find(tree(),resource_id=PACKAGE + ':id/edit'))
    wait_for(text=STRINGS['server_address'])
    capture('05-light-profile-editor')
    tap(find(tree(),text=STRINGS['server_address']))
    wait_for(resource_id='android:id/edit')
    # EditTextPreference opens the keyboard itself. A second tap can hit outside
    # the dialog while the keyboard moves it and accidentally dismiss it.
    time.sleep(.7)
    wait_for(resource_id='android:id/edit')
    capture('06-light-editor-keyboard')
    assert find(tree(),resource_id='android:id/edit') is not None, 'Editor dialog was dismissed'
    adb('shell','input','keyevent','BACK')
    adb('shell','input','keyevent','BACK')
    adb('shell','input','keyevent','BACK')


def service():
    launch()
    # Only the isolated emulator grants VPN consent. No real proxy is used.
    adb('shell', 'appops', 'set', PACKAGE, 'ACTIVATE_VPN', 'allow')
    tap(wait_for(resource_id=PACKAGE + ':id/profile_name'))
    button=wait_for(resource_id=PACKAGE + ':id/fab',enabled='true')
    assert_connect_button_visible(button)
    tap(button)
    deadline=time.monotonic()+25
    while time.monotonic()<deadline:
        services=adb('shell','dumpsys','activity','services',PACKAGE)
        interfaces=adb('shell','ip','-o','link','show')
        if 'isForeground=true' in services and re.search(r'\btun\d+:', interfaces):
            break
        time.sleep(.5)
    else:
        raise AssertionError('VPN did not establish a TUN interface and foreground service')
    time.sleep(3)
    services=adb('shell','dumpsys','activity','services',PACKAGE)
    interfaces=adb('shell','ip','-o','link','show')
    assert 'isForeground=true' in services and re.search(r'\btun\d+:', interfaces), 'VPN stopped during native initialization'
    # Continuous speed updates prevent uiautomator from becoming idle here.
    # Record the real service state and screenshot, then reuse the unchanged FAB bounds.
    (OUT/'06-light-service-started.txt').write_text(services,encoding='utf-8')
    (OUT/'06-light-service-started.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
    RESULTS.append('06-light-service-started')
    tap(button)
    wait_for(content_desc=STRINGS['connect'])
    deadline=time.monotonic()+15
    while time.monotonic()<deadline:
        services=adb('shell','dumpsys','activity','services',PACKAGE)
        interfaces=adb('shell','ip','-o','link','show')
        if 'isForeground=true' not in services and not re.search(r'\btun\d+:', interfaces):
            break
        time.sleep(.5)
    else:
        raise AssertionError('VPN foreground service or TUN interface did not stop')


def backup():
    launch()
    navigate('nav_tools')
    tap(wait_for(text=STRINGS['backup']))
    wait_for(resource_id=PACKAGE + ':id/action_export')
    capture('07-light-backup')


def open_core():
    navigate('nav_kernels')
    tap(scroll_for(text_contains='sing-box',clickable='true'))
    wait_for(resource_id=PACKAGE+':id/core_running')


def app_updates():
    launch()
    navigate('nav_about')
    for key in ['check_update_release','check_update_preview']:
        tap(scroll_for(text=STRINGS[key]))
        wait_for(resource_id='android:id/button1')
        doc=tree()
        text=' '.join(n.get('text','') for n in doc.iter('node'))
        assert '404' not in text and 'Not Found' not in text and 'documentation_url' not in text, text
        expected=STRINGS['update_no_preview'] if key.endswith('preview') else STRINGS['check_update_no']
        assert expected in text, text
        capture('19-'+key)
        tap(find(doc,resource_id='android:id/button1'))


def core_switch():
    profile()
    enable_debug_logs()
    launch()
    open_core()
    assert '1.14.0' in find(tree(),resource_id=PACKAGE+':id/core_details').get('text','')
    capture('20-core-stable')
    for channel,version in [('preview','1.15.0-alpha.2'),('stable','1.14.0')]:
        tap(scroll_for(resource_id=PACKAGE+':id/core_'+channel))
        tap(scroll_for(resource_id=PACKAGE+':id/core_apply',enabled='true'))
        tap(wait_for(resource_id='android:id/button1'))
        time.sleep(4)
        wait_for(resource_id=PACKAGE+':id/toolbar')
        open_core()
        assert version in find(tree(),resource_id=PACKAGE+':id/core_details').get('text','')
        capture('21-core-switched-'+channel)
        # The persisted selection must also be used by a fresh VPN background process.
        service()
        open_core()
        assert version in find(tree(),resource_id=PACKAGE+':id/core_details').get('text','')
    tap(scroll_for(resource_id=PACKAGE+':id/core_restore',enabled='true'))
    tap(wait_for(resource_id='android:id/button1'))
    time.sleep(4)
    launch()
    open_core()
    assert STRINGS['core_builtin'] in find(tree(),resource_id=PACKAGE+':id/core_running').get('text','')
    capture('22-core-restored')


def core_download():
    """Opt-in live Release download, JNI loading, corruption recovery and rollback."""
    profile()
    enable_debug_logs()
    adb('root')
    adb('wait-for-device')
    core_root='/data/user/0/'+PACKAGE+'/no_backup/cores'
    def state():
        return json.loads(adb('shell','cat',core_root+'/state.json'))
    def wait_status(text, timeout=210):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            n=find(tree(),resource_id=PACKAGE+':id/core_status')
            if n is not None and n.get('text')==text:
                return
            time.sleep(1)
        raise AssertionError('Core update did not finish: '+text)
    def apply():
        tap(scroll_for(resource_id=PACKAGE+':id/core_apply',enabled='true'))
        tap(wait_for(resource_id='android:id/button1'))
        time.sleep(4)
        wait_for(resource_id=PACKAGE+':id/toolbar')
        open_core()
    def fetch_core(channel):
        tap(scroll_for(resource_id=PACKAGE+':id/core_'+channel))
        tap(scroll_for(resource_id=PACKAGE+':id/core_check',enabled='true'))
        tap(wait_for(resource_id=PACKAGE+':id/core_download',enabled='true'))
        wait_status(STRINGS['core_ready'])
    for channel,version in [('stable','1.14.0'),('preview','1.15.0-alpha.2')]:
        launch(); open_core()
        before={}
        if adb('shell','ls',core_root+'/state.json',check=False).strip():
            before=state().get('enabled',{})
        fetch_core(channel)
        saved=state()
        assert saved.get('enabled',{})==before, 'Download activated the core before restart'
        assert channel in saved['installed']
        apply()
        text=find(tree(),resource_id=PACKAGE+':id/core_running').get('text','')
        assert STRINGS['core_downloaded'] in text and version in text, text
        pid=adb('shell','pidof',PACKAGE).strip()
        maps=adb('shell','cat','/proc/'+pid+'/maps')
        path=core_root+'/packages/'+state()['enabled'][channel]+'/libgojni.so'
        assert '/no_backup/cores/packages/'+state()['enabled'][channel]+'/libgojni.so' in maps, 'Application did not load downloaded native code'
        (OUT/('core-runtime-maps-'+channel+'.txt')).write_text('\n'.join(line for line in maps.splitlines() if 'libgojni' in line))
        capture('23-core-downloaded-'+channel)
        service()
        bg=adb('shell','pidof',PACKAGE+':bg').strip()
        assert '/no_backup/cores/packages/'+saved['installed'][channel]+'/libgojni.so' in adb('shell','cat','/proc/'+bg+'/maps'), 'VPN process used a different native core'
    launch(); open_core()
    selected=state()['enabled']['preview']
    binary=core_root+'/packages/'+selected+'/libgojni.so'
    adb('shell','am','force-stop',PACKAGE)
    adb('shell','chmod','600',binary)
    adb('shell','dd','if=/dev/zero','of='+binary,'bs=1','count=1','conv=notrunc')
    adb('shell','chmod','444',binary)
    launch(); open_core()
    assert STRINGS['core_builtin'] in find(tree(),resource_id=PACKAGE+':id/core_running').get('text','')
    assert 'preview' not in state().get('enabled',{})
    capture('24-core-corruption-recovered')
    # Re-download repairs the existing bad cache entry rather than getting stuck on its hash directory.
    fetch_core('preview'); apply()
    assert STRINGS['core_downloaded'] in find(tree(),resource_id=PACKAGE+':id/core_running').get('text','')
    capture('25-core-cache-repaired')
    adb('shell','am','force-stop',PACKAGE)
    marker=OUT/'boot-marker.txt'; marker.write_text('interrupted initialization')
    adb('push',str(marker),core_root+'/boot-main.json')
    owner=adb('shell','stat','-c','%u:%g',core_root).strip()
    adb('shell','chown',owner,core_root+'/boot-main.json')
    launch(); open_core()
    current=state()
    assert current['channel']=='stable' and not current.get('enabled')
    assert STRINGS['core_builtin'] in find(tree(),resource_id=PACKAGE+':id/core_running').get('text','')
    capture('26-core-startup-rollback')


def enable_debug_logs():
    launch(); navigate('nav_settings')
    tap(scroll_for(text=STRINGS['log_level'])); tap(wait_for(text='debug'))
    launch()


def compact():
    launch()
    capture('10-compact-main-large-text')
    navigate('nav_settings')
    capture('11-compact-settings-large-text')
    open_drawer()
    capture('12-compact-drawer-large-text')
    adb('shell','input','keyevent','BACK')


def landscape():
    launch()
    capture('13-landscape-main')
    navigate('nav_tools')
    capture('14-landscape-tools')


def run_check(name, check):
    if ONLY_CHECKS and name not in ONLY_CHECKS:
        return
    try:
        check()
        print('PASS:', name, flush=True)
    except Exception as error:
        FAILURES.append({'check': name, 'error': str(error)})
        traceback.print_exc()
        (OUT / ('failure-' + name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True, check=False))
        (OUT / ('failure-' + name + '-core.log')).write_text(adb('shell','cat','/data/user/0/'+PACKAGE+'/cache/neko.log',check=False))
        try:
            (OUT / ('failure-' + name + '.xml')).write_text(ET.tostring(tree(), encoding='unicode'), encoding='utf-8')
        except Exception:
            pass
    finally:
        adb('shell','am','force-stop',PACKAGE)



def main():
    try:
        # The isolated Google APIs emulator permits root diagnostics. Android 15
        # denies netlink interface inspection to shell; the app still runs as its own UID.
        root_emulator()
        apk=next(Path('dist').glob('*x86_64*.apk'))
        adb('install','-r','-g',str(apk))
        adb('shell','logcat','-c')
        adb('shell','cmd','uimode','night','no')
        run_check('startup', startup)
        for name in ['nav_group','nav_route','nav_settings','nav_logcat','nav_tools','nav_about','nav_po0']:
            run_check('light-' + name, lambda name=name: destination(name, '03-light-'))
        run_check('settings', settings)
        run_check('profile', profile)
        run_check('service', service)
        run_check('backup', backup)
        run_check('whitelist', whitelist)
        run_check('launcher', launcher_icon)
        run_check('app-updates', app_updates)
        run_check('core-switch', core_switch)
        if 'core-download' in ONLY_CHECKS:
            run_check('core-download', core_download)
        adb('shell','cmd','uimode','night','yes')
        for name in ['nav_configuration','nav_group','nav_settings','nav_tools','nav_about','nav_po0']:
            run_check('dark-' + name, lambda name=name: destination(name, '09-dark-'))

        adb('shell','cmd','uimode','night','no')
        adb('shell','wm','size','720x1280')
        adb('shell','wm','density','320')
        adb('shell','settings','put','system','font_scale','1.3')
        run_check('compact', compact)
        adb('shell','wm','size','1280x720')
        adb('shell','wm','density','240')
        run_check('landscape', landscape)
        assert not FAILURES, FAILURES
        print('UI SMOKE PASSED:', ', '.join(RESULTS))
    finally:
        (OUT/'results.json').write_text(json.dumps(RESULTS,indent=2),encoding='utf-8')
        (OUT/'failures.json').write_text(json.dumps(FAILURES,indent=2),encoding='utf-8')
        (OUT/'logcat.txt').write_text(adb('shell','logcat','-d',check=False),encoding='utf-8')
        try:
            (OUT/'last-screen.png').write_bytes(adb('exec-out','screencap','-p',binary=True,check=False))
            (OUT/'last-screen.xml').write_text(ET.tostring(tree(),encoding='unicode'),encoding='utf-8')
        except Exception:
            pass


if __name__ == "__main__":
    main()
