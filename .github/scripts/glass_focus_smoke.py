"""Verify a visible glass-button focus ring using only the signed APK's UI."""
import io
import json
import time

from PIL import Image, ImageChops, ImageDraw, ImageStat


def check(ui):
    assert ui.adb('get-serialno').strip().startswith('emulator-')
    ui.launch()
    ui.navigate('nav_settings')
    ui.scroll_for(text=ui.STRINGS['interface_style'])
    previous_style = _style_summary(ui)
    try:
        _set_style(ui, 'Liquid Glass')
        return _check(ui)
    finally:
        # run_check can follow another force-stopped check, and a failed assertion
        # must not leave Glass enabled for later MD3-specific screen regressions.
        ui.launch()
        _set_style(ui, previous_style)


def _style_summary(ui):
    doc = ui.tree()
    names = [name for name in ('Material Design 3', 'Liquid Glass')
             if ui.find(doc, text=name) is not None]
    assert len(names) == 1, f'Interface style summary is ambiguous or missing: {names}'
    return names[0]


def _set_style(ui, name):
    # Establish the style through the public setting, without test-only app hooks.
    ui.navigate('nav_settings')
    ui.tap(ui.scroll_for(text=ui.STRINGS['interface_style']))
    ui.tap(ui.wait_for(text=name, resource_id='android:id/text1'))
    time.sleep(1)
    ui.wait_for(text=ui.STRINGS['interface_style'])
    assert _style_summary(ui) == name, 'Interface style did not persist after recreation'


def _check(ui):
    adb = ui.adb
    target_id = ui.PACKAGE + ':id/action_export'
    ui.navigate('nav_tools')
    tabs = ui.wait_for(resource_id=ui.PACKAGE + ':id/tools_tab')
    ui.tap(ui.find(tabs, text=ui.STRINGS['backup']))
    ui.scroll_for(resource_id=target_id)

    # A reported accessibility bound can still be clipped by the page or FAB.
    # Scroll on the empty left gutter before sending any keyboard input.
    for _ in range(8):
        doc = ui.tree()
        target = ui.find(doc, resource_id=target_id)
        pager = ui.find(doc, resource_id=ui.PACKAGE + ':id/tools_pager')
        assert target is not None and pager is not None, 'Backup export button is missing'
        left, top, right, bottom = ui.bounds(target)
        pl, pt, pr, pb = ui.bounds(pager)
        fab = ui.find(doc, resource_id=ui.PACKAGE + ':id/fab')
        stats = ui.find(doc, resource_id=ui.PACKAGE + ':id/stats')
        if fab is not None:
            pb = min(pb, ui.bounds(fab)[1])
        if stats is not None:
            pb = min(pb, ui.bounds(stats)[1])
        margin = max(8, (bottom - top) // 5)
        if left >= pl and right <= pr and top >= pt + margin and bottom <= pb - margin:
            break
        assert pb - pt > bottom - top + 2 * margin, 'Viewport cannot expose the full focus target'
        x = pl + max(4, (pr - pl) // 100)
        high, low = pt + (pb - pt) // 5, pb - (pb - pt) // 5
        start, end = (high, low) if top < pt + margin else (low, high)
        adb('shell', 'input', 'swipe', str(x), str(start), str(x), str(end), '350')
    else:
        raise AssertionError('Could not scroll the focus target fully into view')

    assert target.get('enabled') == 'true' and target.get('focusable') == 'true'
    assert target.get('focused') != 'true', 'Touch navigation unexpectedly left export focused'
    area = ui.bounds(target)
    label = target.get('text')
    time.sleep(.7)

    def frame(name):
        data = adb('exec-out', 'screencap', '-p', binary=True)
        (ui.OUT / (name + '.png')).write_bytes(data)
        if name not in ui.RESULTS:
            ui.RESULTS.append(name)
        image = Image.open(io.BytesIO(data)).convert('RGB')
        assert 0 <= area[0] < area[2] <= image.width and 0 <= area[1] < area[3] <= image.height
        return image.crop(area)

    before = frame('glass-focus-unfocused')
    width, height = before.size
    # Sample only the horizontal rim, away from rounded ends and the central
    # label/icon area. The clock, tabs and other controls are outside this crop.
    rim = Image.new('L', before.size, 0)
    mask = ImageDraw.Draw(rim)
    for y1, y2 in ((.05, .25), (.75, .95)):
        mask.rectangle((int(width * .3), int(height * y1), int(width * .7), int(height * y2)), fill=255)
    center = (int(width * .3), int(height * .35), int(width * .7), int(height * .65))

    def difference(image):
        delta = ImageChops.difference(before, image)
        strongest = ImageChops.lighter(ImageChops.lighter(*delta.split()[:2]), delta.split()[2])
        histogram = strongest.histogram(mask=rim)
        return {
            'rim_mean': max(ImageStat.Stat(delta, mask=rim).mean),
            'rim_changed_fraction': sum(histogram[13:]) / max(1, sum(histogram)),
            'center_mean': max(ImageStat.Stat(delta.crop(center)).mean),
        }

    steps = 0
    for steps in range(1, 25):
        # TAB only changes focus: never send ENTER/SPACE/DPAD_CENTER to this action.
        adb('shell', 'input', 'keyevent', 'KEYCODE_TAB')
        target = ui.find(ui.tree(), resource_id=target_id)
        if target is not None and target.get('focused') == 'true':
            break
    else:
        raise AssertionError('Keyboard traversal did not reach export within 24 TAB presses')
    assert ui.bounds(target) == area, 'Keyboard focus moved the pre-exposed target'
    assert target.get('text') == label and target.get('enabled') == 'true'
    ui.capture('glass-focus-focused-hierarchy')

    deadline = time.monotonic() + 4
    while True:
        focused = difference(frame('glass-focus-focused'))
        if focused['rim_mean'] > 1 and focused['rim_changed_fraction'] > .03:
            break
        assert time.monotonic() < deadline, f'Focused glass button has no visible rim: {focused}'
        time.sleep(.2)
    assert focused['center_mean'] < 2, f'Focus changed the label/icon area: {focused}'

    for _ in range(3):
        adb('shell', 'input', 'keyevent', 'KEYCODE_TAB')
        target = ui.find(ui.tree(), resource_id=target_id)
        if target is not None and target.get('focused') != 'true':
            break
    else:
        raise AssertionError('Keyboard focus did not leave export')
    assert ui.bounds(target) == area, 'Moving focus away scrolled the reference crop'
    assert target.get('text') == label
    ui.capture('glass-focus-cleared-hierarchy')
    deadline = time.monotonic() + 4
    while True:
        cleared = difference(frame('glass-focus-cleared'))
        if cleared['rim_mean'] < 1 and cleared['rim_changed_fraction'] < .02:
            break
        assert time.monotonic() < deadline, f'Glass focus rim remains after blur: {cleared}'
        time.sleep(.2)

    result = {'target': target_id, 'bounds': area, 'tab_presses_to_focus': steps,
              'focused': focused, 'cleared': cleared, 'no_action_activated': True}
    (ui.OUT / 'glass-focus-results.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result
