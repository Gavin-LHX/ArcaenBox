# 1.7.4 verification

Verified on 2026-10-06 (Asia/Singapore). Application source:
`02d3d529167173d1e96d959b6673f8f9a2e7e2ed`.

## Report and fix

The supplied Android 16 crash log reports `Fragment AboutContent ... not attached
to a context` on the material-about-library AsyncTask. The installed version in
that log is 1.7.1; the same lifecycle path remained in 1.7.3.

AboutContent is now owned by AboutFragment's child FragmentManager. A restored
child is reused. Its list and license loads use the view lifecycle; a completed
background result cannot update a destroyed view. List construction captures an
attached Context rather than calling Fragment.getString from the worker thread.
The library adapter and card models remain in use, without its unmanaged
AsyncTask. The RecyclerView drops its adapter when the view is destroyed.

Crash exports now use `ArcaenBox Crash ... .log`. Regular log exports already
used `ArcaenBox ... .log`. Existing downloaded logs are not modified.

## Negative control

On the independently owned Android 15 emulator, the signed public 1.7.3 APK
retained AboutContent as a resumed Activity-level fragment after navigating to
Configuration. The same lifecycle regression check fails with
`About content survives after its page is removed`.

An intentional `am crash` against the emulator app process produced an
`NB4A Crash ... .log` file, making the new filename assertion fail as expected.
This confirms both regressions against the old binary. The exact user-device
timing of the asynchronous fatal exception was not reproduced.

## Runtime evidence

The signed 1.7.4 x86_64 APK passed startup, About lifecycle and crash-export checks
in [run 37415862140](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37415862140):

- Four enter/leave cycles removed AboutContent with its parent page.
- Portrait/landscape recreation showed one content list; the same process
  survived navigation, rotation and Home/resume.
- An intentional crash generated `ArcaenBox Crash 8588899906447046209.log`, headed
  `ArcaenBox for Android 1.7.4-arcaenbox.1 (305)`. The app reopened successfully.
- The captured logcat contains the single expected `shell-induced crash`;
  no detached-fragment exception, native fatal signal or app ANR was found.
- Portrait and landscape captures were visually reviewed.

That workflow's overall status is **failure**, solely because its additional
application-update query received GitHub's rate-limit response. The app displayed
the translated retry-later message; it did not crash. On the separate local
Android 15 emulator, the exact same APK subsequently passed both stable and
preview update queries, with no fatal event in logcat. The local activity dump
also confirmed `mParentFragment=AboutFragment` on AboutContent. The regression
assertion was tightened to check that field rather than a child-manager header.

The independent CI retry of the failed update-query check **passed** in
[run 37417214299](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37417214299).
It downloaded the immutable APK artifact from the original build and completed
both stable and preview queries without changing the APK or the test assertion.

## Build and packages

- The build and APK signature/identity stages of run 37415862140 passed.
- 16 JUnit suites, 80 tests, zero failures, errors or skipped tests.
- Four downloaded APKs independently passed SHA-256, aapt and apksigner checks:
  `com.arcaenbox.android`, `1.7.4-arcaenbox.1`, version code `305`, one matching ABI each.
- Certificate SHA-256:
  `8750ecddc7b8ce59ff13a701ed30bb5e4f5bcefef8abfd054d91b10e75822ff2`.

| ABI | APK SHA-256 |
| --- | --- |
| arm64-v8a | `de39db695e89fa4cdb66bc9bd0e3587c388535994a3bce83074f50d2cfd53874` |
| armeabi-v7a | `f93583fe7b4e571151f45856b6ccada441779e4685eb8bec2067885f132358d3` |
| x86 | `82a4e96b0df6c5f4bee5c851417080c0017ab6c8033345f83bef8085f5831010` |
| x86_64 | `5ae6c2b0a10ccdcd02ddc1730dcd50669689c9c74240ebc7d0340eafb5ee96da` |

## Limits

The original report is from Android 16. Runtime verification here uses isolated
Android 15 emulators; no physical OnePlus or Android 16 result is claimed. The
intentional crash used only disposable test app data, and its share sheet was
not sent to a third party. The supplied user log is not part of the repository
or release assets. The local emulator was stopped after verification.
