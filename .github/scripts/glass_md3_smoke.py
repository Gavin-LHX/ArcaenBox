"""Glass and MD3 must share the dashboard, node list and secondary navigation."""
import io
import json
import re
import time
from PIL import Image, ImageChops, ImageStat


def check(ui):
    adb = ui.adb
    assert adb('get-serialno').strip().startswith('emulator-')
    original_night = adb('shell', 'cmd', 'uimode', 'night').strip()
    original_style = None
    original_reduce = None
    original_size = adb('shell', 'wm', 'size')
    original_density = adb('shell', 'wm', 'density')

    def style(name=None):
        ui.navigate('nav_settings')
        ui.scroll_for(text=ui.STRINGS['interface_style'])
        if name is not None:
            ui.tap(ui.scroll_for(text=ui.STRINGS['interface_style']))
            ui.tap(ui.wait_for(text=name, resource_id='android:id/text1'))
            ui.wait_for(text=name, resource_id='android:id/summary')
            ui.wait_for_interface(True)
        doc = ui.tree()
        names = [value for value in ('Material Design 3', 'Liquid Glass')
                 if ui.find(doc, text=value, resource_id='android:id/summary') is not None]
        assert len(names) == 1, 'Interface style summary missing or ambiguous'
        return names[0]

    def transparency(reduced=None):
        ui.navigate('nav_settings')
        title = ui.STRINGS['glass_reduce_transparency']
        ui.scroll_for(text=title)
        doc = ui.tree()
        row = ui.find(doc, text=title)
        parents = {c: p for p in doc.iter() for c in p}
        while row is not None and row.get('clickable') != 'true':
            row = parents.get(row)
        assert row is not None, 'Reduce transparency preference missing'
        switch = ui.find(row, resource_id=ui.PACKAGE + ':id/material_switch')
        assert switch is not None, 'Reduce transparency switch missing from its row'
        checked = switch.get('checked') == 'true'
        if reduced is not None and checked != reduced:
            ui.tap(row)
            ui.wait_for_interface(True)
            assert transparency() == reduced, 'Transparency setting did not persist'
        return checked

    def nodes():
        ui.navigate('nav_configuration')
        doc = ui.wait_for_interface(True)
        button = ui.assert_disconnected_footer(doc, flat_background=False)
        holder = ui.find(doc, resource_id=ui.PACKAGE + ':id/fragment_holder')
        nav = ui.shell_navigation(doc)
        assert ui.bounds(holder)[3] >= ui.bounds(button)[3], 'Idle shell reserves a footer'
        if nav.get('resource-id') == ui.PACKAGE + ':id/bottom_nav':
            assert ui.bounds(button)[3] <= ui.bounds(nav)[1], 'Connect button overlaps bottom navigation'
        else:
            assert ui.bounds(button)[0] >= ui.bounds(nav)[2], 'Connect button overlaps navigation rail'
        return Image.open(io.BytesIO(adb('exec-out', 'screencap', '-p', binary=True))).convert('RGB')

    def shell_pages(prefix):
        ui.navigate('nav_home')
        ui.wait_for(resource_id=ui.PACKAGE + ':id/power_button', enabled='true')
        assert ui.wait_for(resource_id=ui.PACKAGE + ':id/status_title').get('text') == ui.STRINGS['not_connected']
        assert ui.find(ui.tree(), resource_id=ui.PACKAGE + ':id/fab') is None, 'Node FAB shown on Home'
        ui.capture(prefix + '-home')
        ui.navigate('nav_more')
        ui.wait_for(resource_id=ui.PACKAGE + ':id/more_scroll')
        assert ui.find(ui.tree(), resource_id=ui.PACKAGE + ':id/fab') is None, 'Node FAB shown on More'
        ui.capture(prefix + '-more')
        ui.navigate('nav_settings')
        toolbar = ui.wait_for(resource_id=ui.PACKAGE + ':id/toolbar')
        assert any(n.get('class') == 'android.widget.ImageButton' for n in toolbar.iter('node')), \
            'Glass secondary page lost its Back button'
        adb('shell', 'input', 'keyevent', 'BACK')
        ui.wait_for(resource_id=ui.PACKAGE + ':id/more_scroll')

    try:
        adb('shell', 'cmd', 'uimode', 'night', 'no')
        ui.launch('redesigned')
        original_style = style()
        original_reduce = transparency()
        transparency(False)
        style('Material Design 3')
        before = nodes()
        ui.capture('glass-md3-reference-nodes')
        style('Liquid Glass')
        after = nodes()
        ui.capture('glass-md3-light-nodes')
        assert before.size == after.size
        width, height = after.size
        nav = ui.shell_navigation(ui.tree())
        nav_top = ui.bounds(nav)[1]
        area = (0, height // 3, width, nav_top)
        assert max(ImageStat.Stat(ImageChops.difference(before.crop(area), after.crop(area))).mean) > 3, \
            'Glass renders the same node surface as MD3'
        shell_pages('glass-md3-light')
        ui.launch('redesigned')
        assert style() == 'Liquid Glass', 'Glass style lost on cold MD3 launch'
        ui.interface_check(lambda module: module.service(flat_background=False),
                           'redesigned', 'glass-md3-service')
        adb('shell', 'cmd', 'uimode', 'night', 'yes')
        ui.launch('redesigned')
        nodes()
        ui.capture('glass-md3-dark-nodes')
        shell_pages('glass-md3-dark')
        adb('shell', 'wm', 'size', '1280x720')
        adb('shell', 'wm', 'density', '240')
        ui.wait_for(resource_id=ui.PACKAGE + ':id/nav_rail')
        nodes()
        ui.capture('glass-md3-rail-nodes')
        shell_pages('glass-md3-rail')
        size = re.search(r'Override size: (\d+x\d+)', original_size)
        density = re.search(r'Override density: (\d+)', original_density)
        adb('shell', 'wm', 'size', size[1] if size else 'reset')
        adb('shell', 'wm', 'density', density[1] if density else 'reset')
        ui.wait_for_interface(True)
        style('Material Design 3')
        assert style() == 'Material Design 3'
        nodes()
        ui.capture('glass-md3-restored-nodes')
        (ui.OUT / 'glass-md3-results.json').write_text(json.dumps({
            'both_style_directions': True, 'cold_launch_persistence': True,
            'rendering_differs_from_md3': True, 'light_dark_shell_navigation': True,
            'home_more_no_node_fab': True, 'secondary_back_stack': True,
            'idle_full_node_viewport': True, 'fab_above_navigation': True,
            'vpn_connect_disconnect_footer': True, 'navigation_rail': True,
        }, indent=2), encoding='utf-8')
    finally:
        size = re.search(r'Override size: (\d+x\d+)', original_size)
        density = re.search(r'Override density: (\d+)', original_density)
        adb('shell', 'wm', 'size', size[1] if size else 'reset')
        adb('shell', 'wm', 'density', density[1] if density else 'reset')
        if original_style is not None:
            ui.launch('redesigned')
            if original_reduce is not None:
                transparency(original_reduce)
            style(original_style)
        mode = original_night.rsplit(':', 1)[-1].strip()
        if mode in ('yes', 'no', 'auto'):
            adb('shell', 'cmd', 'uimode', 'night', mode)
