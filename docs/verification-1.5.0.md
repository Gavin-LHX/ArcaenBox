# 1.5.0-arcaenbox.1 验证记录

发布 APK 来自 [构建 34508586604](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34508586604)，应用源码提交 `49eaf776d7ab8a6ae4db60b4558af309c06a92bc`。后续验证脚本修改未改变安装包。

- 四种 ABI 均完成编译、签名、包名及内置客户端 SHA-256 校验。
- 20 项单元测试通过，包括 Snell 链接、Clash 导入、配置生成、节点序列化及参数校验。
- Android 15 x86_64 模拟器完成节点编辑、VPN 启停、sing-box 正式版/测试版切换检查。
- 从已发布的 `1.4.2-arcaenbox.6` 覆盖安装，保留原有 Mieru 节点，验证 Room 6 → 7 数据迁移。

## 协议流量

测试环境未安装任何外部代理插件。客户端通过应用 VPN 服务启动，进程可执行文件指向安装 APK 的原生库目录。HTTP 响应及 UDP 回包由独立测试服务端转发，停止后确认 TUN 和子进程退出。

| 客户端 | 服务端 | HTTP/TCP | UDP 转发 |
| --- | --- | --- | --- |
| Snell v4 | 官方 Snell 4.1.1 | 通过 | 通过 |
| Snell v5 | 官方 Snell 5.0.1 | 通过 | 通过 |
| Trojan-Go | Trojan-Go 0.10.6 | 通过 | 通过 |
| Trojan-Go WebSocket + Shadowsocks | Trojan-Go 0.10.6 | 通过 | 未纳入此扩展用例 |
| NaïveProxy | Caddy forwardproxy 2.11.2-naive | 通过 | 协议不提供 |
| Mieru TCP 传输 | Mita 3.36.1 | 通过 | 通过 |
| Mieru UDP 传输 | Mita 3.36.1 | 通过 | 通过 |

[首轮流量结果](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34511356789)验证了 Snell、Trojan-Go 和 NaïveProxy，并在切换 sing-box 测试版后再次验证 Snell v5 的 TCP/UDP。该轮 Mieru 测试因测试服务端默认拒绝回环目标而失败；为隔离测试账号设置 `allowLoopbackIP` 后，同一 APK 的 [Mieru 重测](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34512693241)全部通过。该设置仅用于 CI 测试服务端，不改变应用或用户的服务端配置。

Snell v5 使用兼容 v4 的传输，不包含 v5 独有 QUIC。NaïveProxy 最低 Android 7.0。协议运行验证使用模拟器；四种架构的构建和签名验证不等同于四种架构均完成实机测试。
