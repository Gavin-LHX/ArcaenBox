"""Home glass controls retain feedback across connection states and style switches."""
import io
import json
import re
import shlex
import time
import traceback
from PIL import Image, ImageChops, ImageStat


def check(ui):
    adb = ui.adb
    assert adb('get-serialno').strip().startswith('emulator-')
    assert 'isForeground=true' not in adb('shell', 'dumpsys', 'activity', 'services', ui.PACKAGE), \
        'Home glass check requires a stopped fixture VPN'
    keys = ('animator_duration_scale', 'transition_animation_scale', 'window_animation_scale')
    animations = {key: adb('shell', 'settings', 'get', 'global', key).strip() for key in keys}
    original_style = original_reduce = original_interval = None
    fixture = 'Home-glass-loopback-' + str(int(time.time()))
    imported = False
    failed = False

    def preference(key):
        ui.scroll_for(text=ui.STRINGS[key])
        doc = ui.tree()
        row = ui.find(doc, text=ui.STRINGS[key], resource_id='android:id/title')
        parents = {child: parent for parent in doc.iter() for child in parent}
        while row is not None and row.get('clickable') != 'true':
            row = parents.get(row)
        assert row is not None, 'Home glass preference missing: ' + key
        return row

    def style(value=None):
        ui.navigate('nav_settings')
        row = preference('interface_style')
        summary = ui.find(row, resource_id='android:id/summary')
        assert summary is not None, 'Interface style summary missing'
        previous = summary.get('text')
        if value is not None and previous != value:
            ui.tap(row)
            ui.tap(ui.wait_for(text=value, resource_id='android:id/text1'))
            ui.wait_for(text=value, resource_id='android:id/summary')
            ui.wait_for_interface(True)
        return previous

    def transparency(value=None):
        ui.navigate('nav_settings')
        row = preference('glass_reduce_transparency')
        switch = ui.find(row, resource_id=ui.PACKAGE + ':id/material_switch')
        assert switch is not None, 'Reduce transparency switch missing from its own row'
        previous = switch.get('checked') == 'true'
        if value is not None and previous != value:
            ui.tap(row)
            ui.wait_for_interface(True)
            assert transparency() == value, 'Reduce transparency value did not persist'
        return previous

    def interval(value=None):
        ui.navigate('nav_settings')
        row = preference('speed_interval')
        summary = ui.find(row, resource_id='android:id/summary')
        assert summary is not None, 'Speed interval summary missing'
        previous = summary.get('text')
        if value is not None and previous != value:
            ui.tap(row)
            ui.tap(ui.wait_for(text=value, resource_id='android:id/text1'))
            assert ui.find(preference('speed_interval'), resource_id='android:id/summary').get('text') == value
        return previous

    def expose(view_id):
        identity = {'resource_id': ui.PACKAGE + ':id/' + view_id}
        ui.scroll_for(**identity)
        for _ in range(8):
            doc = ui.tree()
            target = ui.find(doc, **identity)
            viewport = ui.find(doc, resource_id=ui.PACKAGE + ':id/home_scroll')
            assert target is not None and viewport is not None, 'Home control or viewport missing'
            left, top, right, bottom = ui.bounds(viewport)
            _, control_top, _, control_bottom = ui.bounds(target)
            margin = max(8, (control_bottom - control_top) // 12)
            if top + margin <= control_top and control_bottom <= bottom - margin:
                return target
            high, low = top + (bottom-top)//4, top + 3*(bottom-top)//4
            start, end = (high, low) if control_top < top + margin else (low, high)
            # Scroll from the gutter, away from the connect control.
            x = left + max(8, (right-left)//20)
            adb('shell', 'input', 'swipe', str(x), str(start), str(x), str(end), '350')
        raise AssertionError('Home control could not be fully exposed: ' + view_id)

    def frame(name, area):
        data = adb('exec-out', 'screencap', '-p', binary=True)
        (ui.OUT / (name + '.png')).write_bytes(data)
        if name not in ui.RESULTS:
            ui.RESULTS.append(name)
        image = Image.open(io.BytesIO(data)).convert('RGB')
        assert 0 <= area[0] < area[2] <= image.width and 0 <= area[1] < area[3] <= image.height
        return image.crop(area)

    def difference(first, second):
        assert first.size == second.size, 'Home control geometry changed between styles'
        return max(ImageStat.Stat(ImageChops.difference(first, second)).mean)

    def controls(prefix):
        ui.navigate('nav_home')
        ui.wait_for(resource_id=ui.PACKAGE + ':id/power_button', enabled='true',
                    content_desc=ui.STRINGS['connect'])
        images = {}
        for view_id in ('power_button', 'action_apps'):
            target = expose(view_id)
            assert target.get('enabled') == 'true', 'Home control disabled: ' + view_id
            images[view_id] = (ui.bounds(target), frame(prefix + '-' + view_id, ui.bounds(target)))
        ui.capture(prefix + '-hierarchy')
        return images

    def identity():
        services = adb('shell', 'dumpsys', 'activity', 'services', ui.PACKAGE)
        tun = re.findall(r'\b(tun\d+):', adb('shell', 'ip', '-o', 'link', 'show'))
        return ('isForeground=true' in services, tuple(tun), adb('shell', 'pidof', ui.PACKAGE + ':bg').strip())

    def motion(prefix):
        target = expose('power_button')
        left, top, right, bottom = ui.bounds(target)
        x, y = (left+right)//2, (top+bottom)//2
        area = (left, top, right, bottom)
        before_service = identity()
        rest = frame(prefix + '-rest', area)

        def rendered(name, reference, changed):
            # Reuse the existing motion check's image tolerance and frame wait.
            deadline = time.monotonic() + 4
            while True:
                image = frame(name, area)
                delta = difference(reference, image)
                if (delta > 1 if changed else delta < 2) or time.monotonic() >= deadline:
                    return image
                time.sleep(.15)

        try:
            adb('shell', 'input', 'motionevent', 'DOWN', str(x), str(y))
            pressed = rendered(prefix + '-pressed', rest, True)
            # Stay horizontal so NestedScrollView retains the button gesture;
            # a vertical move would correctly transfer ownership to scrolling.
            adb('shell', 'input', 'motionevent', 'MOVE', str(right-(right-left)//5), str(y))
            dragged = rendered(prefix + '-dragged', pressed, True)
        finally:
            # CANCEL cannot call performClick, regardless of the last pointer position.
            adb('shell', 'input', 'motionevent', 'CANCEL', str(x), str(y))
        released = rendered(prefix + '-released', rest, False)
        assert difference(rest, pressed) > 1, 'Home power press has no visible glass feedback'
        assert difference(pressed, dragged) > .5, 'Home power glass does not follow the pointer'
        assert difference(rest, released) < 2, 'Home power glass did not return to rest'
        assert ui.bounds(expose('power_button')) == (left, top, right, bottom), 'Home power hit target moved'
        assert identity() == before_service, 'Cancelled Home power drag changed the VPN'
        return rest

    def service(expected):
        deadline = time.monotonic() + (25 if expected else 15)
        while True:
            foreground, tun, _ = identity()
            if foreground == expected and bool(tun) == expected:
                return
            assert time.monotonic() < deadline, 'Home VPN did not ' + ('start' if expected else 'stop')
            time.sleep(.4)

    try:
        ui.launch('redesigned')
        original_style = style()
        original_reduce = transparency()
        original_interval = interval()
        interval(ui.STRINGS['disable'])
        transparency(False)
        ui.navigate('nav_configuration')
        uri = 'socks://127.0.0.1:9#' + fixture
        adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d', shlex.quote(uri), '-p', ui.PACKAGE)
        ui.wait_for(text=ui.STRINGS['profile_import'])
        ui.tap(ui.wait_for(resource_id='android:id/button1'))
        imported = True
        ui.tap(ui.scroll_for(text=fixture, resource_id=ui.PACKAGE + ':id/profile_name'))
        style('Material Design 3')
        md3 = controls('home-md3-reference')
        style('Liquid Glass')
        glass = controls('home-glass-reference')
        for view_id in md3:
            assert md3[view_id][0] == glass[view_id][0], 'Style switch moved Home control: ' + view_id
            # Existing style checks use this same visible-surface threshold.
            assert difference(md3[view_id][1], glass[view_id][1]) > 3, 'Home control still renders as MD3: ' + view_id
        for key in keys:
            adb('shell', 'settings', 'put', 'global', key, '1')
        ui.navigate('nav_home')
        # The quick-action reference leaves this same Home instance scrolled.
        expose('power_button')
        ui.wait_for(resource_id=ui.PACKAGE + ':id/status_title', text=ui.STRINGS['not_connected'])
        stopped_before = motion('home-glass-stopped-before')
        adb('shell', 'appops', 'set', ui.PACKAGE, 'ACTIVATE_VPN', 'allow')
        ui.tap(expose('power_button'))
        service(True)
        ui.wait_for(resource_id=ui.PACKAGE + ':id/power_button', content_desc=ui.STRINGS['stop'], enabled='true')
        ui.capture('home-glass-connected')
        motion('home-glass-connected')
        ui.tap(expose('power_button'))
        service(False)
        ui.wait_for(resource_id=ui.PACKAGE + ':id/status_title', text=ui.STRINGS['not_connected'])
        ui.wait_for(resource_id=ui.PACKAGE + ':id/power_button', content_desc=ui.STRINGS['connect'], enabled='true')
        stopped_after = motion('home-glass-stopped-after')
        assert difference(stopped_before, stopped_after) < 2, 'Service state changes did not restore the resting Home glass surface'
        style('Material Design 3')
        restored = controls('home-md3-restored')
        for view_id in md3:
            assert md3[view_id][0] == restored[view_id][0], 'Returning to MD3 moved Home control: ' + view_id
            assert difference(md3[view_id][1], restored[view_id][1]) < 2, 'Returning to MD3 changed Home control rendering: ' + view_id
        results = {'local_profile': True, 'glass_power_and_quick_action': True,
                   'press_drag_cancel_in_stopped_connected_states': True,
                   'cancel_preserved_vpn': True, 'home_connect_disconnect': True,
                   'resting_glass_restored_after_service': True, 'md3_controls_restored': True}
        (ui.OUT / 'home-glass-results.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
    except Exception:
        failed = True
        try:
            ui.capture('failure-home-glass-before-cleanup')
        except Exception:
            try:
                (ui.OUT / 'failure-home-glass-before-cleanup.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True, check=False))
            except Exception:
                pass
        raise
    finally:
        cleanup_errors = []

        def cleanup(label, operation):
            try:
                operation()
                return True
            except Exception as error:
                cleanup_errors.append((label, error, traceback.format_exc()))
                return False

        # Restore each setting even if deleting the fixture fails, and keep
        # any original motion or service assertion as the reported failure.
        cleanup('stop fixture VPN', lambda: adb('shell', 'am', 'force-stop', ui.PACKAGE))
        for key, value in animations.items():
            cleanup('restore ' + key, lambda key=key, value=value:
                    adb('shell', 'settings', 'delete' if value == 'null' else 'put', 'global', key,
                        *([] if value == 'null' else [value])))

        def remove_fixture():
            ui.navigate('nav_configuration')
            ui.scroll_for(text=fixture, resource_id=ui.PACKAGE + ':id/profile_name')
            doc = ui.tree()
            row = ui.find(doc, text=fixture, resource_id=ui.PACKAGE + ':id/profile_name')
            parents = {child: parent for parent in doc.iter() for child in parent}
            while row is not None and ui.find(row, resource_id=ui.PACKAGE + ':id/node_actions') is None:
                row = parents.get(row)
            assert row is not None and sum(n.get('text') == fixture for n in row.iter('node')) == 1, 'Fixture cleanup row ambiguous'
            ui.tap(ui.find(row, resource_id=ui.PACKAGE + ':id/node_actions'))
            ui.tap(ui.wait_for(text=ui.STRINGS['edit']))
            ui.tap(ui.wait_for(resource_id=ui.PACKAGE + ':id/action_delete'))
            ui.wait_for(text=ui.STRINGS['delete_confirm_prompt'])
            ui.tap(ui.wait_for(resource_id='android:id/button1'))
            # An empty group has no configuration_list in the visible hierarchy.
            ui.wait_for(resource_id=ui.PACKAGE + ':id/group_pager')
            deadline = time.monotonic() + 25
            while ui.find(ui.tree(), text=fixture, resource_id=ui.PACKAGE + ':id/profile_name') is not None:
                assert time.monotonic() < deadline, 'Home fixture was not removed'
                time.sleep(.2)

        if cleanup('open fixture cleanup', lambda: ui.launch('redesigned')) and imported:
            cleanup('remove Home fixture', remove_fixture)
        if original_interval is not None:
            cleanup('restore speed interval', lambda: interval(original_interval))
        if original_reduce is not None:
            cleanup('restore transparency', lambda: transparency(original_reduce))
        if original_style is not None:
            cleanup('restore interface style', lambda: style(original_style))
        if cleanup_errors:
            (ui.OUT / 'home-glass-cleanup-errors.txt').write_text(
                '\n\n'.join(label + '\n' + trace for label, _, trace in cleanup_errors), encoding='utf-8')
            if not failed:
                raise cleanup_errors[0][1]
