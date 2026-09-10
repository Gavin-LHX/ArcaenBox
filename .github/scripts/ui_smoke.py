"""Exercise the signed release on an isolated emulator and save UI evidence."""
import json
import re
import subprocess
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = 'com.arcaenbox.android'
OUT = Path('ui-smoke')
OUT.mkdir(exist_ok=True)
STRINGS = {e.get('name'): ''.join(e.itertext()) for e in ET.parse('app/src/main/res/values/strings.xml').getroot() if e.tag == 'string'}
ANDROID = '{http://schemas.android.com/apk/res/android}'
MENU = {e.get(ANDROID + 'id').split('/')[-1]: STRINGS[e.get(ANDROID + 'title').split('/')[-1]] for e in ET.parse('app/src/main/res/menu/main_drawer_menu.xml').iter('item')}
RESULTS = []
FAILURES = []


def adb(*args, binary=False, check=True):
    result = subprocess.run(['adb', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    if check and result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout if binary else result.stdout.decode(errors='replace')


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))


def tree():
    adb('shell', 'rm', '-f', '/sdcard/arcaenbox-ui.xml')
    adb('shell', 'uiautomator', 'dump', '/sdcard/arcaenbox-ui.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/arcaenbox-ui.xml'))


def find(doc, **attrs):
    for node in doc.iter('node'):
        if all(node.get(k.replace('_', '-')) == v for k, v in attrs.items()):
            if len(bounds(node)) == 4 and bounds(node)[2] > bounds(node)[0]:
                return node
    return None


def tap(node):
    assert node is not None, 'Required control not found'
    x1, y1, x2, y2 = bounds(node)
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.4)


def wait_for(**attrs):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        doc = tree()
        node = find(doc, **attrs)
        if node is not None:
            return node
        time.sleep(.4)
    raise AssertionError(f'Control did not appear: {attrs}')


def launch():
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
    capture('02-light-drawer')
    adb('shell','input','keyevent','BACK')
    wait_for(resource_id=PACKAGE + ':id/toolbar')


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
    field=wait_for(resource_id='android:id/edit')
    tap(field)
    time.sleep(.7)
    capture('06-light-editor-keyboard')
    adb('shell','input','keyevent','BACK')
    adb('shell','input','keyevent','BACK')
    adb('shell','input','keyevent','BACK')


def service():
    launch()
    # Only the isolated emulator grants VPN consent. No real proxy is used.
    adb('shell', 'appops', 'set', PACKAGE, 'ACTIVATE_VPN', 'allow')
    tap(wait_for(resource_id=PACKAGE + ':id/profile_name'))
    button=wait_for(resource_id=PACKAGE + ':id/fab')
    tap(button)
    deadline=time.monotonic()+25
    while time.monotonic()<deadline:
        services=adb('shell','dumpsys','activity','services',PACKAGE)
        if 'isForeground=true' in services:
            break
        time.sleep(.5)
    else:
        raise AssertionError('VPN foreground service did not start')
    time.sleep(3)
    # Continuous speed updates prevent uiautomator from becoming idle here.
    # Record the real service state and screenshot, then reuse the unchanged FAB bounds.
    (OUT/'06-light-service-started.txt').write_text(services,encoding='utf-8')
    (OUT/'06-light-service-started.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
    RESULTS.append('06-light-service-started')
    tap(button)
    wait_for(content_desc=STRINGS['connect'])
    services=adb('shell','dumpsys','activity','services',PACKAGE)
    assert 'isForeground=true' not in services, 'VPN foreground service did not stop'


def backup():
    launch()
    navigate('nav_tools')
    tap(wait_for(text=STRINGS['backup']))
    wait_for(resource_id=PACKAGE + ':id/action_export')
    capture('07-light-backup')


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
    try:
        check()
        print('PASS:', name, flush=True)
    except Exception as error:
        FAILURES.append({'check': name, 'error': str(error)})
        traceback.print_exc()
        (OUT / ('failure-' + name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True, check=False))
        try:
            (OUT / ('failure-' + name + '.xml')).write_text(ET.tostring(tree(), encoding='unicode'), encoding='utf-8')
        except Exception:
            pass
    finally:
        adb('shell','am','force-stop',PACKAGE)


try:
    apk=next(Path('dist').glob('*x86_64*.apk'))
    adb('install','-r','-g',str(apk))
    adb('shell','logcat','-c')
    adb('shell','cmd','uimode','night','no')
    run_check('startup', startup)
    for name in ['nav_group','nav_route','nav_settings','nav_logcat','nav_tools','nav_about']:
        run_check('light-' + name, lambda name=name: destination(name, '03-light-'))
    run_check('settings', settings)
    run_check('profile', profile)
    run_check('service', service)
    run_check('backup', backup)
    adb('shell','cmd','uimode','night','yes')
    for name in ['nav_configuration','nav_group','nav_settings','nav_tools','nav_about']:
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
