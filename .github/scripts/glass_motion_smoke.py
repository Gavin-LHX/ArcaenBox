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
        if name not in ui.RESULTS: ui.RESULTS.append(name)
        return Image.open(io.BytesIO(data)).convert('RGB').crop(area)

    def difference(a, b):
        return max(ImageStat.Stat(ImageChops.difference(a, b)).mean)

    def motion(action, px, py):
        adb('shell', 'input', 'motionevent', action, str(px), str(py))

    def rendered(name, reference, changed):
        # SwiftShader can return the previous display buffer immediately after input.
        # Wait for an actual rendered response instead of assuming a 180 ms GPU budget.
        deadline = time.monotonic()+4
        while True:
            result = frame(name)
            delta = difference(reference, result)
            if (delta > 1 if changed else delta < 2) or time.monotonic() >= deadline:
                return result
            time.sleep(.15)

    before = frame('glass-motion-rest')
    try:
        motion('DOWN', x, y)
        time.sleep(.18)
        pressed = rendered('glass-motion-pressed', before, True)
        motion('MOVE', right-8, top+8)
        time.sleep(.18)
        dragged = rendered('glass-motion-dragged', pressed, True)
        # Moving outside cancels the button action while preserving release feedback.
        motion('MOVE', left-120, top-120)
    finally:
        motion('UP', left-120, top-120)
    time.sleep(1)
    released = rendered('glass-motion-released', before, False)
    assert difference(before, pressed) > 1, 'Press has no visible glass feedback'
    assert difference(pressed, dragged) > .5, 'Light and shape do not follow the pointer'
    assert difference(before, released) < 2, 'Button did not spring back to rest'
    assert 'isForeground=true' not in adb('shell','dumpsys','activity','services',ui.PACKAGE), 'Cancelled drag started the VPN'
    assert ui.bounds(ui.wait_for(resource_id=ui.PACKAGE+':id/fab')) == (left,top,right,bottom), 'Button hit target moved after release'

    switch_results = check_switch(ui)

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
            **switch_results, 'system_reduce_motion':True}


def check_switch(ui):
    """Catch a RecyclerView long press stealing the tap following a thumb drag."""
    adb = ui.adb
    assert adb('get-serialno').strip().startswith('emulator-')
    ui.navigate('nav_route')
    switch = ui.wait_for(resource_id=ui.PACKAGE+':id/enable', enabled='true')
    identity = {'resource_id':ui.PACKAGE+':id/enable', 'content_desc':switch.get('content-desc'), 'enabled':'true'}
    assert identity['content_desc'], 'Rule switch needs a stable accessible identity'
    original = switch.get('checked') == 'true'
    original_bounds = ui.bounds(switch)
    long_press = adb('shell','settings','get','secure','long_press_timeout').strip()
    slow_drag_ms = max(800, (int(long_press) if long_press.isdigit() else 500) + 250)

    def state(expected):
        # Wait for the same rule's state; another unchecked rule cannot satisfy this check.
        node = ui.wait_for(**identity, checked=str(expected).lower())
        assert ui.bounds(node) == original_bounds, 'Switch gesture moved the rule or its hit target'
        return node

    # Both directions exceed the parent's long-press timeout. No extra tap is allowed to
    # dismiss a stray row-drag state before the native switch receives its next tap.
    checked = original
    for index in range(2):
        node = state(checked)
        l,t,r,b = ui.bounds(node)
        sy = (t+b)//2
        start, end = (r-12,l+12) if checked else (l+12,r-12)
        adb('shell','input','swipe',str(start),str(sy),str(end),str(sy),str(slow_drag_ms))
        checked = not checked
        state(checked)
        ui.capture(f'glass-motion-switch-drag-{index+1}')
        # Four consecutive taps must all toggle; the fourth leaves the drag's result.
        for _ in range(4):
            ui.tap(state(checked))
            checked = not checked
            state(checked)
    assert checked == original, 'Switch sequence did not restore the original rule state'
    ui.capture('glass-motion-switch-restored')

    def cards():
        doc = ui.tree()
        route_list = ui.find(doc, resource_id=ui.PACKAGE+':id/route_list')
        result = []
        for card in route_list.iter('node'):
            if card.get('resource-id') != ui.PACKAGE+':id/content': continue
            title = ui.find(card, resource_id=ui.PACKAGE+':id/profile_name')
            if title is not None: result.append((card, title))
        assert len(result) >= 2, 'Reorder regression needs two visible fixture rules'
        return result

    def first_two_names():
        return [title.get('text') for _, title in cards()[:2]]

    def move_first_below_second():
        first, second = cards()[:2]
        l,t,r,b = ui.bounds(first[0])
        _,ty,_,tb = ui.bounds(first[1])
        px,py = l+(r-l)//3,(ty+tb)//2  # Card title, away from edit and switch.
        target = ui.bounds(second[0])[3]-20
        adb('shell','input','motionevent','DOWN',str(px),str(py))
        try:
            time.sleep(slow_drag_ms/1000)
            for y in ((py+target)//2, target):
                adb('shell','input','motionevent','MOVE',str(px),str(y))
                time.sleep(.15)
        finally:
            adb('shell','input','motionevent','UP',str(px),str(target))
        time.sleep(.5)

    order = first_two_names()
    assert order[0] != order[1], 'Reorder fixture rule names must differ'
    move_first_below_second()
    assert first_two_names() == order[::-1], 'Ordinary row long-press reorder stopped working'
    ui.capture('glass-motion-rule-reordered')
    move_first_below_second()
    assert first_two_names() == order, 'Reorder did not restore the original fixture order'
    return {'switch_slow_drag_both_directions':True, 'switch_repeated_taps':True,
            'switch_stable_hit_target':True, 'rule_long_press_reorder':True}
