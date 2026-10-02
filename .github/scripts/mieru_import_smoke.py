"""Round-trip an official Mieru simple URI using an isolated, offline UI fixture."""
import json
import re
import shlex
import time


NAME = 'Mieru-import-check'
SAVED_NAME = NAME + '-saved'
# RFC 5737 documentation address and deliberately fake credentials. Never connect.
URI = ('mierus://user:pass@203.0.113.1?handshake-mode=HANDSHAKE_NO_WAIT'
       '&mtu=1400&multiplexing=MULTIPLEXING_OFF&port=18403'
       '&profile=' + NAME + '&protocol=TCP')


def check(ui):
    adb = ui.adb
    assert adb('get-serialno').strip().startswith('emulator-'), \
        'Mieru import fixture requires an isolated emulator'

    def assert_stopped():
        assert 'isForeground=true' not in adb('shell', 'dumpsys', 'activity', 'services', ui.PACKAGE), \
            'Mieru import check must not run a proxy service'

    def profile_actions(name):
        ui.scroll_for(text=name, resource_id=ui.PACKAGE + ':id/profile_name')
        doc = ui.tree()
        parents = {c: p for p in doc.iter() for c in p}
        matches = [n for n in doc.iter('node') if n.get('text') == name
                   and n.get('resource-id') == ui.PACKAGE + ':id/profile_name']
        assert len(matches) == 1, 'Expected exactly one imported Mieru fixture card'
        row = matches[0]
        while row is not None:
            cards = [n for n in row.iter('node')
                     if n.get('resource-id') == ui.PACKAGE + ':id/profile_name']
            assert len(cards) == 1, 'Mieru actions escaped the fixture card'
            actions = ui.find(row, resource_id=ui.PACKAGE + ':id/node_actions')
            if actions is not None:
                return actions
            row = parents.get(row)
        raise AssertionError('Imported Mieru card actions missing')

    def open_editor(name):
        # Read from the database after a fresh process, not the editor's cache.
        ui.launch()
        ui.navigate('nav_configuration')
        assert_stopped()
        ui.tap(profile_actions(name))
        ui.tap(ui.wait_for(text=ui.STRINGS['edit']))
        ui.wait_for(text=ui.STRINGS['server_address'])
        assert_stopped()

    def preference_row(key):
        title = ui.STRINGS[key]
        for _ in range(6):
            # A node editor has no global-settings foldouts. MTU shares its
            # label with the global VPN setting, but belongs to this profile.
            ui.scroll_for(expand_categories=False, text=title, resource_id='android:id/title')
            doc = ui.tree()
            parents = {c: p for p in doc.iter() for c in p}
            row = ui.find(doc, text=title, resource_id='android:id/title')
            assert row is not None, 'Mieru preference disappeared: ' + key
            while row.get('clickable') != 'true':
                row = parents.get(row)
                assert row is not None and row.get('resource-id') != ui.PACKAGE + ':id/recycler_view', \
                    'Mieru preference row missing: ' + key
            viewport = ui.find(doc, resource_id=ui.PACKAGE + ':id/recycler_view')
            assert viewport is not None, 'Mieru editor list missing'
            left, top, right, bottom = ui.bounds(viewport)
            _, row_top, _, row_bottom = ui.bounds(row)
            title_node = ui.find(row, text=title, resource_id='android:id/title')
            value = ui.find(row, resource_id='android:id/summary')
            # The first row can align exactly with the viewport. Require its
            # title and value inside the viewport instead of scrolling forever.
            visible = [n for n in (title_node, value) if n is not None]
            if visible and all(top < ui.bounds(n)[1] and ui.bounds(n)[3] < bottom for n in visible):
                return row
            middle = (top + bottom) // 2
            offset = (bottom - top) // 6
            start, end = ((middle - offset, middle + offset) if row_top <= top
                          else (middle + offset, middle - offset))
            adb('shell', 'input', 'swipe', str((left + right) // 2), str(start),
                str((left + right) // 2), str(end), '350')
        raise AssertionError('Mieru preference row clipped: ' + key)

    def summary(key, expected):
        node = ui.find(preference_row(key), resource_id='android:id/summary')
        assert node is not None and node.get('text') == expected, \
            'Imported Mieru preference not preserved: ' + key

    def option(key, expected_key, stage):
        expected = ui.STRINGS[expected_key]
        summary(key, expected)
        ui.tap(preference_row(key))
        selected = ui.wait_for(text=expected, resource_id='android:id/text1')
        assert selected.get('checked') == 'true', 'Wrong selected Mieru option: ' + key
        ui.capture('mieru-' + stage + '-' + key)
        adb('shell', 'input', 'keyevent', 'BACK')
        summary(key, expected)

    def verify_editor(name, stage):
        for key, expected in [('profile_name', name), ('server_address', '203.0.113.1'),
                              ('server_port', '18403'), ('protocol', 'TCP'),
                              ('username', 'user'), ('mtu', '1400')]:
            summary(key, expected)
        option('mieru_handshake_mode', 'mieru_handshake_no_wait', stage)
        option('mieru_multiplexing', 'mieru_multiplexing_off', stage)
        assert_stopped()

    assert_stopped()
    ui.launch()
    ui.navigate('nav_configuration')
    ui.assert_disconnected_footer()
    result = adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
                 '-d', shlex.quote(URI), '-p', ui.PACKAGE)
    assert not re.search(r'unable to resolve|error:', result, re.IGNORECASE), \
        'Mieru simple URI intent not registered'
    ui.wait_for(text=ui.STRINGS['profile_import'])
    ui.capture('mieru-system-link-import-dialog')
    ui.tap(ui.wait_for(resource_id='android:id/button1'))
    ui.wait_for(text=NAME, resource_id=ui.PACKAGE + ':id/profile_name')
    assert_stopped()
    ui.capture('mieru-imported-card')

    open_editor(NAME)
    verify_editor(NAME, 'imported')
    # A benign edit makes save exercise Bean serialization, rather than just exit.
    ui.tap(preference_row('profile_name'))
    field = ui.wait_for(resource_id='android:id/edit')
    assert field.get('text') == NAME, 'Wrong Mieru fixture editor'
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    adb('shell', 'input', 'text', '-saved')
    ui.tap(ui.wait_for(resource_id='android:id/button1'))
    summary('profile_name', SAVED_NAME)
    ui.tap(ui.wait_for(resource_id=ui.PACKAGE + ':id/action_apply'))
    ui.wait_for(text=SAVED_NAME, resource_id=ui.PACKAGE + ':id/profile_name')

    open_editor(SAVED_NAME)
    verify_editor(SAVED_NAME, 'saved-reopened')
    ui.capture('mieru-saved-editor')
    # Remove only our synthetic profile through its own editor. No database writes.
    ui.tap(ui.wait_for(resource_id=ui.PACKAGE + ':id/action_delete'))
    ui.wait_for(text=ui.STRINGS['delete_confirm_prompt'])
    ui.tap(ui.wait_for(resource_id='android:id/button1'))
    ui.wait_for(resource_id=ui.PACKAGE + ':id/fab')
    time.sleep(.5)
    assert ui.find(ui.tree(), text=SAVED_NAME, resource_id=ui.PACKAGE + ':id/profile_name') is None, \
        'Mieru fixture was not removed'
    assert_stopped()
    ui.assert_disconnected_footer()
    (ui.OUT / 'mieru-import-results.json').write_text(json.dumps({
        'system_uri_import': True,
        'cold_reopen_after_import': True,
        'handshake_mode': 'HANDSHAKE_NO_WAIT',
        'multiplexing': 'MULTIPLEXING_OFF',
        'save_and_cold_reopen': True,
        'fixture_removed': True,
        'proxy_service_started': False,
    }, indent=2), encoding='utf-8')
