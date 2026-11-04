# PhoneAgent

AIResearch（AI 研究所）下的手机端 Agent 实验项目：把旧 Android 手机变成可以接受语音或文字指令、执行多步手机操作的智能助手。

仓库：[DsDoctor/PhoneAgent](https://github.com/DsDoctor/PhoneAgent)

## 方案方向

优先验证 **Buddy + Claude Code + 外部云端模型**：在手机本地运行真正的 Claude Code，复用其 Agent Loop、上下文与会话管理，通过 MCP 调用手机控制工具。

```text
用户语音 / 文字
    → Buddy App
    → 手机本地 Claude Code ↔ 外部云端模型
    → 本机 MCP 工具
    → Android 无障碍服务
    → 读屏、点击、输入、滑动与结果检查
```

手机负责交互、任务循环和动作执行，模型推理由云端服务提供。候选实现为 [buddy-android](https://github.com/ghorbelhamdi/buddy-android)，本仓库用于沉淀调研、适配与实验结果。

## 当前状态

截至 **2026-11-04**：

- 已完成初步资料与源码调研，确定优先验证上述路线。
- 已准备华为畅享 50 Pro（CTR-AL00，8 GB RAM，HarmonyOS 4.2），配置 ADB 和 Termux。
- 尚未安装 Buddy、配置外部模型或完成实机验证；仓库目前以研究文档为主。
- 设备实际 Android API 级别、中文语音、后台驻留和模型兼容性仍待验证。

## 验证计划

- [ ] 确认设备 Android API 级别与 CPU 架构，验证 Buddy 安装和 Claude Code 启动。
- [ ] 选择外部模型，验证 API 连通、工具调用、流式响应和截图理解。
- [ ] 在计算器、设置等场景验证读屏、点击、中文输入、滑动和结果检查。
- [ ] 验证中文语音指令驱动多步手机操作。
- [ ] 测试后台运行、长任务、熄屏、锁屏和系统清理后的恢复行为。
- [ ] 选取具体业务流程，记录成功率、耗时、模型成本和人工介入次数。

## 文档

- [Buddy + Claude Code 方案调研](docs/research/2026-11-04-buddy-phone-agent.md)：工作方式、设备要求、运行边界与参考来源。

## 项目组织与同步

本项目位于 AIResearch 下的 `PhoneAgent/` 子目录，使用独立 Git 仓库，远端 `origin` 指向 `DsDoctor/PhoneAgent`，主分支为 `main`。

在本项目目录中拉取更新：

```bash
git pull --ff-only
```

完成修改并检查待提交文件后，通过 `git commit` 和 `git push` 同步到 GitHub。这里使用普通 Git 同步，没有配置定时或自动推送。

设备原始维护记录保留在 AIResearch 的 `phone-maintenance/` 中。本仓库收录可分享的文档、代码与脱敏实验结果；凭据和原始设备输出放在被忽略的本地目录中。
