# EPUB 透明 WebView 文本层

## 基线

当前分支从 `1280cfd6e fix(epub): restore web layout as primary renderer` 开始。

## 目标

EPUB 页面继续由现有 Canvas 渲染负责最终视觉，保证图片、背景、边框、复杂 CSS 和翻页动画不被文本选择功能破坏。新增透明 WebView 文本层只负责文本命中、选择和复制。

## 原则

- 不恢复 native translator 作为主渲染路径。
- 不使用大量 `TextView` 覆盖页面。
- 透明 WebView 必须复用 EPUB Web 测量相同的 HTML、CSS、尺寸和资源拦截逻辑。
- WebView 中图片和元素只透明隐藏，不能 `display:none`，避免改变文本避让布局。
- 选择失败时回退到旧 Canvas 选择逻辑，不能影响阅读显示。
- 翻页、切章、重新分页、横竖屏变化时必须清理选择状态。

## 验收

- 普通中文和英文正文能长按选择并拖动光标。
- 复制、搜索、词典、AI 等阅读原生菜单能获取 EPUB 选中文本。
- 图片、背景图、边框和特殊 CSS 不因文本层消失。
- 翻页动画不被透明 WebView 干扰。
- 二次进入同一本书时选择坐标不漂移。
