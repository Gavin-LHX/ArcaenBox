# VLESS / REALITY compatibility

Both ArcaenBox sing-box channels retain the hybrid X25519MLKEM768 key share offered by the selected uTLS fingerprint. ArcaenBox uses plain X25519 for REALITY authentication when present, or the X25519 part of the hybrid share otherwise. Server authentication still uses the saved public key and short ID.

This addresses the newer Xray REALITY handshake check documented in [XTLS/REALITY 8cdf7bf](https://github.com/XTLS/REALITY/commit/8cdf7bf9c7f09cb9814bf08c3eb877f68b85fba8) and [sing-box #4520](https://github.com/SagerNet/sing-box/issues/4520). Choose `chrome` for servers that require the hybrid share; older fingerprint profiles without the share may still fail. Node data and the bundled Xray path for FinalMask are unchanged.

Local TCP Vision tests used the two pinned sing-box source revisions with this patch and temporary loopback Xray servers. The Chrome profile reached a local HTTP origin through both Xray 26.3.27 and 26.10.21 on each channel. Firefox still worked on both channels against 26.3.27. Unmodified sing-box 1.14.4 passed against 26.3.27 and was rejected by 26.10.21 with the same node parameters.
