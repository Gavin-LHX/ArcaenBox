# ArcaenBox 1.6.2 底部适配修复

本次修复主页面、内核管理等侧边页面和导航抽屉在系统导航区上方结束，留下独立色带的问题。版本为 `1.6.2-arcaenbox.1`，versionCode `270`。

## 原因与修改

1.6.1 的窗口最外层统一添加系统导航栏的底部 padding。在 720×1280、手势导航的 Android 15 模拟器上，底栏和侧栏止于 y=1232，剩余 48 像素显示窗口背景；与底栏或抽屉颜色不同。此前的界面回归没有检查这块区域的背景连续性。

- 主窗口将底部安全区域传给底栏和侧栏，由它们把背景绘制到屏幕底边。
- BottomAppBar 自身处理导航边距，文字与连接按钮仍在系统导航区上方。
- 页面列表按包含导航区域的底栏总高度避让；隐藏底栏时仍保留内容安全边距。
- 抽屉取消贴边圆角和底部遮罩，背景铺到底；旧 Android 的兄弟视图仍能获得导航边距。
- 根据实际主题背景亮度设置系统栏图标颜色，关闭主窗口额外的导航栏对比度遮罩。
- 编辑页及键盘区域继续由原来的内容边距逻辑处理。

实现参考 [Android 边到边布局说明](https://developer.android.com/develop/ui/views/layout/edge-to-edge)及 [Material BottomAppBar 1.13.0](https://github.com/material-components/material-components-android/blob/1.13.0/lib/java/com/google/android/material/bottomappbar/BottomAppBar.java)。

## 界面验证

新增 `.github/scripts/bottom_edges.py`：两种导航方式 × 深浅主题 × 两组尺寸/字体 × 三个界面，共 24 个场景。

| 项目 | 覆盖 |
| --- | --- |
| 导航 | 手势导航、三键导航 |
| 主题 | 浅色、深色 |
| 尺寸与字体 | 720×1280 / 320 dpi / 1.0；1080×2400 / 440 dpi / 1.3 |
| 界面 | 主页面、显示底栏的内核管理页、打开侧栏 |
| 判断 | 背景控件结束坐标等于屏幕底边；左右边缘颜色连续；文字及连接按钮在实际系统导航区上方；手势条可见 |

旧版 1.6.1 在同一检查中明确失败：`surface stops at 1232, screen ends at 1280`。本地候选版的 24 个场景已全部通过。测试会设置并恢复其他页面显示底栏的选项，并从系统实际导航栏坐标读取安全区域。

最终安装包来自[构建 34683643313](https://github.com/Gavin-LHX/ArcaenBox/actions/runs/34683643313)，源码为 `d0c028174f6b4bc417cc84da4f51a60f2983fc6b`。

- 60 项单元测试通过，失败、错误、跳过均为 0。
- 四个 ABI 的包名 `com.arcaenbox.android`、应用名称、版本、SHA-256 及签名均经本地复核；签名证书 SHA-256 延续 `8750ecddc7b8ce59ff13a701ed30bb5e4f5bcefef8abfd054d91b10e75822ff2`。
- 最终 x86_64 APK 实际覆盖安装后，再检查中文主页面、内核管理、侧栏及滚到末尾的侧栏。1080×2400 屏幕上四张图的背景控件均结束于 y=2400，左右底部背景颜色连续。
- 本地另验证隐藏底栏时的内核管理及侧栏，背景连续且侧栏延伸到底边。

最终构建的云端界面回归全部通过，保留 37 张截图，失败列表为空。其中 24 个底边场景全部通过；其余覆盖启动、设置持久化、节点编辑及键盘、VPN 服务启动、大字体和横屏。

[公开 Release](https://github.com/Gavin-LHX/ArcaenBox/releases/tag/v1.6.2-arcaenbox.1) 已发布为最新版，标签指向上述测试源码。四个 APK 与 SHA256SUMS 的公开摘要全部匹配本地；未登录重新下载 arm64 APK，SHA-256 为 `684fbedf272e7c8d5bd56953fd45ef4ee484e7501d8847c60d6a2b370f7bf49f`。

## 范围

本次为窗口布局修复，原生内核、节点测试和代理转发逻辑没有修改。此前的协议验证见 [1.6.1 验证记录](verification-1.6.1.md)。本次设备验证使用 Android 15 x86_64 模拟器，不代表已在所有厂商实体手机上验证。
