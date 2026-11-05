# 华为畅享 50 Pro：1.1.0 交互与原生记忆验证

日期：2026-11-05。设备：华为畅享 50 Pro / CTR-AL00，HarmonyOS 4.2，Android 12 / API 31，ARM64，无 root。运行环境使用已安装的 Alpine 3.22.7 与固定 Claude Code **2.1.311 / linux-arm64-musl**；固定版本与来源见 [运行环境说明](../../scripts/RUNTIME.md) 和 [依赖锁文件](../../scripts/runtime-lock.json)。

本文区分原生协议探针与最终 App 集成验证。最终安装包为 Android 1.1.0 / versionCode 11，沿用原安装签名覆盖升级。完整 App 集成和界面快检结果在文末单独记录。

## 协议探针隔离条件

- 探针开始前确认最近任务为已结束状态，且没有现存 Claude / PRoot 进程。
- 每次探针创建唯一的 App 缓存目录，将其中独立的 home 绑定为 Linux `/home/agent`，并使用独立的桥接目录和临时目录；复用已安装的 Linux rootfs。
- 未读取或写入用户真实 Claude HOME、模型配置或历史会话。未安装 instrumentation，未切换或重绑无障碍服务。
- 使用开发机 `127.0.0.1` 上的确定性 Anthropic 协议响应服务、临时 ADB reverse 映射和一次性测试 Key。**没有调用真实外部模型。** 固定响应只验证协议和工具执行，不验证模型推理能力。
- 协议探针的 MCP 配置为空，未执行手机点击、输入或其他手机控制动作。
- 探针完成后已检查：无残留 Claude / PRoot 进程、无端口反向映射、无本次隔离缓存目录。

探针实现见 [probe-interaction.py](../../scripts/probe-interaction.py)，固定响应服务见 [interaction_fixture.py](../../tests/interaction_fixture.py)。

## 已完成：原生交互协议

使用固定版本 CLI 的 `stream-json` 双向输入输出、`stdio` 权限回调和 `default` 权限模式，实际完成以下验证：

| 检查 | 已观测结果 |
|---|---|
| 初始化 | 发送 `initialize` 控制请求，收到匹配请求 ID 的成功响应后发送首条用户消息 |
| 原生反问 | CLI 发出 `can_use_tool` 请求，工具名为 `AskUserQuestion`，包含原问题结构及 `tool_use_id` |
| 回传答案 | 保留原 input，添加以完整问题文本为键的 `answers`，通过匹配请求 ID 的控制响应回传；随后得到成功的最终结果 |
| 基础文件工具 | 真实执行 Write → Read → Edit → Glob → Grep；隔离目录中的文件内容与修改后的预期一致 |
| 打断 | 在固定响应请求进行中发送 `interrupt`，启用 `cancel_queued`，收到控制确认 |
| 同进程继续 | 被打断的旧轮返回 `error_during_execution`，`terminal_reason` 为 `aborted_streaming`；随后发送新的用户补充，同一 CLI 进程、同一会话 ID 返回成功结果 |

打断确认与旧轮的最终结果是两个独立事件；实现不能把确认当成进程退出，也不能把预期的打断结果当成整个交互连接不可用。CLI 在一轮结束后仍保持输入通道开放。

本次反问验证覆盖单个标准选项问题及一次答案回传；未据此宣称多选、多个问题、取消问题、进程重启后的反问恢复或 App 界面提交路径已经通过。

私有证据：

- `local/interaction-probe-a5d53540abe24d059576e76595a1a2b3/result.json`
- 同目录的 `events.json`、`fixture.json`

这些文件只作为本地验证证据，不提交完整请求或事件记录。探针中曾修正固定响应服务对“打断后连续用户文本被 CLI 合并”的识别；上述 PASS 来自修正后的完整重跑。

## 已完成：Claude 原生记忆

另一次独立探针在其测试 HOME 中预置了带合成标记的原生文件，并实际验证：

- `/home/agent/CLAUDE.md` 被 CLI 自动加载进模型请求上下文。
- `/home/agent/.claude/projects/-home-agent/memory/MEMORY.md` 被 CLI 自动加载进模型请求上下文。
- Write 在该原生 memory 目录创建记忆条目文件，Edit 更新 `MEMORY.md` 索引；最终文件内容与索引引用均核对通过。

原生记忆内容可出现在请求中的用户 `system-reminder`，不能只检查顶层 system 字段来判断是否加载。本次检测覆盖整个合成请求，并不输出其完整内容。

本验证证明原生路径的加载和真实文件写入链路可用；未使用真实外部模型判断何时应该记忆，也未修改用户已有记忆。没有另建 App 专属记忆数据库，没有启用 Bash。

私有证据：

- `local/interaction-probe-fff493d43f574470aaca46cf1c28df04/result.json`
- 同目录的 `events.json`、`fixture.json`

## 已完成：本机自动回归

最终本机执行 `python3 -m unittest discover -s tests -q`，结果为 **69 项通过，3.412 秒**；`git diff --check` 通过。新增检查包括恢复答案必须包含原会话的真实反问上下文。

其中与 1.1.0 直接相关的验证包括：

- 原生交互固定响应服务的答案校验、合并用户消息处理、记忆上下文识别与文件工具失败处理。
- 运行真实 `LastTaskStore` 和 `TaskHistoryStore` 类的本机 Java 测试：`paused/waiting_user` 保留；`pausing/resuming` 恢复为 `paused`；问题、答案和会话 ID 保留；恢复幂等；显式清除交互字段；超限数据拒绝；原 `running → interrupted` 行为保留。测试使用 SDK 的 JSON 源码和临时文件适配器，不代替 Android `AtomicFile` 或 Controller 的真机验证。
- 复合 MCP 工具的执行代次保护：`run_routine` 后续步骤继续携带首次响应的 `action_epoch`，换代后被拒绝；`open_app_ready` 遇暂停立即退出；已固定代次发生变化时拒绝成功响应且不发送后续 HTTP 请求。新的顶层工具调用重新建立代次上下文。

`LinuxRuntime` 与新增 `ClaudeTransport` 还通过了独立 Java 编译。完整 App 构建、签名校验也已通过；最终 APK SHA256：`bf4ec84e5c8bebc69ad00cde937a7a26be628dbd2d6578547ec571af47dcbdbf`。

## App 真机集成

最终候选已覆盖安装。使用 `scripts/test-interaction.py` 启动开发机固定响应服务，调用真实 Claude Code 与 App Controller；HOME、历史记录、恢复用会话副本均在唯一缓存目录。此专项会安装测试包、重启 App 和重新绑定无障碍服务，不属于无操作的 UI 快检。

结束任务的针对性验证已通过：在原生反问等待期间结束任务，约 654 毫秒确认原 Process 退出，并在 instrumentation 退出前确认没有可执行的 Claude / PRoot 子进程。启动取消测试也确认 `registerProcess` 返回已取消错误前，创建的进程已被回收。

修正过程中捕获过真实问题：只终止 PRoot 会让仍活跃的 Claude 变为孤儿进程。最终实现先通过原生控制通道取消，再按 PID、启动时间和 UID 校验并清理本次子树；保留 PRoot 直到其子进程退出。最终验收必须包括子进程检查，不能只看 UI 的 STOPPED 或 Java Process 引用。

完整 interaction 回归 **PASS，110.11 秒**，原生 instrumentation 返回 `result=PASS / INSTRUMENTATION_CODE: -1`。完整回归中的结束任务为 620 毫秒；本机固定响应共 6 次请求，无外部模型调用。

- 原生反问进入等待；错误会话和过期问题 ID 的回答被拒绝；正确回答继续同一会话。
- 实际原生卡片的单选、多选、自由回答、页面草稿隔离与旋转保存通过；选择本身不会提交。
- App 仍可见时不发送答案、不打开动作门；隐藏后立即返回的排队竞态也不执行。
- 复制真实未完成 Claude 会话后重建 Controller，不产生自动请求；明确回答后通过 `--resume` 恢复原问题上下文和同一会话 ID。
- 暂停等待 interrupt 确认与旧轮结果；暂停期间桥接拒绝动作，main-thread 队列中的旧代次动作也被拒绝；补充后继续同一进程、同一会话。
- 启动取消等待新建子进程回收；等待回答时结束任务清理原进程和活跃子进程；重置后保留输出。

私有证据：`local/interaction-test-08ad837d12394be9880d402d61987844/`，包括 `result.json`、`device.log`、仅合成问题的 `ui-card.png` 及前后文件摘要。最终已卸载仅测试包，恢复 App 前台与无障碍绑定，清理端口映射和隔离目录；无 Claude/PRoot 残留。

默认 UI 快检 **PASS，126.765 秒**：导航与历史、左滑删除按钮显隐、既有会话/过程、Markdown 表格横滑、草稿隔离、任务/设置软键盘及恢复核验通过。模型调用和任务提交均为 0，历史、上一任务及 preferences 摘要不变。本次未选择可选横屏检查。

私有证据：`local/ui-regression/20261105-175417-f0ef3c/result.json`。首次调用因手机息屏在预检即停止，唤醒后完整重跑得到上述 PASS。

## 用户数据保留

整个升级与测试期间，真实 `task-history.json` 与 Claude HOME 的 64 个文件汇总摘要持续一致；测试模型和记忆只进入隔离 HOME。

首次覆盖安装至首轮测试前，`last-task.json` 的唯一变化为 `acknowledged: false → true`，任务正文和结果没有变化。同阶段 preferences 文件整体摘要发生变化，但安装前未记录逐键摘要，无法归因于具体键；不宣称首轮配置文件逐字节一致。后续最终安装的逐键摘要一致；完整回归前后以及清理后的历史、preferences 和 Linux HOME 文件摘要均一致。没有回写旧用户文件以掩盖差异。
