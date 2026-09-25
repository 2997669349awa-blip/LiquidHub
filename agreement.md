# AI 开发规则

本项目是 RikkaHub 的 AGPL-3.0 衍生作品：
https://github.com/rikkahub/rikkahub

## 许可证（硬性）
- 所有文件保留 AGPL-3.0 版权头和 LICENSE。
- 修改过的文件顶部加：
  // Modified by [你的名字] on 2026-09-25.
  // This file is part of [项目名], a fork of RikkaHub.
  // Licensed under AGPL-3.0.
- 不加任何与 AGPL 冲突的附加条款。

## 架构（硬性）
- 只改 UI 层：ui/theme、ui/components、ui/pages。
- 不改核心逻辑：ChatVM、ChatService、ProviderManager、AppDatabase。
- 保留原有分层。

## UI 目标
- 毛玻璃：Haze 库，半透明 + 背景模糊。
- 液态玻璃：多层渐变 + 边缘高光 + 连续圆角。
- Android < 12 降级：不用模糊，改半透明纯色。
- 深色/AMOLED：纯黑背景 + 玻璃卡片，文字对比度要够。

## 输出要求
- 只输出改动部分或 diff，不要重复未修改代码。
- 每次只做一个组件，编译通过再继续。
- 不生成无关测试和冗余注释。
- 列表项禁止逐个加模糊，只模糊固定区域（输入框、顶栏、弹窗）。
