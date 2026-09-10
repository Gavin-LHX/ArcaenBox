# ArcaenBox for Android

<img src="app/src/main/res/drawable-nodpi/arcaenbox_artwork.png" alt="ArcaenBox" width="128" />

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![Releases](https://img.shields.io/github/v/release/Gavin-LHX/ArcaenBox)](https://github.com/Gavin-LHX/ArcaenBox/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

sing-box / universal proxy toolchain for Android.

一款使用 sing-box 的 Android 通用代理软件.

侧栏提供 [Po0 防火墙白名单](docs/po0-whitelist.md)，支持多机器 Token、手动加白与自动更新。

## 下载 / Downloads

[![GitHub All Releases](https://img.shields.io/github/downloads/Gavin-LHX/ArcaenBox/total?label=downloads-total&logo=github&style=flat-square)](https://github.com/Gavin-LHX/ArcaenBox/releases)

[GitHub Releases 下载](https://github.com/Gavin-LHX/ArcaenBox/releases)

**上游项目的 Google Play 版本自 2024 年 5 月起已被第三方控制，为非开源版本，请不要下载。**

**The upstream Google Play version has been controlled by a third party since May 2024 and is a non-open
source version. Please do not download it.**

## 更新日志 / Changelog

https://github.com/Gavin-LHX/ArcaenBox/releases

## 项目主页 / Homepage

https://github.com/Gavin-LHX/ArcaenBox

## 上游文档 / Upstream Documentation

https://matsuridayo.github.io

## 支持的代理协议 / Supported Proxy Protocols

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* VMess
* Trojan
* VLESS
* AnyTLS
* ShadowTLS
* TUIC
* Hysteria 1/2
* WireGuard
* Trojan-Go（内置 / built-in）
* NaïveProxy（内置 / built-in）
* Mieru（内置 / built-in）
* Snell v4/v5（内置，可选择服务端版本 / built-in, selectable server version）

Trojan-Go、NaïveProxy、Mieru 和 Snell 无需安装插件。[内置组件版本、Snell v5 兼容范围与构建说明](docs/built-in-protocols.md)。Hysteria 1 的特殊模式仍使用可选插件。

Trojan-Go, NaïveProxy, Mieru and Snell ship in the APK. Snell v5 servers use the v4-compatible transport; v5-specific QUIC is not implemented. Special Hysteria 1 modes still use an optional plugin.

## 支持的订阅格式 / Supported Subscription Format

* 一些广泛使用的格式 (如 Shadowsocks, ClashMeta 和 v2rayN)
* sing-box 出站

仅支持解析出站，即节点。分流规则等信息会被忽略。

* Some widely used formats (like Shadowsocks, ClashMeta and v2rayN)
* sing-box outbound

Only resolving outbound, i.e. nodes, is supported. Information such as diversion rules are ignored.

## Credits

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

Android GUI:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

Web Dashboard:

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
