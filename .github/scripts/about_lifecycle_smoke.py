"""About content must be owned by its page, including after recreation."""
import io
import json
import time


def check(ui):
    assert ui.adb('get-serialno').strip().startswith('emulator-')
    ui.launch()
    initial_pid = ui.adb('shell', 'pidof', ui.PACKAGE).strip()

    def about():
        ui.navigate('nav_about')
        ui.wait_for(text=ui.STRINGS['check_update_release'])
        dump = ui.adb('shell', 'dumpsys', 'activity', 'top')
        assert 'mParent=AboutFragment{' in dump, 'About content is not a child of the About page'
        return dump

    def leave():
        ui.navigate('nav_configuration')
        dump = ui.adb('shell', 'dumpsys', 'activity', 'top')
        (ui.OUT/'about-after-leaving.txt').write_text(dump)
        assert 'AboutContent{' not in dump, 'About content survives after its page is removed'
        assert ui.adb('shell', 'pidof', ui.PACKAGE).strip() == initial_pid, 'App restarted during navigation'

    for _ in range(4):
        about()
        leave()
    about()
    ui.capture('about-before-recreation')
    rotation = ui.adb('shell', 'settings', 'get', 'system', 'user_rotation').strip()
    automatic = ui.adb('shell', 'settings', 'get', 'system', 'accelerometer_rotation').strip()
    try:
        from PIL import Image
        ui.adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        for value in (1, 0):
            ui.adb('shell', 'settings', 'put', 'system', 'user_rotation', str(value))
            deadline = time.monotonic() + 25
            while True:
                screenshot = Image.open(io.BytesIO(ui.adb('exec-out', 'screencap', '-p', binary=True)))
                if (screenshot.width > screenshot.height) == bool(value):
                    break
                assert time.monotonic() < deadline, 'Activity rotation did not finish'
                time.sleep(.3)
            ui.wait_for(text=ui.STRINGS['check_update_release'])
            doc = ui.tree()
            assert sum(n.get('text') == ui.STRINGS['check_update_release'] for n in doc.iter('node')) == 1
            ui.capture('about-rotation-' + str(value))
        ui.adb('shell', 'input', 'keyevent', 'HOME')
        ui.adb('shell', 'am', 'start', '-W', '-n', ui.PACKAGE + '/io.nekohasekai.sagernet.ui.MainActivity')
        ui.wait_for(text=ui.STRINGS['check_update_release'])
        leave()
    finally:
        for key, value in (('user_rotation', rotation), ('accelerometer_rotation', automatic)):
            ui.adb('shell', 'settings', 'delete' if value == 'null' else 'put',
                   'system', key, *([] if value == 'null' else [value]))
    (ui.OUT/'about-lifecycle-results.json').write_text(json.dumps({
        'navigation_cycles': 4, 'child_ownership': True, 'no_orphan_after_leaving': True,
        'rotation_both_directions': True, 'background_resume': True,
        'single_content_after_recreation': True, 'same_process_survived': True,
    }, indent=2))


def crash_export(ui):
    """Force a crash only in the disposable emulator; never send its share sheet."""
    assert ui.adb('get-serialno').strip().startswith('emulator-')
    ui.launch()
    directory = '/data/user/0/' + ui.PACKAGE + '/cache/log'

    def files():
        return set(ui.adb('shell', 'find', directory, '-maxdepth', '1', '-type', 'f', check=False).splitlines())

    before = files()
    pid = ui.adb('shell', 'pidof', ui.PACKAGE).strip()
    assert pid.isdigit()
    ui.adb('shell', 'am', 'crash', pid)
    deadline = time.monotonic() + 40
    while True:
        created = files() - before
        if created:
            break
        assert time.monotonic() < deadline, 'Crash handler did not export a log'
        time.sleep(.5)
    assert len(created) == 1, created
    path = created.pop()
    name = path.rsplit('/', 1)[-1]
    assert name.startswith('ArcaenBox Crash ') and name.endswith('.log'), name
    # Do not publish the diagnostic content; only retain the branded header/name.
    import shlex
    while True:
        header = ui.adb('shell', 'head', '-n', '1', shlex.quote(path)).strip()
        if header:
            break
        assert time.monotonic() < deadline, 'Crash log header was not written'
        time.sleep(.3)
    assert header.startswith('ArcaenBox for Android '), header
    (ui.OUT/'crash-export-results.json').write_text(json.dumps({
        'intentional_crash': True, 'filename': name, 'header': header,
        'share_sent': False,
    }, indent=2))
    ui.adb('shell', 'input', 'keyevent', 'BACK')
    ui.launch()
    ui.capture('crash-export-recovered')
