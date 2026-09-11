# 应用与 sing-box 更新

在“关于”中检查应用正式版或预览版更新。检查读取 GitHub Release 的 `tag_name` 和 `prerelease`，不再依赖名为 `preview` 的固定标签，也不再拿发布标题与 `1.4.2` 做子串匹配。ArcaenBox 的修订号参与比较，例如 `.6` 大于 `.5`。内核发布不会混入应用更新列表；没有应用预览版时显示明确提示。

“关于 → sing-box 更新与切换”提供：

- 正式版 `1.14.0`、测试版 `1.15.0-alpha.2`，均保留 Android 所需的适配补丁。上游版本核对于 2026-09-11。
- 无网络也可切换两种内置内核；“应用并重启”先停止代理，再重启应用和后台进程。只在新进程中加载一个 Go 运行时。
- 按所选渠道检查并下载独立的内核包。已是最新版时仍可重新下载当前内核。下载完成只保存候选文件，点击应用后生效。
- 恢复所选渠道的内置内核。节点、订阅、分组、路由和应用设置不会被清空。

内核版本旁的 `rN` 是 ArcaenBox 适配包修订号，不是上游 sing-box 版本。

## 兼容与验证

本项目使用 gomobile JNI 内核，不是启动独立的 sing-box 命令行程序。因此官方 Linux、Android 命令行二进制不能直接替换此内核。下载的是本仓库编译的兼容包：每个渠道和架构都有独立 `.so`，清单包含版本、修订号、文件长度、SHA-256 和 JNI 桥标识。桥标识由全部绑定类生成；正式与测试内核的绑定必须完全一致才能打包。Go 包内的上游 Box 对象不向 Java 自动暴露方法，避免上游新增方法改变桥接口。

清单使用现有 ArcaenBox 签名证书对应的 RSA 私钥签名；应用只内置公钥。每次安装和加载下载内核，都验证签名、桥标识、渠道、CPU 架构、长度和文件哈希。下载限于本仓库的 HTTPS Release 附件，使用临时目录、只读二进制和原子切换。验证失败不会替换当前内核；不兼容的内核提示先升级应用。

下载文件和选择状态放在应用私有 `noBackupFilesDir/cores` 中，不进入配置备份。启动标记记录初始化是否完成；下次启动发现上次未完成时，会撤回下载内核选择并恢复内置正式版。运行过程中出现配置错误仍应查看日志，或在此页面主动恢复内置版本。

旧的生成配置在内存中转换到新版格式：TUN 地址、嗅探与目标解析动作、新 DNS 传输、FakeIP、DNS 拦截、默认域名解析器、WireGuard 端点，以及 GeoIP/GeoSite 兼容规则集。原始节点配置不改写。不具备等价自动转换方式的自定义旧 DNS outbound 匹配规则会明确报错，需要按上游迁移文档调整。

## 构建与发布

1. `buildScript/lib/core/get_source_env.sh` 固定上游提交、渠道、版本、适配修订号和编译标签。稳定版基于 MatsuriDayo 的 Android 分支，测试版基于 SagerNet 官方标签并应用同一组 Android 补丁。补丁在 `buildScript/lib/core/patches/`。
2. 运行 `ArcaenBox Native Cores` 工作流。两种渠道分别执行配置、实际代理请求和流量统计回归测试，再编译四种 Android 架构，生成 `core-stable` / `core-preview` AAR 附件。
3. 运行 `ArcaenBox Signed APK`，填写对应原生工作流运行 ID。工作流校验绑定一致性和实际内核版本，内置双内核，生成独立签名更新包，运行应用单元测试和 Android 15 UI/代理启动/切换验证。`ArcaenBox Android Checks` 可单独检查 Kotlin 和更新验证单元测试。
4. APK 工作流成功后，运行 `ArcaenBox Publish Verified Cores` 并填写该 APK 工作流 ID。发布流程读取原始签名附件，验证签名与所有二进制哈希，再发布两个渠道，拒绝覆盖现有标签。经验证的 APK 发布为 `v1.4.2-arcaenbox.6` 这类应用标签；独立内核发布为 `core-stable-1.14.0-2`、`core-preview-1.15.0-alpha.2-2`。每个内核发布包含四个 `.so`、`manifest.json` 和 `manifest.sig`。内核发布不设为仓库 Latest；测试内核设置 prerelease。
5. 后续升级内核时，先修改固定提交和版本，递增适配包修订号，完成测试后发布新的内核附件。桥接口变化时必须同时发布新版应用，不能仅强改清单中的桥标识。

`libcore/init.sh` 将目标模块的 `golang.org/x/mobile` 指向已打补丁的本地 gomobile 源码。gobind 根据目标模块查找 `Seq.java`，只修改构建工具源码而不修改模块解析路径，会错误地使用模块缓存中的固定加载器。原生构建和 APK 打包都检查最终 `go/Seq.class` 确实调用 `CoreRuntime`。

核心包发布后，可运行 `ArcaenBox APK UI Verification`，指定原始 APK 运行 ID 和 `checks=core-download`，验证真实 Release 下载、主进程/后台进程加载路径、未应用前不切换、损坏文件回退、重新下载修复和初始化失败回退。该检查只操作隔离的 Android 模拟器。

上游：
- https://github.com/SagerNet/sing-box/releases/tag/v1.14.0
- https://github.com/SagerNet/sing-box/releases/tag/v1.15.0-alpha.2
- https://sing-box.sagernet.org/migration/
