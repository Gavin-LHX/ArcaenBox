# 1.7.3 verification

Build and CI verified on 2026-10-03; local updater verification continued on 2026-10-06 (Asia/Singapore). Application source:
`99e7411d6fa0a53091e587b4aaa55aeac970f5d5`.

## Changes

- Profile/group list queries now apply only the latest requested snapshot. A late
  result from before an import cannot replace the newly updated list. Profiles
  retain their database IDs as identity, including identical display names.
- List listeners and adapters are released with their views; restoring a group
  no longer manually runs its view creation a second time.
- About's stable/preview update checks can download the matching APK in the app,
  show progress, cancel/retry, validate it, and invoke Android's system installer.
  APK validation covers release checksum, package, increasing version code,
  current signing certificate and native-library ABI.
- Unknown-source installation permission is requested through Android Settings.
  Returning without permission or cancelling the installer leaves a retry path.

## Import race evidence

The reported intermittent missing-card symptom did not reproduce in a limited
manual run of signed 1.7.2. That baseline covered successive same-name imports,
clipboard import, a new empty group, importing from Settings and background/resume.
No node test was used to refresh the list.

The code had independently queued database snapshots and addition callbacks.
Controlled coroutine tests delay the earlier query until after an import. A
negative control without stale-load cancellation fails both the empty-group and
same-name-profile cases; the fixed implementation passes. Additional tests cover
edit/delete ordering and destroyed views. These are deterministic race tests,
not a claim that the user's physical-device timing was reproduced.

## Build and packages

- [Signed build 37045292610](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37045292610): successful.
- 16 JUnit suites, 80 tests, zero failures, errors or skipped tests.
- Four downloaded APKs independently passed SHA-256, aapt and apksigner checks:
  `com.arcaenbox.android`, `1.7.3-arcaenbox.1`, version code `300`, one matching ABI each.
- Certificate SHA-256:
  `8750ecddc7b8ce59ff13a701ed30bb5e4f5bcefef8abfd054d91b10e75822ff2`.

| ABI | APK SHA-256 |
| --- | --- |
| arm64-v8a | `df8a8e367ae6a9a121e9a82eb01fb93a5dfa53357c4c4fd3da76d4177812dceb` |
| armeabi-v7a | `ca128b4c5a664e560f11f3fccfa45024af148dd358725fe0383cdf1f227df37d` |
| x86 | `d47ca7bae2518fd622073ac093f0dd656ec415709b3f6cc1921fcfdc7bed6daf` |
| x86_64 | `b2c25b5a72ca1595219f59a73034a23cd23b96d41fffdb1a8eb289b03928c8ee` |

## Runtime checks

The signed 1.7.3 x86_64 APK passed all five selected Android 15 CI checks:
startup, profile import/editor, profile refresh, application version queries,
and Mieru import/save/reopen. There were 18 captures and no failures. Logcat
review found no Java/native fatal event or ANR; the app process exits were
adjacent to the smoke script's intentional force-stops.

Profile refresh checks show two same-name cards immediately after two imports,
without running a node test. Returning from another page, rotating to landscape
and back, and a cold reopen preserve both cards. Disposable fixtures were removed.
The CI application-update check covers stable/preview queries only; it does not
exercise download or installation.

The separate local updater test uses a signed APK from the same source, with
only VERSION_NAME changed to `0.0.0-arcaenbox.1` and VERSION_CODE to `1`
(resulting APK code `5`). Its SHA-256 is
`006fabffa7a95bad6fd89991601758723af7c8182cffdfac1fae5cbd1456ba28`.
It downloads the actual public 1.7.2 x86_64 asset (SHA-256
`4be6db1e172506e6d788cf4da3dd20a0e18db74411776c920cc58cc874170238`).
The fixture is an internal CI artifact and is excluded from the public release.

The real in-app download and system installation passed on the independent
Android 15 emulator:

- Download progress was visible and survived Home/resume and portrait/landscape.
- Cancel removed the partial APK; a fresh download completed with the official
  public asset's SHA-256.
- Returning from unknown-source Settings without permission kept Install usable.
  Granting permission and returning automatically opened the Android installer.
- Cancelling the installer left a retry path. The second confirmation installed
  the public APK and changed the installed package to 1.7.2 / code 295.
- The retained dummy profile kept the same ID and its entire database row was
  unchanged. The upgrade itself used the system installer, not `adb install`.

The QA helper originally treated a leaf XML Element as a false boolean and missed
a completed download. This was corrected in the ignored local harness. The app's
download was independently confirmed by UI and the cached APK's hash. Emulator
network throttling required disabling only the disposable AVD's Wi-Fi while the
cancellation test used its emulated mobile connection.

After the system upgrade, the exact signed 1.7.3 APK was installed for local
regressions. Two same-name imports appeared immediately; navigation, rotation and
cold reopening retained them. Importing the **identical URI** twice also displayed
one then two cards immediately, with distinct database IDs and no latency-test or
manual refresh. One loopback-only VPN start/stop check established and removed the
TUN interface and foreground service, and the disconnected main footer returned
to the standalone connect button.

Settings intentionally hides both the footer and FAB when the default
`showBottomBar=false`; a main-screen-only assertion was corrected in the local
harness after checking `MainActivity.displayFragment`. No product change was
required by these final runtime checks.

## Scope

No physical Pixel was connected during this verification. Only disposable
loopback profiles were used; no user's real proxy credentials were imported.
The dedicated updater emulator is separate from the retained baseline AVD.
All five disposable profiles were removed through their editors after testing;
the dedicated emulator's profile database was empty and no TUN remained. That
emulator was shut down. The retained original AVD was not modified by updater QA.

Older installed versions still open a release page because they do not contain
the new updater. Install 1.7.3 once using its APK; subsequent updates can use the
in-app download and Android installer. Downloads remain official GitHub release
assets; opening a GitHub web page is no longer part of the new app's update flow.

## Release

[v1.7.3-arcaenbox.1](https://github.com/Gavin-LHX/ArcaenBox/releases/tag/v1.7.3-arcaenbox.1)
was published on 2026-10-06 after validation. [Asset staging 37352752689](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37352752689)
transferred the exact signed build artifacts into the draft first. After publication,
the tag was independently verified to resolve to the source commit above, all five
assets matched the verified APKs/checksum file, and GitHub's latest-release endpoint
returned this release. The updater QA fixture is not a published asset.
