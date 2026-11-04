# 华为畅享 50 Pro：0.2.1 验证

日期：2026-11-05（北京时间）。PhoneAgent 0.2.1，versionCode 4，调试签名。

设备仍为华为畅享 50 Pro / CTR-AL00，Android 12 / API 31 / ARM64，无 root。后台仍使用 Alpine Linux、PRoot、官方 Claude Code 2.1.311 和 Python MCP；未重装手机上的 Linux 用户数据。

## 本版行为

- Claude Code 单次任务上限从 12 轮改为 30 轮，保留 5 分钟超时和手动停止。
- 同一个按钮在运行时显示“停止”，错误/停止/完成后显示“重置”。重置恢复待命并保留执行结果、模型配置；运行中拒绝重置。
- `open_app_ready` 在本地完成打开、等待和读屏，不为每次轮询调用模型。
- 新增查看、保存、执行基础操作脚本的 MCP 工具，并在 Claude Code 系统提示中加入优先复用、重复验证成功后整理保存的规则。
- 已保存脚本跨任务保留；启动候选计数可跨任务累计。多步脚本仍需当前任务的两份成功记录，具体约束见 [脚本说明](../phone-routines.md)。

## 验证结果

| 检查 | 结果 |
|---|---|
| Python 单元与协议测试 | 45 项通过，覆盖启动过渡/广告/错包/超时、凭据验证、脚本持久化和执行失败终止 |
| Android App 与测试 APK 构建 | 通过 |
| 安装版本 | 目标机确认 versionCode 4 / 0.2.1 |
| 实际 Claude 启动参数 | 测试进程命令行确认 `--max-turns 30`；未读取或输出进程环境 |
| Controller 成功路径 | 本地固定响应调用 Home、read_screen、open_app_ready、read_screen，确认设置页和最终输出 |
| SUCCESS / STOPPED 重置 | 恢复 IDLE，原输出保留，没有额外完成事件 |
| 运行中重置 | 拒绝，任务、状态和输出保持不变 |
| ERROR 界面重置 | 本地测试模型返回明确 HTTP 400 形成真实 ERROR；现有按钮变为可用“重置”，点击后恢复 IDLE |
| 真实手机 MCP 脚本 | 不启动模型；两次打开设置取得真实凭据，保存 JSON 脚本，执行并验证设置页文字 |
| 脚本跨进程持久化 | 第二个 MCP 进程可列出脚本和重复启动候选 |
| 已部署脚本模块 | 手机私有运行目录的两个 Python 模块 SHA256 与仓库源文件一致 |
| 模型配置保留 | 更新前后配置文件 SHA256 一致；测试仅监听配置项变更，不读取其值 |

最初沿用旧 Controller 固定序列时，`open_settings` 后的立即读屏再次遇到过渡期空节点，测试按设计失败。最终 Controller 测试改用独立的 `phoneagent-smoke-ready` 本地测试模型，实际执行新的 `open_app_ready`；没有吞掉错误，也没有把固定等待作为成功依据。旧 loop 测试序列保持原样。

脚本专项真机测试使用 `PHONE_AGENT_ROUTINES_DIR` 指向唯一隔离目录，不更改 `HOME` 或访问用户脚本目录；结束后移除该目录。Controller 联调产生的单个设置启动候选已清理。测试 APK、本地模型服务和 ADB 端口转发均已移除，手机没有残留测试 Claude/PRoot/Python 进程。

本次未调用用户配置的真实模型。脚本的实际保存、运行、持久化已经验证；模型是否会在每个真实任务中选择恰当脚本，仍取决于具体模型和页面。没有将设置页回归等同于“大麦查演唱会”业务任务已完成。开屏广告等待识别在可控测试中覆盖，实际超时会返回最后界面供下一步判断。

## 复现与构建产物

```bash
python3 -m unittest discover -s tests -v
python3 scripts/build.py
python3 scripts/build_android_tests.py
python3 scripts/test-device.py routines
# Controller 需要按实机测试说明启动本地固定响应服务与端口转发
python3 scripts/test-device.py controller
```

详细步骤见 [实机测试说明](../../tests/android/README.md)。

- APK：`build/PhoneAgent-debug.apk`
- 大小：148,321,912 字节
- SHA256：`4055086f1ba921e5684b64dac3a7e54f5086b601f50196ac5556e25875955870`

原始用户任务日志、诊断摘要、截图、安装包和签名密钥均留在本地忽略目录，不提交到 Git。
