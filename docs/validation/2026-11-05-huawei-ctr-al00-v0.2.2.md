# 华为畅享 50 Pro：0.2.2 任务限制与结束记录验证

日期：2026-11-05。应用：PhoneAgent 0.2.2（调试版，`versionCode 5`）。

本次验证覆盖取消应用主动设置的任务轮数、总时长限制，以及任务结束记录、重置和恢复。设备为华为畅享 50 Pro / CTR-AL00，HarmonyOS 4.2，Android 12 / API 31 / ARM64，无 root。后台运行手机私有 Linux 环境中的真实 Claude Code 2.1.311 和 Python MCP。

本次联调使用开发机回环地址上的固定 Anthropic 协议模拟服务，经测试端口映射供手机访问，**没有调用真实外部模型**。手机工具、Claude Code 进程和 App Controller 均为实际执行；固定响应服务只按预定顺序返回工具调用与结束响应，不代表真实模型的推理能力。

## 限制调整及证据范围

- [LinuxRuntime.java](../../app/src/main/java/com/ds/phoneagent/LinuxRuntime.java) 启动 Claude Code 时不再传入 `--max-turns 30`。本次观测到的 9 个 Claude 进程中，携带 `--max-turns` 的数量为 0。
- [AgentController.java](../../app/src/main/java/com/ds/phoneagent/AgentController.java) 已移除运行 5 分钟后主动调用 Stop 的总任务计时器。任务期间持有唤醒锁，并在执行清理时释放，不再以原来的 20 分钟锁租期作为维持唤醒的上限。
- `longtask` 实机测试通过：真实 Claude Code 连续完成 31 次 `read_screen` 工具调用，收到固定服务的最终响应，并保存成功结束记录。这超过了原先的 30 轮限制。
- 此处对 5 分钟限制的结论来自当前启动参数和 Controller 代码检查。本次 31 次读屏测试保留测试程序自身的 3 分钟失败超时，未把它表述为超过 5 分钟或整夜后台运行的耐久测试。

取消的是 App 主动设置的整次任务截止条件。网络错误、系统回收、锁屏及权限等实际阻碍仍可能结束或阻断任务，用户仍可手动停止。

## 实机验证结果

测试入口启动时确认 MainActivity 可见，并启用前台服务和测试用唤醒锁。以下结果来自本次本地日志与对应测试实现：

| 检查 | 结果与证据范围 |
|---|---|
| 31 次只读工具调用 | `PASS longtask`；真实手机工具产生至少 31 次 ACTING 回调，固定服务最终响应被解析，成功记录已落盘 |
| Claude 启动参数 | 观测到 9 个 Claude 进程；`--max-turns` 命中 0 个 |
| 正常完成 | `PASS controller`；本地固定响应经实际 Controller、Claude Code、MCP 打开系统设置，核对前台包名，并保存 `success / success` 终态 |
| 手动停止 | 在真实执行进程已出现时调用 Stop；停止回调、`isRunning=false`、进程引用清理及 `stopped / user_stop` 结束记录均通过 |
| 模型服务错误 | 本地服务主动返回 HTTP 400；Controller 保存 `error / error_result`，日志保留安全的 HTTP 状态提示 |
| 进程没有最终结果 | 对仅连接不可达回环地址的测试任务直接终止 CLI，未调用 Controller.stop；仍保存 `error / cli_no_result` 结束记录 |
| 结束后重置 | SUCCESS、STOPPED 和实际 UI ERROR 的“重置”路径均保留任务输出；历史字段保持不变，只将 `acknowledged` 置为 true |
| 运行中拒绝重置 | 重置没有改变正在运行的任务、状态或输出 |
| 已完成记录恢复 | `PASS taskstore`；在独立缓存目录重建存储和 Controller，恢复完成状态、状态文字及输出 |
| 已确认错误记录恢复 | 重建 Controller 后恢复待命或待配置状态，同时保留历史错误日志，不重新显示旧错误状态 |
| 未结束记录恢复 | 独立目录中的模拟 `running` 记录被恢复为 `interrupted / process_interrupted`，清除过期确认标记、补写结束日志，且没有活动任务；再次恢复不重复追加 |

Controller 测试还监测了模型设置的变更键，断言上述停止与重置路径未触发设置变更；测试不读取配置值。此项不等同于升级前后配置文件内容校验。`taskstore` 使用合成记录和独立缓存目录重建对象，验证持久化恢复逻辑，不将其描述为真实任务遭系统强杀后的完整复现。

## 结束记录行为

[LastTaskStore.java](../../app/src/main/java/com/ds/phoneagent/LastTaskStore.java) 以 AtomicFile 保存最近一次任务记录。运行期间保存可显示的进度和输出，结束时追加 `【任务结束】`，包含状态、原因以及可取得的退出码、执行轮数；即使 CLI 没有正常返回 result，也保留本地生成的结束说明。下一次启动发现未结束记录时，将其标记为中断。

“重置”只确认并收起终态展示，不删除历史输出。新任务的记录默认未确认。运行结果保存完毕后再释放 Controller 的运行状态；任务 epoch 阻止前一次任务的延迟回调覆盖新任务。

记录只保存在应用私有目录，当前仅保留最近一次任务和最多 24,000 个 UTF-16 代码单元的输出尾部。此存储容量限制不影响执行轮数或任务时长。密钥、认证信息和当前服务地址经过脱敏；错误响应仅提取安全的 CLI subtype、HTTP 4xx/5xx 状态及已知错误类别，不原样保存诊断请求体，也不保存模型思考内容。

## 保留的局部超时

以下限制用于结束单个等待、局部流程或测试，不会到点后直接取消整次 Agent 任务：

| 范围 | 当前限制 |
|---|---|
| 普通 MCP 到手机桥接请求 | 默认 15 秒的请求总超时 |
| `open_app_ready` 等待应用就绪 | 默认 15 秒，可设 1–30 秒；失败返回当前可判断的结果，由 Agent 决定下一步 |
| `run_routine` 调度 | 默认 60 秒，最高 90 秒；到截止时间后不再启动下一步，已发出的普通工具调用受自身超时约束 |
| 首次 Linux 解包、版本检查 | 分别保留 600 秒、45 秒的安装或检查超时 |
| 手动停止的进程清理 | 先等待 1.5 秒，必要时强制结束并再等 1.5 秒 |
| `longtask` 测试程序 | 3 分钟的测试失败超时，仅属于测试程序 |

## 本地证据与构建

本报告依据以下本地日志；它们属于被忽略的本地验证产物，不要求提交仓库：

- `local/taskstore-v022.txt`：持久化、确认状态和中断恢复，`PASS taskstore`。
- `local/controller-v022.txt`：成功、错误、停止、缺少最终结果及重置，`PASS controller`。
- `local/longtask-v022.txt`：31 次读屏工具调用及最终记录，`PASS longtask`。
- `local/claude-no-turn-limit-v022.txt`：`Observed Claude processes: 9; with --max-turns: 0`。

测试实现与复现步骤见 [实机测试说明](../../tests/android/README.md)、[TestInstrumentation.java](../../tests/android/java/com/ds/phoneagent/TestInstrumentation.java) 和 [本地模拟服务](../../tests/mock_anthropic.py)。本报告未附私人屏幕、模型配置或真实用户任务内容，也未将本地固定响应结果算作真实模型验证。

报告生成时的构建文件：

- 路径：`build/PhoneAgent-debug.apk`。
- 大小：148,321,912 字节。
- SHA256：`db6453d32cc057ac0e295da4232b009354779d61d268be4bea9c5c8cd328835b`。

摘要仅对应本次核对的文件，后续重建需重新核对。

## 部署与收尾

手机安装版本已核对为 `versionCode 5 / 0.2.2`。升级和回归测试前后，模型配置文件的 SHA256 一致；校验只比较摘要，没有输出配置内容。测试 APK、本地固定响应服务和本次 ADB 端口映射均已移除，未留下 Claude、PRoot 或 Python 测试进程。测试增加的设置启动候选已清理，原有脚本候选数据逐字节恢复。

旧版最后一次任务的本地日志确认触发了 30 轮上限，且没有生成最终文字答复。已将依据原始日志整理的中断摘要恢复到新版 App，并实际点击“重置”：确认标记已保存，原始任务状态、原因、时间及输出均保持不变。App 保留这段历史记录供查看，没有重新执行原任务。原始会话、诊断摘要、截图和安装包仅保存在本地忽略目录。
