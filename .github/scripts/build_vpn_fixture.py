"""Build a disposable device-owner fixture for the isolated Android emulator only."""
import os
import subprocess
import zipfile
from pathlib import Path

root = Path('core-build/vpn-fixture').resolve()
root.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ['ANDROID_HOME'])
build = sdk / 'build-tools/35.0.1'
android = sdk / 'platforms/android-35/android.jar'
(root / 'res/xml').mkdir(parents=True, exist_ok=True)
(root / 'res/xml/admin.xml').write_text('<device-admin><uses-policies /></device-admin>')
(root / 'AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.arcaenbox.vpnfixture">
<uses-sdk android:minSdkVersion="21" android:targetSdkVersion="28" />
<application android:label="ArcaenBox VPN test" android:testOnly="true">
<activity android:name=".Main" android:exported="true" />
<receiver android:name=".Main$Admin" android:permission="android.permission.BIND_DEVICE_ADMIN" android:exported="true">
<meta-data android:name="android.app.device_admin" android:resource="@xml/admin" />
<intent-filter><action android:name="android.app.action.DEVICE_ADMIN_ENABLED" /></intent-filter></receiver>
<service android:name=".Main$VPN" android:permission="android.permission.BIND_VPN_SERVICE" android:exported="true">
<intent-filter><action android:name="android.net.VpnService" /></intent-filter></service>
</application></manifest>''')
(root / 'Main.java').write_text('''package com.arcaenbox.vpnfixture;
public class Main extends android.app.Activity {
  public static class Admin extends android.app.admin.DeviceAdminReceiver {}
  public static class VPN extends android.net.VpnService {}
  public void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    android.app.admin.DevicePolicyManager policy = (android.app.admin.DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE);
    try {
      policy.setAlwaysOnVpnPackage(new android.content.ComponentName(this, Admin.class),
        getIntent().getBooleanExtra("block", false) ? getPackageName() : null, false);
      android.util.Log.i("ArcaenBoxFixture", "always-on=" + getIntent().getBooleanExtra("block", false));
    } catch(Exception e) { throw new RuntimeException(e); }
    finish();
  }
}''')
(root / 'classes').mkdir(exist_ok=True)
(root / 'dex').mkdir(exist_ok=True)
def run(args): subprocess.run([str(x) for x in args], check=True)
run(['javac', '-source', '8', '-target', '8', '-classpath', android, '-d', root/'classes', root/'Main.java'])
run([build/'d8', '--min-api', '21', '--lib', android, '--output', root/'dex', *list((root/'classes').rglob('*.class'))])
run([build/'aapt', 'package', '-f', '-M', root/'AndroidManifest.xml', '-S', root/'res', '-I', android, '-F', root/'unsigned.apk'])
with zipfile.ZipFile(root/'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    archive.write(root/'dex/classes.dex', 'classes.dex')
run([build/'zipalign', '-f', '4', root/'unsigned.apk', root/'aligned.apk'])
if not (root/'debug.p12').exists():
    run(['keytool', '-genkeypair', '-keystore', root/'debug.p12', '-storepass', 'android', '-alias', 'test', '-keypass', 'android', '-dname', 'CN=Disposable emulator fixture', '-keyalg', 'RSA', '-validity', '2'])
run([build/'apksigner', 'sign', '--ks', root/'debug.p12', '--ks-pass', 'pass:android', '--out', root/'fixture.apk', root/'aligned.apk'])
