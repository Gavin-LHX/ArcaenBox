# 新界面 / Redesigned interface

ArcaenBox 默认使用重新设计的 Material 3 界面，原侧栏界面保留为「经典界面」，可随时切换。

The app now opens in a redesigned Material 3 interface. The previous drawer interface
is kept unchanged as the **Classic interface** and can be switched back at any time.

## 布局 / Layout

| 目的地 Destination | 内容 Content |
| --- | --- |
| 首页 Home | 连接开关与状态、实时上传/下载速率、当前节点、真连接延迟与出口 IP、快捷操作（剪贴板导入、扫码、更新订阅、分应用代理） |
| 节点 Nodes | 节点列表（与经典界面相同的分组、测速、多选、编辑功能），右下角为连接按钮 |
| 分组 Groups | 分组与订阅管理 |
| 路由 Route | 路由规则 |
| 更多 More | 资源文件、Po0 白名单、sing-box 面板（启用 Clash API 时）、设置、工具、内核管理、日志、切换到经典界面、文档、关于 |

- 手机使用底部导航栏；宽度 ≥ 600dp（平板、折叠屏、横屏）使用侧边导航栏（Navigation Rail），首页与「更多」内容居中限宽。
- 横竖屏切换会保留当前页面、导航选中项和二级页面的返回栈；连接按钮仅在节点列表中显示。
- 「更多」中打开的页面显示返回箭头，返回键回到上一页；在非首页按返回键回到首页，在首页按返回键将应用移到后台。
- 节点编辑器、设置项、数据库和偏好键均未改变，两个界面共享同一份数据。

Phones use a bottom navigation bar; screens at least 600dp wide use a navigation rail.
Pages opened from **More** show a back arrow. Profiles, settings, database and preference
keys are shared by both interfaces.

## 回退到经典界面 / Falling back to the classic interface

- 新界面：**更多 → 切换到经典界面**，或 **设置 → 经典界面**。
- 经典界面：**设置 → 经典界面**（关闭）即可回到新界面。

The choice is stored in the `classicUi` preference (default off). The launcher entry,
shortcuts, notification, Quick Settings tile and import links always open `MainActivity`,
which forwards to `LegacyMainActivity` while the classic interface is enabled.

## 实现 / Implementation

- `MainHostActivity`：两个界面共享的服务连接、链接导入、插件提示和偏好监听。
- `MainActivity`：新界面外壳（`layout_main_shell`，`layout-w600dp` 为导航栏变体），`HomeFragment`、`MoreFragment`。
- `LegacyMainActivity`：原 `MainActivity` 的侧栏界面，行为未改变。
- 原有页面（节点、分组、路由、设置等）通过 `MainHostActivity` 访问宿主，两个界面共用。

## 验证 / Validation

`.github/scripts/ui_smoke.py` 新增 `redesigned`、`dark-redesigned` 与 `landscape-redesigned`
检查：首页状态、底部导航各目的地、从「更多」进入设置并返回、深链接导入、切换到经典界面再切回，
以及横屏导航栏布局。原有的侧栏检查通过应用内开关切换到经典界面后继续执行，以确保回退界面可用。

`redesigned-regressions` 检查在底部导航与侧边导航之间切换时的页面恢复、返回行为，
以及开启底栏设置后，分组与路由页滚动不会重新显示连接按钮。
