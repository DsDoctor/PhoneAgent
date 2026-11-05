# 华为畅享 50 Pro：0.3.2 顶部按钮顺序验证

日期：2026-11-05。应用：PhoneAgent 0.3.2（调试版，`versionCode 8`）。设备：CTR-AL00，Android 12 / API 31。

本版只交换顶部两个按钮的布局顺序：左侧“＋ 新任务”，右侧“任务记录”。原有点击处理、任务执行和历史管理逻辑未修改。

## 构建与实机检查

- `python3 scripts/build.py` 完整构建、签名验证通过，并成功覆盖安装到手机。
- 安装前确认最近任务已结束，且没有 Claude/PRoot 进程；没有打断正在运行的任务。
- 手机报告 `versionName=0.3.2`、`versionCode=8`。
- 实际界面中“＋ 新任务”边界为 `[66,411][534,543]`，“任务记录”为 `[546,411][1014,543]`，左右顺序及相同行位置符合要求。
- 点击左侧“＋ 新任务”后正常进入新任务页，并保存截图 `local/v032-navigation.png`。
- 安装后系统 `Bound services` 中实际包含 PhoneAgent 手机控制服务，`Binding services` 与 `Crashed services` 均为空；本次无需重新授权或切换无障碍服务。

## 数据保留与验证范围

安装前后仅计算并比较 SHA256，确认 `files/task-history.json`、`files/last-task.json` 和 `shared_prefs/phoneagent.xml` 均逐字节不变。没有删除用户记录、修改模型配置或调用外部模型。

本次未运行 instrumentation 或其他广泛测试，验证范围为此次布局顺序变化、安装版本、相关数据保留和无障碍实际绑定状态。

本地证据：`local/build-v032-output.txt`、`local/v032-hashes-before.json`、`local/v032-hashes-after.json`、`local/v032-navigation.png`。

已核对 APK：`build/PhoneAgent-debug.apk`，148,354,680 字节，SHA256 `b53d283a2f976f3c6d54cc3d71e17c13962b58ce7119e4d23eeea28660d0bce2`。该摘要仅对应本次构建。
