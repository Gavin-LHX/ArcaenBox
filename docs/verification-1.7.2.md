# 1.7.2 verification

Verified on 2026-10-02. Application source: `637f31c35408a086cc68cee9d2042b2e85294ef8`.

## Change

Node test details have a Copy button that copies the node name, measurement results,
timestamps and recorded errors. Copy keeps the dialog open. The message also supports
Android's native text selection and copy actions.

## Build and packages

- [Signed build 37007700736](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37007700736): successful.
- 14 JUnit suites, 72 tests, zero failures, errors or skipped tests.
- Android 15 CI startup and profile import/editor checks passed.
- [Asset staging 37008964673](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37008964673): successful.
- All four downloaded APKs independently passed SHA-256, aapt and apksigner verification:
  `com.arcaenbox.android`, `1.7.2-arcaenbox.1`, version code `295`, one matching ABI each.
- All APKs use certificate SHA-256
  `8750ecddc7b8ce59ff13a701ed30bb5e4f5bcefef8abfd054d91b10e75822ff2`.

| ABI | APK SHA-256 |
| --- | --- |
| arm64-v8a | `4a73d554112f7806fb9263e9a98bb4d221b4e56fbc67b2e1ad68f55054ad1860` |
| armeabi-v7a | `b1dde2ee1108d7ec425dc4e484cbcddc5943e8141c0ee60f8fc6e1b3aef7370c` |
| x86 | `5df3a7e647255b41a7d0d5d66a6d99404622c3be416e9dc5dbabf31959696bcd` |
| x86_64 | `4be6db1e172506e6d788cf4da3dd20a0e18db74411776c920cc58cc874170238` |

## Clipboard checks

The exact signed x86_64 APK was tested on a local Android 15 emulator. A disposable
loopback profile had a synthetic TCP failure stored for it, containing a multiline
`Binding socket to network` / `EPERM (Operation not permitted)` error. No proxy service
was started for this fixture.

- Copy button: pasted into a separate multiline editor; the node name, blank line,
  result, timestamp and error matched the dialog's expected text exactly.
- Long press: native selection handles and Copy / Select all actions appeared.
  Select all followed by native Copy and paste reproduced the entire message exactly.
- The native floating selection toolbar was visible in screenshots but absent from
  UIAutomator's XML. The remaining selection actions used screenshot-observed coordinates.

These are clipboard/UI checks, not reproduction of a third-party VPN permission failure.
No physical Pixel was connected during this verification. Android documents the VPN
bypass restriction in [VpnService.Builder.allowBypass](https://developer.android.com/reference/android/net/VpnService.Builder#allowBypass()).

## Release

[v1.7.2-arcaenbox.1](https://github.com/Gavin-LHX/ArcaenBox/releases/tag/v1.7.2-arcaenbox.1)
was published after validation. The tag resolves to the application source above; all
five published assets match the verified APKs and checksum file. Latest-release status
was independently read back from GitHub.
