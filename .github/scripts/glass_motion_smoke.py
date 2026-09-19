"""Pointer-level checks of glass feedback. Never click the connect button during a drag."""
import io
import time
from PIL import Image, ImageChops, ImageStat


def check(ui):
    # CI normally disables animation for deterministic form tests. This check explicitly
    # enables it, then restores every global setting even if an assertion fails.
    keys = ('animator_duration_scale', 'transition_animation_scale', 'window_animation_scale')
    previous = {key: ui.adb('shell','settings','get','global',key).strip() for key in keys}
    try:
        for key in keys: ui.adb('shell','settings','put','global',key,'1')
        return _check(ui)
    finally:
        for key, value in previous.items():
            if value == 'null': ui.adb('shell','settings','delete','global',key)
            else: ui.adb('shell','settings','put','global',key,value)


def _check(ui):
    adb = ui.adb
    assert adb('get-serialno').strip().startswith('emulator-')
    ui.navigate('nav_configuration')
    button = ui.wait_for(resource_id=ui.PACKAGE+':id/fab')
    left, top, right, bottom = ui.bounds(button)
    x, y = (left+right)//2, (top+bottom)//2
    area = (left-40, top-40, right+40, bottom+40)

    def frame(name):
        data = adb('exec-out', 'screencap', '-p', binary=True)
        (ui.OUT/(name+'.png')).write_bytes(data)
        ui.RESULTS.append(name)
        return Image.open(io.BytesIO(data)).convert('RGB').crop(area)

    def difference(a, b):
        return max(ImageStat.Stat(ImageChops.difference(a, b)).mean)

    def motion(action, px, py):
        adb('shell', 'input', 'motionevent', action, str(px), str(py))

    before = frame('glass-motion-rest')
    try:
        motion('DOWN', x, y)
        time.sleep(.18)
        pressed = frame('glass-motion-pressed')
        motion('MOVE', right-8, top+8)
        time.sleep(.18)
        dragged = frame('glass-motion-dragged')
        # Moving outside cancels the button action while preserving release feedback.
        motion('MOVE', left-120, top-120)
    finally:
        motion('UP', left-120, top-120)
    time.sleep(1)
    released = frame('glass-motion-released')
    assert difference(before, pressed) > 1, 'Press has no visible glass feedback'
    assert difference(pressed, dragged) > .5, 'Light and shape do not follow the pointer'
    assert difference(before, released) < 2, 'Button did not spring back to rest'
    assert 'isForeground=true' not in adb('shell','dumpsys','activity','services',ui.PACKAGE), 'Cancelled drag started the VPN'
    assert ui.bounds(ui.wait_for(resource_id=ui.PACKAGE+':id/fab')) == (left,top,right,bottom), 'Button hit target moved after release'

    # The same native switch supports tap and thumb dragging; a drag updates its actual state.
    ui.navigate('nav_route')
    switch = ui.wait_for(resource_id=ui.PACKAGE+':id/enable', enabled='true')
    original = switch.get('checked') == 'true'
    l,t,r,b = ui.bounds(switch)
    sy = (t+b)//2
    start, end = (r-12,l+12) if original else (l+12,r-12)
    adb('shell','input','swipe',str(start),str(sy),str(end),str(sy),'400')
    changed = ui.wait_for(resource_id=ui.PACKAGE+':id/enable')
    assert (changed.get('checked') == 'true') != original, 'Glass switch thumb drag did not change the rule'
    ui.capture('glass-motion-switch-changed')
    ui.tap(changed)
    restored = ui.wait_for(resource_id=ui.PACKAGE+':id/enable')
    assert (restored.get('checked') == 'true') == original, 'Switch tap failed to restore the rule'

    ui.navigate('nav_configuration')
    old = adb('shell','settings','get','global','animator_duration_scale').strip()
    try:
        adb('shell','settings','put','global','animator_duration_scale','0')
        motion('DOWN', x, y)
        motion('MOVE', right-8, top+8)
        # High contrast feedback is allowed, but reduced motion must keep geometry fixed.
        held = ui.wait_for(resource_id=ui.PACKAGE+':id/fab')
        assert ui.bounds(held) == (left,top,right,bottom), 'System-disabled animations still deform the button'
        frame('glass-motion-disabled')
    finally:
        motion('MOVE', left-120, top-120); motion('UP', left-120, top-120)
        if old == 'null': adb('shell','settings','delete','global','animator_duration_scale')
        else: adb('shell','settings','put','global','animator_duration_scale',old)
    return {'press':True, 'drag_highlight':True, 'spring_return':True, 'cancel_no_connect':True,
            'switch_drag_and_tap':True, 'system_reduce_motion':True}
