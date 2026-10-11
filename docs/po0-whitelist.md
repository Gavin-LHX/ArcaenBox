# Po0 firewall whitelist
# Po0 防火墙白名单

Open **Po0 firewall whitelist** in the navigation drawer. In the Po0 console,
open the machine's firewall page and copy its add script. Paste only its
`pgnfw_…` token into ArcaenBox. Separate multiple machines with commas.

在导航抽屉中打开 **Po0 防火墙白名单**。
在 Po0 控制台打开机器的防火墙页面并复制其添加脚本。仅粘贴其
`pgnfw_…` 标识符转换到 ArcaenBox。多个机器之间用逗号分隔。

- **Save settings** stores the tokens and automatic-update choice. With automatic
  updates off, saving does not contact Po0.
- **Save and whitelist now** saves and submits the current physical connection's
  IPv4 /24 network to each configured machine.
- **Automatic whitelisting** reacts to network changes while the application or
  VPN process is alive. WorkManager provides a roughly 15-minute fallback after
  the process exits. Android Doze, force-stop and vendor battery restrictions can
  delay or suspend jobs; this is not a guaranteed timer.
- Optional `@0` through `@4` suffixes request fixed slots. A slot update can replace
  its previous network. Use separate slots per device and remove conflicting old
  slots in the Po0 console. Phones normally use ordinary entries without a suffix.
- Clear the tokens, switch automatic updates off, and save to remove the setup.

- **保存设置**存储了令牌和自动更新选项。带自动
关闭更新，保存时不连接 Po0。
- **保存并加入白名单** 现在保存并提交当前物理连接的
将 /24 IPv4 网络分配给每台配置的机器。
- **自动白名单** 在应用程序或
VPN 进程处于活动状态。WorkManager 提供了一个大约 15 分钟的回退。
进程退出。Android Doze、强制停止和供应商电池限制可以
延迟或挂起作业；这不是一个保证的定时器。
- 可选的 `@0` 至 `@4` 后缀请求固定槽位。槽位更新可以替换
在其先前网络中为每个设备使用单独的插槽，并删除冲突的旧
Po0 控制台中的插槽。手机通常使用无后缀的普通条目。
清除令牌，关闭自动更新，并保存以移除设置。

## Request and storage behavior
## 请求与存储行为

The protocol follows the [linked tutorial](https://wiki.uuuz.de/guide/tutorials/po0fw-whitelist.html)
and its [upstream implementation](https://github.com/w0ven/po0fw):
`POST https://124.221.69.228/api/firewall/<token>/add[?slot=N]`, empty body.
The IPv4 endpoint presents a publicly trusted IP certificate. Normal certificate
and hostname verification remain enabled. Requests cannot redirect, use a system
HTTP proxy, or fall back to the VPN: sockets and DNS bind to an explicitly checked
non-VPN Android `Network`.

When Po0 tokens are configured, ArcaenBox also adds the equivalent of
`IP-CIDR,124.221.69.228/32,DIRECT,no-resolve` to the beginning of the generated
sing-box route list. It uses a plain direct outbound and an IP-only matcher,
without introducing DNS resolution or changing DNS rules. This applies after
custom configuration merging and also covers full custom sing-box profiles.
Equivalent duplicate rules are coalesced; other rules retain their order.
The generated rule is managed automatically and is not added to the editable
user-rule database. Adding the first token or clearing all tokens reloads an
already connected proxy once to apply/remove it. Later updates do not reload it.
The worker validates the physical network itself because WorkManager's default
network validation can report an unusable VPN while the physical connection works.

Success requires an enabled firewall and the current exit in the returned
whitelist. A requested fixed slot must also match. Malformed responses, disabled
firewalls, slot conflicts, HTTP errors, TLS failures and missing networks appear
separately. Only transient failures retry, with bounded backoff. Server error text,
request URLs and token values never enter result messages or logs.

Tokens are encrypted with AES-GCM, whose per-save key is wrapped by a dedicated
Android Keystore RSA key (also compatible with Android 5). State uses an atomic
file and a cross-process lock under `noBackupFilesDir`; it is excluded from Android
backup and ArcaenBox configuration exports. Tokens are masked in the UI and are
not saved into activity-instance state or autofill.

该协议遵循 [相关教程](https://wiki.uuuz.de/guide/tutorials/po0fw-whitelist.html)
及其 [上游实现](https://github.com/w0ven/po0fw):
`POST https://124.221.69.228/api/firewall/<token>/add[?slot=N]`, 空请求体。
IPv4 端点呈现一个公开受信任的 IP 证书。普通证书
并保持主机名验证启用。请求不能重定向，使用系统
HTTP 代理，或回退到 VPN：套接字和 DNS 绑定到明确检查的
非 VPN Android 网络

当 Po0 令牌被配置时，ArcaenBox 还会添加相当于
`IP-CIDR,124.221.69.228/32,DIRECT,no-resolve`
sing-box 路由列表。它使用一个纯直接出站和一个仅限 IP 的匹配器。
不引入 DNS 解析或更改 DNS 规则。这适用于
自定义配置合并，同时涵盖完整的自定义 sing-box 配置文件。
等价重复规则被合并；其他规则保持其顺序。
生成的规则由系统自动管理，不会添加到可编辑区域
用户规则数据库。添加第一个标记或清除所有标记将重新加载
已经连接过一次代理以应用/移除它。后续更新不会重新加载它。
服务会验证物理网络本身，因为 WorkManager 的默认
网络验证可能报告一个无法使用的 VPN，而物理连接是正常的。

成功需要启用的防火墙和返回的当前出口
白名单。请求的固定插槽也必须匹配。格式错误的响应、禁用
防火墙、插槽冲突、HTTP 错误、TLS 失败和缺失网络出现
单独处理。仅对暂时的故障进行重试，并具有有界的退避。服务器错误文本，
请求 URL 和令牌值永远不会出现在结果消息或日志中。

标记使用 AES-GCM 加密，每项保存的密钥由专用密钥封装
Android 密钥库 RSA 密钥（同时兼容 Android 5）。状态使用原子
文件以及`noBackupFilesDir`下的跨进程锁；它被排除在 Android 之外
备份和 ArcaenBox 配置导出。在用户界面中，令牌被屏蔽，并且是
未保存到活动实例状态或自动填充。

## Verification
## 验证

The release workflow runs protocol unit tests, verifies signed APK metadata and
the decoded transparent PNG in every architecture, and performs Android 15 UI
checks. Po0 checks cover required/invalid tokens, encrypted save-and-reopen,
password masking, removal of saved tokens, and light/dark layouts. Test tokens are
saved with automatic requests off and then removed; no real Po0 firewall is
modified by these checks. A real server/token is needed to validate successful
whitelisting over a device's actual Wi-Fi and mobile networks.

发布工作流运行协议单元测试，验证签名 APK 元数据
在每种架构中解码透明 PNG，并执行 Android 15 UI
检查。Po0 检查涵盖所需/无效标记、加密保存后重新打开。
密码掩码、删除保存的令牌以及浅色/深色布局。测试令牌是
已禁用自动请求并保存，然后删除；没有实际的防火墙 Po0
被这些检查修改。需要一个真实的服务器/令牌来验证成功
设备实际 Wi-Fi 和移动网络的白名单设置。
