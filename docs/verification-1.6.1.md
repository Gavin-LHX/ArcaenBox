# ArcaenBox 1.6.1 验证记录

日期：2026-09-12。Android 运行验证使用 API 35 x86_64 模拟器，应用运行在普通 UID。签名、包名及原生组件延续 1.6.0；版本更新为 `1.6.1-arcaenbox.1`。

## 交互变化

- 单个节点的卡片菜单直接执行 TCP、真连接、UDP 和下载测试。
- 长按卡片在主列表中多选，再从顶部操作栏开始测试；没有独立的节点选择弹窗。
- 测试结果分别存储，TCP/UDP 的失败不会覆盖已经成功的真连接状态。
- 底栏显示当前代理的真连接延迟、出口 IP、可选国家代码和同一行的上下行速度。切换节点与断开会清除旧结果。
- 内核管理使用卡片列表，版本、渠道与更新操作分开展示，正式/测试渠道均可选择。
- 并发数、超时、下载流量上限、下载 URL、真连接 URL、UDP 目标和出口 IP 服务提供预设及自定义输入。
- UDP 新增 DNS、STUN、Minecraft Bedrock 模式，保留 NTP 与旧设置兼容。验证响应的请求标识、结构及对应协议结果。
- 修复 Android 未注册 `vless://` 外部链接导入的问题。

操作说明见[节点测试与高级设置](node-tests-and-advanced-settings.md)。交互参考 [v2rayN](https://github.com/2dust/v2rayN) 与 [v2rayNG](https://github.com/2dust/v2rayNG)，按 Android 的触摸操作和 sing-box 能力实现。

## 用户节点实测

私密节点配置仅用于本地测试，没有提交到仓库、上传 CI 或写入安装包。以下使用协议及地区代称，不公开认证信息。

| 节点 | Android 真连接 | Android 下载 | Android UDP | VPN 出口 |
| --- | --- | --- | --- | --- |
| SS2022 / HK | HTTPS 204 成功，首轮 546 ms | 1 MiB，约 1.22 MiB/s | DNS 成功，复测 88 / 33 ms；部分轮次超时 | 实际建立 TUN，HTTPS 查询返回 HK 公网出口 |
| Reality / IPv4 / SG | HTTPS 204 成功，首轮 613 ms | 1 MiB，约 0.94 MiB/s | DNS 成功，558 / 564 ms | 实际建立 TUN，HTTPS 查询返回 SG 公网出口 |
| Reality / IPv6 / SG | 当前模拟器没有 IPv6 默认路由，未通过 Android 连通测试 | 同左 | 同左 | 不记作 Android 验证通过 |

测速为测试当时的样本，包含握手开销，不能代表手机网络或长期带宽。SS 的 DNS UDP 既有成功也有超时；某个目标超时不能等同于协议整体不可用。

同一 IPv6 节点另外通过 Windows 官方 sing-box 1.14.0 完成 HTTPS 204、出口查询、1 MiB 下载以及 DNS/NTP/STUN UDP 测试。该结果说明节点当时可用，不替代 Android 的 IPv6 网络验证。

本地 Android 还完成了已连接时对 SS 与 Reality 的主列表多选真连接测试：VPN 进程和接口保持，未勾选节点的数据没有变化。取消进行中的 UDP 测试后，之前的结果保持不变。

## 验证范围

最终 APK 的本地复测中，SS 真连接 537 ms、下载约 1.21 MiB/s，UDP 单次超时；Reality IPv4 真连接 621 ms、UDP 559 ms、下载约 0.88 MiB/s，两者均建立 VPN 并显示有效公网出口及底栏真连接延迟。保留这些失败样本，没有把之前的 UDP 成功等同于持续稳定。

## 构建与回归

- 最终 APK：[构建 34634713324](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34634713324)，应用源码 `8ded31e07c5859e7cb3f91aafc260270845d9555`。安装 versionCode `265`；60 项单元测试通过，失败、错误、跳过均为 0。
- 四个 ABI 的包名 `com.arcaenbox.android`、应用名称、版本和 SHA-256 均经本地验证，证书 SHA-256 延续 `8750ecddc7b8ce59ff13a701ed30bb5e4f5bcefef8abfd054d91b10e75822ff2`。逐文件检查最终 APK，不包含本次用户节点的 UUID 或密码。
- [完整界面回归 34632996704](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34632996704)通过，包含深浅色、紧凑屏幕、大字体、横屏、内核正式/测试渠道切换及恢复内置版。之后的应用修改仅为 VLESS 链接注册与内核卡片约束布局；两项均在最终 APK 本地实际操作并截图验证。
- 七组预设全部通过选择、持久化、自定义输入、恢复原值和重新打开检查。最终包另外验证了系统 VLESS 链接导入和卡片长按进入主列表多选。
- [VPN 与出口回归 34634092442](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34634092442)通过：系统授权、始终开启冲突引导、拒绝授权、允许后建立接口、真实出口查询以及故意不可达节点不回退直连。
- 最终 APK 的[未连接批量测试 34636350104](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34636350104)通过：四种测试区分成功与失败，未勾选节点不变，取消保留旧结果并清理测试进程，下载量受 1 MiB 上限约束，结果排序正常。
- 最终 APK 的[连接期间批量测试 34636352849](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34636352849)通过：四种测试不改变 VPN 进程与接口；UDP 请求确实由隔离代理服务端到达目标；Mieru UDP 的 TCP 测试显示不支持，未误改可用状态。
- 最终构建的预设、VLESS 系统链接、服务启动、大字体及横屏检查全部通过。中文节点菜单、底栏和内核卡片另经本地截图检查。
- [公开 Release](https://github.com/Gavin-LHX/ArcaenBox/releases/tag/v1.6.1-arcaenbox.1) 已发布为应用最新版，标签指向上述测试源码。四个 APK 与 SHA256SUMS 的远端摘要均匹配本地；未登录重新下载 arm64 APK，SHA-256 为 `00eae1c42bf3a8eef968906542a8f5e46b9919ceb6c1dc7a92e59d5340b2ee85`。

早期界面测试曾使用旧内核按钮选择器，批量测试脚本也曾假定所有操作都在溢出菜单中。宽屏会直接显示部分操作，因此修正脚本同时查找操作栏与菜单；这些脚本定位失败没有作为功能通过记录。

## 未变更部分与限制

原生 sing-box 与 Trojan-Go、NaïveProxy、Mieru、Snell 组件二进制未在本版本改动。其协议转发、签名更新和恢复内置版本的基线见 [1.6.0 验证记录](verification-1.6.0.md)。

实体手机的厂商 VPN 授权管理及 IPv6 网络仍需在对应设备验证。没有将模拟器、Windows 或某个 UDP 目标的测试结果扩大为所有设备、线路或目标均可用。
