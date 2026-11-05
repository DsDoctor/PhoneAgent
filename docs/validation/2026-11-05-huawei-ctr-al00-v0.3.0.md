# 华为畅享 50 Pro：0.3.0 会话历史、续聊与 Markdown 验证

日期：2026-11-05。应用：PhoneAgent 0.3.0（调试版，`versionCode 6`）。设备：华为畅享 50 Pro / CTR-AL00，HarmonyOS 4.2，Android 12 / API 31 / ARM64，无 root。

本次验证覆盖最近会话存储、旧记录迁移、同一会话的多轮结果展示、真实 Claude Code 会话恢复，以及原生 Markdown 渲染。`history`、`markdown`、`resume` 和 `controller` 实机测试均已通过。

**所有模型协议联调使用开发机回环地址上的固定响应服务，没有调用真实外部模型。** 手机运行的是私有 Linux 中的真实 Claude Code 2.1.311，工具调用也由实际手机 MCP 执行；本地服务用于校验上下文和联调流程，不证明外部模型的推理质量。历史与渲染测试使用合成记录，不展示私人屏幕、模型配置或真实用户任务内容。

## 会话历史、迁移与恢复

`PASS history` 使用独立缓存目录和实际 [TaskHistoryStore.java](../../app/src/main/java/com/ds/phoneagent/TaskHistoryStore.java)，验证结果如下：

| 检查 | 已验证行为 |
|---|---|
| 最近 20 个会话 | 写入 25 个合成会话后，重建存储仍只保留最新 20 个，并按更新时间倒序排列 |
| 会话内追加新轮次 | 续聊新增独立 turn ID；conversation ID 与已有 session ID 保持不变，已完成的旧轮次不被覆盖 |
| 过程和结果分离 | 用户指令、执行过程、最终结果、结束说明分别持久化，不把它们合成一个不可区分的结果字段 |
| 中断恢复 | 重建时将遗留 `running` 轮次标记为 `interrupted`；保留先前完成轮次，再次恢复不重复追加结束说明 |
| 同会话续聊的写入间隙 | 模拟新轮次已写入历史、`last-task.json` 仍指向上轮成功记录的崩溃间隙；重建 Controller 展示本轮中断，而非旧成功状态 |
| 旧版记录迁移 | 旧 `last-task` 文本完整保留，源文件不被修改；迁移只执行一次，不为没有证据的旧记录编造可恢复的 Claude session |

上述恢复验证通过合成文件和重建存储、Controller 实例完成，并非对真实用户任务进行系统强杀。测试只删除其独立缓存目录，未使用真实用户历史或模型凭据。

“最近 20 个会话”是历史保留策略，和 Agent 的执行轮数、任务总时长无关。本轮没有重新添加 30 轮或 5 分钟整次任务限制；此前的相关验证见 [0.2.2 报告](2026-11-05-huawei-ctr-al00-v0.2.2.md)。

## 真实 Claude Code 会话恢复

`PASS resume` 按以下顺序执行三个真实 CLI 调用：

1. 经 Controller 创建会话 A，保存 CLI 实际返回的 session ID 和首轮结果。
2. 创建独立会话 B，确认它使用不同的 CLI session ID。
3. 重建 Controller，再继续 A；[LinuxRuntime.java](../../app/src/main/java/com/ds/phoneagent/LinuxRuntime.java) 通过 `--resume <A 的 session ID>` 恢复原 CLI 会话。

固定响应服务只有在恢复请求的 `messages` 中同时包含 A 的首条用户指令和首轮助手最终回复、顺序正确且没有 B 的标记时，才允许测试继续。每轮还必须实际执行新的 `read_screen`，并携带该轮精确的 tool-use ID；前一轮读屏结果或累计工具次数不能满足这一条件。

测试进一步确认：A 的 CLI session ID 保持不变；A 恰有两个不同 turn ID，首轮内容没有被重写；B 内容保持不变；A 续聊后移动到历史顶部；重建后的历史与预期一致。模拟服务先返回中间助手文字，再返回 Markdown 最终答案，测试检查其 `process`、`result`、`terminal` 分开保存，最终答案没有重复留在过程字段中。

本项使用实际 Linux home 以验证真实会话文件恢复，产生两个新的测试 CLI 会话文件；对应随机 UUID 记录在本地日志中。Android 历史和 last-task 则位于独立缓存目录。这里不把 CLI 会话文件或测试服务的最终清理计入已完成结果。

## 实际 Activity 展示

同一 `resume` 测试临时将独立 Controller 接入真实 [MainActivity.java](../../app/src/main/java/com/ds/phoneagent/MainActivity.java)，检查：

- 历史列表分别显示 A 和 B。
- A 详情含两个原生 Markdown 结果，不混入 B 的内容。
- 两轮执行过程默认折叠；点击首轮展开按钮后，显示该轮中间过程。
- A 详情的“继续对话”按钮可用，当前选择指向 A；切到 B 后只有 B 的一个结果，旧 A 内容不残留。
- 测试结束恢复原 Controller、当前选择、显示页、草稿及过程展开状态。

这项 UI 检查没有点击发送按钮。真实续聊由前述 Controller 测试直接发起，不能把两者合并描述成已经完成一次人工点击“继续对话”的端到端操作。原生视图结构和内容断言已通过，最终截图的视觉检查仍待主流程完成。

## 原生 Markdown 与链接

`PASS markdown` 在 Android UI 线程创建实际 [MarkdownView.java](../../app/src/main/java/com/ds/phoneagent/MarkdownView.java)，遍历子视图及文本 span，确认标题层级、加粗、斜体、删除线、行内等宽代码、列表、引用和围栏代码均正确呈现。代码保留 Markdown 符号与换行，表格使用原生 `TableLayout`，代码与表格具有横向滚动容器。

测试只为合法 HTTPS 目标发现一个 URLSpan/ClickableSpan。`javascript:`、`file:`、`intent:`、`data:`、相对地址、带认证信息的 URL、非法端口、空白和反斜杠地址均不可点击，链接标签仍可阅读。没有实际点击链接、加载远程资源或访问模型服务。

更新 Markdown 后旧节点被移除，相同内容再次设置不会重建视图。实现使用平台原生 Java 组件，没有 WebView 或第三方渲染依赖；HTML 标签按文本显示，图片只显示说明，不加载远程图。

[MarkdownParser.java](../../app/src/main/java/com/ds/phoneagent/MarkdownParser.java) 的渲染限制为单次输入 48,000 个 UTF-16 代码单元、最多 160 块、单表 24 行 × 8 列、单个 MarkdownView 总计 192 个表格单元格，以及基本行内嵌套深度和扫描预算。超出块数或表格渲染容量的内容回退为可复制文本；输入截断会显示提示。这些是显示资源限制，不限制 Agent 执行。它支持常见 Markdown 子集，不声称完全实现 CommonMark、引用式链接、复杂嵌套块或数学公式。

## Controller 回归

`local/controller-v030.txt` 已给出 `PASS controller`。本地固定响应经过真实 Controller 与手机工具完成打开设置；成功、服务错误、手动停止，以及 CLI 没有最终结果四条路径均保存了对应结束记录。停止时进程已经启动，测试验证回调、运行标志和进程清理。SUCCESS、STOPPED、实际 UI ERROR 及 CLI_NO_RESULT 的重置保留输出，运行中重置被拒绝。

测试同时观察模型设置变更键，确认这些停止与重置路径没有触发配置变更；此项不等同于最终升级前后配置文件 hash 校验。

## 证据与构建

本报告依据以下本地文件和 [实机测试说明](../../tests/android/README.md)。原始日志属于被忽略的本地验证产物：

- `local/history-v030.txt`：`PASS history`。
- `local/markdown-v030.txt`：`PASS markdown`。
- `local/resume-v030.txt`：`PASS resume`。
- `local/controller-v030.txt`：`PASS controller`。

报告生成时核对的构建文件：

- 路径：`build/PhoneAgent-debug.apk`。
- 大小：148,338,296 字节。
- SHA256：`4d86b240d11b96f1118c39ee02f5d2a89067db987ccf5cc09dccf33f1fbac8be`。

摘要只对应本次核对的文件，后续重建需重新核对。

## 部署与真实历史保留

已在目标手机安装 0.3.0 / versionCode 6，保留原 Linux 运行环境和模型配置。配置文件升级及联调前后 SHA256 一致；未输出配置值。已依据设备上实际 Claude 会话日志导入 5 条真实旧用户会话，关联原 session UUID，并排除本地测试会话。最近一次成功任务的最终结果与原持久化输出核对一致；没有重新执行该用户任务。

实机截图及界面树确认：最新成功记录位于列表顶部，点开可看到原生 Markdown 标题、加粗、列表和表格；真实横向滑动后，表格的其他列进入视口。“继续对话”入口在原会话文件存在时可用。执行过程默认折叠，终态说明单独展示。截图和原始任务内容仅保存在本地忽略目录，不提交仓库。

测试 APK、模拟模型服务和本次 ADB 反向端口映射均已移除。新增的 5 个专用测试 CLI 日志已清理，原有 CLI 会话保留；设置启动测试候选已删除，原有脚本候选数据逐字节恢复。Android 历史文件已恢复为这 5 条真实会话。

另以真实输入框验证未发送草稿：输入后返回列表，再打开同一会话，草稿保持；测试文字已清除，没有发送任务。
