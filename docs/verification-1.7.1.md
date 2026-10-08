# ArcaenBox 1.7.1 verification

Verified on 2026-10-02. APK source: `051ce972ad68c3352862a216042e5d59766d2913`.

- [Signed APK build 37000577186](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37000577186): compilation, 72 unit tests, APK signing and package checks passed. The overall workflow failed in the UI harness because its generic scroll helper looked for the global VPN category when checking the node editor's MTU field.
- [Focused Android 15 UI run 37001908260](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/37001908260): passed using those same immutable APK artifacts, with the scroll helper scoped to the node editor. The harness correction at `ef685c393560d54824993e5f398e86d1dc9180b6` changes no application code.
- 72 tests: zero failures, errors or skips. Nine Mieru tests cover the query-port URI shape, shared import dispatch, IPv6/escaping, defaults, database/backup/clone, old TCP/UDP records, protected mapping and native configuration, valid enum/MTU boundaries, and rejected unsupported inputs.
- UI verification imported an official-shaped `mierus://` URI, checked its fields and selected NO_WAIT/OFF options, changed its name and saved, restarted the app, verified the stored values again, then removed only the fixture. The proxy service was never started.
- Initial build UI checks for startup, ordinary profile editing and VLESS import also passed.

All four downloaded APKs were independently checked against `SHA256SUMS.txt`, inspected with `aapt`, and verified with `apksigner`. Package `com.arcaenbox.android`, version `1.7.1-arcaenbox.1`, version code 290. The signing certificate is unchanged from 1.7.0:

`8750ecddc7b8ce59ff13a701ed30bb5e4f5bcefef8abfd054d91b10e75822ff2`

| ABI | SHA-256 |
| --- | --- |
| arm64-v8a | `19d774a6a8bb481fb4e846fb0436d09ded25d4bdbca4dfa286c8f9d04dafd179` |
| armeabi-v7a | `fa017aa2d86a8e6be4f00cee110a53e0ea66b99d4c87ecf636870332f93ed9d9` |
| x86 | `10cb29068b8131404c36026229b73b8a6d802d8e5675f6c8edb9f8cce5ab2b0c` |
| x86_64 | `362ab7fcca078aede42ae567fd8e78bcfcb4b7d8095b857812a447e2075751ca` |

This verifies import and persistence using synthetic credentials and documentation-only IPs on an emulator. It does not establish connectivity or throughput to the user's server, or physical-device results. See [supported Mieru import scope](mieru-import.md).
