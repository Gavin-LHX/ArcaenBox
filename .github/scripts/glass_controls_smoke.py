"""Glass controls, idle rendering and style changes with a live fixture VPN."""
import json
import re
import subprocess
import time


def check(ui):
    adb = ui.adb
    assert adb('get-serialno').strip().startswith('emulator-')

    def style(name):
        ui.navigate('nav_settings')
        ui.tap(ui.scroll_for(text=ui.STRINGS['interface_style']))
        ui.tap(ui.wait_for(text=name, resource_id='android:id/text1'))
        time.sleep(1)
        ui.wait_for(text=ui.STRINGS['interface_style'])

    ui.launch()
    style('Liquid Glass')
    ui.navigate('nav_route')
    ui.wait_for(resource_id=ui.PACKAGE+':id/enable')
    time.sleep(2)

    def frames():
        info = adb('shell','dumpsys','gfxinfo',ui.PACKAGE)
        return int(re.search(r'Total frames rendered: (\d+)',info).group(1))

    before = frames()
    time.sleep(3)
    idle_frames = frames()-before
    assert idle_frames <= 3, f'Static glass switches keep redrawing: {idle_frames} frames'

    ui.navigate('nav_tools')
    tabs = ui.wait_for(resource_id=ui.PACKAGE+':id/tools_tab')
    ui.capture('glass-tabs-network')
    ui.tap(ui.find(tabs,text=ui.STRINGS['backup']))
    ui.wait_for(resource_id=ui.PACKAGE+':id/action_export')
    ui.capture('glass-tabs-backup')
    pager = ui.wait_for(resource_id=ui.PACKAGE+':id/tools_pager')
    l,t,r,b = ui.bounds(pager)
    y = t+(b-t)//3
    adb('shell','input','swipe',str(l+(r-l)//5),str(y),str(l+4*(r-l)//5),str(y),'450')
    time.sleep(.5)
    assert ui.find(ui.tree(),resource_id=ui.PACKAGE+':id/action_export') is None
    ui.capture('glass-tabs-swiped-back')

    ui.open_core()
    ui.wait_for(resource_id=ui.PACKAGE+':id/core_running')
    ui.tap(ui.wait_for(resource_id=ui.PACKAGE+':id/core_preview'))
    assert ui.wait_for(resource_id=ui.PACKAGE+':id/core_preview').get('checked') == 'true'
    ui.capture('glass-core-preview')
    ui.tap(ui.wait_for(resource_id=ui.PACKAGE+':id/core_stable'))
    assert ui.wait_for(resource_id=ui.PACKAGE+':id/core_stable').get('checked') == 'true'
    ui.capture('glass-core-stable')

    # Disable speed text updates so UIAutomator can observe activity recreation.
    ui.navigate('nav_settings')
    ui.tap(ui.scroll_for(text=ui.STRINGS['speed_interval']))
    ui.tap(ui.wait_for(text=ui.STRINGS['disable']))
    original = None
    try:
        ui.navigate('nav_configuration')
        adb('shell','appops','set',ui.PACKAGE,'ACTIVATE_VPN','allow')
        ui.tap(ui.wait_for(resource_id=ui.PACKAGE+':id/profile_name'))
        ui.tap(ui.wait_for(resource_id=ui.PACKAGE+':id/fab'))

        def identity():
            assert 'isForeground=true' in adb('shell','dumpsys','activity','services',ui.PACKAGE)
            tun = re.findall(r'\d+: tun\d+:',adb('shell','ip','-o','link','show'))
            assert tun
            pid = adb('shell','pidof',ui.PACKAGE+':bg').strip()
            assert pid
            return {'pid':pid,'tun':tun}

        time.sleep(4)
        original = identity()
        for name in ('Material Design 3','Liquid Glass','Material Design 3','Liquid Glass'):
            style(name)
            assert identity() == original, 'Style change restarted the active VPN'
        ui.capture('glass-live-switch-preserved')
    finally:
        adb('shell','am','force-stop',ui.PACKAGE)
        ui.launch()
        ui.navigate('nav_settings')
        ui.tap(ui.scroll_for(text=ui.STRINGS['speed_interval']))
        ui.tap(ui.wait_for(text='1s'))

    ui.navigate('nav_configuration')
    fab = ui.wait_for(resource_id=ui.PACKAGE+':id/fab')
    l,t,r,b = ui.bounds(fab)
    x,y = (l+r)//2,(t+b)//2
    remote = '/sdcard/arcaenbox-glass-motion.mp4'
    keys = ('animator_duration_scale','transition_animation_scale','window_animation_scale')
    old = {key:adb('shell','settings','get','global',key).strip() for key in keys}
    try:
        for key in keys: adb('shell','settings','put','global',key,'1')
        recorder = subprocess.Popen(['adb','shell','screenrecord','--time-limit','12','--bit-rate','6000000',remote],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        time.sleep(1)
        for _ in range(2):
            adb('shell','input','motionevent','DOWN',str(x),str(y))
            for px,py in [(x+18,y-10),(r-10,t+10),(x-18,y+12),(l+5,b-8),(l-100,t-100)]:
                adb('shell','input','motionevent','MOVE',str(px),str(py))
                time.sleep(.18)
            adb('shell','input','motionevent','UP',str(l-100),str(t-100))
            time.sleep(.7)
        recorder.wait(timeout=18)
        adb('pull',remote,str(ui.OUT/'glass-motion.mp4'))
    finally:
        adb('shell','input','motionevent','CANCEL',str(x),str(y))
        for key,value in old.items():
            if value == 'null': adb('shell','settings','delete','global',key)
            else: adb('shell','settings','put','global',key,value)
    assert 'isForeground=true' not in adb('shell','dumpsys','activity','services',ui.PACKAGE)
    (ui.OUT/'glass-controls-results.json').write_text(json.dumps({
        'idle_frames_in_three_seconds':idle_frames,
        'tabs_tap_and_swipe':True,'core_channel_selection':True,
        'four_live_style_switches_preserved_vpn':original,
        'cancelled_motion_did_not_connect':True,
    },indent=2))
    style('Material Design 3')
