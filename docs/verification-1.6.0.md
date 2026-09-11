# ArcaenBox 1.6.0 验证记录

日期：2026-09-11 至 2026-09-12。运行环境为 Android 15（API 35）x86_64 模拟器，应用以普通 UID 运行，SELinux 为 Enforcing。没有把模拟器结果等同于各品牌实体手机兼容性。

## 功能范围

本版本加入资源文件管理、路由预设/规则导入导出、四项多选节点测试、高级 DNS/Hosts 与第二端口认证，完善 VPN 授权引导、代理出口 IP、独立内核更新及 Snell v6。使用说明见 [连接与资源管理](connection-management.md) 和 [节点测试与高级设置](node-tests-and-advanced-settings.md)。

## 构建与身份

签名 APK 来自[构建 34618081650](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34618081650)，代码为 `43ce7c2161e21f9bf38030b7ea4843d12d06257c`。57 项单元测试通过，无失败、错误或跳过。

- 包名：`com.arcaenbox.android`；版本：`1.6.0-arcaenbox.1`；安装 versionCode：`260`。
- 签名证书 SHA-256：`8750ecddc7b8ce59ff13a701ed30bb5e4f5bcefef8abfd054d91b10e75822ff2`。
- arm64-v8a、armeabi-v7a、x86、x86_64 均通过签名、应用名称、包名、图标透明度和内置 ELF 验证。运行回归使用同一构建的 x86_64 APK。
- sing-box 正式版 `1.14.0`、测试版 `1.15.0-alpha.2` 均为 Android 修订 r3，来自[原生构建 34617688880](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34617688880)。两渠道均通过配置迁移、实际代理转发及 socket 保护单元测试；本地另外重复通过 10 轮延迟/并发保护测试。

## 实际转发与更新

- [协议回归](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34618758614)：Snell v4/v5、Trojan-Go、Mieru TCP/UDP 模式均完成 TCP 与 UDP 转发；NaïveProxy 和 Trojan-Go WebSocket + Shadowsocks 完成 HTTP 转发。Snell v6 的 default、unshaped、unsafe-raw 三种模式完成 TCP 与 UDP 转发，并验证恢复 v5。
- [独立内核加载](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34612283230)：五个签名组件包实际加载后完成相应协议转发、恢复内置版本和进程清理。五个组件在四架构新 APK 中均与该次回归逐字节相同，新的 sing-box r3 则由上述协议回归验证。NaïveProxy 不提供通用 UDP 转发，因此不将该项记作通过。
- [VPN 授权与出口 IP](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34618755465)：验证系统授权页、另一 VPN 的始终开启冲突引导、允许后创建接口；通过 Snell 代理访问 HTTPS IP 服务得到公网出口地址。故意不可达节点显示查询失败，没有回退直连。
- [sing-box r3 在线更新](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34620675158)：从公开 Release 实际下载正式/测试渠道签名内核，确认主进程与 VPN 进程加载同一下载文件；损坏缓存后回退内置版本，重新下载修复缓存，模拟启动中断后恢复内置正式版。新 JNI 接口标识与 r2 不同，旧的不兼容下载包不能覆盖 r3。

- [在线组件更新](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34614264043)：通过应用界面从公开 GitHub Releases 下载 Trojan-Go、NaïveProxy、Mieru 正式版及 Snell 正式/测试版，校验签名和二进制摘要、应用后恢复内置版本；无测试包的三个组件正确显示渠道不可用。
- [自定义 DNS](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34615138543)：默认模板建立 VPN，DNS 查询经所选 Snell 代理访问模板中的 DoH 服务器，收到有效 A 记录。
- [高级 DNS 与认证](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34615141689)：Hosts 查询返回指定 A 记录，AAAA/SVCB/HTTPS 屏蔽返回空成功响应；第二端口未认证请求返回 407，认证后完成代理转发。路由四预设均完成界面切换。
- [未连接时多选测试](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34618751977)：TCP、真连接、UDP 和下载测速各自记录成功与失败结果，未选节点不变，取消保留既有结果并清理进程，下载限制 1 MiB，排序正常。
- [已连接时多选测试](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34618748637)：四项测试均区分可用与不可达节点，VPN 进程 PID 与接口保持不变。UDP NTP 请求确实由隔离 Snell 服务端到达测试目标，下载读取 1 MiB；Mieru UDP 的 TCP 测试显示“不支持”，数据库可用状态保持未判定。

## 界面与数据

- [Material 3 完整界面回归](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34618081650)：42 次截图检查完成，覆盖浅色/深色、紧凑屏幕、大字体、横屏、透明图标、设置、备份、应用更新、Po0 表单，以及内置 sing-box 正式/测试渠道切换与服务启动。
- [路由导入](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34610326940)：取消预览后数据库不变；导入三条规则后追加到已有规则；含不支持桌面字段的文件整次拒绝；导出结果正确。该次运行的另一项设置检查发现首次打开崩溃，已经修复，并由后续界面回归验证。
- [资源及路由设置回归](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34606067714)中的 resources 与 settings-routes 通过，验证实际资源下载、规则集导入和界面操作。该次运行的出口 IP 检查失败已由上面的独立通过记录覆盖。
- 数据库升级到 schema 8，保留既有节点和规则，新增各测试类型的独立结果表。包名和签名与 1.5.0 一致，安装版本号递增。

## 验证中修正的问题

- 首次打开设置时，多选嗅探项缺少默认集合导致崩溃。
- 新版 sing-box 的 typed DNS 服务器不允许将空的 direct 出站作为 detour；默认 DNS 模板和 Bootstrap DNS 改为直接拨号。
- 纯 UDP 协议不适用 TCP 握手测试；显示不支持该项测试，并保留节点既有可用状态。
- VPN 连接期间测试其他节点时，旧 socket 保护服务直接读取非阻塞 fd，存在接收时序竞态，调用方还忽略了失败。r3 使用带超时的 Unix socket 收发与确认，传播系统拒绝保护的错误，避免测试连接再次进入当前 VPN。
- 连接按钮随底栏移出屏幕、TLS 清理阻塞界面、并发内核配置临时文件冲突，均在前续回归中修正。

## 范围限制

实体手机的厂商 VPN 权限管理仍需在对应设备验证。若 NekoBox 等另一应用启用了系统“始终开启 VPN”，应先关闭该选项；Android 不允许 ArcaenBox 绕过这一限制。Windows PAC、托盘和 Xray 专属设置没有作为无效开关添加到 Android。
