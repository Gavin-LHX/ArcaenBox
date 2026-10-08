"""Upgrade a real signed 1.7.5 APK without clearing its profiles or preferences."""
import hashlib
import json
import os
import re
import shlex
import time
from pathlib import Path


def check(ui, candidate):
    assert ui.adb('get-serialno').strip().startswith('emulator-')
    supplied = os.environ.get('UI_UPGRADE_BASE')
    assert supplied, 'upgrade requires UI_UPGRADE_BASE with the signed 1.7.5 APK'
    base = Path(supplied)
    if base.is_dir():
        matches = list(base.rglob('*x86_64*.apk'))
        assert len(matches) == 1, 'Expected exactly one old signed x86_64 APK'
        base = matches[0]
    assert base.is_file(), 'Upgrade base APK missing'
    name = 'Upgrade-same-name-' + str(int(time.time()))
    ports = {'19091', '19092'}
    imported = 0
    installed_new = False

    def version():
        package = ui.adb('shell', 'dumpsys', 'package', ui.PACKAGE)
        value = re.search(r'\bversionName=([^\s]+)', package)
        code = re.search(r'\bversionCode=(\d+)', package)
        assert value and code, 'Installed application version unavailable'
        return {'name': value[1], 'code': int(code[1])}

    def rows():
        doc = ui.tree()
        return doc, [n for n in doc.iter('node')
                     if n.get('resource-id') == ui.PACKAGE + ':id/profile_name' and n.get('text') == name]

    def expect(count):
        deadline = time.monotonic() + 20
        while True:
            doc, cards = rows()
            if len(cards) == count:
                return doc, cards
            assert time.monotonic() < deadline, f'Upgrade fixture count {len(cards)} != {count}'
            time.sleep(.2)

    def editor(index=0):
        doc, cards = expect(imported)
        row = cards[index]
        parents = {c: p for p in doc.iter() for c in p}
        while ui.find(row, resource_id=ui.PACKAGE + ':id/node_actions') is None:
            row = parents[row]
            assert sum(n.get('resource-id') == ui.PACKAGE + ':id/profile_name' for n in row.iter('node')) == 1
        ui.tap(ui.find(row, resource_id=ui.PACKAGE + ':id/node_actions'))
        ui.tap(ui.wait_for(text=ui.STRINGS['edit']))

    def style(name=None):
        ui.navigate('nav_settings')
        ui.scroll_for(text=ui.STRINGS['interface_style'])
        if name is not None:
            ui.tap(ui.scroll_for(text=ui.STRINGS['interface_style']))
            ui.tap(ui.wait_for(text=name, resource_id='android:id/text1'))
            ui.wait_for(text=name, resource_id='android:id/summary')
        doc = ui.tree()
        values = [value for value in ('Liquid Glass', 'Material Design 3')
                  if ui.find(doc, text=value, resource_id='android:id/summary') is not None]
        assert len(values) == 1, 'Upgrade style summary missing or ambiguous'
        return values[0]

    ui.adb('install', '-r', '-g', str(base))
    old_version = version()
    assert old_version['name'].startswith('1.7.5'), 'Upgrade base is not the real 1.7.5 release'
    ui.launch('classic')
    ui.navigate('nav_configuration')
    assert not rows()[1], 'Unique upgrade fixture unexpectedly exists'
    original_style = None
    try:
        for port in sorted(ports):
            uri = f'socks://127.0.0.1:{port}#{name}'
            ui.adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
                   '-d', shlex.quote(uri), '-p', ui.PACKAGE)
            ui.wait_for(text=ui.STRINGS['profile_import'])
            ui.tap(ui.wait_for(resource_id='android:id/button1'))
            imported += 1
        ui.launch('classic')
        ui.navigate('nav_configuration')
        expect(2)
        ui.capture('upgrade-old-same-name-profiles')
        original_style = style()
        style('Liquid Glass')
        ui.adb('shell', 'am', 'force-stop', ui.PACKAGE)
        ui.adb('install', '-r', '-g', str(candidate))
        installed_new = True
        new_version = version()
        assert new_version['code'] > old_version['code'], 'Candidate does not upgrade the old version'
        expected = re.search(r'^VERSION_NAME=(.+)$', Path('nb4a.properties').read_text(), re.M)[1].strip()
        assert new_version['name'] == expected, 'Installed version differs from candidate source'
        # The old release has no classicUi preference. Its retained data must
        # open in the new default host without an explicit UI switch first.
        ui.adb('shell', 'am', 'start', '-W', '-n', ui.PACKAGE + '/io.nekohasekai.sagernet.ui.MainActivity')
        ui.wait_for_interface(True)
        assert style() == 'Liquid Glass', 'Upgrade lost the stored interface style'
        ui.navigate('nav_configuration')
        expect(2)
        ui.capture('upgrade-new-same-name-profiles')
        saved_ports = set()
        for index in range(2):
            editor(index)
            title = ui.scroll_for(text=ui.STRINGS['server_port'], expand_categories=False)
            doc = ui.tree()
            title = ui.find(doc, text=title.get('text'), resource_id='android:id/title')
            parents = {c: p for p in doc.iter() for c in p}
            row = title
            while row is not None and ui.find(row, resource_id='android:id/summary') is None:
                row = parents.get(row)
            value = ui.find(row, resource_id='android:id/summary') if row is not None else None
            assert value is not None, 'Upgraded node port missing'
            saved_ports.add(value.get('text'))
            ui.capture('upgrade-profile-port-' + str(index))
            ui.adb('shell', 'input', 'keyevent', 'BACK')
            ui.wait_for(resource_id=ui.PACKAGE + ':id/configuration_list')
        assert saved_ports == ports, f'Upgrade merged or changed same-name profiles: {saved_ports}'
        assert 'isForeground=true' not in ui.adb('shell', 'dumpsys', 'activity', 'services', ui.PACKAGE)
        (ui.OUT / 'upgrade-results.json').write_text(json.dumps({
            'base_sha256': hashlib.sha256(base.read_bytes()).hexdigest(),
            'candidate_sha256': hashlib.sha256(Path(candidate).read_bytes()).hexdigest(),
            'old_version': old_version, 'new_version': new_version,
            'same_name_profiles': 2, 'distinct_ports_retained': sorted(saved_ports),
            'stored_glass_style_retained': True, 'default_md3_shell': True,
            'data_cleared': False, 'proxy_service_started': False,
        }, indent=2), encoding='utf-8')
    finally:
        ui.launch('redesigned' if installed_new else 'classic')
        ui.navigate('nav_configuration')
        while imported:
            editor()
            ui.tap(ui.wait_for(resource_id=ui.PACKAGE + ':id/action_delete'))
            ui.wait_for(text=ui.STRINGS['delete_confirm_prompt'])
            ui.tap(ui.wait_for(resource_id='android:id/button1'))
            imported -= 1
            ui.navigate('nav_configuration')
            # The empty ungrouped page has no configuration_list after the last deletion.
            ui.wait_for(resource_id=ui.PACKAGE + ':id/group_pager')
            expect(imported)
        expect(0)
        if original_style is not None:
            style(original_style)
