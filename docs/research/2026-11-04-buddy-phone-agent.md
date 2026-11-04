# 手机 Agent：Buddy + Claude Code + 外部云端模型

记录日期：2026-11-04

状态：已完成初步资料与源码调研，待实机验证；尚未安装 Buddy 或配置模型。

> 这是最初的候选方案调研记录。后续按用户要求改为独立实现 PhoneAgent App、私有 Linux 运行时和手机 MCP，不搬用 Buddy 项目。当前实现与使用方法见 [项目说明](../../README.md)。目标华为设备已实测为 Android 12 / API 31 / ARM64。

## 要验证的想法

在旧华为手机上安装 Buddy App，在手机本地运行真正的 Claude Code，接入外部云端模型，通过语音或文字下达指令，让 Agent 读取界面、点击、输入、滑动并完成多步手机操作。

核心偏好：复用 Claude Code 的 agent loop、上下文与会话管理；手机端提供交互入口和执行工具。模型推理放在云端。

## 候选项目

- 项目：[ghorbelhamdi/buddy-android](https://github.com/ghorbelhamdi/buddy-android)
- 安装包：[GitHub Releases](https://github.com/ghorbelhamdi/buddy-android/releases)；本次调研版本为 [v2.2.21](https://github.com/ghorbelhamdi/buddy-android/releases/tag/v2.2.21)。
- 文档：[使用指南](https://github.com/ghorbelhamdi/buddy-android/blob/main/docs/GUIDE.md)
- 主项目许可：Apache-2.0；捆绑组件各自保留其许可。
- 成熟度：已有可安装 APK 和作者真机测试，项目仍自述为早期阶段，主要测试 OnePlus 12 / Android 16。尚不能据此保证华为兼容性或商业交付稳定性。

## 工作方式

```text
用户语音 / 文字
    → Buddy 交互入口
    → 手机上的 Linux 用户空间（Alpine + PRoot）
    → 真正的 Claude Code CLI ↔ 外部云端模型
    → 本机 MCP 手机工具
    → Android 无障碍服务执行操作
    → 界面文字 / 截图 / 动作结果返回 Claude Code
```

Buddy 安装并启动官方 Claude Code CLI，调用形式包含 `claude -p`、`--mcp-config` 和 `--resume`，核心推理与工具调用循环由 Claude Code 运行。手机控制工具包括 `read_screen`、`screenshot`、`tap`、`type_text`、`swipe`、`scroll`、`open_app` 等。

Buddy 自带运行环境，按项目设计无需另装 Termux、无需 root 或电脑常驻。Android/Termux 仍不属于 Claude Code 官方支持平台，整体兼容性需要实测。

### 外部模型接入

Buddy 的 **Settings → API keys** 支持配置模型服务地址和 API Key；其指南说明 Claude Code 路径使用 **Anthropic 风格的 API 地址**。具体供应商和模型尚未选择。

不能把任意模型地址直接当作可用：需要验证所选服务与 Claude Code 的兼容性，包括工具调用、流式响应、上下文处理和截图理解。读结构化界面与看截图是不同能力；模型若不支持图像，截图驱动的操作会受限。保留 Claude Code 的 loop，也不代表更换模型后任务成功率不变。

## 运行体验与边界

- 开启无障碍服务后，可以离开 Buddy 主界面，由服务和悬浮球继续提供入口。
- 操作普通 App 时会使用手机的真实屏幕，把目标 App 切到前台；需要时可暂停 Agent，自己使用手机。
- 部分无需屏幕的任务可以在锁屏时运行；需要界面的任务可能要求用户解锁。支持任务期间保持亮屏。
- 后台服务存活不等于全天候语音监听。普通语音由点按触发；中文识别路径需验证，不能默认 Claude 听写支持中文。
- 系统清理、强停或重启可能打断任务；文档提供中断后 Continue 机制，不能承诺原任务自动无缝恢复。

## 目标设备

华为畅享 50 Pro，型号 CTR-AL00，8 GB RAM，HarmonyOS 4.2.0.130(C00E130R2P5)。此前已配置 ADB 和 Termux 0.118.3，无 root、Bootloader 锁定。

Buddy 要求 64 位 ARM、Android API 30 及以上。实际 Android API 级别待确认，不能由 HarmonyOS 版本名称直接推断。

## 后续验证顺序

1. 确认设备实际 API 级别和架构，验证 Buddy 安装及内置 Claude Code 启动。
2. 选择外部云端模型，验证 API 连通、工具调用和截图理解。
3. 在计算器、设置等简单场景测试读屏、点击、中文输入、滑动和操作结果检查。
4. 接入中文语音，验证“说一句话 → 多步操作 → 返回结果”。
5. 测试切后台、长任务、熄屏、锁屏和系统清理后的恢复行为。
6. 再选具体业务流程，记录成功率、耗时、模型成本和人工介入次数，判断商业价值。

## 调研依据

- [Claude Code MCP 官方文档](https://code.claude.com/docs/en/mcp)
- [Claude Code 系统要求](https://code.claude.com/docs/en/setup)
- [Buddy Claude Code 启动脚本](https://github.com/ghorbelhamdi/buddy-android/blob/main/linux/buddy-run)
- [Buddy 手机工具实现](https://github.com/ghorbelhamdi/buddy-android/blob/main/app/src/main/java/app/buddy/assistant/Tools.java)
- [Buddy 服务生命周期](https://github.com/ghorbelhamdi/buddy-android/blob/main/app/src/main/java/app/buddy/assistant/BuddyService.java)
- [作者真机测试与局限](https://github.com/ghorbelhamdi/buddy-android/tree/main/benchmark)

以上结论来自文档、源码与维护者记录；本次没有在目标华为手机上实测。
