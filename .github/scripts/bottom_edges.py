"""Regression for the navigation-area gap in both the footer and the drawer."""
import io
import re
import time
from PIL import Image


def check(ui):
    adb = ui.adb
    original_overlays = adb('shell', 'cmd', 'overlay', 'list')
    navigation_modes = {
        name: 'com.android.internal.systemui.navbar.' + name
        for name in ('gestural', 'threebutton')
    }
    original_mode = next((value for value in navigation_modes.values()
                          if '[x] ' + value in original_overlays), None)
    original_font = adb('shell', 'settings', 'get', 'system', 'font_scale').strip()
    original_size = adb('shell', 'wm', 'size')
    original_density = adb('shell', 'wm', 'density')
    original_bottom_bar = None

    def bottom_bar_switch():
        ui.scroll_for(text=ui.STRINGS['show_bottom_bar'])
        doc = ui.tree()
        row = ui.find(doc, text=ui.STRINGS['show_bottom_bar'])
        parents = {child: parent for parent in doc.iter() for child in parent}
        while row is not None:
            switch = ui.find(row, resource_id=ui.PACKAGE + ':id/material_switch')
            if switch is not None:
                return switch
            row = parents.get(row)
        raise AssertionError('Bottom bar preference missing')

    def screenshot(name, drawer=False):
        ui.capture(name)
        doc = ui.tree()
        image = Image.open(io.BytesIO(adb('exec-out', 'screencap', '-p', binary=True))).convert('RGB')
        width, height = image.size
        display = adb('shell', 'dumpsys', 'window', 'displays')
        frames = re.findall(r'type=navigationBars frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', display)
        navigation_top = next((int(y1) for x1,y1,x2,y2 in frames
                               if int(y2) == height and int(x1) == 0 and int(x2) == width), None)
        assert navigation_top is not None, 'Bottom navigation frame unavailable'
        target = ui.find(doc, resource_id=ui.PACKAGE + (':id/nav_view' if drawer else ':id/coordinator'))
        assert target is not None, 'Bottom surface missing'
        left, top, right, bottom = ui.bounds(target)
        assert bottom == height, f'{name}: surface stops at {bottom}, screen ends at {height}'
        # Sample uninterrupted background, away from text/icons and the gesture handle.
        # This also catches a bottom scrim, decor gap, or rounded drawer corner.
        for x in (left + 4, right - 4):
            expected = image.getpixel((x, top + 12))
            actual = image.getpixel((x, height - 2))
            assert max(abs(a-b) for a,b in zip(expected, actual)) <= 2, (
                f'{name}: bottom background mismatch at x={x}: {expected} != {actual}')
        if not drawer:
            button = ui.assert_disconnected_footer(doc)
            assert ui.bounds(button)[3] <= navigation_top, f'{name}: A overlaps navigation'
        if mode == 'gestural':
            # The system gesture handle must remain visible on both light and dark surfaces.
            pixels = image.crop((width//3, navigation_top, 2*width//3, height))
            assert max(hi-lo for lo,hi in pixels.getextrema()) >= 60, f'{name}: invisible gesture handle'

    try:
        ui.launch()
        ui.navigate('nav_settings')
        switch = bottom_bar_switch()
        original_bottom_bar = switch.get('checked')
        if original_bottom_bar != 'true':
            ui.tap(switch)
        for mode, overlay in navigation_modes.items():
            assert overlay in original_overlays, f'Navigation overlay unavailable: {overlay}'
            adb('shell', 'cmd', 'overlay', 'enable-exclusive', '--category', overlay)
            for night in ('no', 'yes'):
                adb('shell', 'cmd', 'uimode', 'night', night)
                for size, density, font in [('720x1280', '320', '1.0'), ('1080x2400', '440', '1.3')]:
                    adb('shell', 'wm', 'size', size)
                    adb('shell', 'wm', 'density', density)
                    adb('shell', 'settings', 'put', 'system', 'font_scale', font)
                    ui.launch()
                    time.sleep(.5)
                    prefix = f'edge-{mode}-{night}-{size}'
                    screenshot(prefix + '-main')
                    ui.navigate('nav_kernels')
                    screenshot(prefix + '-kernels')
                    ui.open_drawer()
                    screenshot(prefix + '-drawer', drawer=True)
    finally:
        if original_mode:
            adb('shell', 'cmd', 'overlay', 'enable-exclusive', '--category', original_mode)
        size = re.search(r'Override size: (\d+x\d+)', original_size)
        density = re.search(r'Override density: (\d+)', original_density)
        adb('shell', 'wm', 'size', size.group(1) if size else 'reset')
        adb('shell', 'wm', 'density', density.group(1) if density else 'reset')
        adb('shell', 'settings', 'put', 'system', 'font_scale', original_font)
        adb('shell', 'cmd', 'uimode', 'night', 'no')
        if original_bottom_bar is not None:
            ui.launch()
            ui.navigate('nav_settings')
            switch = bottom_bar_switch()
            if switch.get('checked') != original_bottom_bar:
                ui.tap(switch)
