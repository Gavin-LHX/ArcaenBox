"""Regression for duplicate initial routes, collapsible settings and the idle viewport."""
import json
import sqlite3


def check(ui):
    adb = ui.adb
    assert adb('get-serialno').strip().startswith('emulator-'), 'Database fixtures require an isolated emulator'

    def preference_switch(title):
        """Resolve only the named preference, after its whole row is visible."""
        for _ in range(8):
            ui.scroll_for(text=title)
            doc = ui.tree()
            parents = {c: p for p in doc.iter() for c in p}
            row = ui.find(doc, text=title)
            assert row is not None, f'Preference title disappeared: {title}'
            while row.get('clickable') != 'true':
                row = parents.get(row)
                assert row is not None and row.get('resource-id') != ui.PACKAGE+':id/recycler_view', \
                    f'Clickable preference row missing: {title}'
            viewport = ui.find(doc, resource_id=ui.PACKAGE+':id/recycler_view')
            assert viewport is not None, 'Settings list missing'
            left, top, right, bottom = ui.bounds(viewport)
            _, row_top, _, row_bottom = ui.bounds(row)
            switch = ui.find(row, resource_id=ui.PACKAGE+':id/material_switch')
            # UIAutomator clips offscreen widgets. Never climb beyond this row
            # to find a switch belonging to another preference in the list.
            if top < row_top and row_bottom < bottom:
                assert switch is not None, f'Switch missing from preference: {title}'
                return switch
            middle = (top+bottom)//2
            distance = (bottom-top)//3
            start, end = (middle-distance//2, middle+distance//2) if row_top <= top else (middle+distance//2, middle-distance//2)
            adb('shell', 'input', 'swipe', str((left+right)//2), str(start), str((left+right)//2), str(end), '350')
        raise AssertionError(f'Preference row did not become fully visible: {title}')

    ui.launch(); ui.navigate('nav_route')
    doc = ui.tree()
    for control in ('route_preset', 'route_domain_strategy', 'route_preset_help'):
        assert ui.find(doc, resource_id=ui.PACKAGE+':id/'+control) is None
    ui.wait_for(resource_id=ui.PACKAGE+':id/profile_name')
    ui.capture('simplified-routes')
    adb('shell', 'am', 'force-stop', ui.PACKAGE)
    remote = '/data/user/0/'+ui.PACKAGE+'/databases/sager_net.db'
    fixture = ui.OUT/'route-duplicates-fixture.db'
    adb('pull', remote, str(fixture))
    with sqlite3.connect(fixture) as db:
        initial = db.execute('SELECT count(*) FROM rules').fetchone()[0]
        assert initial in (5, 9), f'Default rules seeded more than once: {initial}'
        columns = [r[1] for r in db.execute('PRAGMA table_info(rules)') if r[1] != 'id']
        fields = ','.join('"'+c+'"' for c in columns)
        db.execute(f'INSERT INTO rules ({fields}) SELECT {fields} FROM rules')
        # Two deliberately identical custom rows must survive the targeted repair.
        db.execute(f'INSERT INTO rules ({fields}) SELECT {fields} FROM rules LIMIT 1')
        db.execute("UPDATE rules SET name='User duplicate', domains='example.com' WHERE id=last_insert_rowid()")
        db.execute(f"INSERT INTO rules ({fields}) SELECT {fields} FROM rules WHERE name='User duplicate' LIMIT 1")
    owner = adb('shell','stat','-c','%u:%g',remote).strip()
    adb('push',str(fixture),remote); adb('shell','chown',owner,remote)
    for attempt in range(2):
        ui.launch(); ui.navigate('nav_route'); ui.wait_for(resource_id=ui.PACKAGE+':id/profile_name')
        ui.capture('repaired-routes-'+str(attempt))
        adb('shell','am','force-stop',ui.PACKAGE)
        snapshot=ui.OUT/('repaired-routes-'+str(attempt)+'.db'); adb('pull',remote,str(snapshot))
        with sqlite3.connect(snapshot) as db:
            assert db.execute('SELECT count(*) FROM rules').fetchone()[0] == initial+2
            assert db.execute("SELECT count(*) FROM rules WHERE name='User duplicate'").fetchone()[0] == 2

    ui.launch(); ui.navigate('nav_settings')
    assert ui.find(ui.tree(), text=ui.STRINGS['speed_interval']) is None, 'Notifications expanded by default'
    ui.tap(ui.scroll_for(text=ui.STRINGS['settings_notifications']))
    # Expanded rows can start below the viewport after the shell/style controls.
    ui.scroll_for(text=ui.STRINGS['speed_interval']); ui.capture('settings-expanded')
    ui.tap(ui.scroll_for(text=ui.STRINGS['settings_notifications']))
    assert ui.find(ui.tree(), text=ui.STRINGS['speed_interval']) is None, 'Cannot collapse settings'
    ui.capture('settings-collapsed')
    address_before = preference_switch(ui.STRINGS['always_show_address']).get('checked')
    switch = preference_switch(ui.STRINGS['show_connection_on_pages'])
    if switch.get('checked')!='true': ui.tap(switch)
    assert preference_switch(ui.STRINGS['show_connection_on_pages']).get('checked') == 'true', \
        'Connection controls preference did not enable'
    assert preference_switch(ui.STRINGS['always_show_address']).get('checked') == address_before, \
        'Enabling connection controls changed Always Show Address'
    ui.navigate('nav_configuration'); ui.navigate('nav_settings')
    ui.tap(ui.scroll_for(text=ui.STRINGS['settings_notifications']))
    doc=ui.tree(); button=ui.assert_disconnected_footer(doc)
    content=ui.find(doc,resource_id=ui.PACKAGE+':id/fragment_holder')
    assert ui.bounds(content)[3] >= ui.bounds(button)[3], 'Idle bottom bar still truncates the content viewport'
    ui.capture('settings-idle-full-height')
    # The tests keep all settings values; folding only changes visibility.
    ui.tap(ui.scroll_for(text=ui.STRINGS['settings_notifications']))
    ui.navigate('nav_configuration'); ui.navigate('nav_settings')
    assert ui.find(ui.tree(),text=ui.STRINGS['speed_interval']) is None
    (ui.OUT/'simplified-ui-results.json').write_text(json.dumps({
        'initial_rules': initial, 'seed_duplicates_repaired': True,
        'custom_duplicates_preserved': True, 'repair_idempotent': True,
        'expand_collapse': True, 'idle_full_viewport': True,
    },indent=2))
