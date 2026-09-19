# 可切换的 Liquid Glass 界面

在“设置 → 界面风格”选择 Material Design 3 或 Liquid Glass。默认仍为 MD3，选择会保存；切换只重建界面，不主动重启代理服务。主题颜色、夜间模式、规则、节点和其他设置保持独立。

Liquid Glass 将工具栏、分组导航、抽屉、连接按钮和连接后的信息区作为玻璃层。节点与规则内容保持清晰的卡片表面。背景使用连续的浅青与紫色柔和渐变，玻璃有模糊、边缘高光。未连接时仍然只保留 A，不恢复空白底栏，也不拦截下方内容的点击。

- A 和操作按钮：按压放大，随拖动方向伸展并小幅跟手移动；高光跟随手指，松手或取消后使用阻尼弹簧复位。移出按钮取消操作，不把视觉拖动改成连接指令。
- 开关：绿色胶囊轨道与玻璃滑块，按住滑块放大，左右拖动改变开关状态；保留原生点击、键盘、RTL 和无障碍语义。
- 分组导航：玻璃选择指示器随点击和页面滑动移动，点击切换带弹性拉伸。
- 页面与菜单：短暂缩放和淡入淡出，弹出层使用更厚的浅色或深色表面保证文字清晰。

“通知与显示 → 降低透明度”可以关闭玻璃的图形特效，使用更清晰的实色表面。系统关闭动画时不播放按压形变。

## 原生实现与兼容

应用仍使用 Android View。内容层单独记录为 RenderNode，由上方的玻璃控件采样，避免把文字或图标一起模糊。工具栏只采样环境背景，侧栏和 A 可采样应用内容。渲染只随界面变化或交互更新，没有持续动画计时器，也不读取应用外的屏幕内容。菜单是高不透明度的着色表面，不声称使用跨窗口实时折射。

- Android 13 / API 33 及以上：RenderEffect 模糊，A 按钮和开关滑块通过 AGSL RuntimeShader 在圆角边缘折射背景。
- Android 12 / API 31–32：RenderEffect 毛玻璃与高光，不使用折射 shader。
- Android 11 / API 30 及以下，或软件绘制：使用带高光的兼容表面。
- 降低透明度：关闭模糊与折射，环境背景也恢复实色。

参考并安装了 [KMP Liquid Glass skill](https://github.com/Kashif-E/KMPLiquidGlass/tree/master/plugins/kmp-liquid-glass/skills/kmp-liquid-glass)。本项目采用其背景与玻璃分层、前景文字保持清晰、阻尼跟手与分级兼容思路，使用原生 View 实现；未引入 Compose Multiplatform 依赖。此效果为 Android 上的 Liquid Glass 风格实现，不使用 Apple 的系统私有 API，也不声称与 iOS 的渲染、容器融合及所有系统转场完全一致。

相关原始文档：[Apple Materials](https://developer.apple.com/design/human-interface-guidelines/materials)、[Android RenderNode](https://developer.android.com/reference/android/graphics/RenderNode)、[RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)、[RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader)。
