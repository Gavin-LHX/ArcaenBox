# 1.7.6 integration verification

APK source: `29ff1f59c79ed3d1477b7700ba4c52070a89639b`. Version: `1.7.6-arcaenbox.1`, Android versionCode `315`, package `com.arcaenbox.android`.

This release combines the merged Material 3 interface with all 1.7.5 protocol, updater, import-refresh and Liquid Glass fixes. Documentation added after the APK build does not change packaged inputs. The publish workflow verifies that relationship and publishes the existing checked binaries without rebuilding them.

## Build and upgrade

- [Signed build and runtime checks](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37817415278): 17 JVM test suites, 88 tests, zero failures, errors or skips. All four ABI APKs passed package, version, v1/v2 signature, bundled-component digest, icon and ELF checks.
- Stable and preview sing-box artifacts came from the locked core build `34617688880`; built-in components came from the 1.7.5 build `37438734174`, including Xray 26.3.27.
- [Upgrade and lifecycle checks](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37818551841) exercise installation over the signed 1.7.5 APK (versionCode 310), same-name node refresh, About-page lifecycle and the in-app update path. Upgrade results are checked for retained profiles, ports and UI preferences, and fixture cleanup.

## Android 15 regression runs

Every run below uses the same signed x86_64 APK from build `37817415278`. Each evidence artifact records that exact source commit in `apk-source.json`.

| Run | Checks |
| --- | --- |
| [Main](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37817415278) | Redesigned startup, profile import/editor, Mieru import/edit/save/cold reopen, local SOCKS service, FinalMask/Sudoku runtime |
| [A](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37818551841) | Signed 1.7.5 overwrite upgrade, same-name imports, About lifecycle, application updater |
| [B](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37818560479) | Profile editor, bottom navigation/rail and secondary-screen restoration, Groups/Route without node FAB, complete final node card clear of the FAB |
| [C](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37818567124) | MD3/Liquid Glass switching in both directions, cold-launch persistence, light/dark navigation, rail, disconnected viewport and VPN footer transitions |
| [D](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37818572887) | Glass keyboard focus, collapsed settings and route-rule repair, classic bottom edges and drawer menu safety across navigation modes, themes and sizes |
| [E](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37818578927) | Home power/quick-action glass rendering, press/MOVE/CANCEL feedback before/during/after connection, connection cleanup, resting-glass and MD3 restoration |

All runs completed successfully with empty failure lists. Screenshots for the node tail, Home glass states and classic drawer bottom edges were visually inspected in addition to automated assertions.

The short-screen classic drawer regression exposed a real menu overlap with the three-button system navigation area. The release keeps its background full height while giving the menu the navigation-bar bottom inset. Bottom-edge checks cover gesture and three-button navigation, light/dark themes, normal and short screens, and enlarged text.

## Protocol runtime evidence and limits

The FinalMask/Sudoku check uses the APK's bundled `libxray.so` against a pinned server on the emulator host. A TCP HTTP request and UDP echo pass, disconnect removes the component process and TUN interface, and reconnect permits another TCP request. Mieru import, editor persistence and cold reopen also pass, with the fixture removed afterwards.

Service and Home connection checks use local fixtures. This release verification does not repeat public-node measurements or claim physical Pixel validation: no device was attached to ADB. Existing 1.7.5 public-node observations remain documented separately in [1.7.5 verification](verification-1.7.5.md).

## Download checksums

Local downloads match the build's `SHA256SUMS.txt`:

| ABI | SHA-256 |
| --- | --- |
| arm64-v8a | `822f65fa472febc2a8371642e94cfa2abdd0de2da103419cb33be21dba123bef` |
| armeabi-v7a | `5c8970ee6395e5e6532d0d6e971e0ec3be47ab62a0f8c272ea18c8636c47c8a6` |
| x86 | `83688506f6c1e35518778a58f11c270615d94971dffafaef04f8820bedf1e513` |
| x86_64 | `d7a3fbd0708643e64fd6a993b45941b2077095dce61203cf97f4ec243520d950` |
