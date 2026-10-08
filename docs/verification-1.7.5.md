# 1.7.5 verification

Validated on 2026-10-06. APK source commit: `7c605d6b0658648a262a99ca5a3f8b532d222763`.

## Build and compatibility

- [Signed APK build](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37438734174): successful; all 88 JVM tests passed with no failures, errors or skips.
- FinalMask tests cover URI import/export, clone/serialization, a real version-4 bean fixture with trailing metadata, TCP/raw normalization, protected local mapping, TLS/REALITY server-name preservation and invalid/unsupported combinations. Ordinary VLESS and VMess retain their sing-box path.
- All four APKs passed package, signing, architecture and bundled-component digest checks. Xray was compiled for each ABI from the locked 26.3.27 source. The local downloads also matched `SHA256SUMS.txt`.
- Android 15 startup, ordinary VLESS import and Mieru import/edit/save/cold reopen passed in the signed build. The Mieru fixture was removed afterwards.

## Android traffic

[Isolated Sudoku runtime verification](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37439666772) used the exact signed x86_64 APK. It imported a synthetic VLESS/Sudoku URI, restarted the app, checked the stored FinalMask in the editor, and connected to a pinned Xray Sudoku server on the emulator host.

The APK's bundled `libxray.so` served a real TCP HTTP request and UDP echo through the existing sing-box protection path. Disconnect removed both the component process and TUN interface. Reconnect and another TCP request passed. Evidence includes the process executable path, screenshot and both traffic results. No external Android plugin or server-side port forwarding was used.

## Public-node check

A separate local Android 15 emulator was upgraded from signed 1.7.4 to this APK and imported the user's existing Sudoku link. The per-node results were TCP 2 ms, true connection 557 ms and UDP 550 ms. With VPN connected, verified-TLS requests returned HTTP 204 from gstatic and HTTP 200 from Cloudflare; the latter reported a Japanese exit. The connection-bar screenshot displayed current latency and the server's exit IP. Normal disconnect removed Xray. These timings are observations from this test environment, not latency guarantees.

UIAutomator could not reach idle while the local traffic counters were updating. The live screen was captured directly and visually inspected; the rerun used the already located connection button to disconnect. Network assertions and process cleanup were checked independently of the accessibility dump.

The public-node credentials and raw network evidence remain outside the repository. The Pixel was not connected to ADB during this verification; physical-device runtime was not tested. TLS/REALITY configuration preservation is unit-tested, while the live Sudoku servers in this run used `security=none`.
