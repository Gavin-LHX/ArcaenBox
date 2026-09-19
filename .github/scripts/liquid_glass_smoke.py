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
        ui.wait_for(text=ui.STRINGS['interface_style'])
        ui.tap(ui.scroll_for(text=ui.STRINGS['interface_style']))
        ui.tap(ui.wait_for(text=name))
        time.sleep(1)  # Activity recreation is asynchronous; don't reuse the outgoing preference tree.
        ui.wait_for(text=ui.STRINGS['interface_style'])
        assert ui.find(ui.tree(), text=name) is not None, 'Style did not persist after activity recreation'

    def idle():
        doc = ui.tree()
        for key in ('status', 'exit_ip', 'tx', 'rx'):
            assert ui.find(doc, resource_id=ui.PACKAGE+':id/'+key) is None, 'Glass exposed idle footer text'
        button=ui.find(doc, resource_id=ui.PACKAGE+':id/fab')
        ui.assert_connect_button_visible(button)
        holder=ui.find(doc, resource_id=ui.PACKAGE+':id/fragment_holder')
        assert ui.bounds(holder)[3] >= ui.bounds(button)[3], 'Glass reserves an idle footer'

    try:
        adb('shell','cmd','uimode','night','no')
        ui.launch(); ui.navigate('nav_configuration')
        before=Image.open(io.BytesIO(adb('exec-out','screencap','-p',binary=True))).convert('RGB')
        style('Liquid Glass')
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
        ui.tap(ui.scroll_for(text=ui.STRINGS['glass_reduce_transparency']))
        time.sleep(1)
        ui.wait_for(text=ui.STRINGS['interface_style'])
        ui.navigate('nav_configuration');idle();ui.capture('glass-reduced-transparency')
        ui.navigate('nav_settings');ui.tap(ui.scroll_for(text=ui.STRINGS['glass_reduce_transparency']))
        time.sleep(1)
        ui.wait_for(text=ui.STRINGS['interface_style'])
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
