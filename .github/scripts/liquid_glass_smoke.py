"""Real style switching, persistence, rendering and service regression on an isolated emulator."""
import io
import json
import time
from PIL import Image, ImageChops, ImageStat


def check(ui):
    adb = ui.adb
    assert adb('get-serialno').strip().startswith('emulator-')

    def style(name):
        ui.navigate('nav_settings')
        ui.tap(ui.scroll_for(text=ui.STRINGS['interface_style']))
        ui.tap(ui.wait_for(text=name, resource_id='android:id/text1'))
        time.sleep(1)  # Activity recreation is asynchronous; don't reuse the outgoing preference tree.
        ui.wait_for(text=ui.STRINGS['interface_style'])
        ui.wait_for(text=name, resource_id='android:id/summary')

    def transparency_state():
        title = ui.STRINGS['glass_reduce_transparency']
        for _ in range(6):
            ui.scroll_for(text=title)
            doc = ui.tree()
            parents = {c:p for p in doc.iter() for c in p}
            row = ui.find(doc, text=title)
            assert row is not None, 'Reduce transparency preference missing'
            while row.get('clickable') != 'true':
                row = parents.get(row)
                assert row is not None and row.get('resource-id') != ui.PACKAGE+':id/recycler_view', \
                    'Reduce transparency preference row missing'
            switch = ui.find(row, resource_id=ui.PACKAGE+':id/material_switch')
            if switch is not None:
                return switch.get('checked') == 'true'
            # A partially visible title can have its trailing switch clipped out
            # of the hierarchy. Scroll this row into view, never search the list.
            viewport = ui.find(doc, resource_id=ui.PACKAGE+':id/recycler_view')
            l,t,r,b = ui.bounds(viewport)
            start,end = (t+(b-t)//3,t+2*(b-t)//3) if ui.bounds(row)[1] <= t else (t+2*(b-t)//3,t+(b-t)//3)
            adb('shell','input','swipe',str((l+r)//2),str(start),str((l+r)//2),str(end),'350')
        raise AssertionError('Reduce transparency switch did not become visible')

    def set_transparency(reduced):
        ui.navigate('nav_settings')
        if transparency_state() != reduced:
            ui.tap(ui.scroll_for(text=ui.STRINGS['glass_reduce_transparency']))
            time.sleep(1)
            ui.wait_for(resource_id=ui.PACKAGE+':id/toolbar')
        assert transparency_state() == reduced, 'Reduce transparency did not persist after recreation'

    def screenshot():
        return Image.open(io.BytesIO(adb('exec-out','screencap','-p',binary=True))).convert('RGB')

    def idle():
        doc = ui.tree()
        for key in ('status', 'exit_ip', 'tx', 'rx'):
            assert ui.find(doc, resource_id=ui.PACKAGE+':id/'+key) is None, 'Glass exposed idle footer text'
        button=ui.find(doc, resource_id=ui.PACKAGE+':id/fab')
        ui.assert_connect_button_visible(button)
        holder=ui.find(doc, resource_id=ui.PACKAGE+':id/fragment_holder')
        assert ui.bounds(holder)[3] >= ui.bounds(button)[3], 'Glass reserves an idle footer'

    original_reduce = None
    transparency_restored = False
    try:
        adb('shell','cmd','uimode','night','no')
        ui.launch(); style('Material Design 3'); ui.navigate('nav_configuration')
        before=Image.open(io.BytesIO(adb('exec-out','screencap','-p',binary=True))).convert('RGB')
        style('Liquid Glass')
        original_reduce = transparency_state()
        set_transparency(False)
        ui.navigate('nav_configuration'); idle(); ui.capture('glass-light-main')
        after=Image.open(ui.OUT/'glass-light-main.png').convert('RGB')
        w,h=after.size
        area=(0,h//2,w,h*3//4)
        assert max(ImageStat.Stat(ImageChops.difference(before.crop(area),after.crop(area))).mean)>3, 'Glass renders the same surface as MD3'
        from glass_motion_smoke import check as motion_check
        motion_results = motion_check(ui)
        for target in ('nav_route','nav_settings','nav_kernels'):
            ui.navigate(target); ui.capture('glass-light-'+target)
        ui.open_drawer(); ui.capture('glass-light-drawer'); adb('shell','input','keyevent','BACK')
        adb('shell','am','force-stop',ui.PACKAGE); ui.launch(); ui.navigate('nav_settings')
        assert ui.find(ui.tree(),text='Liquid Glass') is not None, 'Style lost on cold launch'
        ui.service(flat_background=False)
        ui.capture('glass-stopped')
        adb('shell','cmd','uimode','night','yes'); ui.launch(); idle(); ui.capture('glass-dark-main')
        ui.open_drawer();ui.capture('glass-dark-drawer');adb('shell','input','keyevent','BACK')
        adb('shell','settings','put','system','font_scale','1.3')
        ui.launch();ui.navigate('nav_settings');ui.capture('glass-large-font-settings')
        ui.navigate('nav_configuration')
        normal = screenshot()
        set_transparency(True)
        ui.navigate('nav_configuration');idle();ui.capture('glass-reduced-transparency')
        reduced = Image.open(ui.OUT/'glass-reduced-transparency.png').convert('RGB')
        assert max(ImageStat.Stat(ImageChops.difference(normal.crop(area),reduced.crop(area))).mean)>3, \
            'Reduce transparency changed the setting but not the recreated surface'
        assert max(hi-lo for lo,hi in reduced.crop(area).getextrema()) <= 2, \
            'Reduced transparency still renders the ambient glass gradients'
        set_transparency(original_reduce)
        ui.navigate('nav_configuration');ui.capture('glass-restored-transparency')
        expected = reduced if original_reduce else normal
        restored = Image.open(ui.OUT/'glass-restored-transparency.png').convert('RGB')
        assert max(ImageStat.Stat(ImageChops.difference(expected.crop(area),restored.crop(area))).mean)<2, \
            'Restored transparency setting did not restore the rendered surface'
        transparency_restored = True
        style('Material Design 3')
        adb('shell','settings','put','system','font_scale','1.0')
        adb('shell','cmd','uimode','night','no')
        ui.launch();ui.assert_disconnected_footer();ui.capture('glass-return-to-md3')
        ui.navigate('nav_settings')
        assert ui.find(ui.tree(),text='Material Design 3') is not None
        (ui.OUT/'liquid-glass-results.json').write_text(json.dumps({
            'switch_both_directions':True,'cold_launch_persistence':True,
            'rendering_differs_from_md3':True,'idle_no_reserved_bar':True,
            'light_dark_large_font':True,'reduce_transparency':True,
            'vpn_lifecycle':True,
            'motion':motion_results,
        },indent=2))
    finally:
        adb('shell','settings','put','system','font_scale','1.0')
        adb('shell','cmd','uimode','night','no')
        if original_reduce is not None and not transparency_restored:
            ui.launch()
            style('Liquid Glass')
            set_transparency(original_reduce)
