"""Same-name imports must appear immediately, without running a node test."""
import json
import io
import shlex
import time


NAME = 'Same-name-refresh-fixture'


def check(ui):
    assert ui.adb('get-serialno').strip().startswith('emulator-'), \
        'Profile refresh check requires an isolated emulator'

    def rows():
        doc = ui.tree()
        return doc, [n for n in doc.iter('node')
                     if n.get('resource-id') == ui.PACKAGE + ':id/profile_name'
                     and n.get('text') == NAME]

    def expect(count):
        deadline = time.monotonic() + 20
        while True:
            doc, matches = rows()
            if len(matches) == count:
                return doc, matches
            assert time.monotonic() < deadline, \
                f'Expected {count} same-name cards without refresh; visible: {len(matches)}'
            time.sleep(.2)

    def stopped():
        assert 'isForeground=true' not in ui.adb('shell', 'dumpsys', 'activity', 'services', ui.PACKAGE), \
            'Import checks must not start a proxy service'

    ui.launch()
    ui.navigate('nav_configuration')
    stopped()
    assert not rows()[1], 'Fixture already exists; do not remove unknown profiles'
    results = []
    try:
        for index, port in enumerate((19081, 19082), 1):
            # Only the port differs. Display names must never be used as identity.
            uri = f'socks://127.0.0.1:{port}#{NAME}'
            ui.adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
                   '-d', shlex.quote(uri), '-p', ui.PACKAGE)
            ui.wait_for(text=ui.STRINGS['profile_import'])
            ui.tap(ui.wait_for(resource_id='android:id/button1'))
            expect(index)
            stopped()
            ui.capture(f'profile-refresh-import-{index}')
            results.append({'import': index, 'visible_without_reload': index})

        ui.navigate('nav_about')
        ui.navigate('nav_configuration')
        expect(2)
        ui.capture('profile-refresh-return')
        # Rotation recreates the Activity/view, exercising listener teardown.
        rotation = ui.adb('shell', 'settings', 'get', 'system', 'user_rotation').strip()
        auto_rotate = ui.adb('shell', 'settings', 'get', 'system', 'accelerometer_rotation').strip()
        try:
            ui.adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
            ui.adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
            from PIL import Image
            deadline = time.monotonic() + 20
            while True:
                image = Image.open(io.BytesIO(ui.adb('exec-out', 'screencap', '-p', binary=True)))
                if image.width > image.height:
                    break
                assert time.monotonic() < deadline, 'Landscape recreation did not complete'
                time.sleep(.3)
            ui.wait_for(resource_id=ui.PACKAGE + ':id/toolbar')
            ui.adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
            deadline = time.monotonic() + 20
            while True:
                image = Image.open(io.BytesIO(ui.adb('exec-out', 'screencap', '-p', binary=True)))
                if image.height > image.width:
                    break
                assert time.monotonic() < deadline, 'Portrait recreation did not complete'
                time.sleep(.3)
            expect(2)
        finally:
            for key, value in (('user_rotation', rotation), ('accelerometer_rotation', auto_rotate)):
                ui.adb('shell', 'settings', 'delete' if value == 'null' else 'put',
                       'system', key, *([] if value == 'null' else [value]))
        ui.launch()
        expect(2)
        ui.capture('profile-refresh-cold-reopen')
        stopped()
    finally:
        # Use each fixture's own editor, never clear the database or user data.
        ui.launch()
        ui.navigate('nav_configuration')
        for _ in range(2):
            doc, matches = rows()
            if not matches:
                break
            parents = {child: parent for parent in doc.iter() for child in parent}
            row = matches[0]
            while ui.find(row, resource_id=ui.PACKAGE + ':id/node_actions') is None:
                row = parents[row]
                assert len([n for n in row.iter('node')
                            if n.get('resource-id') == ui.PACKAGE + ':id/profile_name']) == 1
            ui.tap(ui.find(row, resource_id=ui.PACKAGE + ':id/node_actions'))
            ui.tap(ui.wait_for(text=ui.STRINGS['edit']))
            ui.tap(ui.wait_for(resource_id=ui.PACKAGE + ':id/action_delete'))
            ui.wait_for(text=ui.STRINGS['delete_confirm_prompt'])
            ui.tap(ui.wait_for(resource_id='android:id/button1'))
            ui.wait_for(resource_id=ui.PACKAGE + ':id/fab')
        expect(0)
    (ui.OUT / 'profile-refresh-results.json').write_text(json.dumps({
        'imports': results, 'same_name_cards': 2, 'navigation_return': True,
        'activity_recreation': True, 'cold_reopen': True, 'fixtures_removed': True,
        'node_tests_used_to_refresh': False, 'proxy_service_started': False,
    }, indent=2), encoding='utf-8')
