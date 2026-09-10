# 内置协议

从 `1.5.0-arcaenbox.1` 起，Trojan-Go、NaïveProxy、Mieru 和 Snell 随四种架构的 APK 一起安装。原有 Trojan-Go、NaïveProxy 和 Mieru 节点直接使用内置客户端；已安装的同名插件不会覆盖它们。

添加节点 → 手动输入 → Snell，可选择服务端 v4 或 v5，设置 PSK、UDP 转发、连接复用及可选的 HTTP/TLS 混淆。版本选择保存在节点、备份和分享链接中。支持 `snell://PSK@host:port?version=4`、`version=5` 及 Clash YAML 节点导入。不支持的版本和混淆参数会报错。

Snell 使用 mihomo 的实现。其 v5 选项通过服务端的 v4 兼容协议连接，支持 TCP 和经 TCP 转发的 UDP；不实现 v5 独有的 QUIC 传输。应用设置中也显示此限制。

应用仍使用 sing-box 管理 VPN、DNS、路由和代理链；内置客户端只监听本机 SOCKS 端口。客户端连接服务器时经过 sing-box 的本机端口映射，继续使用受 VPN 保护的出站套接字。Snell 的 mihomo 实例关闭 DNS、控制接口、进程查找和地理数据自动更新。

## 来源与重建

精确下载地址、SHA-256 和源码版本位于 [`buildScript/builtins/versions.json`](../buildScript/builtins/versions.json)。构建脚本在下载后校验哈希、ELF 架构和 PIE 格式，再提取可执行文件；不会安装上游插件 APK。编译后再次验证 APK 中每个组件的哈希。

| 组件 | 版本 | 对应源码与许可 |
| --- | --- | --- |
| Trojan-Go | 0.10.6 | [源码提交](https://github.com/p4gefau1t/trojan-go/tree/2dc60f52e79ff8b910e78e444f1e80678e936450)，GPL-3.0；[SagerNet 打包源码](https://github.com/SagerNet/SagerNet/tree/trojan-go-plugin-0.10.6/plugin/trojan-go) |
| NaïveProxy | 150.0.7871.63-1 | [源码与构建工作流](https://github.com/klzgrad/naiveproxy/tree/v150.0.7871.63-1)，BSD-3-Clause 与 Chromium 第三方许可 |
| Mieru | 3.36.1 | [源码提交](https://github.com/enfein/mieru/tree/316cc6606c287d45a321b99ac86a1f2e7f2b785a)，GPL-3.0 |
| mihomo（Snell） | 1.19.30 | [源码与构建工作流](https://github.com/MetaCubeX/mihomo/tree/v1.19.30)，GPL-3.0 |

Mieru 使用锁定源码在 CI 中通过 Go 和 Android NDK 编译四种架构。其余客户端提取自表中版本的官方 Android 发布物。重建需要 Android SDK、NDK 25.0.8775105、Go 1.25、Python 3.12+ 和 curl：

```sh
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/25.0.8775105"
python3 .github/scripts/package_builtins.py
```

然后按照项目的原有流程准备两个 sing-box AAR 并编译 APK。组件清单随应用打包，可在“关于 → 内置协议”查看。升级内置客户端需要更新应用；“sing-box 内核管理”只更新 sing-box。
