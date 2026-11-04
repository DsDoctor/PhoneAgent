# 把跑顺的基础动作保存为脚本

PhoneAgent 已在 Claude Code 的系统提示中加入这条工作方式：任务开始先查已有脚本，优先复用；反复跑通的基础导航动作，在核对结果后整理保存。先完成用户当前目标，有剩余预算再整理，并说明新增或复用了什么。

例如“打开大麦并等待加载”，现在可以由手机本地完成启动、等待和读屏，最后把结果交给模型。达到两次成功满足保存条件；具体何时整理、如何命名，仍由 Claude Code 根据当前任务决定。

## 工具接口

| 工具 | 参数 | 行为与返回 |
|---|---|---|
| `open_app_ready` | `package_name`；可选 `timeout_ms`，默认 15000，范围 1000–30000 | 启动一次，每 500 毫秒观察目标包，等待可读界面稳定至少 1 秒；返回 `status`、`screen` 和结构化 `wait` |
| `list_routines` | `{}` | 返回已保存的 `routines`，以及近期重复成功的启动候选 `candidates` |
| `save_routine` | `name`、`steps`、`verify`，以及下述两种证明之一；可选 `description` | 校验真实执行记录后保存，返回名称与步骤数 |
| `run_routine` | `name`；可选 `timeout_ms`，默认 60000，范围 1000–90000 | 按顺序执行已保存动作，检查必要的前台包名和最终界面；失败立即停止 |

`open_app_ready` 的 `status` 为 `ready`、`blocked` 或 `error`。它只接受目标应用的观察结果；已知大麦开屏节点 `splash_layout`、`splash_image`、`homepage_advert_pb` 出现时继续等待自然结束。超时返回最后界面，不自动点击广告、同意权限或解锁。`ready` 表示当前界面可读且短暂稳定，业务目标仍需模型判断。

## 如何证明已经跑顺

成功的顶层手机工具调用会返回真实的 `routine_receipt`。脚本必须包含最终验证 `verify`：目标 `package`，以及实际界面中出现的 1–5 个 `text_contains` 片段。

**同一任务内保存单步或多步脚本：**提供两份 `proofs`，各包含按顺序执行每一步的 `step_receipts`，以及最后读屏的 `verification_receipt`。两次运行必须独立、先后完成，动作和参数与脚本一致，不能漏掉中途发生的操作。`open_app_ready` 自带的最终界面也可作为验证来源。

下面仅展示参数形状，尖括号内容必须替换为实际返回值；这个示例不表示脚本已经保存：

```json
{
  "name": "open_damai_ready",
  "steps": [
    {"tool": "open_app_ready", "arguments": {"package_name": "cn.damai", "timeout_ms": 15000}}
  ],
  "verify": {"package": "cn.damai", "text_contains": ["<两次实际读到的稳定界面文字>"]},
  "proofs": [
    {"step_receipts": ["<第一次启动凭据>"], "verification_receipt": "<第一次最终读屏凭据>"},
    {"step_receipts": ["<第二次启动凭据>"], "verification_receipt": "<第二次最终读屏凭据>"}
  ]
}
```

**跨任务保存启动脚本：**只有 `open_app_ready` 的成功候选计数会延续。相同应用成功至少两次后，`list_routines` 会提供 `candidate_id` 和建议参数；不同等待预算按同一应用累计，建议保留成功用过的较大预算。保存时使用该候选参数组成一个 `open_app_ready` 步骤，并以 `candidate_id`、当前任务新取得的 `verification_receipt` 替代 `proofs`，再次核对目标包与文字。

多步流程仍需当前任务中的两份成功记录。原始执行凭据不跨任务保存，当前进程内最多保留 256 条、有效期 10 分钟；候选计数保留最近 30 天的数据。保存的脚本可供后续任务复用。

## 上限与执行边界

- **30 轮**是 Claude Code 的模型任务轮数上限，不等于 30 次点击。一次工具调用可以在本地完成多个动作；内部轮询不会额外请求模型。
- **12 步**是一个 JSON 脚本的步骤上限，属于另一层限制。脚本允许 `home`、`back`、`read_screen`、`open_app_ready`、`tap`、`swipe`；不保存输入文本，也不执行任意 Shell、Python 或嵌套脚本。
- `back`、`tap`、`swipe` 必须填写 `expect_package`，执行前重新核对前台应用。每步可设置 `settle_ms`（0–1500）等待界面变化；结束时还要验证目标包和文字。页面布局变化仍可能使脚本失败，失败后交回模型检查。
- `run_routine` 的 60/90 秒是调度预算；到时不再发起下一动作，在途请求按自身超时结束。整个用户任务仍受 App 的 5 分钟上限和停止按钮控制。
- 最多保存 32 个脚本，每个文件最多 32 KiB。系统提示要求只整理基础导航，不把登录、密码验证码、支付或订单确认固化为脚本。

## 存储与开发验证

手机 Linux 用户目录下的 `~/.phoneagent/routines/` 保存脚本 JSON 和有界的启动成功计数。完整读屏和单次凭据只在当前 MCP 进程内存中保留；脚本中选择的最终验证文字会随脚本保存，启动候选仅记录包名、等待参数、次数和时间。

开发测试可用环境变量 `PHONE_AGENT_ROUTINES_DIR` 指向独立测试目录，保持 `HOME` 和用户默认目录不变。跨进程验证应让两次测试 MCP 使用同一隔离目录，结束后只清理该目录。真实手机验证步骤见 [实机测试说明](../tests/android/README.md)；工具契约以 [phone_mcp.py](../app/src/main/assets/phone_mcp.py) 和 [phone_routines.py](../app/src/main/assets/phone_routines.py) 为准。
