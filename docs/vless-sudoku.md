# VLESS + FinalMask + Sudoku (TCP)

From 1.7.5, import a `vless://` link with its URL-encoded `fm` JSON parameter. The existing VLESS editor exposes **FinalMask · Sudoku (TCP)** for inspecting or changing this JSON. Leaving it empty disables FinalMask.

```json
{"tcp":[{"type":"sudoku","settings":{"password":"replace-with-server-password","ascii":"prefer_ascii","paddingMin":0,"paddingMax":3}}]}
```

The VLESS UUID and Sudoku password are separate credentials and must both match the server. TCP (`type=tcp` or `type=raw`) with one Sudoku mask is supported. The JSON is retained in saved profiles, clones, backups and exported links, including optional `customTable` / `customTables`. Invalid or unsupported masks are rejected, rather than imported as ordinary VLESS.

These nodes use a pinned, bundled Xray 26.3.27 executable. The executable exposes a loopback SOCKS listener; sing-box retains routing, DNS, VPN socket protection and chain mapping. UDP payloads use VLESS over the TCP Sudoku connection. Ordinary VLESS/VMess continues to use sing-box. No server-side forwarding or separate Android plugin is needed.

TLS and REALITY retain the original server name when the server connection is mapped locally. ECH, sing-box multiplexing and non-default packet encoding cannot be combined with this path; validation reports those combinations. This feature does not imply support for other FinalMask masks or other Xray transports.

Xray is listed in kernel management and uses the existing signed component update mechanism. The four Android executables are built from the commit and SHA-256 pinned in `buildScript/builtins/versions.json`, using Go 1.26.1 and the Android NDK. APK verification checks each executable's ABI and digest.

References: [Xray FinalMask documentation](https://xtls.github.io/config/transports/finalmask.html), [pinned Xray source](https://github.com/XTLS/Xray-core/tree/d2758a023cd7f4174a5a5fa4ff66e487d4342ba0).
